// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Holds an exclusive OS-level file lock over the whole arknet storage root for the lifetime of
 * this daemon process, acquired once at startup before any project dataset is touched.
 *
 * <p><strong>Why this exists (issue #139).</strong> The RDF4J-backed {@code DatasetLifecycle}'s
 * {@code createAndSeed} treats a lock-conflicting {@code acquire} on a dataset it believes it just
 * created ({@code isNewStore}, evaluated against the directory before the underlying
 * {@code NativeStore} takes its own file lock) as a half-created store and deletes it. Two daemon
 * processes racing to open the very same, freshly registered project's dataset for the first time
 * can land the losing process inside that window: it sees an empty directory, believes it created
 * it, fails to lock it, and deletes the directory the winning process just locked and is now
 * writing into - {@link LockConflictReportingDatasetLifecycle} then reports what looks like an
 * ordinary lock conflict, when actually the winner's data was just lost. A lock scoped to one
 * dataset cannot close that window, because both processes reach {@code isNewStore} before either
 * takes the per-dataset lock. A lock any second daemon fails on before it ever calls
 * {@code acquire} for any dataset can: this one is acquired once, over the storage root as a
 * whole, before {@code ArknetMcpConfiguration#datasetLifecycle} - and therefore anything that could
 * touch a project's directory - is even constructed.</p>
 *
 * <p>Deliberately a plain {@link FileLock} over a dedicated marker file, not a second
 * {@code NativeStore}/RDF4J concept: the arknet composition root stays free of any direct RDF4J
 * dependency (see {@link LockConflictReportingDatasetLifecycle}'s own class documentation), and
 * what this guards is the storage root as a directory, not any dataset's content.</p>
 */
public final class DaemonStorageLock implements AutoCloseable {

    private static final String LOCK_FILE_NAME = ".arknet-daemon.lock";

    private final FileChannel channel;
    private final FileLock lock;

    private DaemonStorageLock(FileChannel channel, FileLock lock) {
        this.channel = channel;
        this.lock = lock;
    }

    /**
     * Acquires the exclusive lock over {@code storageDir}, creating the directory (and the lock
     * file inside it) if they do not exist yet.
     *
     * @throws DaemonAlreadyRunningException if another process already holds the lock
     * @throws UncheckedIOException          if the lock file cannot be created or opened
     */
    public static DaemonStorageLock acquire(Path storageDir) {
        try {
            Files.createDirectories(storageDir);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to acquire the arknet daemon lock at " + storageDir, e);
        }

        final FileChannel channel;
        try {
            channel = FileChannel.open(
                    storageDir.resolve(LOCK_FILE_NAME), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to acquire the arknet daemon lock at " + storageDir, e);
        }

        boolean acquired = false;
        try {
            final FileLock lock;
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException e) {
                throw new DaemonAlreadyRunningException(storageDir);
            }
            if (lock == null) {
                throw new DaemonAlreadyRunningException(storageDir);
            }
            acquired = true;
            return new DaemonStorageLock(channel, lock);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to acquire the arknet daemon lock at " + storageDir, e);
        } finally {
            if (!acquired) {
                closeQuietly(channel);
            }
        }
    }

    /**
     * Releases the lock. Called as the owning bean's Spring destroy method, so a subsequent daemon
     * start against the same storage root succeeds once this process has actually exited/closed
     * its context.
     */
    @Override
    public void close() {
        try {
            lock.release();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            closeQuietly(channel);
        }
    }

    /**
     * Closes {@code channel}, swallowing any {@link IOException} - used on already-failing paths
     * (a failed {@link #acquire} attempt, a {@link #close()} whose {@code lock.release()} itself
     * threw) where the channel is being abandoned and a secondary close failure must not mask the
     * original diagnosis.
     */
    private static void closeQuietly(FileChannel channel) {
        try {
            channel.close();
        } catch (IOException ignored) {
            // best effort: the lock/channel is being abandoned on an already-failing path
        }
    }
}
