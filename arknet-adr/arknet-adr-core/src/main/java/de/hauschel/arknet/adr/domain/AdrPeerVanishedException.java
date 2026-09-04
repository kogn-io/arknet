// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a {@code relatedTo}/{@code supersededBy} peer resolved earlier in a write is found
 * gone by the time the write transaction itself opens - the race the out-adapter's in-transaction
 * backstop closes (kogn-io/arknet#356): {@code AdrService#resolvePeers} looks a peer up by its
 * human-typed {@link AdrCode} outside any transaction, and a concurrent {@code adr_delete} of that
 * very peer can commit in the window between that read and this write's own commit. Without this
 * check the stale peer identity would be written as a dangling {@code relatedTo}/{@code supersededBy}
 * edge, which then fails the SHACL gate on every subsequent write to the referencing decision -
 * recoverable only by clearing the whole {@code relatedTo} list.
 *
 * <p>Deliberately not {@link AdrNotFoundException}: that signal is keyed by the human-typed
 * {@link AdrCode}, which is exactly what this late, in-transaction check no longer has - only the
 * opaque {@link AdrId} the earlier resolution already turned it into. A caller hitting this rare
 * window can simply retry the whole call; the didactic pre-check ({@code
 * AdrService#resolvePeers}) then throws the friendlier {@link AdrNotFoundException} by the human
 * code instead, since the peer is by then observably gone even outside any transaction.</p>
 *
 * <p>Not a programming error: an expected, if rare, outcome of two sessions racing a
 * {@code relatedTo}/{@code supersededBy} edge against a delete of its target (ADR-001: parallel
 * sessions of one user against one local store are the normal case).</p>
 */
public class AdrPeerVanishedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient AdrId peer;

    /**
     * Creates the exception.
     *
     * @param projectId the project the write was targeting
     * @param peer      the opaque identity of the peer that no longer exists
     */
    public AdrPeerVanishedException(ProjectId projectId, AdrId peer) {
        super("a relatedTo/supersededBy peer (" + Objects.requireNonNull(peer, "peer").value().value()
                + ") referenced by this write no longer exists in project "
                + Objects.requireNonNull(projectId, "projectId").value()
                + " - it was likely deleted by a concurrent adr_delete; retry the call");
        this.projectId = projectId;
        this.peer = peer;
    }

    /** @return the project the write was targeting */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the opaque identity of the peer that no longer exists */
    public AdrId peer() {
        return peer;
    }
}
