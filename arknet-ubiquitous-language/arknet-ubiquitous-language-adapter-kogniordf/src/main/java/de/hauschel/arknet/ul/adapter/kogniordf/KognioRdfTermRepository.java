// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.kogniordf;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermConcurrentlyModifiedException;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * Out-adapter: {@link TermRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF store).
 *
 * <p>Maps a {@link Term} to a W3C SKOS concept whose subject is its opaque
 * {@link de.hauschel.arknet.ul.domain.TermId} (minted once by a
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}, never derived from the business code or
 * the label), stored in one named graph shared by all terms of a workspace. Each term is typed
 * {@code skos:Concept}, placed into a per-workspace glossary via {@code skos:inScheme}, and
 * carries {@code skos:prefLabel} (the term) and {@code skos:definition} (its meaning); the
 * human-readable running code ({@link TermCode}, {@code TERM-1}) is additionally kept as
 * {@code dcterms:identifier} - identity and label are deliberately different triples on the same
 * subject.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset})
 * and {@link SimpleRdf} - it never imports RDF4J or any other backend-specific type. The backend
 * ({@link DatasetLifecycle} implementation) is supplied by the composition root.</p>
 *
 * <p><strong>WorkspaceId (local, single-user).</strong> Each {@link WorkspaceId} is mapped 1:1 to
 * a kognio-rdf {@link DatasetId}, so distinct workspaces are fully isolated datasets - and thus
 * distinct glossaries. For the MVP there is exactly one {@code skos:ConceptScheme} per workspace
 * ({@link UlVocabulary#GLOSSARY_SCHEME}).</p>
 *
 * <h2>Graph-backed terms (spike, issue #168)</h2>
 *
 * <p>A {@link Term} this adapter hands out or accepts is a {@link GraphBackedTerm}: it holds the
 * subject's triples, not fields. Three things follow, and they are the point of the spike:</p>
 *
 * <ul>
 *   <li><strong>{@link #create} does not build a candidate graph.</strong> The graph arrived
 *       with the term (built by {@link GraphBackedTermFactory}, which the composition root wired
 *       into the service). All this method adds is the one triple that is not about the term's
 *       own subject - the glossary's own type.</li>
 *   <li><strong>The read paths do not assemble anything.</strong> They {@code CONSTRUCT} the
 *       subjects' triples and wrap them. The per-subject row grouping the {@code SELECT}-based
 *       predecessor needed - because a multi-valued {@code skos:prefLabel}/{@code skos:definition}
 *       multiplies rows (issues #80/#81) - has no counterpart here: a graph is a set of triples,
 *       so nothing multiplied. The {@code OPTIONAL}/{@code BIND} dance that reconstructed the
 *       Actor facette is gone for the same reason ({@code ?s ?p ?o} already brings the type
 *       triples along).</li>
 *   <li><strong>{@link #update} is a replace again, and it is safe this time.</strong> The
 *       predecessor had to patch predicate by predicate (issue #163 follow-up) because a full
 *       {@code Term} could not round-trip what it did not model - a second language-tagged
 *       label, a duplicate definition, a store-first predicate nobody mapped. A graph round-trips
 *       everything it read, so "read, mutate the fields the caller supplied, write the subject
 *       back" preserves the rest by construction rather than by a per-predicate delete list. The
 *       {@code assertedContext} gymnastics that let the SHACL gate see a truthful state
 *       (ADR-007 Nachtrag #63) fall away with it: the gate now validates the complete resulting
 *       subject, because that is what the object is.</li>
 * </ul>
 *
 * <p><strong>The cast is the price.</strong> {@link #create} and the object {@link #update}
 * mutates must be this adapter's own implementation. The {@link TermRepository} contract cannot
 * say so - saying it would put an RDF type in the core - so it is an unwritten precondition
 * enforced by {@link #requireOwn} with a deliberately explicit message rather than a bare
 * {@code ClassCastException}.</p>
 *
 * <p><strong>Create vs. update (opaque identity).</strong> Because identity is opaque and minted
 * once, "insert or replace by identity" was never one coherent operation for {@link #create}. The
 * transactional mechanics - the in-transaction {@code ASK}, the SHACL gate, the commit-conflict
 * translation - live in the shared {@link WriteFunnel} (ADR-013). A business-code collision is
 * rejected there as {@link DuplicateTermCodeException}, an identity collision as
 * {@link ResourceAlreadyExistsException}: an opaque-identity collision is a programming error,
 * a business-code collision is an expected outcome a human can cause - and one a sibling bounded
 * context relies on being unique, since {@code arkreq:usesTerm} resolves a term by its
 * {@code dcterms:identifier} (#36). {@link #update} needs no code check at all: it never rewrites
 * {@code dcterms:identifier}, so a code collision is structurally unreachable through it.</p>
 *
 * <p><strong>Concurrency.</strong> {@link #update} still runs outside the funnel (ADR-013
 * Entscheidung 5; ADR-014 Phase 2 will dissolve it into a conditional funnel write) and
 * translates the store's {@code SERIALIZABLE} commit conflict into
 * {@link TermConcurrentlyModifiedException} via the injected {@link #isWriteConflict} predicate,
 * never naming an RDF4J type. That conflict signal is load-bearing here in a way it was not
 * before: a wholesale replace loses a concurrent writer's change to a <em>different</em>
 * predicate unless the store rejects the overlap, whereas the predecessor's per-predicate patch
 * could not lose one at all. Read-modify-write inside one transaction plus
 * {@code SERIALIZABLE} closes that gap today; ADR-014's head revision is what closes it once the
 * merge moves out of the adapter.</p>
 *
 * <p><strong>Blank-node subject guard (issue #104).</strong> {@code ulshapes:TermShape} carries
 * no {@code sh:nodeKind sh:IRI} constraint, so a store-first (ADR-005) concept with a blank-node
 * subject is SHACL-legal. Both read paths keep the {@code FILTER(isIRI(?s))} guard so such a
 * concept is skipped rather than crashing every other term in the workspace.
 * {@link #findByIds} needs none: its subjects come from a {@code VALUES} clause bound to
 * caller-supplied {@link ResourceId}s.</p>
 *
 * <p><strong>{@link #findByIds} is deliberately untouched by the spike.</strong> It answers "what
 * code names this identity" for a sibling bounded context's display resolution and returns the
 * slim {@link ResolveTerms.ResolvedTerm} projection, not a {@link Term}. A graph-backed object
 * would be strictly more expensive there for no gain - evidence that the pattern is a choice per
 * read path, not an all-or-nothing conversion.</p>
 */
public class KognioRdfTermRepository implements TermRepository {

    private final DatasetLifecycle lifecycle;
    private final ShaclWriteGate gate;
    private final DisplayLocale displayLocale;
    private final Predicate<RuntimeException> isWriteConflict;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle       the kognio-rdf dataset lifecycle to acquire datasets from (must not
     *                        be {@code null})
     * @param gate            the SHACL write-gate validating candidate graphs before persistence
     *                        (must not be {@code null})
     * @param displayLocale   the display-language preference selecting which
     *                        {@code skos:prefLabel} a term surfaces for a multilingual concept
     *                        (issue #80; must not be {@code null})
     * @param isWriteConflict recognises the technology-specific commit-time exception of a lost
     *                        {@code SERIALIZABLE} transaction conflict (issue #144), without this
     *                        class ever naming the RDF4J type itself (must not be {@code null})
     * @param funnel          the shared write funnel (ADR-013) {@link #create} runs through; not
     *                        used by {@link #update} or the read paths (must not be {@code null})
     */
    KognioRdfTermRepository(DatasetLifecycle lifecycle, ShaclWriteGate gate, DisplayLocale displayLocale,
            Predicate<RuntimeException> isWriteConflict, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.isWriteConflict = Objects.requireNonNull(isWriteConflict, "isWriteConflict");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(WorkspaceId workspaceId, Term term) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(term, "term");

        GraphBackedTerm backed = requireOwn(term);
        // The term's own graph carries every triple about its subject. The glossary's type is
        // the one triple that is not about the subject, so it is added here (idempotent - RDF
        // set semantics) instead of travelling inside the term and being lost on a round trip.
        Graph candidate = copyOf(backed.graph());
        candidate.add(UlVocabulary.GLOSSARY_SCHEME_IRI, VocabRdf.TYPE, UlVocabulary.CONCEPT_SCHEME_TYPE_IRI);

        String subjectIri = backed.subject().getIRIString();
        String code = backed.code().value();
        funnel.create(new DatasetId(workspaceId.value()), UlVocabulary.TERMS_GRAPH, subjectIri, code,
                candidate, null,
                () -> new ResourceAlreadyExistsException(workspaceId, backed.id().value()),
                () -> new DuplicateTermCodeException(workspaceId, backed.code()),
                tx -> tx.add(UlVocabulary.TERMS_GRAPH_IRI, candidate));
    }

    /**
     * Corrects specific fields of an existing term by business code, leaving every predicate the
     * caller did not supply a value for untouched.
     *
     * <p>Reads the subject's full graph, mutates only the supplied fields through the term's own
     * setters, and writes the subject back - all inside one transaction, so there is no
     * application-level read-then-write gap. Everything the caller did not touch survives because
     * it never left the graph, not because a delete list spared it.</p>
     *
     * <p>The Actor facette keeps its "a {@code null} role means unchanged, not cleared" contract,
     * merged explicitly here rather than smuggled into the setter, which has the plain
     * replace-or-remove semantics its name promises.</p>
     */
    @Override
    public Term update(WorkspaceId workspaceId, TermCode code, String prefLabel, String definition,
            ActorFacet actorFacet) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            try {
                return handle.transactor().inTransaction(tx -> {
                    GraphBackedTerm term = readByCode(tx::construct, code)
                            .orElseThrow(() -> new TermNotFoundException(workspaceId, code));

                    if (prefLabel != null) {
                        term.prefLabel(prefLabel);
                    }
                    if (definition != null) {
                        term.definition(definition);
                    }
                    if (actorFacet != null) {
                        term.actorFacet(mergedActorFacet(term.actorFacet(), actorFacet));
                    }

                    // The candidate IS the complete resulting state of the subject, so the gate
                    // sees the truth without a single validation-only asserted triple.
                    gate.enforce(term.graph());

                    tx.update(deleteEverythingOf(term.subject().getIRIString()));
                    tx.add(UlVocabulary.TERMS_GRAPH_IRI, term.graph());
                    return term;
                });
            } catch (RuntimeException e) {
                if (isWriteConflict.test(e)) {
                    throw new TermConcurrentlyModifiedException(workspaceId, code);
                }
                throw e;
            }
        }
    }

    @Override
    public Optional<Term> findByCode(WorkspaceId workspaceId, TermCode code) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            return readByCode(handle.sparqlQuery()::construct, code).map(Term.class::cast);
        }
    }

    @Override
    public List<Term> findAll(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");

        String query = "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + UlVocabulary.TERMS_GRAPH + "> { "
                + "?s a <" + UlVocabulary.CONCEPT_TYPE + "> ; "
                + "<" + UlVocabulary.IDENTIFIER_PROPERTY + "> ?identifier ; "
                + "<" + UlVocabulary.PREF_LABEL_PROPERTY + "> ?prefLabel ; "
                + "<" + UlVocabulary.DEFINITION_PROPERTY + "> ?definition ; "
                + "?p ?o . "
                + "FILTER(isIRI(?s)) } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            return wrapAll(handle.sparqlQuery().construct(query));
        }
    }

    /**
     * Batch lookup by opaque identity, backing {@link ResolveTerms} (issue #77) - one
     * {@code VALUES}-bound query for the whole batch, never one query per id.
     *
     * <p>Left on the {@code SELECT}/projection path on purpose (see the class javadoc): the only
     * consumer needs identity plus code, so joining only {@code dcterms:identifier} keeps a
     * store-first term resolvable here that {@link #findByCode}/{@link #findAll} would skip for
     * lacking a {@code prefLabel}. Grouping by subject and keeping the first binding turns a
     * subject with several identifiers (SHACL-legal - no {@code sh:maxCount}) back into "the
     * terms" the port promises rather than one row per predicate combination.</p>
     */
    @Override
    public List<ResolveTerms.ResolvedTerm> findByIds(WorkspaceId workspaceId, List<ResourceId> ids) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return List.of();
        }

        String values = ids.stream()
                .map(id -> SparqlTerms.iriRef(id.value()))
                .collect(Collectors.joining(" "));

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + UlVocabulary.TERMS_GRAPH + "> { "
                + "VALUES ?s { " + values + " } "
                + "?s a <" + UlVocabulary.CONCEPT_TYPE + "> . "
                + "?s <" + UlVocabulary.IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            Map<String, ResolveTerms.ResolvedTerm> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                IRI subject = (IRI) row.getValue("s")
                        .orElseThrow(() -> new IllegalStateException("missing binding 's'"));
                Literal identifier = (Literal) row.getValue("identifier")
                        .orElseThrow(() -> new IllegalStateException("missing binding 'identifier'"));
                // putIfAbsent, not put: the first row wins if a subject has several identifiers.
                bySubject.putIfAbsent(subject.getIRIString(), new ResolveTerms.ResolvedTerm(
                        ResourceId.of(subject.getIRIString()), new TermCode(identifier.getLexicalForm())));
            });
            return List.copyOf(bySubject.values());
        }
    }

    /**
     * Reads one term's complete subject graph by business code - shared by {@link #findByCode}
     * (outside any transaction) and {@link #update} (inside the very transaction that then writes
     * it back), so both see the same mandatory-join semantics: a concept missing either
     * {@code skos:prefLabel} or {@code skos:definition} is invisible to both.
     */
    private Optional<GraphBackedTerm> readByCode(Function<String, ReadableGraph> constructFn, TermCode code) {
        String query = "CONSTRUCT { ?s ?p ?o } WHERE { GRAPH <" + UlVocabulary.TERMS_GRAPH + "> { "
                + "?s a <" + UlVocabulary.CONCEPT_TYPE + "> ; "
                + "<" + UlVocabulary.IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" ; "
                + "<" + UlVocabulary.PREF_LABEL_PROPERTY + "> ?prefLabel ; "
                + "<" + UlVocabulary.DEFINITION_PROPERTY + "> ?definition ; "
                + "?p ?o . "
                + "FILTER(isIRI(?s)) } }";
        return wrapAll(constructFn.apply(query)).stream().findFirst().map(GraphBackedTerm.class::cast);
    }

    /**
     * Splits a {@code CONSTRUCT} result into one graph-backed term per IRI subject.
     *
     * <p>This is the entire read-side mapping layer. It knows no predicate of the ubiquitous
     * language - only that a subject's triples belong together - which is why the multi-value,
     * row-multiplication and optional-facette handling the predecessor needed here has no
     * counterpart. Subjects are ordered by IRI so a result is reproducible without depending on
     * the store's incidental statement order.</p>
     */
    private List<Term> wrapAll(ReadableGraph constructed) {
        Map<String, Graph> bySubject = new LinkedHashMap<>();
        Map<String, IRI> subjects = new LinkedHashMap<>();
        constructed.stream().forEach(triple -> {
            if (!(triple.getSubject() instanceof IRI subject)) {
                return;
            }
            bySubject.computeIfAbsent(subject.getIRIString(), iri -> rdf.createGraph()).add(triple);
            subjects.putIfAbsent(subject.getIRIString(), subject);
        });
        List<Term> terms = new ArrayList<>();
        bySubject.keySet().stream().sorted(Comparator.naturalOrder()).forEach(iri ->
                terms.add(new GraphBackedTerm(bySubject.get(iri), subjects.get(iri), displayLocale)));
        return List.copyOf(terms);
    }

    /**
     * Rejects a {@link Term} this adapter did not create. The precondition cannot live in the
     * {@link TermRepository} contract without dragging an RDF type into the core, so it is
     * checked here and reported as what it is: a wiring mistake (the composition root handed the
     * service a {@link de.hauschel.arknet.ul.application.port.out.TermFactory} that does not
     * belong to this repository), not a domain failure.
     */
    private static GraphBackedTerm requireOwn(Term term) {
        if (term instanceof GraphBackedTerm backed) {
            return backed;
        }
        throw new IllegalArgumentException("this repository only persists terms created by its own "
                + "TermFactory (GraphBackedTermFactory), got " + term.getClass().getName());
    }

    /**
     * Applies the port's "a {@code null} role leaves an already-set role untouched" contract:
     * a non-null facette always replaces the kind, but keeps the current role when the caller
     * supplied none.
     */
    private static ActorFacet mergedActorFacet(ActorFacet current, ActorFacet supplied) {
        if (supplied.role() != null) {
            return supplied;
        }
        return new ActorFacet(supplied.kind(), current != null ? current.role() : null);
    }

    /** Every triple of {@code subjectIri} - what a wholesale subject replace deletes first. */
    private static String deleteEverythingOf(String subjectIri) {
        return "DELETE WHERE { GRAPH <" + UlVocabulary.TERMS_GRAPH + "> { "
                + SparqlTerms.iriRef(subjectIri) + " ?p ?o } }";
    }

    private Graph copyOf(ReadableGraph source) {
        Graph copy = rdf.createGraph();
        source.stream().forEach((Triple triple) -> copy.add(triple));
        return copy;
    }
}
