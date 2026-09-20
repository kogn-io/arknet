// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link DaemonStorageLock} in isolation from the RDF store it guards: a second
 * {@link DaemonStorageLock#acquire(Path)} over the same storage root must fail before touching any
 * dataset, and releasing the first must free the root for a subsequent acquire (issue #139).
 */
class DaemonStorageLockTest {

    private static final long CHILD_STARTUP_TIMEOUT_SECONDS = 30;
    private static final long CHILD_EXIT_TIMEOUT_SECONDS = 10;

    @TempDir
    Path storageDir;

    @Test
    void aSecondAcquireOverTheSameStorageRootFailsWhileTheFirstIsHeld() {
        final DaemonStorageLock first = DaemonStorageLock.acquire(storageDir);
        try {
            assertThatThrownBy(() -> DaemonStorageLock.acquire(storageDir))
                    .isInstanceOf(DaemonAlreadyRunningException.class)
                    .hasMessageContaining(storageDir.toString());
        } finally {
            first.close();
        }
    }

    /**
     * The test above only exercises the {@code OverlappingFileLockException} branch of
     * {@link DaemonStorageLock#acquire(Path)} - a JVM-internal check that fires for a second
     * {@code tryLock()} from the <em>same</em> JVM. Issue #139 is about two separate daemon
     * <em>processes</em> (two containers, two {@code systemctl} instances) racing for the same
     * storage root; across processes {@code tryLock()} instead returns {@code null}, the branch
     * this test drives via a genuine child JVM holding the lock.
     */
    @Test
    void aSecondAcquireFromAnotherProcessFailsWhileTheFirstProcessHoldsTheLock() throws Exception {
        final Process child = startLockHolderProcess(storageDir);
        try {
            assertThat(readChildStartupSignal(child)).isEqualTo("LOCKED");

            assertThatThrownBy(() -> DaemonStorageLock.acquire(storageDir))
                    .isInstanceOf(DaemonAlreadyRunningException.class)
                    .hasMessageContaining(storageDir.toString());
        } finally {
            releaseChild(child);
        }
    }

    private static Process startLockHolderProcess(Path storageDir) throws IOException {
        final String javaBin = System.getProperty("java.home") + File.separator + "bin" + File.separator + "java";
        final ProcessBuilder builder = new ProcessBuilder(
                javaBin, "-cp", System.getProperty("java.class.path"),
                LockHolderProcess.class.getName(), storageDir.toString());
        builder.redirectErrorStream(false);
        return builder.start();
    }

    private static String readChildStartupSignal(Process child) throws InterruptedException {
        final CompletableFuture<String> firstLine = CompletableFuture.supplyAsync(() -> {
            try (BufferedReader reader =
                    new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
                return reader.readLine();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
        try {
            return firstLine.get(CHILD_STARTUP_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (ExecutionException | TimeoutException e) {
            child.destroyForcibly();
            throw new AssertionError("The lock-holder child process never signalled it acquired the lock", e);
        }
    }

    private static void releaseChild(Process child) throws InterruptedException, IOException {
        try (OutputStream stdin = child.getOutputStream()) {
            stdin.write('\n');
            stdin.flush();
        } catch (IOException ignored) {
            // best effort: the child may already have exited
        }
        if (!child.waitFor(CHILD_EXIT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            child.destroyForcibly();
        }
    }

    @Test
    void releasingTheLockLetsALaterAcquireSucceed() {
        final DaemonStorageLock first = DaemonStorageLock.acquire(storageDir);
        first.close();

        final DaemonStorageLock second = DaemonStorageLock.acquire(storageDir);
        second.close();
    }

    @Test
    void createsTheStorageDirectoryIfItDoesNotExistYet() {
        final Path notYetCreated = storageDir.resolve("nested/project-root");

        final DaemonStorageLock lock = DaemonStorageLock.acquire(notYetCreated);
        try {
            assertThat(Files.isDirectory(notYetCreated)).isTrue();
        } finally {
            lock.close();
        }
    }

    /**
     * Runs in a separate JVM process, launched by
     * {@link #aSecondAcquireFromAnotherProcessFailsWhileTheFirstProcessHoldsTheLock}: acquires the
     * storage-root lock, signals the parent it did so, then blocks until the parent tells it to
     * release the lock and exit.
     */
    public static final class LockHolderProcess {

        private LockHolderProcess() {}

        public static void main(String[] args) throws IOException {
            final DaemonStorageLock lock = DaemonStorageLock.acquire(Path.of(args[0]));
            System.out.println("LOCKED");
            System.out.flush();
            try (BufferedReader stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
                stdin.readLine();
            } finally {
                lock.close();
            }
        }
    }
}
