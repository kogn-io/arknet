// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Tests {@link DaemonStorageLock} in isolation from the RDF store it guards: a second
 * {@link DaemonStorageLock#acquire(Path)} over the same storage root must fail before touching any
 * dataset, and releasing the first must free the root for a subsequent acquire (issue #139).
 */
class DaemonStorageLockTest {

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
}
