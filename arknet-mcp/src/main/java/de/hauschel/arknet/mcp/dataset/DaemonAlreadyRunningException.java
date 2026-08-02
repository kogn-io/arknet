// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import java.nio.file.Path;

/**
 * Thrown by {@link DaemonStorageLock#acquire(Path)} when another process already holds the
 * exclusive lock over the arknet storage root.
 *
 * <p>arknet's operating model is one shared daemon per machine (ADR-009); this failure surfaces
 * that invariant at startup, before the second process ever opens a project dataset (issue #139)
 * - see {@link DaemonStorageLock} for why that timing is the point.</p>
 */
public class DaemonAlreadyRunningException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param storageDir the storage root the lock is contended over, named in the message so the
     *                    operator knows which directory/daemon to stop
     */
    public DaemonAlreadyRunningException(Path storageDir) {
        super("Another arknet daemon instance already holds the storage root " + storageDir + ". "
                + "arknet runs as one shared daemon per machine - stop the other process, or point "
                + "every client at the already-running daemon's HTTP endpoint instead of starting a "
                + "second one.");
    }
}
