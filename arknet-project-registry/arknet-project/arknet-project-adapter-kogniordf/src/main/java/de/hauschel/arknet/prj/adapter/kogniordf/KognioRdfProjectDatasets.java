// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.RdfFormat;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.versioning.VersioningGraphs;
import io.kogn.rdf.versioning.VocabVersioning;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.ExportMetadata;
import de.hauschel.arknet.persistence.Prefixes;
import de.hauschel.arknet.prj.application.port.out.ProjectDatasets;
import de.hauschel.arknet.prj.domain.DatasetInUseException;

/**
 * kognio-rdf-backed {@link ProjectDatasets}: counts the typed resources in a project's dataset,
 * removes the dataset through {@link DatasetLifecycle#delete} and writes its backup.
 *
 * <p><strong>Nothing is created by looking.</strong> {@link DatasetLifecycle#acquire} opens a
 * dataset by creating it if it is missing. Counting and deleting therefore ask {@link DatasetLifecycle#list}
 * first and leave a dataset that is not there alone - a project that never wrote anything must not
 * gain an empty dataset by being counted, which {@code project_list} would then offer for
 * adoption.</p>
 *
 * <p><strong>What counts as a model resource.</strong> Every subject carrying an {@code rdf:type}
 * in the project's content graph ({@link ContentGraph}) - the same graph {@code StoreReader} reads
 * for {@code store_overview}, so a project that report shows as empty is one this port reports as
 * empty. A resource carrying two types is counted under each; the types are shortened to the
 * prefixed names the store report uses.</p>
 *
 * <p><strong>History is counted apart.</strong> The library versioning keeps publish entries and
 * tombstones in a history graph of its own, which the model count leaves out, so
 * {@link #historyEntryCount} answers for it: a dataset without model resources can still hold
 * history.</p>
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
 *
 * <p><strong>The backup hides nothing.</strong> {@link #export} serialises the complete dataset -
 * default graph and every named graph alike - unlike {@code StoreReader}, which excludes the
 * provenance and project-identity graphs because it feeds a view of the current model, not of its
 * own machinery. A backup exists precisely to restore both the model and that machinery, so
 * excluding either graph here would silently drop data from the backup rather than declutter a
 * view of it. Serialisation itself is {@link DatasetHandle#datasetExport()} (kognio-rdf 0.2.2): a
 * spec-compliant, RDF4J-Rio-backed writer, not a hand-rolled one - an earlier hand-rolled version
 * fell back to {@code RDFTerm#ntriplesString()} for literal objects, which the RDF4J-backed term
 * implementation resolves to {@code Value#toString()}, and that method does not escape an embedded
 * {@code "}, {@code \} or newline in the lexical form. Any requirement/term/use-case text
 * containing one produced syntactically invalid TriG that still reported as a successful
 * export.</p>
 *
 * <p><strong>One graph the store does not hold.</strong> Every dump ends with
 * {@link ExportMetadata}'s envelope (issue #194) - which server version, built when, exported
 * when, against which ontology module versions - in a named graph of its own. It is appended to
 * the serialised text and never written into the dataset, so the store keeps exactly the triples
 * it had; a reader who wants the store alone drops that one graph.</p>
 */
public final class KognioRdfProjectDatasets implements ProjectDatasets {

    /** How often {@link #delete} tries before it gives up on an open lease. */
    static final int DEFAULT_DELETE_ATTEMPTS = 20;

    /** How long {@link #delete} waits between two attempts. */
    static final Duration DEFAULT_DELETE_PAUSE = Duration.ofMillis(50);

    private static final String COUNT_BY_TYPE = "SELECT ?type (COUNT(DISTINCT ?s) AS ?count) WHERE { "
            + "GRAPH " + ContentGraph.SPARQL_IRIREF + " { ?s a ?type } } GROUP BY ?type";

    /** One history entry - a publish or a tombstone - is one subject with a sequence number. */
    private static final String COUNT_HISTORY_ENTRIES = "SELECT (COUNT(DISTINCT ?e) AS ?count) WHERE { GRAPH <"
            + VersioningGraphs.HISTORY.getIRIString() + "> { ?e <" + VocabVersioning.SEQUENCE.getIRIString()
            + "> ?sequence } }";

    private final DatasetLifecycle lifecycle;
    private final ExportMetadata exportMetadata;
    private final Prefixes prefixes = Prefixes.defaults();
    private final int deleteAttempts;
    private final Duration deletePause;

    /**
     * @param lifecycle      the shared dataset lifecycle every project dataset is acquired through
     * @param exportMetadata the envelope appended to every backup (must not be {@code null}) - not
     *                       optional, because a dump that sometimes states its origin and sometimes
     *                       does not is worse than one that never does: the absence would read as a
     *                       fact about the exporting server rather than about the wiring
     */
    public KognioRdfProjectDatasets(final DatasetLifecycle lifecycle, final ExportMetadata exportMetadata) {
        this(lifecycle, exportMetadata, DEFAULT_DELETE_ATTEMPTS, DEFAULT_DELETE_PAUSE);
    }

    /**
     * @param lifecycle      the shared dataset lifecycle every project dataset is acquired through
     * @param exportMetadata the envelope appended to every backup
     * @param deleteAttempts how often {@link #delete} tries before reporting an open lease, at least 1
     * @param deletePause    the wait between two attempts
     */
    KognioRdfProjectDatasets(final DatasetLifecycle lifecycle, final ExportMetadata exportMetadata,
            final int deleteAttempts, final Duration deletePause) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.exportMetadata = Objects.requireNonNull(exportMetadata, "exportMetadata");
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
    public long historyEntryCount(final ProjectId projectId) {
        final DatasetId dataset = datasetOf(projectId);
        if (!lifecycle.list().contains(dataset)) {
            return 0L;
        }
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            return handle.sparqlQuery().select(COUNT_HISTORY_ENTRIES)
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

    @Override
    public void export(final ProjectId projectId, final OutputStream out) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(out, "out");
        try (DatasetHandle handle = lifecycle.acquire(datasetOf(projectId))) {
            handle.datasetExport().export(out, RdfFormat.TRIG);
        }
        // After the dataset export, not before: the envelope states when the export ended, and
        // appending keeps it out of the way of a reader that only skims the first graph blocks.
        try {
            out.write(exportMetadata.trigBlock().getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (final IOException failure) {
            throw new UncheckedIOException("failed to append the export metadata graph", failure);
        }
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
