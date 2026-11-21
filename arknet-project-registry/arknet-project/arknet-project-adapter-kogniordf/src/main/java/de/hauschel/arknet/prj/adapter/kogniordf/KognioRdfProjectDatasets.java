// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.InfrastructureGraphs;
import de.hauschel.arknet.persistence.Prefixes;
import de.hauschel.arknet.prj.application.port.out.ProjectDatasets;
import de.hauschel.arknet.prj.domain.DatasetInUseException;

/**
 * kognio-rdf-backed {@link ProjectDatasets}: counts the typed resources in a project's dataset and
 * removes the dataset through {@link DatasetLifecycle#delete}.
 *
 * <p><strong>Nothing is created by looking.</strong> {@link DatasetLifecycle#acquire} opens a
 * dataset by creating it if it is missing. Both methods therefore ask {@link DatasetLifecycle#list}
 * first and leave a dataset that is not there alone - a project that never wrote anything must not
 * gain an empty dataset by being counted, which {@code project_list} would then offer for
 * adoption.</p>
 *
 * <p><strong>What counts as a model resource.</strong> Every subject carrying an {@code rdf:type}
 * in a named graph other than the two infrastructure graphs every project dataset holds - the
 * self-description ({@link ArkprjVocabulary#IDENTITY_GRAPH}) and the revision trail ({@link
 * ArkprovVocabulary#PROVENANCE_GRAPH}). That is the same split {@code StoreReader} draws for
 * {@code store_overview}, so a project that report shows as empty is one this port reports as
 * empty. A resource carrying two types is counted under each; the types are shortened to the
 * prefixed names the store report uses.</p>
 *
 * <p><strong>Revisions are counted apart.</strong> The revision trail lives in {@link
 * ArkprovVocabulary#PROVENANCE_GRAPH}, which the model count leaves out, so {@link
 * #revisionCount} answers for it: a dataset without model resources can still hold history.</p>
 *
 * <p><strong>An open lease is waited for, briefly.</strong> {@link DatasetLifecycle#delete}
 * refuses with an {@link IllegalStateException} while any lease on the dataset is open. arknet
 * holds no lease beyond a single read or write - every acquisition is a try-with-resources around
 * one query or transaction, and no per-project handle is cached anywhere - so a refusal means a
 * concurrent tool call is in flight on this very project, and it will be done in milliseconds.
 * The removal is retried for a bounded time and only then reported as {@link
 * DatasetInUseException}, leaving the dataset untouched. The caller holds the project's lock during
 * that wait, which is deliberate: it only blocks calls on the project being deleted, and releasing
 * it would let an attach, rename or adopt of that project slip in between the registry removal and
 * the dataset removal. An interrupt during the wait ends it
 * early with the same report, the interrupt flag left set.</p>
 */
public final class KognioRdfProjectDatasets implements ProjectDatasets {

    /** How often {@link #delete} tries before it gives up on an open lease. */
    static final int DEFAULT_DELETE_ATTEMPTS = 20;

    /** How long {@link #delete} waits between two attempts. */
    static final Duration DEFAULT_DELETE_PAUSE = Duration.ofMillis(50);

    private static final String COUNT_BY_TYPE = "SELECT ?type (COUNT(DISTINCT ?s) AS ?count) WHERE { "
            + "GRAPH ?g { ?s a ?type } "
            + "FILTER(" + InfrastructureGraphs.outsideExpression("?g") + ") } GROUP BY ?type";

    /**
     * One revision is one {@code arkprov:Revision} subject; the activity and head triples the
     * funnel writes next to it are bookkeeping of the same write and do not count again.
     */
    private static final String COUNT_REVISIONS = "SELECT (COUNT(DISTINCT ?r) AS ?count) WHERE { GRAPH <"
            + ArkprovVocabulary.PROVENANCE_GRAPH + "> { ?r a <" + ArkprovVocabulary.REVISION_TYPE + "> } }";

    private final DatasetLifecycle lifecycle;
    private final Prefixes prefixes = Prefixes.defaults();
    private final int deleteAttempts;
    private final Duration deletePause;

    /**
     * @param lifecycle the shared dataset lifecycle every project dataset is acquired through
     */
    public KognioRdfProjectDatasets(final DatasetLifecycle lifecycle) {
        this(lifecycle, DEFAULT_DELETE_ATTEMPTS, DEFAULT_DELETE_PAUSE);
    }

    /**
     * @param lifecycle      the shared dataset lifecycle every project dataset is acquired through
     * @param deleteAttempts how often {@link #delete} tries before reporting an open lease, at least 1
     * @param deletePause    the wait between two attempts
     */
    KognioRdfProjectDatasets(final DatasetLifecycle lifecycle, final int deleteAttempts, final Duration deletePause) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        if (deleteAttempts < 1) {
            throw new IllegalArgumentException("deleteAttempts must be at least 1");
        }
        this.deleteAttempts = deleteAttempts;
        this.deletePause = Objects.requireNonNull(deletePause, "deletePause");
    }

    @Override
    public Map<String, Long> modelResourceCounts(final ProjectId projectId) {
        final DatasetId dataset = datasetOf(projectId);
        if (!lifecycle.list().contains(dataset)) {
            return Map.of();
        }
        final Map<String, Long> counts = new LinkedHashMap<>();
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            handle.sparqlQuery().select(COUNT_BY_TYPE)
                    .map(this::toEntry)
                    .sorted(Map.Entry.comparingByKey())
                    .forEachOrdered(entry -> counts.merge(entry.getKey(), entry.getValue(), Long::sum));
        }
        return counts;
    }

    @Override
    public long revisionCount(final ProjectId projectId) {
        final DatasetId dataset = datasetOf(projectId);
        if (!lifecycle.list().contains(dataset)) {
            return 0L;
        }
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            return handle.sparqlQuery().select(COUNT_REVISIONS)
                    .map(row -> Long.parseLong(((Literal) row.getValue("count").orElseThrow()).getLexicalForm()))
                    .findFirst()
                    .orElse(0L);
        }
    }

    @Override
    public void delete(final ProjectId projectId) {
        final DatasetId dataset = datasetOf(projectId);
        IllegalStateException lastRefusal = null;
        for (int attempt = 1; attempt <= deleteAttempts; attempt++) {
            if (!lifecycle.list().contains(dataset)) {
                return;
            }
            try {
                lifecycle.delete(dataset);
                return;
            } catch (final IllegalStateException e) {
                // DatasetLifecycle#delete's documented refusal: a lease on the dataset is still open.
                lastRefusal = e;
                if (attempt < deleteAttempts && !pause()) {
                    break;
                }
            }
        }
        throw new DatasetInUseException(projectId, lastRefusal);
    }

    private Map.Entry<String, Long> toEntry(final BindingSet row) {
        final RDFTerm type = row.getValue("type").orElseThrow();
        final RDFTerm count = row.getValue("count").orElseThrow();
        final String name = type instanceof IRI iri ? prefixes.toCurie(iri.getIRIString()) : type.toString();
        return Map.entry(name, Long.parseLong(((Literal) count).getLexicalForm()));
    }

    /**
     * Waits {@link #deletePause} before the next attempt.
     *
     * @return {@code false} if the wait was interrupted - the interrupt flag is restored and the
     *         caller stops retrying, since a thread asked to stop must not keep spinning
     */
    private boolean pause() {
        try {
            Thread.sleep(deletePause);
            return true;
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static DatasetId datasetOf(final ProjectId projectId) {
        return new DatasetId(Objects.requireNonNull(projectId, "projectId").value());
    }
}
