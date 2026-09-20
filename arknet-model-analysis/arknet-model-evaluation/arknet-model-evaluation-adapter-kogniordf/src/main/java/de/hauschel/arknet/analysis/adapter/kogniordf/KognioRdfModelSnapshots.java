// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.adapter.kogniordf;

import java.util.Objects;

import de.hauschel.arknet.analysis.application.port.out.ModelSnapshots;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.StoreSnapshot;

/**
 * Serves {@link ModelSnapshots} out of the project's dataset: one {@code SELECT ?s ?p ?o} over
 * the model graphs, read through the shared, technology-neutral store read path.
 *
 * <p>This is the whole of what makes model analysis a reader of the published language: it names
 * no module of any other context, only the triples those contexts publish and the predicate
 * constants they publish them under ({@code Ark*Vocabulary}). The coupling is to the ontologies,
 * not to a neighbour's core, and no compiler sees it - which is why the relationship stands in
 * the context map (ADR-49, ADR-54).</p>
 */
public final class KognioRdfModelSnapshots implements ModelSnapshots {

    private final StoreReader storeReader;

    /**
     * @param storeReader the shared read path over the project's dataset; the same one the
     *                    composition root's store overview uses, so both see one model
     */
    public KognioRdfModelSnapshots(final StoreReader storeReader) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
    }

    @Override
    public StoreSnapshot read(final ProjectId projectId) {
        return storeReader.readSnapshot(projectId);
    }
}
