// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.out;

import java.util.function.Consumer;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Writes the resources of one project in one transaction. The core works on triples (ADR-69,
 * consequence 4), so the batch it is handed speaks the neutral RDF terms.
 */
public interface ResourceRepository {

    /**
     * Runs {@code writes} against one transaction of the project's store and commits what it names,
     * all or nothing: every write is checked against the shapes, every created or changed resource
     * gets one revision, and any exception rolls the transaction back.
     *
     * @param projectId the project to write into
     * @param writes    names the writes on the batch; must not keep the batch past its return
     * @throws de.hauschel.arknet.resource.domain.SubmitRejectedException if a written slice violates
     *         its shapes
     * @throws de.hauschel.arknet.resource.domain.SubmitConflictException if a concurrent write
     *         changed what the batch read, or took a code it assigned
     */
    void writeAll(ProjectId projectId, Consumer<ResourceBatch> writes);
}
