package de.hauschel.arknet.ul.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * Out-adapter: {@link TermRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF dataset).
 *
 * <p>Maps a {@link Term} to a W3C SKOS concept whose subject is its opaque {@link TermId}
 * (minted once by a {@link de.hauschel.arknet.kernel.ResourceIdFactory}, never derived from the
 * business code or the label), stored in one named graph shared by all terms of a workspace.
 * Each term is typed {@code skos:Concept}, placed into a per-workspace glossary via
 * {@code skos:inScheme}, and carries {@code skos:prefLabel} (the term) and
 * {@code skos:definition} (its meaning); the human-readable running code
 * ({@link TermCode}, {@code TERM-1}) is additionally kept as {@code dcterms:identifier} -
 * identity and label are deliberately different triples on the same subject. This choice makes
 * the model a native fit for kognio-rdf (SKOS concepts are its model) and for arknet's own
 * dogfood glossary.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} +
 * {@code dataset}) and {@link SimpleRdf} - it never imports RDF4J or any other
 * backend-specific type. The backend ({@link DatasetLifecycle} implementation) is
 * supplied by the composition root.</p>
 *
 * <p><strong>WorkspaceId (local, single-user).</strong> Each {@link WorkspaceId} is
 * mapped 1:1 to a kognio-rdf {@link DatasetId}, so distinct workspaces are fully
 * isolated datasets - and thus distinct glossaries. For the MVP there is exactly one
 * {@code skos:ConceptScheme} per workspace ({@link #GLOSSARY_SCHEME}); a per-bounded-context
 * scheme is a later refinement (tracked alongside the requirement-to-term linking).</p>
 *
 * <p><strong>Create vs. update (opaque identity).</strong> Because identity is opaque and
 * minted once, "insert or replace by identity" is no longer one coherent operation.
 * {@link #create} and {@link #update} each check whether the subject already exists
 * <em>inside</em> the write transaction (an {@code ASK}) before writing - not via a separate
 * {@code findByCode} call beforehand, which would leave a check-then-act race between the check
 * and the write. {@link #create} rejects an existing subject with
 * {@link ResourceAlreadyExistsException}; {@link #update} rejects a missing one with
 * {@link TermNotFoundException}. An {@link #update} otherwise replaces the subject's triples
 * wholesale (the same replace-by-identity mechanic the previous save-only contract used).</p>
 *
 * <p><strong>Identity collision vs. code collision.</strong> Both {@link #create} and
 * {@link #update} run a second {@code ASK} in the same transaction - by {@code dcterms:identifier},
 * not by subject - and reject a match with {@link DuplicateTermCodeException} (issue #114:
 * {@link #update} used to skip this check entirely, letting two subjects end up sharing one code).
 * The {@code ASK} excludes the subject being written itself, so {@link #update} may always keep a
 * term's own existing code - only a collision with an <em>other</em> subject's code is rejected.
 * This is deliberately a separate check and a separate exception from
 * {@link ResourceAlreadyExistsException}: an opaque-identity collision is a programming error
 * (identities are minted once and never reused), while a business-code collision (two terms both
 * claiming {@code TERM-1}) is an expected, rejectable outcome a human can cause - and one a
 * sibling bounded context relies on being unique, since {@code arkreq:usesTerm} resolves a term by
 * its {@code dcterms:identifier} (#36).</p>
 *
 * <p><strong>The second interleaving (issue #144).</strong> The in-transaction {@code ASK} above
 * catches two callers racing to {@link #create} the same code only when one fully commits before
 * the other's transaction begins. Two <em>genuinely overlapping</em> transactions instead run
 * under the store's {@code SERIALIZABLE} isolation (kogn-io/rdf-core#18): neither sees the
 * other's uncommitted write, both {@code ASK}s pass, and the loser's {@code commit()} itself is
 * rejected as a conflict - surfacing not as {@link DuplicateTermCodeException} but as the
 * RDF4J-backed store's own commit-time exception. That technology-specific exception must not
 * reach this class (ArchUnit rule 2 in {@code arknet-architecture-tests} - only the
 * {@code *RepositoryFactory} may name RDF4J types), so {@link #isWriteConflict} is a
 * technology-neutral {@link Predicate} the factory builds and injects: this class only ever asks
 * it "was this a write conflict?" and translates a positive answer into the same
 * {@link DuplicateTermCodeException} the synchronous check throws (only on {@link #create}, the
 * only path {@code CodeAssignment}'s retry - see {@code arknet-shared-kernel} - wraps), without
 * ever importing an RDF4J type here.</p>
 *
 * <p><strong>SHACL write-gate.</strong> Every write call validates the candidate instance graph
 * against the ubiquitous-language SHACL shapes via {@link ShaclWriteGate} before starting the
 * write transaction (symmetric to the requirements adapter); a violation throws
 * {@link WriteConstraintViolationException} and nothing is persisted. The gate itself is
 * technology-neutral - only {@link KognioRdfTermRepositoryFactory} names RDF4J.</p>
 *
 * <p><strong>Display language (issue #80).</strong> A concept may carry {@code skos:prefLabel}
 * in several languages ({@code "Kunde"@de}, {@code "Customer"@en}) - SKOS-legal and store-first
 * reachable (ADR-005). {@link #findByCode}/{@link #findAll} therefore join {@code prefLabel} as a
 * <em>multi-valued</em> (but still mandatory) pattern, group the resulting rows per subject, and
 * let the injected {@link DisplayLocale} pick the label to display through a fallback chain
 * (requested language, system default, untagged, deterministic last resort). A concept is never
 * dropped for lacking the requested language - only the shown language degrades. {@code findByIds}
 * (the {@link ResolveTerms} batch) is deliberately untouched: it joins only {@code identifier},
 * never {@code prefLabel}.</p>
 *
 * <p><strong>Blank-node subject guard (issue #104).</strong> {@code ulshapes:TermShape} carries no
 * {@code sh:nodeKind sh:IRI} constraint on the subject, so a store-first (ADR-005) concept whose
 * subject is a blank node (e.g. {@code [] a skos:Concept ; skos:prefLabel "X" ; ...}) is
 * SHACL-legal, even though {@link #create}/{@link #update} always mint an opaque IRI. {@code ?s} is
 * the primary-entity subject here, not a reference-field target, but the same problem applies: the
 * {@code IRI} cast in {@link #iriOf} throws on anything else. Unlike a reference field, though, a
 * crashing primary subject takes the whole result list down with it - {@link #findByCode} and
 * {@link #findAll} therefore add {@code FILTER(isIRI(?s))} (mirroring the {@code
 * FILTER(isIRI(?target))} guard on cross-BC reference fields in the requirements/use-cases
 * adapters) so such a concept is skipped rather than crashing every other term in the workspace.
 * {@link #findByIds} needs no such filter: its subjects come from a {@code VALUES} clause bound to
 * caller-supplied {@link ResourceId}s, which can never denote a blank node.</p>
 *
 * <p><strong>Row multiplication on {@code skos:definition} (issue #81).</strong> Like
 * {@code prefLabel}, {@code skos:definition} carries no {@code sh:maxCount} in {@code ulshapes} -
 * a store-first (ADR-005) concept with two definition literals (e.g. one per language) legally
 * multiplies a subject into two SPARQL rows. Unlike {@code prefLabel}, there is no
 * {@link DisplayLocale} guarantee for {@code definition} (deliberately out of #80's scope), so
 * {@link #findByCode}/{@link #findAll} instead take the first-seen value deterministically (stable
 * because the grouping map preserves row insertion order) and log a single {@code WARN} per
 * assembled {@link Term} when more than one distinct value was seen - visible instead of silently
 * dropped, without inventing a second display-selection mechanism next to {@link DisplayLocale}.</p>
 */
public class KognioRdfTermRepository implements TermRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KognioRdfTermRepository.class);

    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";
    private static final String GLOSSARY_SCHEME = "https://w3id.org/arknet/model/glossary";
    private static final String ARKPROC_NAMESPACE = "https://w3id.org/arknet/process#";

    private static final String CONCEPT_TYPE = ArkreqVocabulary.CONCEPT_TYPE;
    private static final String CONCEPT_SCHEME_TYPE = SKOS_NAMESPACE + "ConceptScheme";
    private static final String IN_SCHEME_PROPERTY = SKOS_NAMESPACE + "inScheme";
    private static final String PREF_LABEL_PROPERTY = SKOS_NAMESPACE + "prefLabel";
    private static final String DEFINITION_PROPERTY = SKOS_NAMESPACE + "definition";
    private static final String IDENTIFIER_PROPERTY = VocabDct.NAMESPACE + "identifier";
    private static final String HUMAN_ACTOR_TYPE = ARKPROC_NAMESPACE + "HumanActor";
    private static final String SYSTEM_ACTOR_TYPE = ARKPROC_NAMESPACE + "SystemActor";
    private static final String ACTOR_ROLE_PROPERTY = ARKPROC_NAMESPACE + "actorRole";

    private final DatasetLifecycle lifecycle;
    private final ShaclWriteGate gate;
    private final DisplayLocale displayLocale;
    private final Predicate<RuntimeException> isWriteConflict;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle       the kognio-rdf dataset lifecycle to acquire datasets from (must not
     *                        be {@code null})
     * @param gate            the SHACL write-gate validating candidate graphs before persistence
     *                        (must not be {@code null})
     * @param displayLocale   the display-language preference selecting which
     *                        {@code skos:prefLabel} the read paths surface for a multilingual
     *                        concept (issue #80; must not be {@code null})
     * @param isWriteConflict recognises the technology-specific commit-time exception of a lost
     *                        {@code SERIALIZABLE} transaction conflict (issue #144), without this
     *                        class ever naming the RDF4J type itself (must not be {@code null})
     */
    KognioRdfTermRepository(DatasetLifecycle lifecycle, ShaclWriteGate gate, DisplayLocale displayLocale,
            Predicate<RuntimeException> isWriteConflict) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.isWriteConflict = Objects.requireNonNull(isWriteConflict, "isWriteConflict");
    }

    @Override
    public void create(WorkspaceId workspaceId, Term term) {
        write(workspaceId, term, true);
    }

    @Override
    public void update(WorkspaceId workspaceId, Term term) {
        write(workspaceId, term, false);
    }

    private void write(WorkspaceId workspaceId, Term term, boolean expectAbsent) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(term, "term");

        // ResourceId#of (issue #83) validates IRIREF-safety at construction, so term.id()'s
        // wrapped IRI is already guaranteed safe to embed here - no separate check needed.
        String subjectIriString = term.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI schemeIri = rdf.createIRI(GLOSSARY_SCHEME);

        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
        graph.add(subjectIri, rdf.createIRI(IN_SCHEME_PROPERTY), schemeIri);
        graph.add(subjectIri, rdf.createIRI(IDENTIFIER_PROPERTY), rdf.createLiteral(term.code().value()));
        graph.add(subjectIri, rdf.createIRI(PREF_LABEL_PROPERTY), rdf.createLiteral(term.prefLabel()));
        graph.add(subjectIri, rdf.createIRI(DEFINITION_PROPERTY), rdf.createLiteral(term.definition()));
        // The per-workspace glossary itself, typed once (idempotent - RDF set semantics).
        graph.add(schemeIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_SCHEME_TYPE));

        // Optional actor facet: the same skos:Concept is additionally typed as an
        // arkproc:Actor (#45). Added before the gate so the facet is validated too. The facet
        // hangs off the subject, so it moves with the now-opaque identity for free.
        ActorFacet actorFacet = term.actorFacet();
        if (actorFacet != null) {
            String actorType = actorFacet.kind() == ActorKind.HUMAN ? HUMAN_ACTOR_TYPE : SYSTEM_ACTOR_TYPE;
            graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(actorType));
            if (actorFacet.role() != null) {
                graph.add(subjectIri, rdf.createIRI(ACTOR_ROLE_PROPERTY), rdf.createLiteral(actorFacet.role()));
            }
        }

        gate.enforce(graph);

        String askExists = "ASK { GRAPH <" + TERMS_GRAPH + "> { " + subject + " ?p ?o } }";
        // Excludes the subject being written itself, so a term is always allowed to keep its own
        // existing code on update() - only a collision with an *other* subject's code is rejected.
        String askCodeExists = "ASK { GRAPH <" + TERMS_GRAPH + "> { "
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(term.code().value()) + "\" . "
                + "FILTER(?s != " + subject + ") } }";
        String deleteExisting = "DELETE WHERE { GRAPH <" + TERMS_GRAPH + "> { " + subject + " ?p ?o } }";
        IRI graphIri = rdf.createIRI(TERMS_GRAPH);

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            try {
                handle.transactor().inTransaction(tx -> {
                    boolean exists = tx.ask(askExists);
                    if (expectAbsent) {
                        if (exists) {
                            throw new ResourceAlreadyExistsException(workspaceId, term.id().value());
                        }
                    } else if (!exists) {
                        throw new TermNotFoundException(workspaceId, term.code());
                    }
                    // Identity is opaque and unique by construction, but the human-readable code
                    // is a separate triple this ASK alone cannot rule out - check it here, inside
                    // the same write transaction, so no other create()/update() can race in
                    // between. Applies to both create() and update(): the FILTER above excludes
                    // the subject's own (about to be replaced) code triple, so update() keeping
                    // its own code never false-trips.
                    if (tx.ask(askCodeExists)) {
                        throw new DuplicateTermCodeException(workspaceId, term.code());
                    }
                    if (exists) {
                        tx.update(deleteExisting);
                    }
                    tx.add(graphIri, graph);
                    return null;
                });
            } catch (RuntimeException e) {
                // The synchronous ASK above only catches a concurrent create() that already fully
                // committed; two genuinely overlapping SERIALIZABLE transactions instead surface
                // here, as the store's own commit-time conflict (issue #144) - translated to the
                // same signal CodeAssignment's retry already handles. Only create() is wrapped by
                // that retry, so update() lets a real conflict propagate unchanged.
                if (expectAbsent && isWriteConflict.test(e)) {
                    throw new DuplicateTermCodeException(workspaceId, term.code());
                }
                throw e;
            }
        }
    }

    @Override
    public Optional<Term> findByCode(WorkspaceId workspaceId, TermCode code) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");

        String query = "SELECT ?s ?prefLabel ?definition ?isHuman ?isSystem ?actorRole WHERE { GRAPH <"
                + TERMS_GRAPH + "> { "
                + "?s a <" + CONCEPT_TYPE + "> ; "
                + "<" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" ; "
                + "<" + PREF_LABEL_PROPERTY + "> ?prefLabel ; "
                + "<" + DEFINITION_PROPERTY + "> ?definition . "
                + "FILTER(isIRI(?s)) "
                + "OPTIONAL { ?s a <" + HUMAN_ACTOR_TYPE + "> . BIND(true AS ?isHuman) } "
                + "OPTIONAL { ?s a <" + SYSTEM_ACTOR_TYPE + "> . BIND(true AS ?isSystem) } "
                + "OPTIONAL { ?s <" + ACTOR_ROLE_PROPERTY + "> ?actorRole } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            Map<String, TermAssembly> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                TermAssembly assembly = assemblyFor(bySubject, row, code);
                assembly.addPrefLabel(literalOf(row, "prefLabel"));
                assembly.addDefinition(literalOf(row, "definition").getLexicalForm());
            });
            return bySubject.values().stream().findFirst().map(assembly -> assembly.toTerm(displayLocale));
        }
    }

    @Override
    public List<Term> findAll(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");

        String query = "SELECT ?s ?identifier ?prefLabel ?definition ?isHuman ?isSystem ?actorRole "
                + "WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "?s a <" + CONCEPT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "?s <" + PREF_LABEL_PROPERTY + "> ?prefLabel . "
                + "?s <" + DEFINITION_PROPERTY + "> ?definition . "
                + "FILTER(isIRI(?s)) "
                + "OPTIONAL { ?s a <" + HUMAN_ACTOR_TYPE + "> . BIND(true AS ?isHuman) } "
                + "OPTIONAL { ?s a <" + SYSTEM_ACTOR_TYPE + "> . BIND(true AS ?isSystem) } "
                + "OPTIONAL { ?s <" + ACTOR_ROLE_PROPERTY + "> ?actorRole } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            Map<String, TermAssembly> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                TermAssembly assembly = assemblyFor(bySubject, row, null);
                assembly.addPrefLabel(literalOf(row, "prefLabel"));
                assembly.addDefinition(literalOf(row, "definition").getLexicalForm());
            });
            return bySubject.values().stream().map(assembly -> assembly.toTerm(displayLocale)).toList();
        }
    }

    /**
     * Groups the (potentially several) rows of one concept - a mandatory but now
     * <em>multi-valued</em> {@code skos:prefLabel}/{@code skos:definition} join multiplies a
     * concept into one row per candidate value (issues #80, #81) - into a single
     * {@link TermAssembly}, keyed by subject IRI. The remaining scalar fields (identity, code,
     * actor facet) are read once from the first row of a subject; every row contributes its
     * {@code prefLabel}/{@code definition} literal as a candidate via
     * {@link TermAssembly#addPrefLabel}/{@link TermAssembly#addDefinition}, called by the two
     * callers ({@link #findByCode}/{@link #findAll}) once per row.
     *
     * <p>{@code identifier}/{@code actorRole} stay single-valued reads - {@code identifier} is
     * already narrowed by the {@code knownCode}/query filter to the code being looked up, and
     * {@code actorRole} is out of scope for #81 (no reported store-first multiplicity vector).
     * Keeping {@code prefLabel} a <em>required</em> (non-optional) join means a store-first concept
     * carrying no {@code prefLabel} at all still binds nothing and is omitted exactly as before -
     * it never reaches the {@link Term} constructor, whose non-blank {@code prefLabel} invariant
     * stays strict.</p>
     *
     * @param knownCode the code when the caller already knows it ({@code findByCode}), else
     *                  {@code null} to read it from the row's {@code identifier} ({@code findAll})
     */
    private static TermAssembly assemblyFor(Map<String, TermAssembly> bySubject, BindingSet row, TermCode knownCode) {
        String subjectIri = iriOf(row, "s").getIRIString();
        return bySubject.computeIfAbsent(subjectIri, iri -> new TermAssembly(
                new TermId(ResourceId.of(iri)),
                knownCode != null ? knownCode : new TermCode(literalOf(row, "identifier").getLexicalForm()),
                actorFacetOf(row)));
    }

    /**
     * Mutable per-subject accumulator collecting a concept's {@code skos:prefLabel} and
     * {@code skos:definition} candidates across rows, then choosing one of each when the concept
     * is finally materialised into a {@link Term}: {@code prefLabel} via the {@link DisplayLocale}
     * fallback chain (issue #80), {@code definition} deterministically as the first-seen value
     * (issue #81 - no display-language guarantee for this field), logging a {@code WARN} if more
     * than one distinct definition was collected.
     */
    private static final class TermAssembly {

        private final TermId id;
        private final TermCode code;
        private final ActorFacet actorFacet;
        private final List<LocalizedLiteral> prefLabels = new ArrayList<>();
        private final List<String> definitions = new ArrayList<>();

        private TermAssembly(TermId id, TermCode code, ActorFacet actorFacet) {
            this.id = id;
            this.code = code;
            this.actorFacet = actorFacet;
        }

        private void addPrefLabel(Literal literal) {
            prefLabels.add(new LocalizedLiteral(literal.getLexicalForm(), literal.getLanguageTag().orElse(null)));
        }

        private void addDefinition(String definition) {
            definitions.add(definition);
        }

        private Term toTerm(DisplayLocale displayLocale) {
            String prefLabel = displayLocale.select(prefLabels)
                    .map(LocalizedLiteral::value)
                    .orElseThrow(() -> new IllegalStateException(
                            "prefLabel is a required join, so at least one candidate must exist"));
            return new Term(id, code, prefLabel, firstDistinctDefinition(), actorFacet);
        }

        /**
         * Returns the first-seen {@code skos:definition} candidate (stable across repeated calls,
         * since {@link LinkedHashMap}/row order preserves insertion order), logging a single
         * {@code WARN} when the subject carried more than one distinct value - issue #81's
         * "stille Luege" this makes visible instead of silently swallowing.
         */
        private String firstDistinctDefinition() {
            long distinctCount = definitions.stream().distinct().count();
            if (distinctCount > 1) {
                LOG.warn("Term {}: field 'definition' had {} distinct values, returning the first",
                        id.value().value(), distinctCount);
            }
            return definitions.get(0);
        }
    }

    /**
     * Batch variant of {@link #findByCode}, keyed by opaque identity instead of business code -
     * backs {@link ResolveTerms} (issue #77 nachtrag). One {@code VALUES}-bound query for the
     * whole batch, not one query per id: the caller (a sibling bounded context's driving adapter,
     * rendering several term references at once) must not pay an N+1 store round-trip.
     *
     * <p>Returns the slim {@link ResolveTerms.ResolvedTerm} projection, not the full {@link Term}
     * aggregate (issue #84): the query below therefore joins only {@code identifier}, not
     * {@code prefLabel}/{@code definition} - fields {@link ResolveTerms} never reads. A store-first
     * term that carries an identity and a code but happens to miss a {@code prefLabel} (which
     * {@link #findByCode}/{@link #findAll} still require) is thus resolvable here.</p>
     *
     * <p><strong>Exactly one {@link ResolveTerms.ResolvedTerm} per resolved subject (issue #77
     * nachtrag 2).</strong> {@code ulshapes:Term-prefLabel} carries {@code sh:minCount 1} but
     * deliberately no {@code sh:maxCount}: SKOS allows - and this glossary intends to allow - one
     * {@code skos:prefLabel} per language on the same concept, store-first (ADR-005) legally so.
     * Its own SHACL identifier constraint carries no {@code sh:maxCount} either, so the single
     * mandatory join below (identifier) is not guaranteed to bind exactly one row per subject.
     * Grouping by subject and keeping the first row's binding turns that cardinality back into
     * "the terms" the port promises, not "one row per predicate combination" - which is what a
     * naive per-row mapping would leak to every caller (a caller keying results by identity, e.g.
     * via {@code Collectors.toMap}, would throw {@code IllegalStateException} on the duplicate
     * key). Which identifier ends up chosen in that (pathological, store-first-only) case is
     * deliberately unspecified.</p>
     */
    @Override
    public List<ResolveTerms.ResolvedTerm> findByIds(WorkspaceId workspaceId, List<ResourceId> ids) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return List.of();
        }

        // ResourceId#of (issue #83) validates IRIREF-safety at construction, so every id here is
        // already guaranteed safe to embed - restores ResolveTerms#getById's "never rejects"
        // contract, which this used to violate by throwing on an impossible identity.
        String values = ids.stream()
                .map(id -> SparqlTerms.iriRef(id.value()))
                .collect(Collectors.joining(" "));

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "VALUES ?s { " + values + " } "
                + "?s a <" + CONCEPT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            Map<String, ResolveTerms.ResolvedTerm> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                String subjectIri = iriOf(row, "s").getIRIString();
                // putIfAbsent, not put: the first row wins if a subject has several identifiers.
                bySubject.putIfAbsent(subjectIri, new ResolveTerms.ResolvedTerm(
                        ResourceId.of(subjectIri),
                        new TermCode(literalOf(row, "identifier").getLexicalForm())));
            });
            return List.copyOf(bySubject.values());
        }
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    private static Literal literalOf(BindingSet row, String name) {
        return (Literal) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    /**
     * Reconstructs the {@link ActorFacet} of a term row, or {@code null} if the
     * subject carries no {@code arkproc:HumanActor}/{@code arkproc:SystemActor} type.
     */
    private static ActorFacet actorFacetOf(BindingSet row) {
        if (row.hasBinding("isHuman")) {
            return new ActorFacet(ActorKind.HUMAN, optionalLiteralOf(row, "actorRole"));
        }
        if (row.hasBinding("isSystem")) {
            return new ActorFacet(ActorKind.SYSTEM, optionalLiteralOf(row, "actorRole"));
        }
        return null;
    }

    private static String optionalLiteralOf(BindingSet row, String name) {
        return row.getValue(name).map(value -> ((Literal) value).getLexicalForm()).orElse(null);
    }
}
