// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import java.io.OutputStream;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: write a complete backup of one project's dataset - its model, its version history
 * and its self-description - to a stream.
 *
 * <p>Backs the tool {@code project_export}, the counterpart of {@code project_delete} and
 * {@code project_adopt}: all three act on a project's dataset as a whole rather than on a resource
 * in it. The backup is TriG, the one serialisation that carries every named graph of the dataset;
 * it ends with an export-metadata graph of its own naming the server and ontology versions it was
 * written against. That graph is output only and never stored, so a reader who drops it holds
 * exactly the dataset.</p>
 *
 * <p>Which projects to back up, and where the stream goes, is the caller's business: this port
 * neither enumerates projects nor touches the filesystem.</p>
 */
public interface ExportProject {

    /**
     * Writes the backup of {@code projectId}'s dataset to {@code out}.
     *
     * @param projectId the project to back up
     * @param out       the stream the TriG text is written to; not closed by this method
     * @throws RuntimeException if the dataset cannot be read - for instance because another
     *                          process holds its lock - or the stream refuses the text; a partial
     *                          write is possible, so the caller discards what it received
     */
    void export(ProjectId projectId, OutputStream out);
}
