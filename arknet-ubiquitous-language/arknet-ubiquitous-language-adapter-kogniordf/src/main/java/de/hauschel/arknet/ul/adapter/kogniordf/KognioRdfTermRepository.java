// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.kogniordf;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermConcurrentlyModifiedException;
import de.hauschel.arknet.ul.domain.TermCycleException;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermNotFoundException;
import de.hauschel.arknet.ul.domain.TermReferencedException;

/**
 * Out-adapter: {@link TermRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF store).
 *
 * <p>Maps a {@link Term} to a W3C SKOS concept whose subject is its opaque {@link TermId}
 * (minted once by a {@link de.hauschel.arknet.kernel.ResourceIdFactory}, never derived from the
 * business code or the label), stored in one named graph shared by all terms of a project.
 * Each term is typed {@code skos:Concept}, placed into a per-project glossary via
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
 * <p><strong>ProjectId (local, single-user).</strong> Each {@link ProjectId} is
 * mapped 1:1 to a kognio-rdf {@link DatasetId}, so distinct projects are fully
 * isolated datasets - and thus distinct glossaries. For the MVP there is exactly one
 * {@code skos:ConceptScheme} per project ({@link #GLOSSARY_SCHEME}); a per-bounded-context
 * scheme is a later refinement (tracked alongside the requirement-to-term linking).</p>
 *
 * <p><strong>Create vs. update (opaque identity).</strong> Because identity is opaque and
 * minted once, "insert or replace by identity" was never one coherent operation for
 * {@link #create}. The transactional mechanics of that check - the in-transaction {@code contains}
 * check, the SHACL gate, the commit-conflict translation - live in the shared {@link WriteFunnel}
 * (ADR-013), not here; {@link #create} only builds the candidate graph and rejects an existing
 * subject with {@link ResourceAlreadyExistsException}.</p>
 *
 * <p><strong>Update is a targeted correction by code, not a replace by identity.</strong>
 * {@link #update} used to take a full {@link Term} and wholesale-replace the
 * subject's triples the same way {@link #create} inserts them - which meant every field the
 * caller did not intend to touch had to be read back first and merged into that full replacement,
 * destroying any triple the read could not faithfully round-trip (most severely a multi-valued
 * {@code skos:prefLabel}/{@code skos:definition}, see the display-language and row-multiplication
 * notes below). {@link #update} instead resolves the subject by its unchanged {@link TermCode}
 * and deletes-and-reinserts only the predicate(s) whose new value the caller actually supplied -
 * {@code null} means the predicate is never touched at all, at the triple level, not "read back
 * and rewritten identically". A missing code throws {@link TermNotFoundException}.</p>
 *
 * <p><strong>Identity collision vs. code collision.</strong> {@link #create} runs a second
 * {@code contains} check in the same transaction - by {@code dcterms:identifier}, not by subject - and
 * rejects a match with {@link DuplicateTermCodeException}. This is deliberately a separate check
 * and a separate exception from {@link ResourceAlreadyExistsException}: an opaque-identity
 * collision is a programming error (identities are minted once and never reused), while a
 * business-code collision (two terms both claiming {@code TERM-1}) is an expected, rejectable
 * outcome a human can cause - and one a sibling bounded context relies on being unique, since
 * {@code arkreq:usesTerm} resolves a term by its {@code dcterms:identifier}.
 * {@link #update} needs no such check (now moot): it never rewrites
 * {@code dcterms:identifier} at all, so it cannot itself introduce a code collision - a stronger,
 * structural guarantee rather than a checked one.</p>
 *
 * <p><strong>Compare-and-set through the funnel, with retry (ADR-014 decision
 * 4).</strong> An earlier version ran its own transaction and translated a genuine {@code
 * SERIALIZABLE} write conflict (the "second interleaving" scenario) on the caller's own patched
 * predicate into {@link TermConcurrentlyModifiedException}. {@link #update} now retries {@link
 * #attemptUpdate} (bounded by {@link #MAX_RETRY_ATTEMPTS}) against the shared {@link WriteFunnel}
 * (ADR-013): each attempt reads the term's current state and {@code arkprov:head} together, then
 * asks the funnel to apply the patch only if that head still matches - a head conflict, whether
 * from a losing synchronous comparison or a losing commit under {@code SERIALIZABLE} isolation,
 * surfaces identically and is retried transparently, exactly the CAS guard {@code
 * RequirementRepository#compareAndUpdate} degenerated to for the same reason.</p>
 *
 * <p><strong>SHACL write-gate.</strong> The gate mechanics - validate before the write transaction
 * opens, {@link WriteConstraintViolationException} on a violation, nothing persisted - live in the
 * shared {@link WriteFunnel} (ADR-013), for {@link #create} and {@link #update}
 * alike: {@link #attemptUpdate} builds the same validation-only {@code assertedContext} an earlier
 * version enforced itself (mirroring how the sibling requirements adapter asserts a referenced
 * term's type) - a predicate {@link #update} is not touching is asserted there for validation
 * only, from what was just read before the transaction, so {@code ulshapes:TermShape}'s
 * {@code prefLabel} shape still sees the resulting state truthfully without this class ever
 * persisting that assertion again.</p>
 *
 * <p><strong>Display language.</strong> A concept may carry {@code skos:prefLabel} and
 * {@code skos:definition} in several languages ({@code "Kunde"@de}/{@code "Eine juristische..."@de},
 * {@code "Customer"@en}/{@code "A legal..."@en}) - SKOS-legal and store-first reachable (ADR-005).
 * {@link #findByCode}/{@link #findAll} therefore join both {@code prefLabel} and {@code definition}
 * as <em>multi-valued</em> (but still mandatory) patterns, group the resulting rows per subject, and
 * let the injected {@link DisplayLocale} pick both fields' displayed value through the very same
 * fallback chain instance (requested language, system default, untagged, deterministic last resort)
 * - so a card showing both fields for one concept never mixes two languages between them (issue
 * #248). A concept is never dropped for lacking the requested language - only the shown language
 * degrades. {@code findByIds} (the {@link ResolveTerms} batch) is deliberately untouched: it joins
 * only {@code identifier}, never {@code prefLabel}/{@code definition}.</p>
 *
 * <p><strong>Blank-node subject guard.</strong> {@code ulshapes:TermShape} carries no
 * {@code sh:nodeKind sh:IRI} constraint on the subject, so a store-first (ADR-005) concept whose
 * subject is a blank node (e.g. {@code [] a skos:Concept ; skos:prefLabel "X" ; ...}) is
 * SHACL-legal, even though {@link #create} always mints an opaque IRI subject. {@code ?s} is
 * the primary-entity subject here, not a reference-field target, but the same problem applies: the
 * {@code IRI} cast in {@link #iriOf} throws on anything else. Unlike a reference field, though, a
 * crashing primary subject takes the whole result list down with it - {@link #findByCode} and
 * {@link #findAll} therefore add {@code FILTER(isIRI(?s))} (mirroring the {@code
 * FILTER(isIRI(?target))} guard on cross-BC reference fields in the requirements/use-cases
 * adapters) so such a concept is skipped rather than crashing every other term in the project.
 * {@link #findByIds} needs no such filter: its subjects come from a {@code VALUES} clause bound to
 * caller-supplied {@link ResourceId}s, which can never denote a blank node.</p>
 *
 * <p><strong>Row multiplication on {@code skos:definition}.</strong> Like
 * {@code prefLabel}, {@code skos:definition} carries no {@code sh:maxCount} in {@code ulshapes} -
 * a store-first (ADR-005) concept with two definition literals (e.g. one per language) legally
 * multiplies a subject into two SPARQL rows. {@code definition} shares the exact same
 * {@link DisplayLocale} fallback chain as {@code prefLabel} (issue #248): a card that shows a
 * concept's label and its definition side by side must resolve both against the very same
 * {@link DisplayLocale}, or the two fields silently disagree on the displayed language for one
 * and the same resource - which is precisely the bug an earlier, definition-only "first-seen"
 * shortcut caused. {@link #findByCode}/{@link #findAll} therefore collect {@code definition}
 * candidates exactly like {@code prefLabel} and let {@link TermAssembly#toTerm} select from both
 * with one shared {@link DisplayLocale} instance.</p>
 *
 * <p><strong>No actor facet (since issue #336).</strong> A term used to be optionally
 * double-typed as an {@code arkproc:Actor} subtype ({@code HumanActor}/{@code SystemActor}/
 * {@code LegalActor}) with an optional {@code arkproc:actorRole} literal - that facet has been
 * removed without replacement. Actors now live in {@code arknet-actor}'s own register, one
 * ungoverned resource type in its own named graph; a glossary term is a {@code skos:Concept}
 * and nothing more.</p>
 */
public class KognioRdfTermRepository implements TermRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KognioRdfTermRepository.class);

    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";
    private static final String GLOSSARY_SCHEME = "https://w3id.org/arknet/model/glossary";

    private static final String CONCEPT_TYPE = ArkreqVocabulary.CONCEPT_TYPE;
    private static final String CONCEPT_SCHEME_TYPE = SKOS_NAMESPACE + "ConceptScheme";
    private static final String IN_SCHEME_PROPERTY = SKOS_NAMESPACE + "inScheme";
    private static final String PREF_LABEL_PROPERTY = SKOS_NAMESPACE + "prefLabel";
    private static final String DEFINITION_PROPERTY = ArkreqVocabulary.DEFINITION;
    private static final String BROADER_PROPERTY = ArkreqVocabulary.BROADER;
    private static final String IDENTIFIER_PROPERTY = VocabDct.NAMESPACE + "identifier";

    /**
     * The prefix every code this hexagon mints carries. Used only by {@link #findRetainedCodes} to
     * tell a term's own retained code apart from a neighbouring bounded context's, since the
     * provenance graph {@link WriteFunnel#findRetainedCodes} reads from is shared by all of them.
     */
    private static final String CODE_PREFIX = "TERM-";

    /**
     * Bound on {@link #update}'s CAS retry loop (same bound and rationale as {@code
     * RequirementService#MAX_RETRY_ATTEMPTS}): a head conflict is resolved by a single retry in
     * the overwhelming majority of cases, since each retry re-reads the now-current state and
     * head before trying again; this bound only exists so a pathological, sustained storm of
     * concurrent writers against the very same term fails loudly instead of looping forever.
     */
    private static final int MAX_RETRY_ATTEMPTS = 20;

    private final DatasetLifecycle lifecycle;
    private final DisplayLocale displayLocale;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from (must not be
     *                      {@code null})
     * @param displayLocale the display-language preference selecting which {@code skos:prefLabel}
     *                      the read paths surface for a multilingual concept (must not
     *                      be {@code null})
     * @param funnel        the shared write funnel (ADR-013) every write runs through - both
     *                      {@link #create} and {@link #update} (must not be {@code null})
     */
    KognioRdfTermRepository(DatasetLifecycle lifecycle, DisplayLocale displayLocale, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, Term term, String language) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(term, "term");

        DatasetId datasetId = new DatasetId(projectId.value());
        // Built unconditionally (empty when term.broader() is null) - simpler than a nullable
        // Graph, and attemptUpdate() already always builds one for the same reason.
        Graph assertedContext = rdf.createGraph();
        // Resolved before the graph is built: an unresolvable broader code must abort the whole
        // create, not leave a half-written term behind. No cycle check is needed here - the
        // identity below is minted fresh, so it can never already sit anywhere in an existing
        // broader chain (see TermCycleException's javadoc).
        String broaderTargetIri = resolveBroaderTargetIri(projectId, datasetId, term.broader(), assertedContext);

        // ResourceId#of validates IRIREF-safety at construction, so term.id()'s
        // wrapped IRI is already guaranteed safe to embed here - no separate check needed.
        String subjectIriString = term.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        IRI schemeIri = rdf.createIRI(GLOSSARY_SCHEME);

        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
        graph.add(subjectIri, rdf.createIRI(IN_SCHEME_PROPERTY), schemeIri);
        graph.add(subjectIri, rdf.createIRI(IDENTIFIER_PROPERTY), rdf.createLiteral(term.code().value()));
        String tag = canonicalLanguageTag(language);
        graph.add(subjectIri, rdf.createIRI(PREF_LABEL_PROPERTY), literalOf(term.prefLabel(), tag));
        graph.add(subjectIri, rdf.createIRI(DEFINITION_PROPERTY), literalOf(term.definition(), tag));
        // The per-project glossary itself, typed once (idempotent - RDF set semantics).
        graph.add(schemeIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_SCHEME_TYPE));

        if (broaderTargetIri != null) {
            graph.add(subjectIri, rdf.createIRI(BROADER_PROPERTY), rdf.createIRI(broaderTargetIri));
        }

        IRI graphIri = rdf.createIRI(TERMS_GRAPH);

        funnel.create(datasetId, TERMS_GRAPH, subjectIriString, term.code().value(),
                graph, assertedContext,
                () -> new ResourceAlreadyExistsException(projectId, term.id().value()),
                () -> new DuplicateTermCodeException(projectId, term.code()),
                tx -> tx.add(graphIri, graph));
    }

    /**
     * Resolves {@code broaderCode} to its subject IRI within {@code projectId}'s glossary and
     * asserts just enough of the target's own already-persisted state into
     * {@code assertedContext} for the gate to accept it as a shape-legal {@code skos:broader}
     * target (see {@link #assertBroaderTargetShapeState}), or returns {@code null} without
     * touching {@code assertedContext} if {@code broaderCode} itself is {@code null} (no broader
     * term requested). Read outside any transaction, mirroring {@link #attemptUpdate}'s own
     * pre-transaction resolution - both {@link #create} and {@link #update} need the target's
     * identity before the SHACL-gated write, not inside it.
     *
     * @throws TermNotFoundException if {@code broaderCode} does not resolve to an existing term
     */
    private String resolveBroaderTargetIri(
            ProjectId projectId, DatasetId datasetId, TermCode broaderCode, Graph assertedContext) {
        if (broaderCode == null) {
            return null;
        }
        try (DatasetHandle handle = lifecycle.acquire(datasetId)) {
            Function<String, Stream<BindingSet>> selectFn = handle.sparqlQuery()::select;
            String targetIri = resolveTermSubjectIri(selectFn, broaderCode)
                    .orElseThrow(() -> new TermNotFoundException(projectId, broaderCode));
            assertBroaderTargetShapeState(assertedContext, selectFn, targetIri);
            return targetIri;
        }
    }

    /**
     * Asserts just enough of {@code targetIri}'s own already-persisted state into
     * {@code assertedContext} for {@code ulshapes:TermShape} to accept it as a shape-legal
     * {@code skos:broader} target: its type and one {@code skos:prefLabel} literal.
     *
     * <p><strong>Why a bare type assertion is not enough here.</strong> Unlike a cross-BC
     * reference (e.g. the requirements adapter asserting a term's type for
     * {@code arkreq:usesTerm}), the referenced node's own home shape - {@code
     * ulshapes:TermShape} - is loaded in <em>this very adapter's own</em> {@link ShaclWriteGate},
     * since {@code skos:broader} is self-referential (Term -&gt; Term). Asserting only {@code
     * targetIri a skos:Concept} therefore does not just satisfy {@code ulshapes:Term-broader}'s
     * {@code sh:class} constraint on the referencing subject - it also makes {@code
     * ulshapes:TermShape} itself target {@code targetIri}, whose real {@code skos:prefLabel} the
     * gate's isolated candidate+assertedContext graph does not otherwise contain, which fails
     * {@code Term-prefLabel}'s {@code sh:minCount 1}. Asserting one of the target's real {@code
     * skos:prefLabel} literals closes that gap ({@code Term-definition} carries no
     * {@code sh:minCount}, and {@code Term-inScheme} is {@code sh:Warning}-severity only, so
     * neither needs the same treatment). Which literal is picked when the target legally carries
     * several (a store-first, multi-language term) is deliberately unspecified - this exists only
     * to keep the gate from re-rejecting a target whose full state already satisfies the shape,
     * not to re-verify a shape this class's own {@link #create}/{@link #attemptUpdate} already
     * enforced when that target was written.</p>
     */
    private void assertBroaderTargetShapeState(
            Graph assertedContext, Function<String, Stream<BindingSet>> selectFn, String targetIri) {
        IRI target = rdf.createIRI(targetIri);
        assertedContext.add(target, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
        String query = "SELECT ?prefLabel WHERE { GRAPH <" + TERMS_GRAPH + "> { " + SparqlTerms.iriRef(targetIri)
                + " <" + PREF_LABEL_PROPERTY + "> ?prefLabel } } LIMIT 1";
        selectFn.apply(query).findFirst()
                .ifPresent(row -> assertedContext.add(target, rdf.createIRI(PREF_LABEL_PROPERTY), literalOf(row, "prefLabel")));
    }

    /**
     * Resolves a term's business code to its subject IRI within {@code TERMS_GRAPH}, mirroring
     * {@code KognioRdfTermLookup#resolveByCode} but scoped to this class's own graph (this is a
     * same-BC, self-referential lookup - see {@link TermCycleException}'s javadoc - so it needs no
     * cross-context lookup port). The first match wins if the store-first (ADR-005) store legally
     * holds more than one, mirroring every other code lookup in this class (e.g.
     * {@link #readAssemblyByCode}); {@code dcterms:identifier} uniqueness going forward is
     * {@link DuplicateTermCodeException}'s concern, not this method's.
     */
    private Optional<String> resolveTermSubjectIri(
            Function<String, Stream<BindingSet>> selectFn, TermCode code) {
        String query = "SELECT ?s WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "?s a <" + CONCEPT_TYPE + "> ; <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value())
                + "\" . FILTER(isIRI(?s)) } }";
        return selectFn.apply(query).findFirst().map(row -> iriOf(row, "s").getIRIString());
    }

    /**
     * Reads the single {@code skos:broader} target of {@code subjectIri}, if any - the one-hop
     * primitive {@link #assertNoCycle} repeatedly calls to walk a candidate broader term's own
     * chain.
     */
    private Optional<String> readBroaderSubjectIri(
            Function<String, Stream<BindingSet>> selectFn, String subjectIri) {
        String query = "SELECT ?broader WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + SparqlTerms.iriRef(subjectIri) + " <" + BROADER_PROPERTY + "> ?broader } } LIMIT 1";
        return selectFn.apply(query).findFirst().map(row -> iriOf(row, "broader").getIRIString());
    }

    /**
     * Rejects a candidate {@code skos:broader} target that would close a cycle: {@code
     * candidateBroaderIri} itself, or anywhere transitively up {@code candidateBroaderIri}'s own
     * existing broader chain, must not be {@code selfSubjectIri} (the term being corrected).
     * Walking stops as soon as a subject repeats (a pre-existing cycle this call did not create -
     * defensive only, {@link #attemptUpdate} never lets one arise going forward).
     *
     * <p>Only {@link #update} calls this - see {@link TermCycleException}'s javadoc for why
     * {@link #create} structurally cannot trigger it. {@link #attemptUpdate} calls it twice per
     * attempt: once before the write transaction (a fast, friendly rejection for the ordinary
     * sequential case) and once more inside {@link WriteFunnel#compareAndUpdate}'s write body,
     * against {@code tx::select} rather than the pre-transaction {@code selectFn} - the second call
     * is what actually guards against two terms racing to close a cycle from opposite ends; see
     * that call site.</p>
     */
    private void assertNoCycle(Function<String, Stream<BindingSet>> selectFn, ProjectId projectId, TermCode code,
            String selfSubjectIri, TermCode candidateBroaderCode, String candidateBroaderIri) {
        Set<String> visited = new HashSet<>();
        String current = candidateBroaderIri;
        while (current != null && visited.add(current)) {
            if (current.equals(selfSubjectIri)) {
                throw new TermCycleException(projectId, code, candidateBroaderCode);
            }
            current = readBroaderSubjectIri(selectFn, current).orElse(null);
        }
    }

    /**
     * Corrects specific fields of an existing term by business code,
     * touching only the predicate(s) whose new value the caller actually supplied.
     *
     * <p><strong>No read-then-merge.</strong> An earlier version resolved the term via
     * {@link #findByCode} (a plain read, outside any transaction), folded every omitted argument's
     * value from that read into a freshly-built {@link Term}, and handed the whole thing to a
     * replace-by-identity write - which silently destroyed every triple the read had to collapse
     * away to fit {@link Term}'s single-{@code String} fields (a store-first term
     * can legally carry several language-tagged {@code skos:prefLabel}s or several
     * {@code skos:definition} literals). This method instead reads exactly what it needs to
     * preserve, builds the candidate/context from that, and only ever deletes-and-reinserts the
     * predicate(s) the caller is actually replacing - every other predicate, and every other value
     * of a multi-valued predicate the caller does not touch, survives completely untouched at the
     * triple level.</p>
     *
     * <p><strong>No code collision to guard against.</strong> {@code dcterms:identifier} is never
     * among the fields this method can change - the code is how the subject is found, not
     * something it rewrites - so unlike an earlier version there
     * is no {@code askCodeExists} check here at all: it is structurally impossible for this method
     * to introduce a duplicate code, not merely checked and rejected.</p>
     *
     * <p><strong>Read-modify-write through the funnel, with retry (ADR-014 decision
     * 4).</strong> The read of whatever is being preserved now happens <em>before</em> the write
     * transaction, exactly like {@link #create} - the SHACL gate therefore runs before the
     * transaction opens again, not inside it. What used to be a single in-adapter-transaction
     * merge is now a compare-and-set on the {@link WriteFunnel}: {@link #attemptUpdate} reads the
     * term's current state and {@code arkprov:head} together, then asks the funnel to patch it
     * only if that head still matches. Two callers changing <em>different</em> fields at the same
     * time now both succeed only if neither loses the race on the shared head - unlike the
     * predicate-scoped conflict detection this replaces, a head conflict on either field now
     * triggers a retry for both, resolved transparently by the loop in {@link #update} the same
     * way {@code RequirementService}'s read-modify-write retry already worked:
     * {@link #attemptUpdate} re-reads the now-current state and head on every attempt, so a
     * losing caller's own change is never silently discarded. Only sustained, pathological
     * contention on the very same term exhausts {@link #MAX_RETRY_ATTEMPTS} and surfaces {@link
     * TermConcurrentlyModifiedException} to the caller.</p>
     *
     * <p><strong>No-op update.</strong> Every field the
     * {@code term_update} MCP tool exposes is {@code required = false}, so a caller can invoke this
     * method with {@code prefLabel} and {@code definition} both {@code null}.
     * Such a call never reaches the funnel: no write, no SHACL gate, no {@code arkprov:head}
     * comparison. A revision documents a model change (ADR-011/ADR-014); recording one for an
     * empty patch would grow the immutable provenance trail without cause and would move the head,
     * handing a concurrent CAS writer a spurious conflict it did not actually have. The
     * requirements BC guards the same case symmetrically in
     * {@code RequirementService#updateWithOptimisticRetry}, comparing the mutated value against the
     * one just read instead of comparing arguments, since its mutation is a whole-value transform
     * rather than per-field patches.</p>
     */
    @Override
    public Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
            String language, String defaultLanguage, Optional<TermCode> broader) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        String tag = canonicalLanguageTag(language);
        String defaultTag = canonicalLanguageTag(defaultLanguage);
        TermConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return attemptUpdate(projectId, code, prefLabel, definition, tag, defaultTag, broader);
            } catch (TermConcurrentlyModifiedException e) {
                // A concurrent writer advanced the head between our read and our write - retry
                // against the now-current state instead of surfacing a transient race.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    /**
     * Deletes the term identified by {@code code}, and every triple it carries in
     * {@link #TERMS_GRAPH}, from the project (issue #335). Resolves the subject outside any
     * transaction (mirroring {@link #attemptUpdate}'s own pre-transaction reads), then hands the
     * whole check-and-delete to {@link WriteFunnel#delete}: {@link #rejectIfReferenced} runs
     * first, inside the funnel's own write transaction (so a concurrent writer racing to add a
     * reference is resolved by the store's {@code SERIALIZABLE} isolation rather than a window
     * between a separate pre-check and this write), and only once it finds nothing pointing at the
     * term does the body remove the subject's triples wholesale.
     */
    @Override
    public void delete(ProjectId projectId, TermCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        DatasetId dataset = new DatasetId(projectId.value());
        String subjectIriString;
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            subjectIriString = resolveTermSubjectIri(handle.sparqlQuery()::select, code)
                    .orElseThrow(() -> new TermNotFoundException(projectId, code));
        }
        String subject = SparqlTerms.iriRef(subjectIriString);

        funnel.delete(dataset, TERMS_GRAPH, subjectIriString, code.value(),
                () -> new TermNotFoundException(projectId, code),
                tx -> {
                    rejectIfReferenced(tx, subjectIriString, projectId, code);
                    tx.update("DELETE WHERE { GRAPH <" + TERMS_GRAPH + "> { " + subject + " ?p ?o } }");
                });
    }

    /**
     * Reads back the codes {@link WriteFunnel#delete}'s {@code code} parameter retained (issue
     * #350): the shared funnel keeps the number out of circulation, this hexagon only maps its raw
     * strings to {@link TermCode}.
     */
    @Override
    public List<TermCode> findRetainedCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        return funnel.findRetainedCodes(new DatasetId(projectId.value()), CODE_PREFIX).stream()
                .map(TermCode::new)
                .toList();
    }

    /**
     * The predicates that, if found pointing at a term, block its deletion (issue #335): a
     * requirement's or use case's {@code arkreq:usesTerm}, an architecture decision's
     * {@code arkarch:usesTerm} (kogn-io/arknet#399), a bounded context's
     * {@code arkddd:ubiquitousLanguageTerm}, another term's {@code skos:broader}, and - still
     * checked although issue #336 moved actor resolution off glossary terms, since a store filled
     * before that cut can hold such an edge - a use case's {@code arkreq:primaryActor}/{@code
     * supportingActor}. Keys
     * are the absolute predicate IRIs this adapter and its siblings write; values are the
     * human-readable shorthand {@link de.hauschel.arknet.ul.domain.TermReferencedException} names
     * a caller by.
     *
     * <p><strong>Why the shorthands carry their namespace prefix.</strong> Two entries share the
     * local name {@code usesTerm}: an ADR's edge lives in arknet's own {@code arkarch} namespace
     * rather than extending the shared {@code arkreq:usesTerm} domain (kogn-io/arknet#393), so
     * they are two different properties written by two different bounded contexts. A bare
     * {@code "usesTerm"} in the rejection message would leave the caller guessing which edge to
     * remove - and would send them to {@code req_update}/{@code uc_update} for an edge only
     * {@code adr_update} can drop. Every shorthand is prefixed, not just the ambiguous pair: a
     * half-prefixed list reads as if the bare names were a different kind of thing.</p>
     *
     * <p>Whether this map is complete is not left to reviewer attention: {@code
     * TermReferenceGuardCoversEveryTermEdgeTest} in {@code arknet-architecture-tests} holds it
     * against the shipped ontologies, so every property declared with {@code rdfs:range
     * skos:Concept} has to appear here.</p>
     */
    private static final Map<String, String> REFERENCING_PREDICATES = Map.of(
            ArkreqVocabulary.USES_TERM, "arkreq:usesTerm",
            ArkarchVocabulary.USES_TERM, "arkarch:usesTerm",
            ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM, "arkddd:ubiquitousLanguageTerm",
            ArkreqVocabulary.PRIMARY_ACTOR, "arkreq:primaryActor",
            ArkreqVocabulary.SUPPORTING_ACTOR, "arkreq:supportingActor");

    /**
     * Rejects the delete, without touching a single triple, if anything in the project still
     * references {@code subjectIri} via one of {@link #REFERENCING_PREDICATES} or the
     * ubiquitous-language BC's own {@code skos:broader} - searched across every named graph
     * ({@code GRAPH ?g}), since a referencing edge lives in its own BC's model graph, not
     * {@link #TERMS_GRAPH}. Runs inside the live write transaction {@link WriteFunnel#delete} hands
     * its {@code body}, so the check and the eventual delete share one atomic snapshot.
     */
    private void rejectIfReferenced(DatasetTx tx, String subjectIri, ProjectId projectId, TermCode code) {
        IRI target = rdf.createIRI(subjectIri);
        List<String> referencing = new ArrayList<>();
        REFERENCING_PREDICATES.forEach((predicateIri, shorthand) -> {
            if (isReferencedVia(tx, target, predicateIri)) {
                referencing.add(shorthand);
            }
        });
        if (isReferencedVia(tx, target, BROADER_PROPERTY)) {
            referencing.add("skos:broader");
        }
        if (!referencing.isEmpty()) {
            throw new TermReferencedException(projectId, code, referencing);
        }
    }

    /** {@code true} if any named graph holds a triple {@code ?s <predicateIri> target}. */
    private boolean isReferencedVia(DatasetTx tx, IRI target, String predicateIri) {
        String query = "ASK { GRAPH ?g { ?s <" + predicateIri + "> ?target } }";
        return tx.ask(query, Map.of("target", target));
    }

    /**
     * One attempt of {@link #update}'s CAS retry loop: reads the term's current state and head
     * together, from a single query ({@link #readCurrentByCode}), outside any transaction, builds
     * the same candidate/context {@code update} always built, and hands the granular patch to
     * {@link WriteFunnel#compareAndUpdate} as the write body - the funnel checks the head again
     * inside its own transaction and runs the body only if it still matches.
     *
     * <p><strong>Why one combined read, not two.</strong> An earlier
     * version read the assembly via {@link #readAssemblyByCode} and the head via a separate,
     * second {@code SparqlQuery#select} call. That port's contract only guarantees that each
     * individual call is a self-contained read against the store's current committed state -
     * nothing ties two separate calls to the same snapshot. A concurrent writer's commit landing
     * exactly between the two calls therefore left the first call's assembly stale (read before
     * the commit) while the second call's head was already fresh (read after it): the funnel's
     * head comparison then wrongly succeeded against a state that was no longer current, and the
     * retry loop never noticed - the caller ended up reporting a field value the store no longer
     * held. Reading both from one query makes that impossible: the assembly and the head are
     * always the same snapshot, so a concurrent commit either lands entirely before or entirely
     * after this read, never in between it.</p>
     *
     * <p><strong>No-op short-circuit.</strong> Once {@code current}/{@code currentHead} are read,
     * this method returns immediately (still throwing {@link TermNotFoundException} for an unknown
     * code first) if all three field arguments ({@code prefLabel}, {@code definition}, {@code
     * broader}) are {@code null} - see the class-level "No-op update" note on
     * {@link #update}.</p>
     *
     * <p><strong>Broader (issue #252).</strong> A non-{@code null} {@code broader} is resolved and
     * cycle-checked against this project's own glossary before anything is built for the gate -
     * {@link #resolveTermSubjectIri}/{@link #assertNoCycle} run against the very same {@code
     * DatasetHandle} this method already holds open for {@link #readCurrentByCode}, so both reads
     * see one consistent snapshot. {@code broader.isPresent()} sets/replaces the triple;
     * {@code broader.isEmpty()} (an explicit clear) removes it without asserting a replacement;
     * {@code broader == null} (unchanged) re-asserts {@code current}'s own existing target for
     * the gate, mirroring the {@code prefLabel} "untouched" branch below. This
     * pre-transaction check alone only catches a concurrent change that had already fully
     * committed by the time it ran; {@link #assertNoCycle} runs a second time, against {@code
     * tx::select}, inside the write body handed to {@link WriteFunnel#compareAndUpdate} - see that
     * call site for why two terms racing to close a cycle from opposite ends needs an in-transaction
     * re-check, not just this one.</p>
     */
    private Term attemptUpdate(ProjectId projectId, TermCode code, String prefLabel, String definition,
            String language, String defaultLanguage, Optional<TermCode> broader) {
        DatasetId dataset = new DatasetId(projectId.value());
        CurrentTerm currentTerm;
        String broaderTargetIri = null;
        TermCode broaderCode = null;
        // Collects the broader target's shape-legal-reference state (see
        // assertBroaderTargetShapeState) while the DatasetHandle below is still open - unlike
        // every other assertedContext contribution further down, this one needs a live read
        // against the store (a target's own type/prefLabel), not just values already known from
        // currentTerm/broaderTargetIri.
        Graph broaderTargetAssertedContext = rdf.createGraph();
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            Function<String, Stream<BindingSet>> selectFn = handle.sparqlQuery()::select;
            currentTerm = readCurrentByCode(selectFn, code)
                    .orElseThrow(() -> new TermNotFoundException(projectId, code));
            if (broader != null && broader.isPresent()) {
                TermCode resolvedBroaderCode = broader.get();
                broaderCode = resolvedBroaderCode;
                broaderTargetIri = resolveTermSubjectIri(selectFn, resolvedBroaderCode)
                        .orElseThrow(() -> new TermNotFoundException(projectId, resolvedBroaderCode));
                assertNoCycle(selectFn, projectId, code,
                        currentTerm.assembly().id.value().value(), resolvedBroaderCode, broaderTargetIri);
                assertBroaderTargetShapeState(broaderTargetAssertedContext, selectFn, broaderTargetIri);
            } else if (broader == null && currentTerm.assembly().broaderSubjectIri != null) {
                assertBroaderTargetShapeState(
                        broaderTargetAssertedContext, selectFn, currentTerm.assembly().broaderSubjectIri);
            }
        }
        TermAssembly current = currentTerm.assembly();
        String currentHead = currentTerm.head();

        if (prefLabel == null && definition == null && broader == null) {
            // No field to patch - a true no-op: the funnel is never
            // consulted, so no revision is recorded and the head does not move (see class-level
            // "No-op update" note).
            return resultingTerm(current, null, null, null);
        }

        String subjectIriString = current.id.value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(TERMS_GRAPH);

        // Only the predicate(s) actually being replaced go into the gate's candidate; an
        // untouched-but-shape-relevant predicate (the type triple always, the caller's own
        // existing prefLabel candidates when prefLabel is not being replaced) is asserted instead
        // - validation-only, never written again, so the gate still sees the resulting state
        // truthfully without this class ever rewriting a triple nobody asked to change (see
        // class-level SHACL note).
        Graph writeCandidate = rdf.createGraph();
        Graph assertedContext = rdf.createGraph();
        assertedContext.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
        if (prefLabel != null) {
            writeCandidate.add(subjectIri, rdf.createIRI(PREF_LABEL_PROPERTY), rdf.createLiteral(prefLabel));
        } else {
            for (LocalizedLiteral existing : current.prefLabels) {
                assertedContext.add(subjectIri, rdf.createIRI(PREF_LABEL_PROPERTY), toLiteral(existing));
            }
        }
        // skos:definition's shape carries sh:uniqueLang but no sh:minCount - nothing to assert
        // for the gate to still pass when it is left untouched.
        if (broader != null) {
            if (broader.isPresent()) {
                writeCandidate.add(subjectIri, rdf.createIRI(BROADER_PROPERTY), rdf.createIRI(broaderTargetIri));
            }
            // broader.isEmpty() (explicit clear): nothing to assert - ulshapes:Term-broader
            // carries sh:minCount 0, so an absent skos:broader never fails the gate.
        } else if (current.broaderSubjectIri != null) {
            // Untouched: assert the existing target for the gate only, mirroring prefLabel above.
            assertedContext.add(subjectIri, rdf.createIRI(BROADER_PROPERTY), rdf.createIRI(current.broaderSubjectIri));
        }
        // The target's own shape-legal-reference state (type + one prefLabel, see
        // assertBroaderTargetShapeState) was already collected above, while the DatasetHandle was
        // still open - merged in here for both the "set/replace" and "untouched" cases.
        broaderTargetAssertedContext.stream().forEach(assertedContext::add);

        String finalBroaderTargetIri = broaderTargetIri;
        TermCode finalBroaderCode = broaderCode;
        funnel.compareAndUpdate(dataset, TERMS_GRAPH, subjectIriString, currentHead,
                writeCandidate, assertedContext,
                () -> new TermNotFoundException(projectId, code),
                () -> new TermConcurrentlyModifiedException(projectId, code),
                tx -> {
                    if (broader != null && broader.isPresent()) {
                        // Re-verify inside the very transaction the CAS head check also runs in
                        // (issue #252 review, "two terms racing into a cycle together"). The
                        // pre-transaction assertNoCycle above only ever sees a concurrent change
                        // that fully committed before this method's own read - it is a fast,
                        // friendly rejection for the ordinary sequential case, not the guard. Two
                        // term_update calls that each give the other term a fresh, still-empty
                        // broader chain to read can otherwise both pass that pre-transaction check
                        // and each go on to write a different subject, so neither commit conflicts
                        // on the surface - yet together they close a cycle no single check ever
                        // saw. Reading the candidate's chain again here, from this transaction's
                        // own snapshot via tx::select, gives the store's SERIALIZABLE isolation
                        // something to actually overlap with a concurrent term_update racing to
                        // close the same cycle from the other end: both transactions now read the
                        // very chain state the other one is about to write, so one commit loses as
                        // a genuine write conflict (translated to TermConcurrentlyModifiedException
                        // below, absorbed by update()'s retry) instead of both succeeding blindly.
                        // A retry's fresh pre-transaction check then sees the now-real cycle and
                        // reports it as TermCycleException, exactly as a purely sequential caller
                        // would have seen it from the start.
                        assertNoCycle(tx::select, projectId, code, subjectIriString,
                                finalBroaderCode, finalBroaderTargetIri);
                    }
                    if (prefLabel != null) {
                        tx.update(deleteTriplesOfLanguage(subject, PREF_LABEL_PROPERTY, language, defaultLanguage));
                        tx.add(graphIri, singleTriple(subjectIri, PREF_LABEL_PROPERTY, literalOf(prefLabel, language)));
                    }
                    if (definition != null) {
                        tx.update(deleteTriplesOfLanguage(subject, DEFINITION_PROPERTY, language, defaultLanguage));
                        tx.add(graphIri, singleTriple(subjectIri, DEFINITION_PROPERTY, literalOf(definition, language)));
                    }
                    if (broader != null) {
                        // Both "set/replace" and "explicit clear" start by removing the existing
                        // triple (if any); only "set/replace" reinserts one.
                        tx.update(deleteAllTriplesOf(subject, BROADER_PROPERTY));
                        if (broader.isPresent()) {
                            tx.add(graphIri, singleTriple(subjectIri, BROADER_PROPERTY,
                                    rdf.createIRI(finalBroaderTargetIri)));
                        }
                    }
                });

        return resultingTerm(current, prefLabel, definition, broader);
    }

    /** Deletes every existing triple of {@code subject} on {@code predicateIri} - a no-op if none exists. */
    private static String deleteAllTriplesOf(String subject, String predicateIri) {
        return "DELETE WHERE { GRAPH <" + TERMS_GRAPH + "> { " + subject + " <" + predicateIri + "> ?o } }";
    }

    /**
     * Canonicalizes a BCP-47 tag (e.g. {@code "DE"} -&gt; {@code "de"}), or {@code null} unchanged
     * - and rejects one that is not well-formed at all, via the shared kernel {@link LanguageTag}
     * (see that class's javadoc for why {@link Locale#forLanguageTag} is the wrong tool here: it
     * never throws, silently degrading a typo like {@code "de_DE"} to {@code "und"}).
     *
     * <p>{@link #deleteTriplesOfLanguage}'s {@code FILTER(lang(?o) = "tag")} compares the raw
     * string RDF4J's {@code lang()} returns - the exact case a literal was written with - against
     * this method's {@code tag} argument, so an un-normalized case mismatch between two calls
     * (e.g. {@code term_add(..., language="de")} followed by {@code term_update(...,
     * language="DE")}) leaves the existing {@code @de} literal undeleted and inserts a second
     * {@code @DE} one instead of correcting it - two literals for one language, defeating
     * {@code sh:uniqueLang} and the exact bug this scoped delete exists to fix, only triggered by
     * case instead of missing scoping. Canonicalizing every tag through this method before both
     * writing a literal ({@link #literalOf}) and building the delete filter keeps stored tags in
     * one consistent case, so a later scoped delete always matches - the same guarantee
     * {@code DisplayLocale#matching} already gives the read side by comparing tags
     * case-insensitively.</p>
     */
    private static String canonicalLanguageTag(String language) {
        return LanguageTag.canonicalize(language);
    }

    /**
     * Deletes only the existing triple(s) of {@code subject} on {@code predicateIri} whose literal
     * carries the same language tag as {@code language} - every other language-tagged (or
     * untagged) variant of a multi-valued predicate such as {@code skos:prefLabel}/
     * {@code skos:definition} survives untouched. A no-op if no literal with that tag exists.
     *
     * <p>This is the fix for the bug {@code term_update} used to have: an earlier version deleted
     * <strong>every</strong> value of the predicate regardless of language before writing the one
     * new literal, silently discarding every other language variant a store-first (ADR-005) term
     * legally carried. {@code lang(?o)} is {@code ""} for a plain, untagged literal, which is
     * exactly what {@code language == null} maps {@code tag} to below - so an untagged correction
     * scopes its delete to the untagged slot alone, the same way a tagged one scopes to its own
     * tag.</p>
     *
     * <p><strong>Widening the filter for a default-language write (issue #258).</strong> {@code
     * language} is normally never {@code null} by the time a real {@code term_update} call reaches
     * here - {@code TermService#update} already resolved it against the project's {@code
     * defaultLanguage} (or rejected the call) before {@code prefLabel}/{@code definition} could be
     * non-{@code null} - but this out-adapter's own port contract still permits a caller-supplied
     * {@code null} directly (untagged write), so this method stays null-tolerant for that
     * lower-level case. When {@code language} is non-{@code null} and (canonicalized) equals
     * {@code defaultLanguage} (canonicalized), the literal about to be written <em>is</em>, by
     * construction, what an omitted {@code language} argument would have resolved to - so an
     * existing <em>untagged</em> literal on this predicate is no longer a genuine other-language
     * variant, it is a stale duplicate of the value now being written under its proper tag. The
     * filter widens from matching only {@code tag} to also matching the untagged slot ({@code
     * lang(?o) = ""}) in exactly that case, sweeping the stale untagged literal away instead of
     * leaving it stranded next to the newly-tagged one - a lazy, incremental normalisation
     * triggered only by the next {@code term_update} that happens to touch this field, not a batch
     * migration. {@code language == null} (an untagged write itself) never widens: the untagged
     * slot is already exactly what is being replaced.</p>
     *
     * @param language        the BCP-47 tag of the literal being replaced, or {@code null} for
     *                        untagged
     * @param defaultLanguage the target project's configured default language, canonicalized, or
     *                        {@code null} if it has none
     */
    private static String deleteTriplesOfLanguage(
            String subject, String predicateIri, String language, String defaultLanguage) {
        // The DELETE WHERE {...} shorthand only accepts quad patterns, no FILTER - the general
        // DELETE {...} WHERE {...} form is required to scope the delete by language.
        String tag = language == null ? "" : SparqlTerms.escape(language);
        String filter = "lang(?o) = \"" + tag + "\"";
        if (language != null && language.equals(defaultLanguage)) {
            filter += " || lang(?o) = \"\"";
        }
        return "DELETE { GRAPH <" + TERMS_GRAPH + "> { " + subject + " <" + predicateIri + "> ?o } } "
                + "WHERE { GRAPH <" + TERMS_GRAPH + "> { " + subject + " <" + predicateIri + "> ?o . "
                + "FILTER(" + filter + ") } }";
    }

    /** A one-triple graph, for the common "insert exactly one new value" case in {@link #update}. */
    private Graph singleTriple(IRI subject, String predicateIri, RDFTerm object) {
        Graph graph = rdf.createGraph();
        graph.add(subject, rdf.createIRI(predicateIri), object);
        return graph;
    }

    /** Converts a {@link LocalizedLiteral} back to the RDF {@link Literal} it was read from. */
    private Literal toLiteral(LocalizedLiteral literal) {
        return literal.languageTag() == null
                ? rdf.createLiteral(literal.value())
                : rdf.createLiteral(literal.value(), literal.languageTag());
    }

    /** Builds a language-tagged literal, or a plain untagged one when {@code language} is {@code null}. */
    private Literal literalOf(String value, String language) {
        return language == null ? rdf.createLiteral(value) : rdf.createLiteral(value, language);
    }

    /**
     * Builds the {@link Term} {@link #update} returns: {@code newXxx} where the caller actually
     * supplied one, otherwise {@code current}'s own already-selected/materialised value - so the
     * caller sees exactly the state {@link #update} just wrote, without a second read.
     */
    private Term resultingTerm(TermAssembly current, String newPrefLabel, String newDefinition,
            Optional<TermCode> newBroader) {
        Term currentProjection = current.toTerm(displayLocale);
        String prefLabel = newPrefLabel != null ? newPrefLabel : currentProjection.prefLabel();
        String definition = newDefinition != null ? newDefinition : currentProjection.definition();
        TermCode broader = resultingBroader(current.broaderCode, newBroader);
        return new Term(current.id, current.code, prefLabel, definition, broader);
    }

    /**
     * Merges the caller's {@code newBroader} onto {@code current}: {@code null} leaves {@code
     * current} untouched, {@link Optional#empty()} clears it, {@link Optional#of} replaces it -
     * matching what {@link #attemptUpdate} actually persists.
     */
    private static TermCode resultingBroader(TermCode current, Optional<TermCode> newBroader) {
        if (newBroader == null) {
            return current;
        }
        return newBroader.orElse(null);
    }

    @Override
    public Optional<Term> findByCode(ProjectId projectId, TermCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        DisplayLocale effective = withRequestedOverride(displayLocale);
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return readAssemblyByCode(handle.sparqlQuery()::select, code).map(assembly -> assembly.toTerm(effective));
        }
    }

    /**
     * Overrides this repository's own configured {@link #displayLocale}'s {@code requested} tier
     * for one call - shared by {@link #findByCode} and {@link #findAll}, e.g. an explicit
     * {@code term_get} {@code displayLocale} argument or a project's own default language merged
     * in by the caller (ADR-016-adjacent: the ubiquitous-language MCP adapter combines an
     * explicit override with {@code ResolvedProject#defaultLanguage()} before {@link #findByCode}
     * ever sees it, and passes {@code ResolvedProject#defaultLanguage()} straight through - {@code
     * term_list} has no explicit {@code displayLocale} tool argument of its own to merge against -
     * before {@link #findAll} sees it, issue #274). The configured {@code systemDefault} tier -
     * and the rest of {@link DisplayLocale#select}'s fallback chain - is unaffected, so an
     * override that matches nothing still degrades exactly the way the process-wide default
     * already does.
     *
     * @param requestedOverride a BCP-47 language tag, or {@code null}/blank to use the configured
     *                          {@link #displayLocale} unchanged
     */
    private DisplayLocale withRequestedOverride(String requestedOverride) {
        if (requestedOverride == null || requestedOverride.isBlank()) {
            return displayLocale;
        }
        return new DisplayLocale(Locale.forLanguageTag(requestedOverride), displayLocale.systemDefault());
    }

    /**
     * Builds the WHERE-clause body (inside {@code GRAPH <TERMS_GRAPH>}) shared by
     * {@link #readAssemblyByCode} and {@link #readCurrentByCode}: the mandatory joins (type,
     * identifier, prefLabel, definition) plus the blank-node subject guard, scoped to one
     * {@code code}, plus the optional {@code skos:broader} join (issue #252): binding the target's raw subject
     * ({@code ?broaderSubject}, needed to re-assert the untouched triple during
     * {@link #attemptUpdate}'s gate check) in its own {@code OPTIONAL}, and its business code
     * ({@code ?broaderCode}, needed to project {@link Term#broader()}) in a second, nested
     * {@code OPTIONAL} scoped inside the first. A store-first (ADR-005) broader target that itself
     * carries no {@code dcterms:identifier} therefore still binds {@code ?broaderSubject} - the
     * edge stays visible to {@link #attemptUpdate}'s gate check even though {@link Term#broader()}
     * cannot name it by code; a single, non-nested {@code OPTIONAL} joining both variables together
     * used to drop the whole edge (subject included) whenever the code binding failed. Extracted because both
     * callers build a {@link TermAssembly} from the same row shape - drift between two
     * near-identical read paths in this class was a real bug twice before, so
     * this text now lives in one place. The caller supplies the surrounding
     * {@code SELECT}/{@code GRAPH}/{@code WHERE} wrapping and, in {@link #readCurrentByCode}'s
     * case, the additional provenance-graph join - only the WHERE body itself is common.
     */
    private static String termByCodeWhereClause(TermCode code) {
        return "?s a <" + CONCEPT_TYPE + "> ; "
                + "<" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" ; "
                + "<" + PREF_LABEL_PROPERTY + "> ?prefLabel ; "
                + "<" + DEFINITION_PROPERTY + "> ?definition . "
                + "FILTER(isIRI(?s)) "
                + "OPTIONAL { ?s <" + BROADER_PROPERTY + "> ?broaderSubject . FILTER(isIRI(?broaderSubject)) "
                + "OPTIONAL { ?broaderSubject <" + IDENTIFIER_PROPERTY + "> ?broaderCode } } ";
    }

    /**
     * Reads one term's full current state by business code - used by {@link #findByCode} (reads
     * outside any transaction). A term missing either {@code skos:prefLabel} or
     * {@code skos:definition} entirely is invisible, exactly as before.
     */
    private Optional<TermAssembly> readAssemblyByCode(
            Function<String, Stream<BindingSet>> selectFn, TermCode code) {
        String query = "SELECT ?s ?prefLabel ?definition "
                + "?broaderSubject ?broaderCode WHERE { GRAPH <"
                + TERMS_GRAPH + "> { "
                + termByCodeWhereClause(code)
                + "} }";

        Map<String, TermAssembly> bySubject = new LinkedHashMap<>();
        selectFn.apply(query).forEach(row -> {
            TermAssembly assembly = assemblyFor(bySubject, row, code);
            assembly.addPrefLabel(literalOf(row, "prefLabel"));
            assembly.addDefinition(literalOf(row, "definition"));
        });
        return bySubject.values().stream().findFirst();
    }

    /**
     * One term's current state ({@link TermAssembly}) together with its {@code arkprov:head}
     * concurrency token, read from a single query - see {@link #readCurrentByCode}
     * for why this must be one query, not two.
     */
    private record CurrentTerm(TermAssembly assembly, String head) {
    }

    /**
     * Reads one term's full current state together with its {@code arkprov:head} concurrency
     * token in a single query (mirroring
     * {@code KognioRdfRequirementRepository#findCurrentByCode}) - used by {@link #attemptUpdate},
     * whose compare-and-set write must know both the state to patch and the token to check.
     * Shares {@link #termByCodeWhereClause} with {@link #readAssemblyByCode} so the two
     * single-term read paths cannot drift apart field-by-field (already taught
     * this lesson once).
     *
     * <p><strong>Why the state and the head must come from the same query.</strong>
     * {@code SparqlQuery#select}'s port contract guarantees only that each individual call is a
     * self-contained read against the store's current committed state - two separate calls are
     * two independent snapshots, with no guarantee that nothing committed in between them. An
     * earlier version read the assembly and the head via two separate calls; a concurrent
     * writer's commit landing between them left the first call's assembly stale (pre-commit)
     * paired with the second call's head, which was already fresh (post-commit) - the funnel's
     * head comparison in {@link #attemptUpdate} then wrongly matched against a state that was no
     * longer current, and the bounded retry loop in {@link #update} never got a chance to catch
     * it. Joining {@code ?head} into this query instead makes the pairing atomic: both values
     * always come from the same snapshot, so a concurrent commit either precedes or follows this
     * whole read, never falls inside it.</p>
     *
     * <p>The head is single-valued and therefore identical on every row this query binds for one
     * subject (the mandatory {@code prefLabel}/{@code definition} joins in
     * {@link #termByCodeWhereClause} can still multiply a subject into several rows)
     * - it is kept <em>per subject</em> and paired with the assembly this method
     * actually returns, exactly as the row grouping into {@link TermAssembly} already does for
     * the other per-subject fields. Keying it by subject rather than taking the first head seen
     * matters because {@code dcterms:identifier} carries no {@code sh:maxCount}: a store-first
     * store (ADR-005) can hold two subjects under the same code, and the returned token must be
     * the token of the subject whose state is returned with it - a head belonging to the other
     * subject would make {@link #attemptUpdate}'s compare-and-set check a foreign resource's
     * revision.</p>
     */
    private Optional<CurrentTerm> readCurrentByCode(
            Function<String, Stream<BindingSet>> selectFn, TermCode code) {
        String query = "SELECT ?s ?prefLabel ?definition "
                + "?broaderSubject ?broaderCode ?head WHERE { GRAPH <"
                + TERMS_GRAPH + "> { "
                + termByCodeWhereClause(code)
                + "} "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?s <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        Map<String, TermAssembly> bySubject = new LinkedHashMap<>();
        Map<String, String> headBySubject = new LinkedHashMap<>();
        selectFn.apply(query).forEach(row -> {
            TermAssembly assembly = assemblyFor(bySubject, row, code);
            assembly.addPrefLabel(literalOf(row, "prefLabel"));
            assembly.addDefinition(literalOf(row, "definition"));
            String head = headOf(row);
            if (head != null) {
                headBySubject.putIfAbsent(assembly.id.value().value(), head);
            }
        });
        return bySubject.values().stream().findFirst()
                .map(assembly -> new CurrentTerm(assembly, headBySubject.get(assembly.id.value().value())));
    }

    /** Extracts a row's {@code ?head} binding as an IRI string, or {@code null} if absent or not an IRI. */
    private static String headOf(BindingSet row) {
        return row.getValue("head")
                .filter(IRI.class::isInstance)
                .map(value -> ((IRI) value).getIRIString())
                .orElse(null);
    }

    @Override
    public List<Term> findAll(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");

        DisplayLocale effective = withRequestedOverride(displayLocale);
        String query = "SELECT ?s ?identifier ?prefLabel ?definition "
                + "?broaderSubject ?broaderCode "
                + "WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "?s a <" + CONCEPT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "?s <" + PREF_LABEL_PROPERTY + "> ?prefLabel . "
                + "?s <" + DEFINITION_PROPERTY + "> ?definition . "
                + "FILTER(isIRI(?s)) "
                + "OPTIONAL { ?s <" + BROADER_PROPERTY + "> ?broaderSubject . FILTER(isIRI(?broaderSubject)) "
                + "OPTIONAL { ?broaderSubject <" + IDENTIFIER_PROPERTY + "> ?broaderCode } } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Map<String, TermAssembly> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                TermAssembly assembly = assemblyFor(bySubject, row, null);
                assembly.addPrefLabel(literalOf(row, "prefLabel"));
                assembly.addDefinition(literalOf(row, "definition"));
            });
            return bySubject.values().stream().map(assembly -> assembly.toTerm(effective)).toList();
        }
    }

    /**
     * Groups the (potentially several) rows of one concept - a mandatory but now
     * <em>multi-valued</em> {@code skos:prefLabel}/{@code skos:definition} join multiplies a
     * concept into one row per candidate value - into a single
     * {@link TermAssembly}, keyed by subject IRI. The remaining scalar fields (identity, code)
     * are read once from the first row of a subject; every row contributes its
     * {@code prefLabel}/{@code definition} literal as a candidate via
     * {@link TermAssembly#addPrefLabel}/{@link TermAssembly#addDefinition}, called by the two
     * callers ({@link #findByCode}/{@link #findAll}) once per row.
     *
     * <p>{@code identifier} stays a single-valued read - it is already narrowed by the
     * {@code knownCode}/query filter to the code being looked up. Keeping {@code prefLabel}
     * a <em>required</em> (non-optional) join means a store-first concept carrying no
     * {@code prefLabel} at all still binds nothing and is omitted exactly as before - it never
     * reaches the {@link Term} constructor, whose non-blank {@code prefLabel} invariant stays
     * strict.</p>
     *
     * @param knownCode the code when the caller already knows it ({@code findByCode}), else
     *                  {@code null} to read it from the row's {@code identifier} ({@code findAll})
     */
    private static TermAssembly assemblyFor(Map<String, TermAssembly> bySubject, BindingSet row, TermCode knownCode) {
        String subjectIri = iriOf(row, "s").getIRIString();
        return bySubject.computeIfAbsent(subjectIri, iri -> new TermAssembly(
                new TermId(ResourceId.of(iri)),
                knownCode != null ? knownCode : new TermCode(literalOf(row, "identifier").getLexicalForm()),
                broaderSubjectIriOf(row), broaderCodeOf(row)));
    }

    /**
     * Mutable per-subject accumulator collecting a concept's {@code skos:prefLabel} and
     * {@code skos:definition} candidates across rows, then choosing one of each when the concept
     * is finally materialised into a {@link Term}: {@code prefLabel} and {@code definition} both
     * via the very same {@link DisplayLocale} fallback chain, applied to that one
     * {@link DisplayLocale} instance passed into {@link #toTerm} - a card rendering both fields
     * for one resource therefore always sees them resolved for the same language (issue #248).
     */
    private static final class TermAssembly {

        private final TermId id;
        private final TermCode code;
        /**
         * The subject's own {@code skos:broader} target, captured once at construction (issue
         * #252's shape is {@code sh:maxCount 1}, so unlike {@code prefLabel}/{@code definition}
         * there is nothing genuinely multi-valued to accumulate here). {@code broaderSubjectIri}
         * is what {@link #attemptUpdate} needs to re-assert the untouched triple for the gate;
         * {@code broaderCode} is what {@link #toTerm} projects into {@link Term#broader()}.
         */
        private final String broaderSubjectIri;
        private final TermCode broaderCode;
        private final List<LocalizedLiteral> prefLabels = new ArrayList<>();
        private final List<LocalizedLiteral> definitions = new ArrayList<>();

        private TermAssembly(TermId id, TermCode code, String broaderSubjectIri, TermCode broaderCode) {
            this.id = id;
            this.code = code;
            this.broaderSubjectIri = broaderSubjectIri;
            this.broaderCode = broaderCode;
        }

        private void addPrefLabel(Literal literal) {
            prefLabels.add(new LocalizedLiteral(literal.getLexicalForm(), literal.getLanguageTag().orElse(null)));
        }

        private void addDefinition(Literal literal) {
            definitions.add(new LocalizedLiteral(literal.getLexicalForm(), literal.getLanguageTag().orElse(null)));
        }

        /**
         * Materialises this accumulator into a {@link Term}, selecting {@code prefLabel} and
         * {@code definition} from the very same {@code displayLocale} - the fix for issue #248:
         * an earlier version resolved {@code definition} independently of {@code prefLabel} (a
         * first-seen, store-row-order pick), so a term whose label and definition were not both
         * available in the same language could show label and definition in two different
         * languages on the very same card.
         */
        private Term toTerm(DisplayLocale displayLocale) {
            String prefLabel = displayLocale.select(prefLabels)
                    .map(LocalizedLiteral::value)
                    .orElseThrow(() -> new IllegalStateException(
                            "prefLabel is a required join, so at least one candidate must exist"));
            String definition = displayLocale.select(definitions)
                    .map(LocalizedLiteral::value)
                    .orElseThrow(() -> new IllegalStateException(
                            "definition is a required join, so at least one candidate must exist"));
            return new Term(id, code, prefLabel, definition, broaderCode);
        }
    }

    /**
     * Batch variant of {@link #findByCode}, keyed by opaque identity instead of business code -
     * backs {@link ResolveTerms}. One {@code VALUES}-bound query for the
     * whole batch, not one query per id: the caller (a sibling bounded context's driving adapter,
     * rendering several term references at once) must not pay an N+1 store round-trip.
     *
     * <p>Returns the slim {@link ResolveTerms.ResolvedTerm} projection, not the full {@link Term}
     * aggregate: the query below therefore joins only {@code identifier}, not
     * {@code prefLabel}/{@code definition} - fields {@link ResolveTerms} never reads. A store-first
     * term that carries an identity and a code but happens to miss a {@code prefLabel} (which
     * {@link #findByCode}/{@link #findAll} still require) is thus resolvable here.</p>
     *
     * <p><strong>Exactly one {@link ResolveTerms.ResolvedTerm} per resolved subject.</strong>
     * {@code ulshapes:Term-prefLabel} carries {@code sh:minCount 1} but
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
    public List<ResolveTerms.ResolvedTerm> findByIds(ProjectId projectId, List<ResourceId> ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return List.of();
        }

        // ResourceId#of validates IRIREF-safety at construction, so every id here is
        // already guaranteed safe to embed - restores ResolveTerms#resolve's "never rejects"
        // contract, which this used to violate by throwing on an impossible identity.
        String values = ids.stream()
                .map(id -> SparqlTerms.iriRef(id.value()))
                .collect(Collectors.joining(" "));

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "VALUES ?s { " + values + " } "
                + "?s a <" + CONCEPT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
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
     * Extracts a row's {@code ?broaderSubject} binding as an IRI string, or {@code null} if the
     * subject carries no {@code skos:broader} (the join is {@code OPTIONAL}).
     */
    private static String broaderSubjectIriOf(BindingSet row) {
        return row.getValue("broaderSubject")
                .filter(IRI.class::isInstance)
                .map(value -> ((IRI) value).getIRIString())
                .orElse(null);
    }

    /**
     * Extracts a row's {@code ?broaderCode} binding as a {@link TermCode}, or {@code null} if the
     * subject carries no {@code skos:broader} (the join is {@code OPTIONAL}).
     */
    private static TermCode broaderCodeOf(BindingSet row) {
        return row.getValue("broaderCode")
                .filter(Literal.class::isInstance)
                .map(value -> new TermCode(((Literal) value).getLexicalForm()))
                .orElse(null);
    }
}
