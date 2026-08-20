// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorReferencedException;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.actor.domain.DuplicateActorCodeException;
import de.hauschel.arknet.actor.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Out-adapter: {@link ActorRepository} backed by the kognio-rdf substrate ({@code io.kogn.rdf},
 * embeddable RDF store).
 *
 * <p>Maps an {@link Actor} to its opaque {@link ActorId} as the subject IRI (minted once by a
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}, never derived from the business code),
 * stored in one named graph shared by all actors: the concrete type triple (one of
 * {@code arkproc:HumanActor}/{@code SystemActor}/{@code LegalActor}/{@code GroupActor}), the
 * mandatory {@code dcterms:identifier} (the business code {@code ACTOR-1}), the generic
 * {@code arknet:name} literal and an optional {@code arknet:description} literal. This class
 * depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) and
 * {@link SimpleRdf} - it never imports RDF4J. The backend ({@link DatasetLifecycle} implementation)
 * is supplied by the composition root.</p>
 *
 * <p><strong>Only the concrete type is written.</strong> {@link #typeIriFor} maps the
 * {@link ActorType} to exactly one {@code rdf:type}; the abstract {@code arkproc:Actor} superclass
 * is never asserted, mirroring {@code KognioRdfConstraintRepository}'s treatment of
 * {@code arkreq:Constraint}. That is precisely why the gate this adapter writes through reasons
 * over the ontology axioms - see {@link KognioRdfActorRepositoryFactory#buildGate}.</p>
 *
 * <p><strong>Untagged literals.</strong> {@code name}/{@code description} are written as plain
 * literals with no language tag, so there is none of
 * {@code KognioRdfConstraintRepository}'s capture-before-delete/re-attach machinery for other
 * language variants, and no {@link de.hauschel.arknet.kernel.DisplayLocale} on any read path. See
 * {@link Actor} for why an actor is a structural identity resource rather than a prose carrier.</p>
 *
 * <p><strong>Create vs. compare-and-set update (opaque identity).</strong> The transactional
 * mechanics - the in-transaction {@code contains} existence guards, the SHACL gate, the
 * commit-conflict translation and the head comparison - live in the shared {@link WriteFunnel}
 * (ADR-013/ADR-014), not here. {@link #create} rejects an existing subject with
 * {@link ResourceAlreadyExistsException} and a business-code collision (by
 * {@code dcterms:identifier}) with {@link DuplicateActorCodeException}; {@link #compareAndUpdate}
 * rejects a missing subject with {@link ActorNotFoundException}, a stale {@code expectedHead} with
 * {@link ActorConcurrentlyModifiedException}, and - via its own {@link #rejectCodeCollision} check,
 * since {@link WriteFunnel#compareAndUpdate} runs no such check itself - a business-code collision
 * with {@link DuplicateActorCodeException} too, and otherwise replaces the subject's triples
 * wholesale. There is no unconditional update: every correction to an already-created actor goes
 * through the compare-and-set guard.</p>
 *
 * <p><strong>Nothing to preserve across a replace.</strong> Unlike the bounded-context adapter,
 * which has to carry {@code arkddd:hasAggregate} and blank-node term edges over its
 * replace-by-identity write, an {@link Actor} has no side edges at all in this scope: the aggregate
 * carries every field this graph holds. A resource that is also a glossary term keeps its
 * {@code skos:*} triples regardless - those live in the ubiquitous-language context's own named
 * graph, which this adapter's whole-subject delete is scoped away from.</p>
 *
 * <p><strong>SHACL write-gate.</strong> The gate mechanics - validate the candidate instance graph
 * against the actor SHACL shapes before the write transaction opens, throw
 * {@link WriteConstraintViolationException} on a violation, persist nothing - live in the shared
 * {@link WriteFunnel} (ADR-013). No {@code sh:class} constraint applies to anything this adapter
 * writes, so the plain {@link ShaclWriteGate#enforce(io.kogn.rdf.terms.ReadableGraph)} suffices -
 * no validation-only asserted context is needed.</p>
 *
 * <p><strong>Row multiplication.</strong> SHACL gates writes, not the store: a store-first
 * (ADR-005) actor can legally carry two {@code arknet:name}, two {@code arknet:description} or two
 * of the four actor types despite {@code actor-shapes.ttl} demanding at most one of each. Every
 * read path therefore groups its rows per subject and reduces each field with
 * {@link #firstDistinctValue} - the same guard the bounded-context adapter needed (issue #158) -
 * logging a single {@code WARN} when more than one distinct value was collapsed. A plain
 * {@code findFirst()} would otherwise pick one arbitrary, unlogged combination, and
 * {@link #compareAndUpdate}'s replace-by-identity write would then silently drop every other value
 * on the very next update.</p>
 */
public class KognioRdfActorRepository implements ActorRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KognioRdfActorRepository.class);

    private static final String ARKNET_NAMESPACE = "https://w3id.org/arknet/core#";
    private static final String ARKPROC_NAMESPACE = "https://w3id.org/arknet/process#";
    private static final String ACTOR_GRAPH = "https://w3id.org/arknet/model/actors";

    private static final String HUMAN_ACTOR_TYPE = ARKPROC_NAMESPACE + "HumanActor";
    private static final String SYSTEM_ACTOR_TYPE = ARKPROC_NAMESPACE + "SystemActor";
    private static final String LEGAL_ACTOR_TYPE = ARKPROC_NAMESPACE + "LegalActor";
    private static final String GROUP_ACTOR_TYPE = ARKPROC_NAMESPACE + "GroupActor";

    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String NAME_PROPERTY = ARKNET_NAMESPACE + "name";
    private static final String DESCRIPTION_PROPERTY = ARKNET_NAMESPACE + "description";

    private final DatasetLifecycle lifecycle;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from - read paths only,
     *                  the write path goes through {@code funnel} (must not be {@code null})
     * @param funnel    the shared write funnel (ADR-013) running the SHACL gate, dataset
     *                  acquisition and existence/head checks for every
     *                  {@link #create}/{@link #compareAndUpdate} (must not be {@code null})
     */
    KognioRdfActorRepository(DatasetLifecycle lifecycle, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, Actor actor) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(actor, "actor");

        // ResourceId#of validates IRIREF-safety at construction, so the wrapped IRI is already
        // guaranteed safe to embed here - no separate check needed.
        String subjectIriString = actor.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        IRI graphIri = rdf.createIRI(ACTOR_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, actor);

        funnel.create(new DatasetId(projectId.value()), ACTOR_GRAPH, subjectIriString,
                actor.code().value(), graph, null,
                () -> new ResourceAlreadyExistsException(projectId, actor.id().value()),
                () -> new DuplicateActorCodeException(projectId, actor.code()),
                tx -> tx.add(graphIri, graph));
    }

    /**
     * Compare-and-set update (ADR-014): replaces the actor's triples only if its
     * {@code arkprov:head} still equals {@code expectedHead} at the moment the shared
     * {@link WriteFunnel} checks it inside the write transaction - closing the lost-update window a
     * plain read (via {@link #findCurrentByCode}) followed by an unconditional replace would
     * otherwise leave open between the read and the write.
     *
     * <p><strong>Business-code uniqueness.</strong> Unlike {@link #create},
     * {@link WriteFunnel#compareAndUpdate} runs no {@code dcterms:identifier} collision check of its
     * own - a create's subject is brand-new, but a compare-and-set update's subject already exists
     * and, ordinarily, already carries this very code. So the check this method runs itself, via
     * {@link #rejectCodeCollision}, must exclude the subject being updated rather than simply asking
     * whether {@code updated.code()} exists anywhere. Rejects before any triple is touched, so a
     * rejected code change writes nothing and records no revision - the same atomicity a stale
     * {@code expectedHead} already gets.</p>
     */
    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Actor updated) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updated, "updated");

        String subjectIriString = updated.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(ACTOR_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, updated);

        funnel.compareAndUpdate(new DatasetId(projectId.value()), ACTOR_GRAPH, subjectIriString,
                expectedHead == null ? null : expectedHead.value(), graph, null,
                () -> new ActorNotFoundException(projectId, updated.code()),
                () -> new ActorConcurrentlyModifiedException(projectId, updated.code()),
                tx -> {
                    rejectCodeCollision(tx, graphIri, subjectIri, updated.code(), projectId);
                    replaceExistingTriples(tx, graphIri, subject, graph);
                });
    }

    /**
     * Rejects the write if {@code code} already labels an actor other than {@code subjectIri} -
     * {@link #create}'s business-code uniqueness rule, ported to {@link #compareAndUpdate}. Two
     * {@link DatasetTx#contains} checks rather than a {@code SELECT}/{@code ASK}, following the same
     * reasoning {@link #create}'s own code check already relies on ({@code DatasetTx#contains}'s
     * javadoc: a pattern-matched {@code contains} is answered from the backend's own pattern lookup
     * and stays conflict-guarded under {@code SERIALIZABLE}, where a query's rewritten terms are not
     * guaranteed to be). Plain {@code contains(graph, null, identifierProperty, code)} alone cannot
     * exclude {@code subjectIri}: at this point in the transaction {@code subjectIri}'s own,
     * not-yet-deleted {@code dcterms:identifier} triple still carries whatever code it had before
     * the update, so an unscoped check would misreport a no-op code change - the only case any
     * caller in this codebase currently exercises - as a collision with itself. A collision is
     * exactly "some subject other than {@code subjectIri} has {@code code}": true when any subject
     * has it but {@code subjectIri} does not (yet).
     */
    private void rejectCodeCollision(DatasetTx tx, IRI graphIri, IRI subjectIri, ActorCode code,
            ProjectId projectId) {
        IRI identifierProperty = rdf.createIRI(IDENTIFIER_PROPERTY);
        Literal codeLiteral = rdf.createLiteral(code.value());
        boolean anySubjectHasCode = tx.contains(graphIri, null, identifierProperty, codeLiteral);
        boolean thisSubjectHasCode = tx.contains(graphIri, subjectIri, identifierProperty, codeLiteral);
        if (anySubjectHasCode && !thisSubjectHasCode) {
            throw new DuplicateActorCodeException(projectId, code);
        }
    }

    /**
     * Builds the candidate graph for one actor's triples: the concrete actor type, the identifier,
     * the name and - when present - the description. Shared by {@link #create} and
     * {@link #compareAndUpdate} so both write paths serialise an {@link Actor} identically.
     */
    private Graph buildCandidateGraph(IRI subjectIri, Actor actor) {
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(typeIriFor(actor.type())));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(actor.code().value()));
        graph.add(subjectIri, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral(actor.name()));
        if (actor.description() != null) {
            graph.add(subjectIri, rdf.createIRI(DESCRIPTION_PROPERTY), rdf.createLiteral(actor.description()));
        }
        return graph;
    }

    /**
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write transaction
     * - the tail of {@link #compareAndUpdate}, reached once the funnel's own head comparison has
     * decided the write should proceed. ({@link #create} has no such tail: a freshly minted identity
     * has nothing to delete.)
     *
     * <p>A plain whole-subject delete, deliberately: within this graph an {@link Actor} carries no
     * field the record does not represent and no edge to follow, so there is nothing to capture and
     * re-attach (see the class-level "Nothing to preserve" note). The delete is scoped to
     * {@code ACTOR_GRAPH}, so triples the same subject may carry as a glossary term in another named
     * graph are out of reach by construction.</p>
     */
    private void replaceExistingTriples(DatasetTx tx, IRI graphIri, String subject, Graph graph) {
        String deleteExisting = "DELETE { GRAPH <" + ACTOR_GRAPH + "> { " + subject + " ?p ?o } } WHERE { "
                + "GRAPH <" + ACTOR_GRAPH + "> { " + subject + " ?p ?o } }";
        tx.update(deleteExisting);
        tx.add(graphIri, graph);
    }

    /**
     * Deletes the actor identified by {@code code}, and every triple it carries in
     * {@link #ACTOR_GRAPH}, from the project (issue #335). Resolves the subject by code outside any
     * transaction (mirroring {@link #findByCode}'s own read), then hands the whole
     * check-and-delete to {@link WriteFunnel#delete}: {@link #rejectIfReferenced} runs first,
     * inside the funnel's own write transaction, and only once it finds nothing pointing at the
     * actor does the body remove the subject's triples wholesale - the same "nothing to preserve"
     * whole-subject delete {@link #replaceExistingTriples} already runs, scoped to
     * {@link #ACTOR_GRAPH} so a subject that is also a glossary term keeps its {@code skos:*}
     * triples in the ul context's own named graph untouched.
     */
    @Override
    public void delete(ProjectId projectId, ActorCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        DatasetId dataset = new DatasetId(projectId.value());
        String subjectIriString;
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            String query = "SELECT ?s WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                    + "?s a ?type . " + actorTypeFilter()
                    + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" } }";
            subjectIriString = handle.sparqlQuery().select(query).findFirst()
                    .map(row -> iriOf(row, "s").getIRIString())
                    .orElseThrow(() -> new ActorNotFoundException(projectId, code));
        }
        String subject = SparqlTerms.iriRef(subjectIriString);

        funnel.delete(dataset, ACTOR_GRAPH, subjectIriString,
                () -> new ActorNotFoundException(projectId, code),
                tx -> {
                    rejectIfReferenced(tx, subjectIriString, projectId, code);
                    tx.update("DELETE WHERE { GRAPH <" + ACTOR_GRAPH + "> { " + subject + " ?p ?o } }");
                });
    }

    /**
     * The predicates that, if found pointing at an actor, block its deletion (issue #335): a use
     * case's {@code arkreq:primaryActor}/{@code supportingActor}. See
     * {@link de.hauschel.arknet.actor.domain.ActorReferencedException}'s javadoc for why this check
     * is currently unreachable in practice - no consumer in this cut writes either predicate
     * against an actor identity yet - but is still run, matching issue #335's own scope.
     */
    private static final Map<String, String> REFERENCING_PREDICATES = Map.of(
            ArkreqVocabulary.PRIMARY_ACTOR, "primaryActor",
            ArkreqVocabulary.SUPPORTING_ACTOR, "supportingActor");

    /**
     * Rejects the delete, without touching a single triple, if anything in the project still
     * references {@code subjectIri} via one of {@link #REFERENCING_PREDICATES} - searched across
     * every named graph ({@code GRAPH ?g}), since a referencing edge would live in the use-case
     * BC's own model graph, not {@link #ACTOR_GRAPH}. Runs inside the live write transaction
     * {@link WriteFunnel#delete} hands its {@code body}, so the check and the eventual delete
     * share one atomic snapshot.
     */
    private void rejectIfReferenced(DatasetTx tx, String subjectIri, ProjectId projectId, ActorCode code) {
        IRI target = rdf.createIRI(subjectIri);
        List<String> referencing = new ArrayList<>();
        REFERENCING_PREDICATES.forEach((predicateIri, shorthand) -> {
            String query = "ASK { GRAPH ?g { ?s <" + predicateIri + "> ?target } }";
            if (tx.ask(query, Map.of("target", target))) {
                referencing.add(shorthand);
            }
        });
        if (!referencing.isEmpty()) {
            throw new ActorReferencedException(projectId, code, referencing);
        }
    }

    @Override
    public Optional<Actor> findByCode(ProjectId projectId, ActorCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        String query = "SELECT ?s ?type ?name ?description WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                + actorByCodeWhereClause(code)
                + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<BindingSet> rows = handle.sparqlQuery().select(query).toList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return Optional.of(actorOf(rows, code));
        }
    }

    /**
     * Reads an actor's current state together with its concurrency token. The rows built from
     * {@link #actorByCodeWhereClause} (possibly row-multiplied - see the class-level "Row
     * multiplication" note) plus the head itself come from this method's one query call - one
     * snapshot, which is the load-bearing guarantee, not an ordering of clauses within that query.
     * {@code head} is single-valued (ADR-014's queryable-head invariant), so every row carries the
     * same value; only the first row is consulted for it. Builds the {@link Actor} the same way
     * {@link #findByCode} does - both call {@link #actorOf} on their rows, so the two read paths
     * cannot drift apart field-by-field.
     */
    @Override
    public Optional<CurrentActor> findCurrentByCode(ProjectId projectId, ActorCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        String query = "SELECT ?s ?type ?name ?description ?head WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                + actorByCodeWhereClause(code)
                + "} "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?s <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<BindingSet> rows = handle.sparqlQuery().select(query).toList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            Actor actor = actorOf(rows, code);
            RevisionToken head = rows.get(0).getValue("head")
                    .filter(IRI.class::isInstance)
                    .map(value -> new RevisionToken(((IRI) value).getIRIString()))
                    .orElse(null);
            return Optional.of(new CurrentActor(actor, head));
        }
    }

    /**
     * The WHERE body shared by {@link #findByCode} and {@link #findCurrentByCode}: the mandatory
     * joins (type - filtered to the four known actor types, mirroring
     * {@code KognioRdfConstraintRepository}'s own type filter - identifier and name) plus the
     * optional description join. Extracted because both callers build an {@link Actor} from the same
     * row shape via {@link #actorOf}; drift between two near-identical read paths is what row
     * multiplication cost the requirements adapter, so this text lives in one place. The caller
     * supplies the surrounding {@code SELECT}/{@code GRAPH}/{@code WHERE} wrapping and, in
     * {@link #findCurrentByCode}'s case, the additional provenance-graph join.
     */
    private static String actorByCodeWhereClause(ActorCode code) {
        return "?s a ?type . "
                + actorTypeFilter()
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . "
                + "?s <" + NAME_PROPERTY + "> ?name . "
                + "OPTIONAL { ?s <" + DESCRIPTION_PROPERTY + "> ?description } ";
    }

    /** Restricts {@code ?type} to the four concrete actor classes this adapter writes. */
    private static String actorTypeFilter() {
        return "FILTER(?type = <" + HUMAN_ACTOR_TYPE + "> || ?type = <" + SYSTEM_ACTOR_TYPE
                + "> || ?type = <" + LEGAL_ACTOR_TYPE + "> || ?type = <" + GROUP_ACTOR_TYPE + ">) ";
    }

    /**
     * Builds one {@link Actor} from every row of {@link #actorByCodeWhereClause}'s projection for
     * one subject. {@code type}, {@code name} and {@code description} are collected across
     * <strong>all</strong> rows and reduced with {@link #firstDistinctValue}, because {@code rows}
     * can legally hold a cross product of candidates for this one subject (see the class-level "Row
     * multiplication" note). Shared by {@link #findByCode} and {@link #findCurrentByCode} so both
     * single-actor read paths build the aggregate the same way.
     */
    private Actor actorOf(List<BindingSet> rows, ActorCode code) {
        String subjectIriString = iriOf(rows.get(0), "s").getIRIString();
        ActorAssembly assembly = new ActorAssembly(new ActorId(ResourceId.of(subjectIriString)), code);
        rows.forEach(assembly::addRow);
        return assembly.toActor();
    }

    @Override
    public List<Actor> findAll(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?s ?identifier ?type ?name ?description WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                + "?s a ?type . "
                + actorTypeFilter()
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "?s <" + NAME_PROPERTY + "> ?name . "
                + "OPTIONAL { ?s <" + DESCRIPTION_PROPERTY + "> ?description } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            // Grouped by subject: SHACL gates writes only, so a store-first actor with two type,
            // name or description triples binds a cross product of rows for the same subject.
            // Mapping each row straight to an Actor would surface that subject more than once.
            Map<String, ActorAssembly> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                String subjectIri = iriOf(row, "s").getIRIString();
                bySubject.computeIfAbsent(subjectIri, iri -> new ActorAssembly(
                        new ActorId(ResourceId.of(iri)),
                        new ActorCode(literalOf(row, "identifier").getLexicalForm()))).addRow(row);
            });
            return bySubject.values().stream().map(ActorAssembly::toActor).toList();
        }
    }

    /**
     * Picks one value of {@code candidates} deterministically (first-seen), logging a single
     * {@code WARN} naming {@code subjectIri}/{@code fieldName} when more than one distinct value was
     * collapsed. The shared row-multiplication guard behind both read paths - {@code actor-shapes.ttl}
     * bounds every field to one value, but SHACL gates writes rather than the store, so a
     * store-first (ADR-005) actor can legally bind more than one row per field.
     */
    private static <T> T firstDistinctValue(List<T> candidates, String subjectIri, String fieldName) {
        if (candidates.isEmpty()) {
            return null;
        }
        long distinctCount = candidates.stream().distinct().count();
        if (distinctCount > 1) {
            LOG.warn("Actor {}: field '{}' had {} distinct values, returning the first",
                    subjectIri, fieldName, distinctCount);
        }
        return candidates.get(0);
    }

    /**
     * Mutable per-subject accumulator collecting an actor's {@code type}, {@code name} and
     * {@code description} candidates across rows, then choosing one of each deterministically
     * (first-seen) when the actor is finally materialised.
     */
    private static final class ActorAssembly {

        private final ActorId id;
        private final ActorCode code;
        private final List<ActorType> types = new ArrayList<>();
        private final List<String> names = new ArrayList<>();
        private final List<String> descriptions = new ArrayList<>();

        private ActorAssembly(ActorId id, ActorCode code) {
            this.id = id;
            this.code = code;
        }

        private void addRow(BindingSet row) {
            types.add(typeFromIri(iriOf(row, "type").getIRIString()));
            names.add(literalOf(row, "name").getLexicalForm());
            row.getValue("description")
                    .filter(Literal.class::isInstance)
                    .map(value -> ((Literal) value).getLexicalForm())
                    .ifPresent(descriptions::add);
        }

        private Actor toActor() {
            String subjectIri = id.value().value();
            return new Actor(id, code,
                    firstDistinctValue(types, subjectIri, "type"),
                    firstDistinctValue(names, subjectIri, "name"),
                    firstDistinctValue(descriptions, subjectIri, "description"));
        }
    }

    // ---- helpers -----------------------------------------------------------------------

    private static String typeIriFor(ActorType type) {
        return switch (type) {
            case HUMAN -> HUMAN_ACTOR_TYPE;
            case SYSTEM -> SYSTEM_ACTOR_TYPE;
            case LEGAL -> LEGAL_ACTOR_TYPE;
            case GROUP -> GROUP_ACTOR_TYPE;
        };
    }

    private static ActorType typeFromIri(String iri) {
        if (HUMAN_ACTOR_TYPE.equals(iri)) {
            return ActorType.HUMAN;
        }
        if (SYSTEM_ACTOR_TYPE.equals(iri)) {
            return ActorType.SYSTEM;
        }
        if (LEGAL_ACTOR_TYPE.equals(iri)) {
            return ActorType.LEGAL;
        }
        if (GROUP_ACTOR_TYPE.equals(iri)) {
            return ActorType.GROUP;
        }
        throw new IllegalStateException("unexpected actor type " + iri);
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    private static Literal literalOf(BindingSet row, String name) {
        return (Literal) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
