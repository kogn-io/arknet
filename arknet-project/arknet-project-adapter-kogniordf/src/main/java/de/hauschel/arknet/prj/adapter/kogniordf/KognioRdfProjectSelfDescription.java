// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import java.util.List;
import java.util.Objects;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.prj.application.port.out.ProjectSelfDescription;
import de.hauschel.arknet.prj.domain.Project;

/**
 * Out-adapter: {@link ProjectSelfDescription} backed by the kognio-rdf substrate. Writes the same
 * triple shape {@link KognioRdfProjectRegistry} writes into the registry (see
 * {@link ProjectGraphs} - the shared builder that keeps the two from drifting apart), but into
 * {@code project}'s own dataset ({@code new DatasetId(project.id().value())}), named graph
 * {@link ArkprjVocabulary#IDENTITY_GRAPH}, per ADR-016 decision 7.
 *
 * <p><strong>With the SHACL gate, without the shared {@link WriteFunnel}.</strong> Both halves of
 * that sentence are deliberate:</p>
 *
 * <ul>
 *   <li><strong>With the gate.</strong> Every write must be validated, and this is a write -
 *       {@link #describe} runs {@link ShaclWriteGate#enforce(io.kogn.rdf.terms.ReadableGraph)} on
 *       the candidate graph before opening the transaction, exactly like every funnelled write
 *       does, and throws the identical {@link WriteConstraintViolationException} on a
 *       violation.</li>
 *   <li><strong>Without the funnel.</strong> {@link WriteFunnel} exists to guard the distinction
 *       between "insert" and "replace" - {@code create} rejects an existing identity,
 *       {@code compareAndUpdate} rejects a stale one. {@link #describe} has no such distinction to
 *       guard: it is an idempotent replace-by-identity with no create/update branch, because this
 *       adapter has no cheap way to know in advance whether {@code project}'s own dataset already
 *       carries a self-description - and a read to find out, taken outside this method's own
 *       write transaction, would reopen exactly the TOCTOU window the funnel's in-transaction
 *       {@code contains} checks exist to close. There is also nothing here for a concurrency
 *       token to protect: the registry (via {@link KognioRdfProjectRegistry#compareAndUpdate}) is
 *       the single, CAS-guarded, revisioned source of truth for a project's anchors and label -
 *       see {@link ProjectSelfDescription}'s own javadoc for why the application service always
 *       calls the registry first. A second, independent revision chain over the very same
 *       information in the project's own dataset would only fill that dataset's provenance graph
 *       with routing noise; it would never answer a question the registry's revision trail does
 *       not already answer. The self-description is a rebuildable duplicate of registry state
 *       (ADR-016 decision 7), not a second model object with its own history worth tracking.</li>
 * </ul>
 *
 * <p>Because there is no create/update distinction, the write body is a plain delete-then-add
 * inside one transaction: delete the project subject's own triples and the triples of every
 * anchor node found under it (mirroring {@link KognioRdfProjectRegistry}'s orphan-avoidance, for
 * the same reason - see that class's javadoc), then add the freshly built candidate graph. Run
 * twice with the same {@link Project}, the result is the same triple set both times - idempotent
 * by construction, not merely by accident.</p>
 */
public class KognioRdfProjectSelfDescription implements ProjectSelfDescription {

    private final DatasetLifecycle lifecycle;
    private final ShaclWriteGate gate;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire a project's own dataset from
     *                  (must not be {@code null})
     * @param gate      the SHACL write-gate validating every candidate graph before the write
     *                  transaction opens (must not be {@code null})
     */
    KognioRdfProjectSelfDescription(DatasetLifecycle lifecycle, ShaclWriteGate gate) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    @Override
    public void describe(Project project) {
        Objects.requireNonNull(project, "project");

        Graph candidate = ProjectGraphs.buildGraph(project);
        gate.enforce(candidate);

        String projectSubject = SparqlTerms.iriRef(ProjectGraphs.projectIri(project.id()));
        IRI graphIri = rdf.createIRI(ArkprjVocabulary.IDENTITY_GRAPH);
        DatasetId dataset = new DatasetId(project.id().value());

        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            handle.transactor().inTransaction(tx -> {
                replacePreviousSelfDescription(tx, graphIri, projectSubject);
                tx.add(graphIri, candidate);
                return null;
            });
        }
    }

    /**
     * Deletes whatever self-description {@code project}'s own dataset already carried - the
     * project subject's own triples and the triples of every anchor node found under it - before
     * the fresh candidate graph is added. See the class javadoc for why an orphaned anchor node
     * must not be left behind here for the same reason it must not in
     * {@link KognioRdfProjectRegistry}.
     */
    private void replacePreviousSelfDescription(DatasetTx tx, IRI graphIri, String projectSubject) {
        String selectAnchors = "SELECT ?a WHERE { GRAPH <" + ArkprjVocabulary.IDENTITY_GRAPH + "> { "
                + projectSubject + " <" + ArkprjVocabulary.ANCHOR + "> ?a } }";
        List<IRI> previousAnchors = tx.select(selectAnchors).map(row -> iriOf(row, "a")).toList();

        tx.update("DELETE WHERE { GRAPH <" + ArkprjVocabulary.IDENTITY_GRAPH + "> { "
                + projectSubject + " ?p ?o } }");
        for (IRI anchor : previousAnchors) {
            tx.update("DELETE WHERE { GRAPH <" + ArkprjVocabulary.IDENTITY_GRAPH + "> { "
                    + SparqlTerms.iriRef(anchor.getIRIString()) + " ?p ?o } }");
        }
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
