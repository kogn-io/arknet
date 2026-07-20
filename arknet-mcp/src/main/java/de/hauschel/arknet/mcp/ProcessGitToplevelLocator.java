// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * {@link GitToplevelLocator} that shells out to {@code git rev-parse --git-common-dir}.
 *
 * <p>Runs the command with {@code dir} as its working directory and resolves the
 * reported common-dir path (git prints it relative to {@code dir} when it lies
 * beneath it, e.g. plain {@code .git} from a main checkout's own root - it prints
 * an absolute path when invoked from a linked worktree) to an absolute path, then
 * returns its parent: the main checkout's root. This is what makes a linked
 * {@code git worktree} resolve to the same workspace as its main checkout instead
 * of its own, worktree-local top-level (#136) - {@code --show-toplevel} would
 * return the latter. Any failure mode - {@code dir} not inside a git working tree
 * (non-zero exit), git not installed ({@link IOException} on start), or the
 * command not finishing within {@value #TIMEOUT_SECONDS} seconds - is mapped to
 * {@link Optional#empty()} so that workspace resolution degrades gracefully to a
 * directory-name fallback.</p>
 *
 * <p>The timeout guard is enforced by racing {@link Process#waitFor(long, TimeUnit)}
 * against a concurrent stdout drain: stdout is read on a separate (virtual) thread
 * while the calling thread waits on the process with a bound. Reading stdout
 * synchronously on the calling thread first - as a naive implementation might - would
 * defeat the timeout entirely, since {@code readAllBytes()} only returns at EOF (i.e.
 * process exit) and blocks indefinitely for a command that hangs without closing
 * stdout (stuck mount, credential prompt, ...). Waiting for exit first and only then
 * reading would avoid that specific deadlock but trade it for another: a command
 * whose output exceeds the OS pipe buffer would block on the write end once nobody is
 * draining it, so it would never exit within the timeout either.</p>
 */
public final class ProcessGitToplevelLocator implements GitToplevelLocator {

    private static final long TIMEOUT_SECONDS = 5;

    private final List<String> command;

    public ProcessGitToplevelLocator() {
        this(List.of("git", "rev-parse", "--git-common-dir"));
    }

    /**
     * Visible for tests: substitutes the {@code git} invocation with an arbitrary
     * command so the timeout guard can be exercised deterministically (e.g. with a
     * command that hangs without ever writing to stdout).
     */
    ProcessGitToplevelLocator(List<String> command) {
        this.command = List.copyOf(command);
    }

    @Override
    public Optional<Path> toplevelOf(Path dir) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(dir.toFile())
                    .start();

            AtomicReference<byte[]> stdout = new AtomicReference<>();
            Thread reader = Thread.ofVirtual().start(() -> {
                try {
                    stdout.set(process.getInputStream().readAllBytes());
                } catch (IOException e) {
                    // Stream closed under us, typically because the process was
                    // force-killed after a timeout; leave stdout unset.
                }
            });

            if (!process.waitFor(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                reader.interrupt();
                return Optional.empty();
            }

            reader.join(TimeUnit.SECONDS.toMillis(TIMEOUT_SECONDS));
            byte[] bytes = stdout.get();
            String output = bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8).trim();
            if (process.exitValue() != 0 || output.isBlank()) {
                return Optional.empty();
            }
            Path gitCommonDir = dir.resolve(output).normalize();
            return Optional.ofNullable(gitCommonDir.getParent());
        } catch (IOException e) {
            return Optional.empty();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }
}
