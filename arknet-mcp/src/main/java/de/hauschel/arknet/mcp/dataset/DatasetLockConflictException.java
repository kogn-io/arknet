// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

/**
 * Thrown by {@link LockConflictReportingDatasetLifecycle} when opening the shared RDF store fails
 * because another process already holds the storage directory's file lock.
 *
 * <p>Without this translation, the failure a caller actually sees is RDF4J's own
 * {@code RepositoryLockedException} - meaningful to someone who knows the store is a
 * {@code NativeStore}, opaque to everyone else. The message names the concrete cause the arknet
 * operating model produces this failure from: a second daemon instance, or a client/subagent MCP
 * config that spawns its own local {@code stdio} server process instead of pointing at the one
 * shared HTTP daemon (see {@code README.md}, "one shared daemon").</p>
 */
public class DatasetLockConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message the didactic explanation of the lock conflict and its remedy
     */
    public DatasetLockConflictException(String message) {
        super(message);
    }
}
