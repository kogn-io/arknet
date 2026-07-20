// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic check that the process-based locator degrades gracefully: a
 * directory that is not inside a git working tree (a fresh temp dir) yields an
 * empty result. This also covers the git-absent case, since a missing {@code git}
 * binary is mapped to the same empty result.
 */
class ProcessGitToplevelLocatorTest {

    @TempDir
    Path nonGitDir;

    @Test
    void returnsEmptyForDirectoryOutsideAnyGitWorkingTree() {
        assertThat(new ProcessGitToplevelLocator().toplevelOf(nonGitDir)).isEmpty();
    }

    /**
     * Regression test for a bug where {@code readAllBytes()} on the subprocess'
     * stdout ran (and blocked until EOF) before {@code waitFor(TIMEOUT_SECONDS, ...)}
     * was ever reached. A command that hangs without exiting AND without writing
     * anything to stdout - a stuck NFS mount, a credential prompt, a hanging fsck on
     * a corrupt repo - therefore blocked the caller forever instead of degrading to
     * {@link Optional#empty()} after the documented timeout.
     *
     * <p>Substitutes {@code git} with {@code sleep <marker>}, a command that never
     * writes to stdout and outlives the locator's timeout by a wide margin, to
     * reproduce this deterministically without depending on real git internals.
     * Wrapping the call in {@code assertTimeoutPreemptively} bounds the test itself:
     * against the pre-fix implementation this test fails with a timeout instead of
     * hanging the build.</p>
     */
    @Test
    void enforcesTimeoutAgainstAHangingProcessInsteadOfBlockingOnStdout() {
        String uniqueDurationMarker = "42" + (System.nanoTime() % 100_000);
        ProcessGitToplevelLocator locator =
                new ProcessGitToplevelLocator(List.of("sleep", uniqueDurationMarker));

        Optional<Path> result = assertTimeoutPreemptively(
                Duration.ofSeconds(8),
                () -> locator.toplevelOf(nonGitDir),
                "toplevelOf() must honor its timeout guard instead of blocking on the "
                        + "hanging process' stdout");

        assertThat(result).isEmpty();
        assertThat(isProcessStillAlive(uniqueDurationMarker))
                .as("the hanging process must have been force-killed on timeout, not merely abandoned")
                .isFalse();
    }

    /**
     * Regression test for #136: a linked {@code git worktree} must resolve to its
     * <em>main</em> checkout's root, not its own worktree-local top-level - the
     * bug that made a worktree land on an empty store of its own instead of the
     * project's actual one.
     */
    @Test
    void resolvesLinkedWorktreeToSameRootAsMainCheckout(@TempDir Path mainCheckout, @TempDir Path worktreeParent)
            throws IOException, InterruptedException {
        runGit(mainCheckout, "init", "--quiet");
        runGit(mainCheckout, "config", "user.email", "test@example.invalid");
        runGit(mainCheckout, "config", "user.name", "Test");
        Files.writeString(mainCheckout.resolve("file.txt"), "content");
        runGit(mainCheckout, "add", "file.txt");
        runGit(mainCheckout, "commit", "--quiet", "-m", "initial");

        Path worktree = worktreeParent.resolve("linked-worktree");
        runGit(mainCheckout, "worktree", "add", "--quiet", worktree.toString(), "-b", "wt-branch");

        ProcessGitToplevelLocator locator = new ProcessGitToplevelLocator();

        assertThat(locator.toplevelOf(worktree))
                .as("a linked worktree must resolve to its main checkout's root, not its own")
                .isEqualTo(locator.toplevelOf(mainCheckout));
    }

    private static void runGit(Path workingDir, String... args) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(List.of("git"));
        command.addAll(List.of(args));
        Process process = new ProcessBuilder(command)
                .directory(workingDir.toFile())
                .redirectErrorStream(true)
                .start();
        byte[] output = process.getInputStream().readAllBytes();
        int exit = process.waitFor();
        if (exit != 0) {
            throw new IllegalStateException("git " + String.join(" ", args) + " failed with exit " + exit
                    + ": " + new String(output, StandardCharsets.UTF_8));
        }
    }

    private static boolean isProcessStillAlive(String uniqueDurationMarker) {
        // destroyForcibly() is asynchronous; give the OS a brief, bounded moment to reap.
        for (int attempt = 0; attempt < 20; attempt++) {
            boolean alive = ProcessHandle.allProcesses()
                    .filter(ProcessHandle::isAlive)
                    .anyMatch(handle -> handle.info().arguments()
                            .map(args -> List.of(args).contains(uniqueDurationMarker))
                            .orElse(false));
            if (!alive) {
                return false;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return alive;
            }
        }
        return true;
    }
}
