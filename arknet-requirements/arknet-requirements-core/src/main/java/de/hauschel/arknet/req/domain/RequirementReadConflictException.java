// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when a read-only lookup (e.g. {@code req_get}, {@code req_list}) keeps losing the
 * {@code SERIALIZABLE} race against concurrent writers of the same project across every retry
 * attempt the out-adapter allows (see {@code
 * de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepository#readInTransaction}).
 *
 * <p>An expected-but-rare domain outcome, not a programming error: sustained, high-frequency
 * concurrent writes against the requirements this read touches. Distinct from {@link
 * RequirementConcurrentlyModifiedException} - that one guards a specific write's compare-and-set
 * against a stale {@code expectedHead}; this one has no write and no expected head of its own, it
 * simply never observed a snapshot stable enough to commit its own read-only transaction. Wraps
 * the store's raw {@code io.kogn.rdf.dataset.ConcurrencyConflictException} as {@linkplain
 * #getCause() cause} - the same "never leak the raw store exception" convention the shared {@code
 * WriteFunnel} (ADR-013) establishes for every write path of this adapter, extended here to its
 * read paths.</p>
 */
public class RequirementReadConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;

    /**
     * Creates the exception.
     *
     * @param projectId the project the read was scoped to
     * @param attempts    the number of attempts made before giving up
     * @param cause       the store's own conflict exception from the last attempt
     */
    public RequirementReadConflictException(ProjectId projectId, int attempts, Throwable cause) {
        super("could not read requirements in project " + Objects.requireNonNull(projectId, "projectId").value()
                + " after " + attempts
                + " attempts - lost the SERIALIZABLE race against concurrent writers every time", cause);
        this.projectId = projectId;
    }

    /** @return the project the read was scoped to */
    public ProjectId projectId() {
        return projectId;
    }
}
