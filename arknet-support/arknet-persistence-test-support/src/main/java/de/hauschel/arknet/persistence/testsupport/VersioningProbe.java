// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence.testsupport;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.versioning.DraftSlice;
import io.kogn.rdf.versioning.HistoryEntry;
import io.kogn.rdf.versioning.PublishState;
import io.kogn.rdf.versioning.ResourceVersioning;
import io.kogn.rdf.versioning.ResourceVersioningNamedGraphs;
import io.kogn.rdf.versioning.RevisionToken;

/**
 * Reads what the library versioning keeps for one resource - its draft, the draft's concurrency
 * token, its history and publish state - so a test can tell whether a write landed without
 * knowing the library's graphs. Each read runs in a transaction of its own.
 */
public final class VersioningProbe {

    private final DatasetLifecycle lifecycle;
    private final ResourceVersioning versioning;
    private final SimpleRdf rdf = new SimpleRdf();

    /**
     * @param lifecycle       the lifecycle the datasets under test are acquired from
     * @param contentGraphIri the content graph the out-adapters write
     */
    public VersioningProbe(DatasetLifecycle lifecycle, String contentGraphIri) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.versioning = new ResourceVersioningNamedGraphs(rdf.createIRI(contentGraphIri));
    }

    /**
     * @return the draft's token, or empty if the draft is empty (never written, or deleted)
     */
    public Optional<RevisionToken> token(DatasetId dataset, String subjectIri) {
        DraftSlice draft = inTransaction(dataset, tx -> versioning.readDraft(tx, iri(subjectIri)));
        return draft.triples().isEmpty() ? Optional.empty() : Optional.of(draft.token());
    }

    /** @return the draft slice, empty if never written or deleted */
    public ReadableGraph draft(DatasetId dataset, String subjectIri) {
        return inTransaction(dataset, tx -> versioning.readDraft(tx, iri(subjectIri)).triples());
    }

    /** @return the publish entries and tombstones, oldest first */
    public List<HistoryEntry> history(DatasetId dataset, String subjectIri) {
        return inTransaction(dataset, tx -> versioning.history(tx, iri(subjectIri)));
    }

    /** @return the publish state */
    public PublishState publishState(DatasetId dataset, String subjectIri) {
        return inTransaction(dataset, tx -> versioning.publishState(tx, iri(subjectIri)));
    }

    private IRI iri(String subjectIri) {
        return rdf.createIRI(subjectIri);
    }

    private <T> T inTransaction(DatasetId dataset, Function<DatasetTx, T> work) {
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            return handle.transactor().inTransaction(work);
        }
    }
}
