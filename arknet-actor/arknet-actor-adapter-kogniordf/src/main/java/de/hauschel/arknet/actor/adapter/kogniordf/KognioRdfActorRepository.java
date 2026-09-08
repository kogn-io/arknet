// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.SparqlQuery;
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
import de.hauschel.arknet.actor.domain.ActorDisplayFallback;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorNotFoundException;
import de.hauschel.arknet.actor.domain.ActorReferencedException;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.actor.domain.DuplicateActorCodeException;
import de.hauschel.arknet.actor.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.InvalidLanguageTagException;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
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
 * mandatory {@code dcterms:identifier} (the business code {@code ACTOR-1}), one or more
 * language-tagged {@code arknet:name} literals and zero or more language-tagged
 * {@code arknet:description} literals. This class depends only on the neutral kognio-rdf ports
 * ({@code terms} + {@code dataset}) and {@link SimpleRdf} - it never imports RDF4J. The backend
 * ({@link DatasetLifecycle} implementation) is supplied by the composition root.</p>
 *
 * <p><strong>Only the concrete type is written.</strong> {@link #typeIriFor} maps the
 * {@link ActorType} to exactly one {@code rdf:type}; the abstract {@code arkproc:Actor} superclass
 * is never asserted, mirroring {@code KognioRdfConstraintRepository}'s treatment of
 * {@code arkreq:Constraint}. That is precisely why the gate this adapter writes through reasons
 * over the ontology axioms - see {@link KognioRdfActorRepositoryFactory#buildGate}.</p>
 *
 * <p><strong>Multilingual {@code name}/{@code description}, mirroring
 * {@code KognioRdfRoleRepository} (kogn-io/arknet#520).</strong> Both carry one language-tagged
 * literal per language (SHACL {@code sh:uniqueLang}), read as their own follow-up queries
 * ({@link #readNames}/{@link #readDescriptions}) and selected through {@link DisplayLocale}, never
 * joined into the single-row scalar clause {@link #actorByCodeWhereClause} builds - a join would
 * multiply one subject into a row per name/description candidate combination.
 * {@link #compareAndUpdate} writes exactly one variant of each per call and preserves every other
 * one across its replace-by-identity write (see {@link #replaceTriplesForUpdate}), including the
 * issue #258 sweep of a stale untagged sibling of a default-language write. Before kogn-io/
 * arknet#520 both fields were plain untagged literals - see {@link Actor}'s own javadoc for why an
 * actor's name is nonetheless free to differ per language, unlike a glossary term's
 * {@code prefLabel}.</p>
 *
 * <p><strong>Create vs. compare-and-set update (opaque identity).</strong> The transactional
 * mechanics - the in-transaction {@code contains} existence guards, the SHACL gate, the
 * commit-conflict translation and the head comparison - live in the shared {@link WriteFunnel},
 * not here. {@link #create} rejects an existing subject with
 * {@link ResourceAlreadyExistsException} and a business-code collision (by
 * {@code dcterms:identifier}) with {@link DuplicateActorCodeException}; {@link #compareAndUpdate}
 * rejects a missing subject with {@link ActorNotFoundException}, a stale {@code expectedHead} with
 * {@link ActorConcurrentlyModifiedException}, and - via its own {@link #rejectCodeCollision} check,
 * since {@link WriteFunnel#compareAndUpdate} runs no such check itself - a business-code collision
 * with {@link DuplicateActorCodeException} too, and otherwise replaces the subject's triples
 * wholesale. There is no unconditional update: every correction to an already-created actor goes
 * through the compare-and-set guard.</p>
 *
 * <p><strong>Nothing to preserve across a replace besides languages.</strong> Unlike the
 * bounded-context adapter, which has to carry {@code arkddd:hasAggregate} and blank-node term
 * edges over its replace-by-identity write, an {@link Actor} has no side edges at all in this
 * scope: the aggregate carries every field this graph holds besides the language variants
 * {@link #replaceTriplesForUpdate} preserves. A resource that is also a glossary term keeps its
 * {@code skos:*} triples regardless - those live in the ubiquitous-language context's own named
 * graph, which this adapter's whole-subject delete is scoped away from.</p>
 *
 * <p><strong>SHACL write-gate.</strong> The gate mechanics - validate the candidate instance graph
 * against the actor SHACL shapes before the write transaction opens, throw
 * {@link WriteConstraintViolationException} on a violation, persist nothing - live in the shared
 * {@link WriteFunnel}. No {@code sh:class} constraint applies to anything this adapter
 * writes, so the plain {@link ShaclWriteGate#enforce(io.kogn.rdf.terms.ReadableGraph)} suffices -
 * no validation-only asserted context is needed.</p>
 *
 * <p><strong>Row multiplication (type only).</strong> SHACL gates writes, not the store: a
 * store-first actor can legally carry two of the four actor types despite {@code actor-shapes.ttl}
 * demanding at most one. Every read path therefore groups its rows per subject and reduces the
 * type with {@link #firstDistinctValue} - the same guard the bounded-context adapter needed (issue
 * #158) - logging a single {@code WARN} when more than one distinct value was collapsed. {@code
 * name}/{@code description} no longer share this treatment since kogn-io/arknet#520: they are read
 * through their own multilingual queries, not the row-multiplying scalar join.</p>
 */
public class KognioRdfActorRepository implements ActorRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KognioRdfActorRepository.class);

    private static final String ARKNET_NAMESPACE = "https://w3id.org/arknet/core#";
    private static final String ACTOR_GRAPH = "https://w3id.org/arknet/model/actors";

    // Shared via ArkprocVocabulary (kogn-io/arknet#148): this class used to declare its own private
    // copy of these four IRI literals, duplicated with TraceabilityGraph's and
    // KognioRdfActorLookup's own private copies.
    private static final String HUMAN_ACTOR_TYPE = ArkprocVocabulary.HUMAN_ACTOR_TYPE;
    private static final String SYSTEM_ACTOR_TYPE = ArkprocVocabulary.SYSTEM_ACTOR_TYPE;
    private static final String LEGAL_ACTOR_TYPE = ArkprocVocabulary.LEGAL_ACTOR_TYPE;
    private static final String GROUP_ACTOR_TYPE = ArkprocVocabulary.GROUP_ACTOR_TYPE;

    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String NAME_PROPERTY = ARKNET_NAMESPACE + "name";
    private static final String DESCRIPTION_PROPERTY = ARKNET_NAMESPACE + "description";

    /**
     * The prefix every code this hexagon mints carries. Used only by {@link #findRetainedCodes} to
     * tell an actor's own retained code apart from a neighbouring bounded context's, since the
     * provenance graph {@link WriteFunnel#findRetainedCodes} reads from is shared by all of them.
     */
    private static final String CODE_PREFIX = "ACTOR-";

    private final DatasetLifecycle lifecycle;
    private final DisplayLocale displayLocale;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from - read paths
     *                      only, the write path goes through {@code funnel} (must not be
     *                      {@code null})
     * @param displayLocale the display-language preference selecting which {@code arknet:name}/
     *                      {@code arknet:description} the read paths surface for a multilingual
     *                      actor (must not be {@code null})
     * @param funnel        the shared write funnel running the SHACL gate, dataset
     *                      acquisition and existence/head checks for every
     *                      {@link #create}/{@link #compareAndUpdate} (must not be {@code null})
     */
    KognioRdfActorRepository(DatasetLifecycle lifecycle, DisplayLocale displayLocale, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, Actor actor, String language) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(actor, "actor");
        String tag = LanguageTag.canonicalize(language);

        // ResourceId#of validates IRIREF-safety at construction, so the wrapped IRI is already
        // guaranteed safe to embed here - no separate check needed.
        String subjectIriString = actor.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        IRI graphIri = rdf.createIRI(ACTOR_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, actor, tag, tag);

        funnel.create(new DatasetId(projectId.value()), ACTOR_GRAPH, subjectIriString,
                actor.code().value(), graph, null,
                () -> new ResourceAlreadyExistsException(projectId, actor.id().value()),
                () -> new DuplicateActorCodeException(projectId, actor.code()),
                tx -> tx.add(graphIri, graph));
    }

    /**
     * Compare-and-set update, mirroring {@code KognioRdfRoleRepository#compareAndUpdate} exactly
     * for the multilingual mechanics, and its own prior self for the
     * {@link #rejectCodeCollision} check.
     */
    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Actor updated,
            String nameLanguage, String descriptionLanguage, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updated, "updated");
        String nameTag = canonicalizeLenient(nameLanguage);
        String descriptionTag = canonicalizeLenient(descriptionLanguage);
        String defaultTag = canonicalizeLenient(defaultLanguage);

        String subjectIriString = updated.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(ACTOR_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, updated, nameTag, descriptionTag);

        funnel.compareAndUpdate(new DatasetId(projectId.value()), ACTOR_GRAPH, subjectIriString,
                expectedHead == null ? null : expectedHead.value(), graph, null,
                () -> new ActorNotFoundException(projectId, updated.code()),
                () -> new ActorConcurrentlyModifiedException(projectId, updated.code()),
                tx -> {
                    rejectCodeCollision(tx, graphIri, subjectIri, updated.code(), projectId);
                    replaceTriplesForUpdate(tx, graphIri, subjectIri, subject, graph, nameTag, descriptionTag,
                            defaultTag);
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
     * the name (tagged {@code nameTag}) and the optional description (tagged
     * {@code descriptionTag}). Shared by {@link #create} and {@link #compareAndUpdate} - never more
     * than one {@code name}/{@code description} each, since preserving every other language variant
     * is {@link #replaceTriplesForUpdate}'s job.
     */
    private Graph buildCandidateGraph(IRI subjectIri, Actor actor, String nameTag, String descriptionTag) {
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(typeIriFor(actor.type())));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(actor.code().value()));
        graph.add(subjectIri, rdf.createIRI(NAME_PROPERTY), literalOf(actor.name(), nameTag));
        if (actor.description() != null) {
            graph.add(subjectIri, rdf.createIRI(DESCRIPTION_PROPERTY), literalOf(actor.description(), descriptionTag));
        }
        return graph;
    }

    /**
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write
     * transaction - mirrors {@code KognioRdfRoleRepository#replaceTriplesForUpdate} exactly: every
     * <em>other</em> language variant of {@code name}/{@code description} is captured before the
     * unconditional whole-subject delete and re-attached afterwards, including the issue #258 sweep
     * of a stale untagged sibling of a default-language write.
     */
    private void replaceTriplesForUpdate(DatasetTx tx, IRI graphIri, IRI subjectIri, String subject, Graph graph,
            String nameTag, String descriptionTag, String defaultTag) {
        String deleteExisting = "DELETE { GRAPH <" + ACTOR_GRAPH + "> { " + subject + " ?p ?o } } WHERE { "
                + "GRAPH <" + ACTOR_GRAPH + "> { " + subject + " ?p ?o } }";

        // Captured inside this same transaction, never by a separate read beforehand - that would
        // leave a TOCTOU window the caller's own head comparison deliberately avoids.
        List<Literal> preservedNames = otherLanguageLiterals(tx, subject, NAME_PROPERTY, nameTag, defaultTag);
        List<Literal> preservedDescriptions =
                otherLanguageLiterals(tx, subject, DESCRIPTION_PROPERTY, descriptionTag, defaultTag);
        tx.update(deleteExisting);
        tx.add(graphIri, graph);
        if (!preservedNames.isEmpty() || !preservedDescriptions.isEmpty()) {
            Graph preservedLanguageVariants = rdf.createGraph();
            for (Literal name : preservedNames) {
                preservedLanguageVariants.add(subjectIri, rdf.createIRI(NAME_PROPERTY), name);
            }
            for (Literal description : preservedDescriptions) {
                preservedLanguageVariants.add(subjectIri, rdf.createIRI(DESCRIPTION_PROPERTY), description);
            }
            tx.add(graphIri, preservedLanguageVariants);
        }
    }

    /**
     * Reads every existing literal of {@code subject} on {@code predicateIri} whose language tag
     * differs from {@code writtenTag}, inside the live write transaction - mirrors
     * {@code KognioRdfRoleRepository#otherLanguageLiterals} exactly, sweep included.
     */
    private List<Literal> otherLanguageLiterals(
            DatasetTx tx, String subject, String predicateIri, String writtenTag, String defaultTag) {
        String query = "SELECT ?o WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                + subject + " <" + predicateIri + "> ?o } }";
        boolean sweepUntagged = defaultTag != null && defaultTag.equals(writtenTag);
        return tx.select(query)
                .map(row -> literalOf(row, "o"))
                .filter(literal -> {
                    String existingTag = canonicalizeLenient(literal.getLanguageTag().orElse(null));
                    if (sweepUntagged && existingTag == null) {
                        return false;
                    }
                    return !Objects.equals(existingTag, writtenTag);
                })
                .toList();
    }

    /**
     * {@link LanguageTag#canonicalize(String)}, but falls back to {@code null} (untagged) instead
     * of throwing - mirrors {@code KognioRdfRoleRepository#canonicalizeLenient} exactly.
     */
    private static String canonicalizeLenient(String tag) {
        try {
            return LanguageTag.canonicalize(tag);
        } catch (InvalidLanguageTagException e) {
            return null;
        }
    }

    /**
     * Deletes the actor identified by {@code code}, and every triple it carries in
     * {@link #ACTOR_GRAPH}, from the project (issue #335). Resolves the subject by code outside any
     * transaction (mirroring {@link #findByCode}'s own read), then hands the whole
     * check-and-delete to {@link WriteFunnel#delete}: {@link #rejectIfReferenced} runs first,
     * inside the funnel's own write transaction, and only once it finds nothing pointing at the
     * actor does the body remove the subject's triples wholesale.
     */
    @Override
    public void delete(ProjectId projectId, ActorCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        DatasetId dataset = new DatasetId(projectId.value());
        String subjectIriString;
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            String query = "SELECT ?s WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                    + actorByCodeWhereClause(code) + "} }";
            subjectIriString = handle.sparqlQuery().select(query).findFirst()
                    .map(row -> iriOf(row, "s").getIRIString())
                    .orElseThrow(() -> new ActorNotFoundException(projectId, code));
        }
        String subject = SparqlTerms.iriRef(subjectIriString);

        funnel.delete(dataset, ACTOR_GRAPH, subjectIriString, code.value(),
                () -> new ActorNotFoundException(projectId, code),
                tx -> {
                    rejectIfReferenced(tx, subjectIriString, projectId, code);
                    tx.update("DELETE WHERE { GRAPH <" + ACTOR_GRAPH + "> { " + subject + " ?p ?o } }");
                });
    }

    /**
     * Reads back the codes {@link WriteFunnel#delete}'s {@code code} parameter retained (issue
     * #350): the shared funnel keeps the number out of circulation, this hexagon only maps its raw
     * strings to {@link ActorCode}.
     */
    @Override
    public List<ActorCode> findRetainedCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        return funnel.findRetainedCodes(new DatasetId(projectId.value()), CODE_PREFIX).stream()
                .map(ActorCode::new)
                .toList();
    }

    /**
     * The predicates that, if found pointing at an actor, block its deletion (issue #335):
     * a role's {@code arkproc:filledBy}.
     */
    private static final Map<String, String> REFERENCING_PREDICATES = Map.of(
            ArkprocVocabulary.FILLED_BY, "filledBy");

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

    /**
     * The WHERE body shared by {@link #findByCode}/{@link #findCurrentByCode}/{@link #delete} -
     * the mandatory type and identifier joins. {@code name}/{@code description} are read
     * separately, not joined here, since both may carry several language-tagged literals each - see
     * the class-level multilingual note.
     */
    private static String actorByCodeWhereClause(ActorCode code) {
        return "?s a ?type . "
                + actorTypeFilter()
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . "
                + "FILTER(isIRI(?s)) ";
    }

    /** Restricts {@code ?type} to the four concrete actor classes this adapter writes. */
    private static String actorTypeFilter() {
        return "FILTER(?type = <" + HUMAN_ACTOR_TYPE + "> || ?type = <" + SYSTEM_ACTOR_TYPE
                + "> || ?type = <" + LEGAL_ACTOR_TYPE + "> || ?type = <" + GROUP_ACTOR_TYPE + ">) ";
    }

    @Override
    public Optional<Actor> findByCode(ProjectId projectId, ActorCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?type WHERE { GRAPH <" + ACTOR_GRAPH + "> { " + actorByCodeWhereClause(code)
                + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            List<BindingSet> rows = sparql.select(query).toList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return actorOf(rows, code, sparql::select, effective);
        }
    }

    /**
     * Reads an actor's current state together with its concurrency token. Mirrors
     * {@code KognioRdfRoleRepository#findCurrentByCode} exactly, including which language variant
     * this read projects through (the target project's own configured default, not the reading
     * process's own preference - issue #456).
     */
    @Override
    public Optional<CurrentActor> findCurrentByCode(ProjectId projectId, ActorCode code, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(canonicalizeLenient(defaultLanguage));

        String query = "SELECT ?s ?type ?head WHERE { GRAPH <" + ACTOR_GRAPH + "> { " + actorByCodeWhereClause(code)
                + "} "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?s <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            List<BindingSet> rows = sparql.select(query).toList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            String subjectIriString = iriOf(rows.get(0), "s").getIRIString();
            String subject = SparqlTerms.iriRef(subjectIriString);
            Optional<NameDescriptionSelection> selection = selectNameDescription(sparql::select, subject, effective);
            if (selection.isEmpty()) {
                return Optional.empty();
            }
            NameDescriptionSelection selected = selection.get();
            ActorType type = firstDistinctType(rows, subjectIriString);
            Actor actor = new Actor(new ActorId(ResourceId.of(subjectIriString)), code, type,
                    selected.name().value(),
                    selected.description() == null ? null : selected.description().value());
            RevisionToken head = rows.get(0).getValue("head")
                    .filter(IRI.class::isInstance)
                    .map(value -> new RevisionToken(((IRI) value).getIRIString()))
                    .orElse(null);
            return Optional.of(new CurrentActor(actor, head, selected.name().languageTag(),
                    selected.description() == null ? null : selected.description().languageTag()));
        }
    }

    @Override
    public List<Actor> findAll(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?identifier ?type WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                + "?s a ?type . "
                + actorTypeFilter()
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "FILTER(isIRI(?s)) } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            Map<String, List<LocalizedLiteral>> namesBySubject = literalsBySubject(sparql, NAME_PROPERTY);
            Map<String, List<LocalizedLiteral>> descriptionsBySubject = literalsBySubject(sparql, DESCRIPTION_PROPERTY);
            Map<String, ActorAssembly> bySubject = new LinkedHashMap<>();
            sparql.select(query).forEach(row -> {
                String subjectIri = iriOf(row, "s").getIRIString();
                ActorAssembly assembly = bySubject.computeIfAbsent(subjectIri, iri -> new ActorAssembly(
                        new ActorId(ResourceId.of(iri)),
                        new ActorCode(literalOf(row, "identifier").getLexicalForm())));
                assembly.addType(typeFromIri(iriOf(row, "type").getIRIString()));
            });
            List<Actor> actors = new ArrayList<>();
            bySubject.forEach((subjectIri, assembly) -> {
                Optional<LocalizedLiteral> name =
                        effective.select(namesBySubject.getOrDefault(subjectIri, List.of()));
                if (name.isEmpty()) {
                    // actshapes:Actor-name carries sh:minCount 1 at sh:Violation severity, so this
                    // is unreachable via the MCP tools - skip this one store-first actor rather
                    // than crash the whole listing, mirroring KognioRdfRoleRepository#findAll.
                    return;
                }
                Optional<LocalizedLiteral> description =
                        effective.select(descriptionsBySubject.getOrDefault(subjectIri, List.of()));
                actors.add(assembly.toActor(name.get().value(), description.map(LocalizedLiteral::value).orElse(null)));
            });
            return List.copyOf(actors);
        }
    }

    /**
     * Companion to {@link #findAll}: not the displayed value, but whether displaying it required
     * falling back past the requested/project-default language tier (kogn-io/arknet#520) - mirrors
     * {@code KognioRdfRoleRepository#findAllDisplayFallback} exactly.
     */
    @Override
    public Map<ActorCode, ActorDisplayFallback> findAllDisplayFallback(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                + "?s a ?type . "
                + actorTypeFilter()
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "FILTER(isIRI(?s)) } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            Map<String, List<LocalizedLiteral>> namesBySubject = literalsBySubject(sparql, NAME_PROPERTY);
            Map<String, List<LocalizedLiteral>> descriptionsBySubject = literalsBySubject(sparql, DESCRIPTION_PROPERTY);
            Map<ActorCode, ActorDisplayFallback> result = new LinkedHashMap<>();
            sparql.select(query).forEach(row -> {
                String subject = iriOf(row, "s").getIRIString();
                ActorCode code = new ActorCode(literalOf(row, "identifier").getLexicalForm());
                ActorDisplayFallback fallback = new ActorDisplayFallback(
                        fallbackTag(namesBySubject.getOrDefault(subject, List.of()), effective),
                        fallbackTag(descriptionsBySubject.getOrDefault(subject, List.of()), effective));
                if (!fallback.isEmpty()) {
                    result.put(code, fallback);
                }
            });
            return result;
        }
    }

    /**
     * {@code null} if the candidate matching {@code displayLocale}'s requested language was shown;
     * otherwise the tag of whatever was shown instead - mirrors
     * {@code KognioRdfRoleRepository#fallbackTag} exactly. A subject carrying no candidate at all
     * (an optional {@code description} an actor never had) is never a fallback - there is nothing
     * to have shown in a different language.
     */
    private static String fallbackTag(List<LocalizedLiteral> candidates, DisplayLocale displayLocale) {
        if (candidates.isEmpty()) {
            return null;
        }
        LocalizedLiteral selected = displayLocale.select(candidates)
                .orElseThrow(() -> new IllegalStateException("candidates checked non-empty above"));
        String tag = selected.languageTag();
        String requestedLanguage = displayLocale.requested().getLanguage();
        boolean matchesRequested = tag != null
                && Locale.forLanguageTag(tag).getLanguage().equalsIgnoreCase(requestedLanguage);
        return matchesRequested ? null : (tag == null ? "" : tag);
    }

    /**
     * Reads every registered actor's business code straight off {@code dcterms:identifier}, joining
     * nothing but the type triple {@link #actorTypeFilter} needs - in particular not
     * {@code arknet:name}, whose mandatory join is exactly what hides a store-first actor from
     * {@link #findAll} while its {@code ACTOR-N} stays taken (kogn-io/arknet#360, see
     * {@link ActorRepository#findAllCodes}'s own javadoc). The same type filter as every other read
     * path, because one {@code ACTOR-N} counter spans all four actor types: a code missed here is a
     * code handed out twice.
     */
    @Override
    public List<ActorCode> findAllCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?identifier WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                + "?s a ?type . "
                + actorTypeFilter()
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .distinct()
                    .map(ActorCode::new)
                    .toList();
        }
    }

    /**
     * Finds every actor in a project whose identity is among {@code ids}, in one store
     * round-trip, returning the full {@link Actor} aggregate - used by {@code RoleService}
     * (ADR-37/kogn-io/arknet#405) to resolve a role's {@code arkproc:filledBy} occupants for
     * display, under the caller's {@code displayLocale} override exactly as {@link #findAll} applies
     * it - the same selection {@code KognioRdfRoleRepository#findByIds} makes for a role's name.
     */
    @Override
    public List<Actor> findAllByIds(ProjectId projectId, String displayLocale, List<ResourceId> ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return List.of();
        }
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String values = ids.stream()
                .map(id -> SparqlTerms.iriRef(id.value()))
                .collect(Collectors.joining(" "));

        String query = "SELECT ?s ?identifier ?type WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                + "VALUES ?s { " + values + " } "
                + "?s a ?type . "
                + actorTypeFilter()
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            Map<String, List<LocalizedLiteral>> namesBySubject = literalsBySubject(sparql, NAME_PROPERTY);
            Map<String, List<LocalizedLiteral>> descriptionsBySubject = literalsBySubject(sparql, DESCRIPTION_PROPERTY);
            Map<String, ActorAssembly> bySubject = new LinkedHashMap<>();
            sparql.select(query).forEach(row -> {
                String subjectIri = iriOf(row, "s").getIRIString();
                ActorAssembly assembly = bySubject.computeIfAbsent(subjectIri, iri -> new ActorAssembly(
                        new ActorId(ResourceId.of(iri)),
                        new ActorCode(literalOf(row, "identifier").getLexicalForm())));
                assembly.addType(typeFromIri(iriOf(row, "type").getIRIString()));
            });
            List<Actor> actors = new ArrayList<>();
            bySubject.forEach((subjectIri, assembly) -> {
                Optional<LocalizedLiteral> name = effective.select(namesBySubject.getOrDefault(subjectIri, List.of()));
                if (name.isEmpty()) {
                    return;
                }
                Optional<LocalizedLiteral> description =
                        effective.select(descriptionsBySubject.getOrDefault(subjectIri, List.of()));
                actors.add(assembly.toActor(name.get().value(), description.map(LocalizedLiteral::value).orElse(null)));
            });
            return List.copyOf(actors);
        }
    }

    /**
     * Picks one value of {@code candidates} deterministically (first-seen), logging a single
     * {@code WARN} naming {@code subjectIri}/{@code fieldName} when more than one distinct value was
     * collapsed. The shared row-multiplication guard behind the {@code type} field - {@code
     * actor-shapes.ttl} bounds it to one value, but SHACL gates writes rather than the store, so a
     * store-first actor can legally bind more than one row for it.
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

    private static ActorType firstDistinctType(List<BindingSet> rows, String subjectIri) {
        List<ActorType> types = rows.stream().map(row -> typeFromIri(iriOf(row, "type").getIRIString())).toList();
        return firstDistinctValue(types, subjectIri, "type");
    }

    /**
     * Mutable per-subject accumulator collecting an actor's {@code type} candidates across rows,
     * choosing one deterministically (first-seen) when the actor is finally materialised with its
     * already-selected {@code name}/{@code description}.
     */
    private static final class ActorAssembly {

        private final ActorId id;
        private final ActorCode code;
        private final List<ActorType> types = new ArrayList<>();

        private ActorAssembly(ActorId id, ActorCode code) {
            this.id = id;
            this.code = code;
        }

        private void addType(ActorType type) {
            types.add(type);
        }

        private Actor toActor(String name, String description) {
            String subjectIri = id.value().value();
            return new Actor(id, code, firstDistinctValue(types, subjectIri, "type"), name, description);
        }
    }

    /**
     * One actor's selected {@code name}/optional {@code description} literal, each carrying the
     * {@link LocalizedLiteral#languageTag()} it was chosen under - mirrors
     * {@code KognioRdfRoleRepository}'s {@code NameDescriptionSelection} exactly.
     */
    private record NameDescriptionSelection(LocalizedLiteral name, LocalizedLiteral description) {
    }

    /**
     * Selects the {@code name} candidate via {@code locale}, plus a {@code description} candidate
     * if the actor carries one - {@link Optional#empty()} only if this subject carries no
     * {@code name} literal at all (unreachable via the MCP tools; {@code actshapes:Actor-name}
     * carries {@code sh:minCount 1} at {@code sh:Violation}), a store-first gap only.
     */
    private Optional<NameDescriptionSelection> selectNameDescription(
            Function<String, Stream<BindingSet>> selectFn, String subject, DisplayLocale locale) {
        Optional<LocalizedLiteral> name = locale.select(readNames(selectFn, subject));
        if (name.isEmpty()) {
            return Optional.empty();
        }
        Optional<LocalizedLiteral> description = locale.select(readDescriptions(selectFn, subject));
        return Optional.of(new NameDescriptionSelection(name.get(), description.orElse(null)));
    }

    private Optional<Actor> actorOf(List<BindingSet> rows, ActorCode code,
            Function<String, Stream<BindingSet>> selectFn, DisplayLocale locale) {
        String subjectIriString = iriOf(rows.get(0), "s").getIRIString();
        String subject = SparqlTerms.iriRef(subjectIriString);
        ActorType type = firstDistinctType(rows, subjectIriString);
        return selectNameDescription(selectFn, subject, locale).map(selection -> new Actor(
                new ActorId(ResourceId.of(subjectIriString)),
                code,
                type,
                selection.name().value(),
                selection.description() == null ? null : selection.description().value()));
    }

    /** Reads the {@code arknet:name} candidates of one actor, tagged for {@link DisplayLocale}. */
    private List<LocalizedLiteral> readNames(Function<String, Stream<BindingSet>> selectFn, String subject) {
        return readLocalizedLiterals(selectFn, subject, NAME_PROPERTY);
    }

    /** {@link #readNames} for {@code arknet:description}. */
    private List<LocalizedLiteral> readDescriptions(Function<String, Stream<BindingSet>> selectFn, String subject) {
        return readLocalizedLiterals(selectFn, subject, DESCRIPTION_PROPERTY);
    }

    private List<LocalizedLiteral> readLocalizedLiterals(
            Function<String, Stream<BindingSet>> selectFn, String subject, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + ACTOR_GRAPH + "> { " + subject + " <" + predicateIri + "> ?o } }";
        return selectFn.apply(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /** Bulk variant of {@link #readLocalizedLiterals}: every actor's candidates in one query. */
    private Map<String, List<LocalizedLiteral>> literalsBySubject(SparqlQuery query, String predicateIri) {
        String sparql = "SELECT ?s ?o WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                + "?s <" + predicateIri + "> ?o . FILTER(isIRI(?s)) } }";
        Map<String, List<LocalizedLiteral>> bySubject = new LinkedHashMap<>();
        query.select(sparql).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(localizedLiteralOf(row, "o")));
        return bySubject;
    }

    /** Builds a language-tagged literal, or a plain untagged one when {@code tag} is {@code null}. */
    private Literal literalOf(String value, String tag) {
        return tag == null ? rdf.createLiteral(value) : rdf.createLiteral(value, tag);
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

    /** Converts a bound literal into the technology-neutral {@link LocalizedLiteral} projection. */
    private static LocalizedLiteral localizedLiteralOf(BindingSet row, String name) {
        Literal literal = literalOf(row, name);
        return new LocalizedLiteral(literal.getLexicalForm(), literal.getLanguageTag().orElse(null));
    }
}
