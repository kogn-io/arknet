// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
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
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.application.port.out.RevisionToken;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.DuplicateProjectLabelException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;
import de.hauschel.arknet.prj.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.prj.domain.StaleProjectException;
import de.hauschel.arknet.prj.domain.UnattributedRegistrationConflictException;

/**
 * Out-adapter: {@link ProjectRegistry} backed by the kognio-rdf substrate ({@code io.kogn.rdf},
 * embeddable RDF store).
 *
 * <p><strong>Every method addresses the one reserved system dataset - always.</strong> Unlike
 * every other bounded context's out-adapter in this codebase, no method here takes a
 * {@code ProjectId}/{@code ProjectId} routing parameter: {@link ProjectRegistry}'s own javadoc
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
 * <p><strong>Two uniqueness rules, so a lost race needs attributing.</strong> Both
 * guards above pass when two registrations genuinely overlap - neither transaction sees the other's
 * uncommitted write under {@code SERIALIZABLE} - and the loser is rejected by the store at commit
 * time, which reveals only that it lost, not what it collided with. Unlike the four model contexts,
 * this one has two rules a write can break (label, anchor) and no {@code CodeAssignment}-style
 * retry that would absorb the signal, so {@link #attributeLostRegistration} re-reads the committed
 * state and names the actual collision, wrapping the residual case in
 * {@link de.hauschel.arknet.prj.domain.UnattributedRegistrationConflictException} for
 * {@code ProjectService#register} to retry rather than leaving it to the caller. The shared
 * {@link WriteFunnel} takes that decision as a parameter for exactly this reason.</p>
 *
 * <p><strong>Replace-by-identity leaves no orphaned anchor nodes.</strong> {@link #replaceExistingProject}
 * first deletes the project subject's own triples <em>and</em> the triples of every
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
    private final DisplayLocale displayLocale;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire the reserved system
     *                      dataset from - read paths only, the write path goes through
     *                      {@code funnel} (must not be {@code null})
     * @param displayLocale the display-language preference selecting which {@code
     *                      dcterms:description} the read paths surface for a multilingual
     *                      project (must not be {@code null})
     * @param funnel        the shared write funnel (ADR-013) running the SHACL gate, dataset
     *                      acquisition and existence/head checks for every {@link #register}/
     *                      {@link #compareAndUpdate}/{@link #updateAttributes} (must not be
     *                      {@code null})
     */
    KognioRdfProjectRegistry(DatasetLifecycle lifecycle, DisplayLocale displayLocale, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void register(Project project, String description, String descriptionLanguage,
            String defaultLanguage) {
        Objects.requireNonNull(project, "project");
        String projectIriString = ProjectGraphs.projectIri(project.id());
        IRI projectIri = rdf.createIRI(projectIriString);
        IRI graphIri = rdf.createIRI(ArkprjVocabulary.REGISTRY_GRAPH);
        Graph candidate = ProjectGraphs.buildGraph(project);
        // description/defaultLanguage are never part of ProjectGraphs#buildGraph (see that
        // class's javadoc and this class's javadoc on updateAttributes): they are written here,
        // additively, only because this is a brand-new identity with nothing to preserve or
        // corrupt yet - compareAndUpdate's replace-by-identity write must never do the same.
        if (description != null) {
            candidate.add(projectIri, rdf.createIRI(ArkprjVocabulary.DESCRIPTION),
                    literalOf(description, canonicalLanguageTag(descriptionLanguage)));
        }
        if (defaultLanguage != null) {
            candidate.add(projectIri, rdf.createIRI(ArkprjVocabulary.DEFAULT_LANGUAGE),
                    rdf.createLiteral(canonicalLanguageTag(defaultLanguage)));
        }

        funnel.create(SYSTEM_DATASET, ArkprjVocabulary.REGISTRY_GRAPH, projectIriString, project.label(),
                candidate, null,
                () -> new ResourceAlreadyExistsException(project.id()),
                () -> new DuplicateProjectLabelException(project.label()),
                conflict -> attributeLostRegistration(project, conflict),
                tx -> insertNewProject(tx, graphIri, project, candidate));
    }

    @Override
    public void compareAndUpdate(RevisionToken expectedHead, Project project) {
        Objects.requireNonNull(project, "project");
        String projectIriString = ProjectGraphs.projectIri(project.id());
        String projectSubject = SparqlTerms.iriRef(projectIriString);
        IRI graphIri = rdf.createIRI(ArkprjVocabulary.REGISTRY_GRAPH);
        Graph candidate = ProjectGraphs.buildGraph(project);

        funnel.compareAndUpdate(SYSTEM_DATASET, ArkprjVocabulary.REGISTRY_GRAPH, projectIriString,
                expectedHead == null ? null : expectedHead.value(), candidate, null,
                () -> new ProjectNotFoundException(project.id()),
                () -> new StaleProjectException(project.id()),
                tx -> replaceExistingProject(tx, graphIri, projectSubject, project, candidate));
    }

    /**
     * Targeted patch of {@code dcterms:description}/{@code arkprj:defaultLanguage}, sharing
     * {@link #compareAndUpdate}'s CAS token but never its replace-by-identity write - see {@link
     * ProjectRegistry#updateAttributes} and {@link #deleteProjectAndItsAnchors}'s javadoc for why
     * these two predicates must stay outside that write entirely. Mirrors {@code
     * KognioRdfTermRepository#attemptUpdate}: reads the project's current label/anchors (needed
     * only to assert them to the SHACL gate - {@code prjshapes:ProjectShape} requires
     * {@code dcterms:identifier}/{@code arkprj:anchor} unconditionally, and this write never
     * touches either) before the write transaction, then deletes-and-reinserts only the
     * predicate(s) actually being replaced - {@code description}'s delete scoped to the same
     * language tag as the new value, exactly like {@code TermRepository#update}'s
     * {@code skos:prefLabel}/{@code skos:definition} patch.
     */
    @Override
    public Project updateAttributes(ProjectId projectId, RevisionToken expectedHead, String description,
            String descriptionLanguage, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        String descriptionTag = canonicalLanguageTag(descriptionLanguage);
        String defaultLanguageTag = canonicalLanguageTag(defaultLanguage);

        ProjectRegistry.CurrentProject current = findCurrentById(projectId)
                .orElseThrow(() -> new ProjectNotFoundException(projectId));
        Project currentProject = current.project();

        String projectIriString = ProjectGraphs.projectIri(projectId);
        IRI projectIri = rdf.createIRI(projectIriString);
        String projectSubject = SparqlTerms.iriRef(projectIriString);
        IRI graphIri = rdf.createIRI(ArkprjVocabulary.REGISTRY_GRAPH);

        // Only the predicate(s) actually being replaced go into the gate's candidate; the
        // project's own type, identifier and anchors - untouched by this method, but required by
        // prjshapes:ProjectShape - are asserted instead, validation-only, mirroring
        // KognioRdfTermRepository#attemptUpdate's assertedContext for an untouched prefLabel.
        Graph writeCandidate = rdf.createGraph();
        Graph assertedContext = rdf.createGraph();
        assertedContext.add(projectIri, VocabRdf.TYPE, rdf.createIRI(ArkprjVocabulary.PROJECT_TYPE));
        assertedContext.add(projectIri, VocabDct.IDENTIFIER, rdf.createLiteral(currentProject.label()));
        for (Anchor anchor : currentProject.anchors()) {
            assertedContext.add(projectIri, rdf.createIRI(ArkprjVocabulary.ANCHOR),
                    rdf.createIRI(ProjectGraphs.anchorIri(anchor)));
        }
        if (description != null) {
            writeCandidate.add(projectIri, rdf.createIRI(ArkprjVocabulary.DESCRIPTION),
                    literalOf(description, descriptionTag));
        }
        if (defaultLanguage != null) {
            writeCandidate.add(projectIri, rdf.createIRI(ArkprjVocabulary.DEFAULT_LANGUAGE),
                    rdf.createLiteral(defaultLanguageTag));
        }

        funnel.compareAndUpdate(SYSTEM_DATASET, ArkprjVocabulary.REGISTRY_GRAPH, projectIriString,
                expectedHead == null ? null : expectedHead.value(), writeCandidate, assertedContext,
                () -> new ProjectNotFoundException(projectId),
                () -> new StaleProjectException(projectId),
                tx -> {
                    if (description != null) {
                        tx.update(deleteDescriptionOfLanguage(projectSubject, descriptionTag));
                        tx.add(graphIri, singleTriple(projectIri, ArkprjVocabulary.DESCRIPTION,
                                literalOf(description, descriptionTag)));
                    }
                    if (defaultLanguage != null) {
                        tx.update(deleteAllTriplesOf(projectSubject, ArkprjVocabulary.DEFAULT_LANGUAGE));
                        tx.add(graphIri, singleTriple(projectIri, ArkprjVocabulary.DEFAULT_LANGUAGE,
                                rdf.createLiteral(defaultLanguageTag)));
                    }
                });

        return new Project(projectId, currentProject.label(), currentProject.anchors(),
                description != null ? description : currentProject.description(),
                defaultLanguage != null ? defaultLanguageTag : currentProject.defaultLanguage());
    }

    /** Deletes every existing triple of {@code subject} on {@code predicateIri} - a no-op if none exists. */
    private static String deleteAllTriplesOf(String subject, String predicateIri) {
        return "DELETE WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + subject + " <" + predicateIri + "> ?o } }";
    }

    /**
     * Canonicalizes a BCP-47 tag (e.g. {@code "DE"} -&gt; {@code "de"}), or {@code null} unchanged
     * - and rejects one that is not well-formed at all, via the shared kernel {@link LanguageTag}
     * (see that class's javadoc for why {@link Locale#forLanguageTag} is the wrong tool here: it
     * never throws, silently degrading a typo like {@code "de_DE"} to {@code "und"}).
     *
     * <p>{@link #deleteDescriptionOfLanguage}'s {@code FILTER(lang(?o) = "tag")} compares the raw
     * string RDF4J's {@code lang()} returns against this method's {@code tag} argument, so an
     * un-normalized case mismatch between two calls (e.g. {@code project_add(...,
     * language="de")} followed by {@code project_update(..., language="DE")}) leaves the existing
     * {@code @de} literal undeleted and inserts a second {@code @DE} one instead of correcting it
     * - the same class of bug fixed for {@code TermRepository#update}
     * ({@code KognioRdfTermRepository#canonicalLanguageTag}), only triggered by case here.
     * Canonicalizing every tag through this method before both writing a literal and building the
     * delete filter keeps stored tags in one consistent case, so a later scoped delete always
     * matches - the same guarantee {@code DisplayLocale#matching} already gives the read side by
     * comparing tags case-insensitively.</p>
     */
    private static String canonicalLanguageTag(String language) {
        return LanguageTag.canonicalize(language);
    }

    /**
     * Deletes only the existing {@code dcterms:description} triple(s) of {@code subject} whose
     * literal carries the same language tag as {@code language} - every other language-tagged (or
     * untagged) variant survives untouched. {@code lang(?o)} is {@code ""} for a plain, untagged
     * literal, which is exactly what {@code language == null} maps {@code tag} to below.
     */
    private static String deleteDescriptionOfLanguage(String subject, String language) {
        // The DELETE WHERE {...} shorthand only accepts quad patterns, no FILTER - the general
        // DELETE {...} WHERE {...} form is required to scope the delete by language.
        String tag = language == null ? "" : SparqlTerms.escape(language);
        return "DELETE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + subject + " <" + ArkprjVocabulary.DESCRIPTION + "> ?o } } "
                + "WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + subject + " <" + ArkprjVocabulary.DESCRIPTION + "> ?o . "
                + "FILTER(lang(?o) = \"" + tag + "\") } }";
    }

    /** A one-triple graph, for the common "insert exactly one new value" case in {@link #updateAttributes}. */
    private Graph singleTriple(IRI subject, String predicateIri, RDFTerm object) {
        Graph graph = rdf.createGraph();
        graph.add(subject, rdf.createIRI(predicateIri), object);
        return graph;
    }

    /** Builds a language-tagged literal, or a plain untagged one when {@code language} is {@code null}. */
    private Literal literalOf(String value, String language) {
        return language == null ? rdf.createLiteral(value) : rdf.createLiteral(value, language);
    }

    /**
     * Names what a registration that lost a commit race actually collided with.
     *
     * <p>{@link WriteFunnel#create}'s own conflict translation cannot do this: a lost commit tells
     * it only that somebody wrote first, and this context guards <em>two</em> uniqueness rules, not
     * one - the label via the funnel's {@code code} parameter, the anchor via
     * {@link #checkAnchorUniqueness} inside the body (ADR-016 decision 4). Reporting every lost
     * race as a label collision would tell a caller who lost on an <em>anchor</em> that its label
     * is taken, when that label may never have been used. The four model contexts are not exposed
     * to this: a business code is the only thing that can collide there, and {@code CodeAssignment}
     * heals the signal before any caller sees it - {@link #register} has no read-modify-write to
     * retry with a fresh read, but a plain repeat of the same, fully rolled-back create is honest
     * here (see {@link UnattributedRegistrationConflictException}'s javadoc) - so what this method
     * cannot attribute becomes the residual signal {@code ProjectService#register} retries on.</p>
     *
     * <p>Runs after the write transaction was rolled back, so these reads see committed state
     * only - the winner's write included. The anchor is checked before the label: it is the rule
     * whose violation crosses the project boundary, so when both collide it is the one worth
     * naming. Attributing nothing, the store's own conflict is wrapped in
     * {@link UnattributedRegistrationConflictException} rather than dressed up as a collision that
     * did not happen - the wrapping names no invented rule, it only makes the residual case
     * catchable by a core that must stay free of the store's own exception type (see that
     * exception's javadoc). That residual case is not what today's store does to two unrelated
     * registrations - {@code ProjectRegistryRealStoreConcurrencyTest} shows those overlap without
     * either losing - but the fallback stays: which writes a store finds in conflict is a property
     * of the store behind the port, and it is swappable (ADR-001).</p>
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
        return new UnattributedRegistrationConflictException(conflict);
    }

    /**
     * Whether {@code project}'s label is {@code dcterms:identifier} of some <em>other</em>
     * registered project - the label half of {@link #attributeLostRegistration}'s attribution.
     *
     * <p>The {@code FILTER (?other != <projectIri>)} is defensive, not load-bearing on the one
     * path that reaches this method today: {@link #attributeLostRegistration} runs only from
     * {@link #register}'s {@code commitConflict} translator, and {@link WriteFunnel#create}'s own
     * {@code alreadyExists} guard has already ruled out that this subject exists before the
     * translator ever runs - a freshly minted {@link ProjectId} (a UUID, never reused) that no
     * other writer could have committed. So on this path the filter can never actually exclude
     * anything; it exists to keep this method correct if a translator is ever wired onto the
     * {@link #compareAndUpdate} path too, where the subject <em>does</em> already exist and
     * excluding it would matter.</p>
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
     * Writes a freshly minted project's triples inside an already-open write transaction - the
     * tail of {@link #register}, reached once the funnel's own existence check has decided the
     * write may proceed. Unlike {@link #replaceExistingProject}, there is nothing under this
     * identity yet, so there is nothing to delete first.
     */
    private void insertNewProject(DatasetTx tx, IRI graphIri, Project project, Graph candidate) {
        checkLabelUniqueness(tx, graphIri, project);
        checkAnchorUniqueness(tx, graphIri, project);
        tx.add(graphIri, candidate);
    }

    /**
     * Replaces an already-registered project's triples inside an already-open write transaction -
     * the tail of {@link #compareAndUpdate}, reached once the funnel's own head comparison has
     * decided the write may proceed. See the class javadoc's "replace-by-identity leaves no
     * orphaned anchor nodes" section for the ordering this relies on: delete this project's own
     * old state first, check label and anchor uniqueness against what is left, only then add the
     * new candidate.
     */
    private void replaceExistingProject(DatasetTx tx, IRI graphIri, String projectSubject, Project project,
            Graph candidate) {
        deleteProjectAndItsAnchors(tx, graphIri, projectSubject);
        checkLabelUniqueness(tx, graphIri, project);
        checkAnchorUniqueness(tx, graphIri, project);
        tx.add(graphIri, candidate);
    }

    /**
     * Deletes the project subject's own triples and the triples of every anchor node it held
     * before this write - both read <em>before</em> anything is deleted, inside this same write
     * transaction, so nothing is lost if a later step in {@link #replaceExistingProject} rejects
     * the write.
     *
     * <p><strong>{@code dcterms:description}/{@code arkprj:defaultLanguage} are deliberately
     * excluded from this delete.</strong> Both are written only through {@link
     * #updateAttributes}'s targeted, language-scoped patch, never through this replace-by-identity
     * write - if this delete touched them too, every rename or attached anchor would silently
     * wipe a project's description (and every one of its language variants) the same way an
     * earlier {@code term_update} used to wipe a term's {@code skos:prefLabel} (issue #228). The
     * candidate {@link #replaceExistingProject} re-adds after this delete
     * ({@code ProjectGraphs#buildGraph}) never contains either predicate either, so the two stay
     * symmetric: neither deleted here nor re-added there.</p>
     */
    private void deleteProjectAndItsAnchors(DatasetTx tx, IRI graphIri, String projectSubject) {
        String selectAnchors = "SELECT ?a WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + projectSubject + " <" + ArkprjVocabulary.ANCHOR + "> ?a } }";
        List<IRI> previousAnchors = tx.select(selectAnchors).map(row -> iriOf(row, "a")).toList();

        // The DELETE WHERE {...} shorthand only accepts quad patterns, no FILTER - the general
        // DELETE {...} WHERE {...} form is required to exclude description/defaultLanguage.
        tx.update("DELETE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { " + projectSubject + " ?p ?o } } "
                + "WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + projectSubject + " ?p ?o . "
                + "FILTER(?p != <" + ArkprjVocabulary.DESCRIPTION + "> && ?p != <"
                + ArkprjVocabulary.DEFAULT_LANGUAGE + ">) } }");
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
            Map<String, List<LocalizedLiteral>> descriptionsByProject = readDescriptionsByProject(handle);
            Map<String, String> defaultLanguagesByProject = readDefaultLanguagesByProject(handle);
            return handle.sparqlQuery().select(query)
                    .map(row -> {
                        String projectIriString = iriOf(row, "project").getIRIString();
                        String defaultLanguage = defaultLanguagesByProject.get(projectIriString);
                        String description = selectDescription(
                                descriptionsByProject.getOrDefault(projectIriString, List.of()), defaultLanguage);
                        return new Project(ProjectGraphs.projectIdOf(projectIriString),
                                literalOf(row, "label").getLexicalForm(),
                                anchorsByProject.get(projectIriString),
                                description,
                                defaultLanguage);
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
        String query = "SELECT ?label ?description ?defaultLanguage ?head WHERE { GRAPH <"
                + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + projectSubject + " <" + VocabDct.IDENTIFIER.getIRIString() + "> ?label . "
                + "OPTIONAL { " + projectSubject + " <" + ArkprjVocabulary.DESCRIPTION + "> ?description } "
                + "OPTIONAL { " + projectSubject + " <" + ArkprjVocabulary.DEFAULT_LANGUAGE + "> ?defaultLanguage } } "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + projectSubject + " <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        try (DatasetHandle handle = lifecycle.acquire(SYSTEM_DATASET)) {
            List<BindingSet> rows = handle.sparqlQuery().select(query).toList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            String label = literalOf(rows.get(0), "label").getLexicalForm();
            String defaultLanguage = defaultLanguageOf(rows);
            String description = selectDescription(descriptionCandidates(rows), defaultLanguage);
            List<Anchor> anchors = readAnchors(handle.sparqlQuery()::select, projectSubject);
            Project project = new Project(id, label, anchors, description, defaultLanguage);
            RevisionToken head = rows.stream()
                    .flatMap(row -> row.getValue("head").filter(IRI.class::isInstance).map(IRI.class::cast).stream())
                    .findFirst()
                    .map(iri -> new RevisionToken(iri.getIRIString()))
                    .orElse(null);
            return Optional.of(new ProjectRegistry.CurrentProject(project, head));
        }
    }

    /** Shared single-project read for {@link #findByAnchor} and {@link #findById}. */
    private Optional<Project> readProject(DatasetHandle handle, String projectIriString) {
        String projectSubject = SparqlTerms.iriRef(projectIriString);
        String query = "SELECT ?label ?description ?defaultLanguage WHERE { GRAPH <"
                + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + projectSubject + " <" + VocabDct.IDENTIFIER.getIRIString() + "> ?label . "
                + "OPTIONAL { " + projectSubject + " <" + ArkprjVocabulary.DESCRIPTION + "> ?description } "
                + "OPTIONAL { " + projectSubject + " <" + ArkprjVocabulary.DEFAULT_LANGUAGE
                + "> ?defaultLanguage } } }";

        List<BindingSet> rows = handle.sparqlQuery().select(query).toList();
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        String label = literalOf(rows.get(0), "label").getLexicalForm();
        String defaultLanguage = defaultLanguageOf(rows);
        String description = selectDescription(descriptionCandidates(rows), defaultLanguage);
        List<Anchor> anchors = readAnchors(handle.sparqlQuery()::select, projectSubject);
        return Optional.of(new Project(ProjectGraphs.projectIdOf(projectIriString), label, anchors,
                description, defaultLanguage));
    }

    /** Extracts every {@code ?description} binding across {@code rows} as {@link LocalizedLiteral} candidates. */
    private static List<LocalizedLiteral> descriptionCandidates(List<BindingSet> rows) {
        return rows.stream()
                .flatMap(row -> row.getValue("description").filter(Literal.class::isInstance)
                        .map(Literal.class::cast).stream())
                .map(literal -> new LocalizedLiteral(literal.getLexicalForm(), literal.getLanguageTag().orElse(null)))
                .distinct()
                .toList();
    }

    /**
     * Selects the description to surface from a set of candidates via the injected {@link
     * #displayLocale}, merged per call with {@code defaultLanguage} - the very same project's own
     * registered {@code arkprj:defaultLanguage}, read from the same query as {@code candidates} -
     * or {@code null} if {@code candidates} is empty - unlike {@code skos:prefLabel}, {@code
     * dcterms:description} is optional, so an absent description is a legal {@code null} value on
     * {@link Project}, never a reason to drop the project itself.
     *
     * <p>Without this merge, every reader of this class ({@code project_list}, and - borrowed via
     * {@link de.hauschel.arknet.prj.application.port.in.FindProject}, ADR-008 - the
     * {@code store_overview} digest/HTML headers) would show the description in the process-wide
     * {@link #displayLocale}, while the very same page's body already merges in the project's own
     * default language (issue #276) - a project whose default language differs from the daemon's
     * could show its header in one language and its body in another. {@link
     * DisplayLocale#withRequestedOverride} is a no-op for a {@code null}/blank
     * {@code defaultLanguage}, so an unconfigured project degrades exactly as before this method
     * existed (issue #296).</p>
     *
     * @param candidates      the language-tagged (and/or untagged) description literals found for
     *                        one project
     * @param defaultLanguage the same project's own {@code arkprj:defaultLanguage}, or
     *                        {@code null} if it has none configured
     */
    private String selectDescription(List<LocalizedLiteral> candidates, String defaultLanguage) {
        return displayLocale.withRequestedOverride(defaultLanguage).select(candidates)
                .map(LocalizedLiteral::value).orElse(null);
    }

    /**
     * Extracts {@code ?defaultLanguage} from {@code rows} - functionally single-valued (
     * {@code prjshapes:Project-defaultLanguage} carries {@code sh:maxCount 1}), so the first
     * binding seen suffices; a store-first project breaking that constraint deterministically
     * picks the first row's value rather than throwing.
     */
    private static String defaultLanguageOf(List<BindingSet> rows) {
        return rows.stream()
                .flatMap(row -> row.getValue("defaultLanguage").filter(Literal.class::isInstance)
                        .map(Literal.class::cast).stream())
                .map(Literal::getLexicalForm)
                .findFirst()
                .orElse(null);
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

    /** Bulk variant of {@link #descriptionCandidates}: every project's description candidates, for {@link #findAll}. */
    private Map<String, List<LocalizedLiteral>> readDescriptionsByProject(DatasetHandle handle) {
        String query = "SELECT ?project ?description WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + "?project <" + ArkprjVocabulary.DESCRIPTION + "> ?description } }";
        Map<String, List<LocalizedLiteral>> byProject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> byProject
                .computeIfAbsent(iriOf(row, "project").getIRIString(), key -> new ArrayList<>())
                .add(new LocalizedLiteral(literalOf(row, "description").getLexicalForm(),
                        literalOf(row, "description").getLanguageTag().orElse(null))));
        return byProject;
    }

    /** Bulk variant of {@link #defaultLanguageOf}: every project's default language, for {@link #findAll}. */
    private Map<String, String> readDefaultLanguagesByProject(DatasetHandle handle) {
        String query = "SELECT ?project ?defaultLanguage WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + "?project <" + ArkprjVocabulary.DEFAULT_LANGUAGE + "> ?defaultLanguage } }";
        Map<String, String> byProject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> byProject
                .putIfAbsent(iriOf(row, "project").getIRIString(), literalOf(row, "defaultLanguage").getLexicalForm()));
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
