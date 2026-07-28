// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;

import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.DuplicateProjectLabelException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.ProjectId;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;
import de.hauschel.arknet.prj.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.prj.domain.StaleProjectException;

/**
 * Out-adapter: {@link ProjectRegistry} backed by the kognio-rdf substrate ({@code io.kogn.rdf},
 * embeddable RDF store).
 *
 * <p><strong>Every method addresses the one reserved system dataset - always.</strong> Unlike
 * every other bounded context's out-adapter in this codebase, no method here takes a
 * {@code WorkspaceId}/{@code ProjectId} routing parameter: {@link ProjectRegistry}'s own javadoc
 * explains why - there is exactly one registry, and it lives permanently in
 * {@link ProjectId#RESERVED_SYSTEM_DATASET} (ADR-016 decision 6). This class hard-codes that one
 * dataset id ({@link #SYSTEM_DATASET}) rather than accepting it as a parameter, so a caller
 * cannot accidentally point the registry at a project's own dataset.</p>
 *
 * <p>Maps a {@link Project} to its RDF triples via the shared {@link ProjectGraphs} helper (see
 * that class's javadoc for the deterministic anchor-identity design this adapter's central
 * invariant rests on), stored in {@link ArkprjVocabulary#REGISTRY_GRAPH}. This class depends only
 * on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) and {@link SimpleRdf} - it
 * never imports RDF4J. The backend ({@link DatasetLifecycle} implementation) is supplied by the
 * composition root.</p>
 *
 * <p><strong>Register vs. compare-and-update (opaque identity).</strong> Identity is opaque and
 * minted once ({@link ProjectId}'s javadoc), so "insert or replace by identity" is not one
 * coherent operation, the same reasoning the requirements adapter follows: an identity either
 * already exists (an update) or it does not (a create). The transactional mechanics - the
 * in-transaction {@code contains} existence check, the label-uniqueness check (via
 * {@code dcterms:identifier}, since the label <em>is</em> {@code dcterms:identifier} - see the
 * ontology's "identity vs. label" note, no separate label property), the SHACL gate and the
 * commit-conflict translation - live in the shared {@link WriteFunnel} (ADR-013), not here.
 * {@link #register} rejects an existing identity with {@link ResourceAlreadyExistsException} and
 * a label collision with {@link DuplicateProjectLabelException}; {@link #compareAndUpdate}
 * rejects a missing identity with {@link ProjectNotFoundException} and a stale
 * {@code expectedHead} with {@link StaleProjectException}. There is no unconditional update:
 * every correction to an already-registered project goes through the compare-and-set guard.</p>
 *
 * <p><strong>Anchor uniqueness is enforced inside the write transaction, not via a SPARQL
 * {@code ASK} guard.</strong> ADR-016 decision 4's central invariant - an anchor belongs to at
 * most one project - is checked in {@link #checkAnchorUniqueness} using
 * {@code DatasetTx#contains(graph, anchorIri, null, null)} against the write's own
 * {@link DatasetTx}, the same pattern the shared {@link WriteFunnel} already uses for its own
 * existence checks (ADR-013 Nachtrag): an {@code ASK} query on a not-yet-known IRI is not
 * conflict-protected under {@code SERIALIZABLE} the way {@code contains} is. This is exactly why
 * {@link ProjectGraphs}'s deterministic anchor IRI matters - the check needs a concrete subject to
 * ask {@code contains} about <em>before</em> the write commits, which a randomly minted anchor
 * identity could never offer.</p>
 *
 * <p><strong>Two uniqueness rules, so a lost race needs attributing (issue #181).</strong> Both
 * guards above pass when two registrations genuinely overlap - neither transaction sees the other's
 * uncommitted write under {@code SERIALIZABLE} - and the loser is rejected by the store at commit
 * time, which reveals only that it lost, not what it collided with. Unlike the four model contexts,
 * this one has two rules a write can break (label, anchor) and no {@code CodeAssignment}-style
 * retry that would absorb the signal, so {@link #attributeLostRegistration} re-reads the committed
 * state and names the actual collision. The shared {@link WriteFunnel} takes that decision as a
 * parameter for exactly this reason.</p>
 *
 * <p><strong>Replace-by-identity leaves no orphaned anchor nodes.</strong> {@link #writeBody}, on
 * an update, first deletes the project subject's own triples <em>and</em> the triples of every
 * anchor node the project held <em>before</em> the update (read via {@link #deleteProjectAndItsAnchors}
 * before anything is deleted), then runs the anchor-uniqueness check, then re-adds the candidate
 * graph. An anchor kept across the update maps to the same deterministic node (see
 * {@link ProjectGraphs}), so it is deleted and immediately re-added bit-identical - not retired
 * and re-minted. An anchor dropped from the project has its node's triples deleted and nothing
 * re-adds them, so it disappears cleanly rather than lingering as a dangling {@code arkprj:Anchor}
 * with no incoming {@code arkprj:anchor} edge. A foreign anchor - one belonging to a
 * <em>different</em> project - was never among this project's own anchors, so it is never touched
 * by the delete step; it still exists when {@link #checkAnchorUniqueness} runs immediately after,
 * so the collision is still caught and the whole transaction (including the delete) rolls back.</p>
 *
 * <p><strong>SHACL write-gate.</strong> The gate mechanics - validate the candidate instance graph
 * against the project SHACL shapes before the write transaction opens, throw
 * {@link WriteConstraintViolationException} on a violation, persist nothing - live in the shared
 * {@link WriteFunnel} (ADR-013). No validation-only asserted context is needed: unlike the
 * requirements adapter's {@code arkreq:usesTerm}, nothing in this candidate graph references a
 * subject outside of it.</p>
 */
public class KognioRdfProjectRegistry implements ProjectRegistry {

    private static final DatasetId SYSTEM_DATASET = new DatasetId(ProjectId.RESERVED_SYSTEM_DATASET);

    private final DatasetLifecycle lifecycle;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire the reserved system dataset
     *                  from - read paths only, the write path goes through {@code funnel} (must
     *                  not be {@code null})
     * @param funnel    the shared write funnel (ADR-013) running the SHACL gate, dataset
     *                  acquisition and existence/head checks for every {@link #register}/
     *                  {@link #compareAndUpdate} (must not be {@code null})
     */
    KognioRdfProjectRegistry(DatasetLifecycle lifecycle, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void register(Project project) {
        Objects.requireNonNull(project, "project");
        write(null, project, false);
    }

    @Override
    public void compareAndUpdate(String expectedHead, Project project) {
        Objects.requireNonNull(project, "project");
        write(expectedHead, project, true);
    }

    private void write(String expectedHead, Project project, boolean exists) {
        String projectIriString = ProjectGraphs.projectIri(project.id());
        String projectSubject = SparqlTerms.iriRef(projectIriString);
        IRI graphIri = rdf.createIRI(ArkprjVocabulary.REGISTRY_GRAPH);
        Graph candidate = ProjectGraphs.buildGraph(project);

        if (exists) {
            funnel.compareAndUpdate(SYSTEM_DATASET, ArkprjVocabulary.REGISTRY_GRAPH, projectIriString,
                    expectedHead, candidate, null,
                    () -> new ProjectNotFoundException(project.id()),
                    () -> new StaleProjectException(project.id()),
                    tx -> writeBody(tx, graphIri, projectSubject, project, candidate, true));
        } else {
            funnel.create(SYSTEM_DATASET, ArkprjVocabulary.REGISTRY_GRAPH, projectIriString, project.label(),
                    candidate, null,
                    () -> new ResourceAlreadyExistsException(project.id()),
                    () -> new DuplicateProjectLabelException(project.label()),
                    conflict -> attributeLostRegistration(project, conflict),
                    tx -> writeBody(tx, graphIri, projectSubject, project, candidate, false));
        }
    }

    /**
     * Names what a registration that lost a commit race actually collided with (issue #181).
     *
     * <p>{@link WriteFunnel#create}'s own conflict translation cannot do this: a lost commit tells
     * it only that somebody wrote first, and this context guards <em>two</em> uniqueness rules, not
     * one - the label via the funnel's {@code code} parameter, the anchor via
     * {@link #checkAnchorUniqueness} inside the body (ADR-016 decision 4). Reporting every lost
     * race as a label collision would tell a caller who lost on an <em>anchor</em> that its label
     * is taken, when that label may never have been used. The four model contexts are not exposed
     * to this: a business code is the only thing that can collide there, and {@code CodeAssignment}
     * heals the signal before any caller sees it - {@link #register} has no such retry (there is no
     * {@code PRJ-N} code to recompute), so what this method returns is what the caller reads.</p>
     *
     * <p>Runs after the write transaction was rolled back, so these reads see committed state
     * only - the winner's write included. The anchor is checked before the label: it is the rule
     * whose violation crosses the project boundary, so when both collide it is the one worth
     * naming. Attributing nothing, the store's own conflict is returned unchanged rather than
     * dressed up as a collision that did not happen. That residual case is not what today's store
     * does to two unrelated registrations - {@code ProjectRegistryRealStoreConcurrencyTest} shows
     * those overlap without either losing - but the fallback stays: which writes a store finds in
     * conflict is a property of the store behind the port, and it is swappable (ADR-001).</p>
     */
    private RuntimeException attributeLostRegistration(Project project, RuntimeException conflict) {
        for (Anchor anchor : project.anchors()) {
            Optional<ProjectId> owner = findByAnchor(anchor).map(Project::id);
            if (owner.isPresent() && !owner.get().equals(project.id())) {
                return new AnchorAlreadyRegisteredException(anchor, owner.get());
            }
        }
        if (labelHeldByAnotherProject(project)) {
            return new DuplicateProjectLabelException(project.label());
        }
        return conflict;
    }

    /**
     * Whether {@code project}'s label is {@code dcterms:identifier} of some <em>other</em>
     * registered project - the label half of {@link #attributeLostRegistration}'s attribution.
     * Excludes the project's own subject so a rewrite of an already-registered project is not
     * mistaken for a collision with itself.
     */
    private boolean labelHeldByAnotherProject(Project project) {
        String query = "ASK { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + "?other <" + VocabDct.IDENTIFIER.getIRIString() + "> \""
                + SparqlTerms.escape(project.label()) + "\" "
                + "FILTER (?other != " + SparqlTerms.iriRef(ProjectGraphs.projectIri(project.id())) + ") } }";

        try (DatasetHandle handle = lifecycle.acquire(SYSTEM_DATASET)) {
            return handle.sparqlQuery().ask(query);
        }
    }

    /**
     * Replaces the project's triples inside an already-open write transaction - see the class
     * javadoc's "replace-by-identity leaves no orphaned anchor nodes" section for the ordering
     * this relies on: delete this project's own old state first, check anchor uniqueness against
     * what is left, only then add the new candidate.
     */
    private void writeBody(DatasetTx tx, IRI graphIri, String projectSubject, Project project, Graph candidate,
            boolean exists) {
        if (exists) {
            deleteProjectAndItsAnchors(tx, graphIri, projectSubject);
        }
        checkLabelUniqueness(tx, graphIri, project);
        checkAnchorUniqueness(tx, graphIri, project);
        tx.add(graphIri, candidate);
    }

    /**
     * Deletes the project subject's own triples and the triples of every anchor node it held
     * before this write - both read <em>before</em> anything is deleted, inside this same write
     * transaction, so nothing is lost if a later step in {@link #writeBody} rejects the write.
     */
    private void deleteProjectAndItsAnchors(DatasetTx tx, IRI graphIri, String projectSubject) {
        String selectAnchors = "SELECT ?a WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + projectSubject + " <" + ArkprjVocabulary.ANCHOR + "> ?a } }";
        List<IRI> previousAnchors = tx.select(selectAnchors).map(row -> iriOf(row, "a")).toList();

        tx.update("DELETE WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + projectSubject + " ?p ?o } }");
        for (IRI anchor : previousAnchors) {
            tx.update("DELETE WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                    + SparqlTerms.iriRef(anchor.getIRIString()) + " ?p ?o } }");
        }
    }

    /**
     * Enforces label uniqueness inside the write transaction. Redundant, but harmless, on the
     * {@link #register} path - {@link WriteFunnel#create} already runs the identical check via
     * its {@code code} parameter (here bound to {@code project.label()}) before this body ever
     * runs. It is not redundant on the {@link #compareAndUpdate} path: unlike {@link WriteFunnel#create},
     * {@link WriteFunnel#compareAndUpdate} takes no business-code parameter and therefore performs
     * no such check itself - {@link ProjectRegistry}'s own contract nonetheless promises
     * {@link DuplicateProjectLabelException} on a rename that collides with a different project's
     * label, so this adapter enforces it itself, here, after {@link #deleteProjectAndItsAnchors}
     * has already removed this project's own previous {@code dcterms:identifier} triple - an
     * unchanged label therefore no longer matches its own, now-deleted triple and is not mistaken
     * for a collision with itself.
     */
    private void checkLabelUniqueness(DatasetTx tx, IRI graphIri, Project project) {
        IRI identifierPredicate = rdf.createIRI(VocabDct.IDENTIFIER.getIRIString());
        Literal labelLiteral = rdf.createLiteral(project.label());
        if (tx.contains(graphIri, null, identifierPredicate, labelLiteral)) {
            throw new DuplicateProjectLabelException(project.label());
        }
    }

    /**
     * Enforces ADR-016 decision 4 inside the write transaction - see the class javadoc for why
     * {@code contains} rather than {@code ASK}. Any anchor of {@code project} that already has
     * triples at this point in the transaction belongs to a different, still-existing project (an
     * update has already cleared this project's own previous anchors in
     * {@link #deleteProjectAndItsAnchors}), so the write is rejected.
     */
    private void checkAnchorUniqueness(DatasetTx tx, IRI graphIri, Project project) {
        for (Anchor anchor : project.anchors()) {
            IRI anchorIri = rdf.createIRI(ProjectGraphs.anchorIri(anchor));
            if (tx.contains(graphIri, anchorIri, null, null)) {
                throw new AnchorAlreadyRegisteredException(anchor, ownerOf(tx, anchorIri));
            }
        }
    }

    /**
     * Resolves the project that already owns {@code anchorIri}, for the collision message. No
     * conflict protection is needed here (unlike {@link #checkAnchorUniqueness}'s
     * {@code contains} call): the write is already doomed to fail once this method is reached, so
     * a plain {@code tx.select} suffices.
     */
    private ProjectId ownerOf(DatasetTx tx, IRI anchorIri) {
        String query = "SELECT ?project WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + "?project <" + ArkprjVocabulary.ANCHOR + "> " + SparqlTerms.iriRef(anchorIri.getIRIString())
                + " } }";
        return tx.select(query)
                .findFirst()
                .map(row -> ProjectGraphs.projectIdOf(iriOf(row, "project").getIRIString()))
                .orElseThrow(() -> new IllegalStateException(
                        "anchor " + anchorIri.getIRIString() + " exists but has no owning project - "
                                + "registry graph is inconsistent"));
    }

    @Override
    public Optional<Project> findByAnchor(Anchor anchor) {
        Objects.requireNonNull(anchor, "anchor");

        String anchorIriString = ProjectGraphs.anchorIri(anchor);
        String query = "SELECT ?project WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + "?project <" + ArkprjVocabulary.ANCHOR + "> " + SparqlTerms.iriRef(anchorIriString) + " } }";

        try (DatasetHandle handle = lifecycle.acquire(SYSTEM_DATASET)) {
            Optional<String> projectIriString = handle.sparqlQuery().select(query)
                    .map(row -> iriOf(row, "project").getIRIString())
                    .findFirst();
            if (projectIriString.isEmpty()) {
                return Optional.empty();
            }
            return readProject(handle, projectIriString.get());
        }
    }

    @Override
    public Optional<Project> findById(ProjectId id) {
        Objects.requireNonNull(id, "id");

        try (DatasetHandle handle = lifecycle.acquire(SYSTEM_DATASET)) {
            return readProject(handle, ProjectGraphs.projectIri(id));
        }
    }

    /**
     * Lists every registered project.
     *
     * <p>Deliberately looks up each project's anchors in {@code anchorsByProject} with
     * {@link Map#get} rather than {@link Map#getOrDefault} with an empty list: the {@link Project}
     * constructor rejects an empty anchor list with {@link IllegalArgumentException}, so a default
     * would not make a project without anchors survive here - it would only move the failure one
     * line down and dress it up as if it were handled. On the normal write path this case cannot
     * arise at all: the project SHACL shape's {@code sh:minCount 1} on {@code arkprj:anchor}
     * rejects an anchor-less project before it is ever written (see the SHACL write-gate tests in
     * this class). A missing entry here would mean the registry graph itself is inconsistent, and
     * {@link Project}'s constructor rejects the resulting {@code null} with the very message that
     * names the invariant ("a project must hold at least one anchor") - a louder and more useful
     * failure than silently returning a project that violates the domain's own invariant.</p>
     */
    @Override
    public List<Project> findAll() {
        String query = "SELECT ?project ?label WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + "?project a <" + ArkprjVocabulary.PROJECT_TYPE + "> ; <" + VocabDct.IDENTIFIER.getIRIString()
                + "> ?label } }";

        try (DatasetHandle handle = lifecycle.acquire(SYSTEM_DATASET)) {
            Map<String, List<Anchor>> anchorsByProject = readAnchorsByProject(handle);
            return handle.sparqlQuery().select(query)
                    .map(row -> {
                        String projectIriString = iriOf(row, "project").getIRIString();
                        return new Project(ProjectGraphs.projectIdOf(projectIriString),
                                literalOf(row, "label").getLexicalForm(),
                                anchorsByProject.get(projectIriString));
                    })
                    .toList();
        }
    }

    /**
     * Reads a project's current state together with its concurrency token, mirroring the
     * requirements adapter's {@code findCurrentByCode}: the head must come from the same read as
     * the label, or it would already be stale by the time the caller observes it. The anchors
     * themselves are read afterwards, by a separate query ({@link #readAnchors}) - safe by the
     * same ordering argument the requirements adapter documents for its own follow-up reads: the
     * head is read first, so it is never fresher than any part of the state paired with it.
     */
    @Override
    public Optional<ProjectRegistry.CurrentProject> findCurrentById(ProjectId id) {
        Objects.requireNonNull(id, "id");

        String projectIriString = ProjectGraphs.projectIri(id);
        String projectSubject = SparqlTerms.iriRef(projectIriString);
        String query = "SELECT ?label ?head WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + projectSubject + " <" + VocabDct.IDENTIFIER.getIRIString() + "> ?label } "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + projectSubject + " <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        try (DatasetHandle handle = lifecycle.acquire(SYSTEM_DATASET)) {
            Optional<BindingSet> found = handle.sparqlQuery().select(query).findFirst();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            BindingSet row = found.get();
            List<Anchor> anchors = readAnchors(handle.sparqlQuery()::select, projectSubject);
            Project project = new Project(id, literalOf(row, "label").getLexicalForm(), anchors);
            String head = row.getValue("head")
                    .filter(IRI.class::isInstance)
                    .map(value -> ((IRI) value).getIRIString())
                    .orElse(null);
            return Optional.of(new ProjectRegistry.CurrentProject(project, head));
        }
    }

    /** Shared single-project read for {@link #findByAnchor} and {@link #findById}. */
    private Optional<Project> readProject(DatasetHandle handle, String projectIriString) {
        String projectSubject = SparqlTerms.iriRef(projectIriString);
        String query = "SELECT ?label WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + projectSubject + " <" + VocabDct.IDENTIFIER.getIRIString() + "> ?label } }";

        Optional<String> label = handle.sparqlQuery().select(query)
                .map(row -> literalOf(row, "label").getLexicalForm())
                .findFirst();
        if (label.isEmpty()) {
            return Optional.empty();
        }
        List<Anchor> anchors = readAnchors(handle.sparqlQuery()::select, projectSubject);
        return Optional.of(new Project(ProjectGraphs.projectIdOf(projectIriString), label.get(), anchors));
    }

    /**
     * Reads one project's anchors, ordered by anchor value (deterministically - RDF carries no
     * intrinsic statement order and {@link Project} compares its {@code anchors} list
     * positionally).
     */
    private List<Anchor> readAnchors(Function<String, Stream<BindingSet>> selectFn, String projectSubject) {
        String query = "SELECT ?value ?type WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + projectSubject + " <" + ArkprjVocabulary.ANCHOR + "> ?a . "
                + "?a <" + ArkprjVocabulary.ANCHOR_VALUE + "> ?value . "
                + "?a <" + ArkprjVocabulary.ANCHOR_TYPE + "> ?type } } ORDER BY ?value";
        return selectFn.apply(query)
                .map(row -> new Anchor(literalOf(row, "value").getLexicalForm(),
                        ProjectGraphs.anchorTypeFromIri(iriOf(row, "type").getIRIString())))
                .toList();
    }

    /** Bulk variant of {@link #readAnchors}: every project's anchors in one query, for {@link #findAll}. */
    private Map<String, List<Anchor>> readAnchorsByProject(DatasetHandle handle) {
        String query = "SELECT ?project ?value ?type WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + "?project <" + ArkprjVocabulary.ANCHOR + "> ?a . "
                + "?a <" + ArkprjVocabulary.ANCHOR_VALUE + "> ?value . "
                + "?a <" + ArkprjVocabulary.ANCHOR_TYPE + "> ?type } } ORDER BY ?project ?value";
        Map<String, List<Anchor>> byProject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> byProject
                .computeIfAbsent(iriOf(row, "project").getIRIString(), key -> new ArrayList<>())
                .add(new Anchor(literalOf(row, "value").getLexicalForm(),
                        ProjectGraphs.anchorTypeFromIri(iriOf(row, "type").getIRIString()))));
        return byProject;
    }

    // ---- helpers -----------------------------------------------------------------------

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    private static Literal literalOf(BindingSet row, String name) {
        return (Literal) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
