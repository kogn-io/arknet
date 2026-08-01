// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.io.OutputStream;
import java.util.Objects;

import io.kogn.rdf.dataset.RdfFormat;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * The backup read path into a project dataset: the complete dataset - default graph and every
 * named graph alike - serialised as TriG.
 *
 * <p>Unlike {@link StoreReader}, this class hides nothing. {@link StoreReader} excludes the
 * provenance and project-identity graphs because it feeds a view of the current model, not of
 * its own machinery; a backup exists precisely to restore both the model and that machinery, so
 * excluding either graph here would silently drop data from the backup rather than declutter a
 * view of it. Serialisation itself is {@link DatasetHandle#datasetExport()} (kognio-rdf 0.2.2):
 * a spec-compliant, RDF4J-Rio-backed writer, not a hand-rolled one - the
 * previous hand-rolled version fell back to {@code RDFTerm#ntriplesString()} for literal objects,
 * which the RDF4J-backed term implementation resolves to {@code Value#toString()}, and that
 * method does not escape an embedded {@code "}, {@code \} or newline in the lexical form. Any
 * requirement/term/use-case text containing one produced syntactically invalid TriG that still
 * reported as a successful export.</p>
 */
public final class StoreExporter {

    private final DatasetLifecycle lifecycle;

    /**
     * @param lifecycle the shared kognio-rdf dataset lifecycle (must not be {@code null})
     */
    public StoreExporter(final DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /**
     * Writes every statement of every graph in the project's dataset to {@code out} as TriG text.
     *
     * @param projectId the project to export
     * @param out       the stream the TriG text is written to; not closed by this method
     */
    public void exportTrig(final ProjectId projectId, final OutputStream out) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(out, "out");
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.datasetExport().export(out, RdfFormat.TRIG);
        }
    }
}
