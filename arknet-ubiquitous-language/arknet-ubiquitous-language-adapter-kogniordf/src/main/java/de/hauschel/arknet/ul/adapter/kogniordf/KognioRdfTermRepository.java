// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.kogniordf;

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
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermConcurrentlyModifiedException;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

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
 * <p><strong>Display language.</strong> A concept may carry {@code skos:prefLabel}
 * in several languages ({@code "Kunde"@de}, {@code "Customer"@en}) - SKOS-legal and store-first
 * reachable (ADR-005). {@link #findByCode}/{@link #findAll} therefore join {@code prefLabel} as a
 * <em>multi-valued</em> (but still mandatory) pattern, group the resulting rows per subject, and
 * let the injected {@link DisplayLocale} pick the label to display through a fallback chain
 * (requested language, system default, untagged, deterministic last resort). A concept is never
 * dropped for lacking the requested language - only the shown language degrades. {@code findByIds}
 * (the {@link ResolveTerms} batch) is deliberately untouched: it joins only {@code identifier},
 * never {@code prefLabel}.</p>
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
 * multiplies a subject into two SPARQL rows. Unlike {@code prefLabel}, there is no
 * {@link DisplayLocale} guarantee for {@code definition} (a deliberately narrower scope), so
 * {@link #findByCode}/{@link #findAll} instead take the first-seen value deterministically (stable
 * because the grouping map preserves row insertion order) and log a single {@code WARN} per
 * assembled {@link Term} when more than one distinct value was seen - visible instead of silently
 * dropped, without inventing a second display-selection mechanism next to {@link DisplayLocale}.</p>
 *
 * <p><strong>Row multiplication on {@code arkproc:actorRole}.</strong> Neither {@code ulshapes}
 * nor {@code arknet-actor.ttl} constrain {@code arkproc:actorRole} with {@code sh:maxCount}
 * either, so a store-first (ADR-005) actor-facetted concept with two role literals is just as
 * SHACL-legal as a two-valued {@code definition} and multiplies a subject into two rows the same
 * way. {@link #findByCode}/{@link #findAll} therefore collect {@code actorRole} candidates across
 * a subject's rows exactly like {@code definition} and resolve them with the same first-seen
 * value plus single {@code WARN}-on-collision policy, not a silent {@code computeIfAbsent} pick
 * of whatever the first row happened to bind.</p>
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

        // ResourceId#of validates IRIREF-safety at construction, so term.id()'s
        // wrapped IRI is already guaranteed safe to embed here - no separate check needed.
        String subjectIriString = term.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        IRI schemeIri = rdf.createIRI(GLOSSARY_SCHEME);

        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
        graph.add(subjectIri, rdf.createIRI(IN_SCHEME_PROPERTY), schemeIri);
        graph.add(subjectIri, rdf.createIRI(IDENTIFIER_PROPERTY), rdf.createLiteral(term.code().value()));
        graph.add(subjectIri, rdf.createIRI(PREF_LABEL_PROPERTY), literalOf(term.prefLabel(), language));
        graph.add(subjectIri, rdf.createIRI(DEFINITION_PROPERTY), literalOf(term.definition(), language));
        // The per-project glossary itself, typed once (idempotent - RDF set semantics).
        graph.add(schemeIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_SCHEME_TYPE));

        // Optional actor facet: the same skos:Concept is additionally typed as an
        // arkproc:Actor. Added before the gate so the facet is validated too. The facet
        // hangs off the subject, so it moves with the now-opaque identity for free.
        ActorFacet actorFacet = term.actorFacet();
        if (actorFacet != null) {
            String actorType = actorFacet.kind() == ActorKind.HUMAN ? HUMAN_ACTOR_TYPE : SYSTEM_ACTOR_TYPE;
            graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(actorType));
            if (actorFacet.role() != null) {
                graph.add(subjectIri, rdf.createIRI(ACTOR_ROLE_PROPERTY), rdf.createLiteral(actorFacet.role()));
            }
        }

        IRI graphIri = rdf.createIRI(TERMS_GRAPH);

        funnel.create(new DatasetId(projectId.value()), TERMS_GRAPH, subjectIriString, term.code().value(),
                graph, null,
                () -> new ResourceAlreadyExistsException(projectId, term.id().value()),
                () -> new DuplicateTermCodeException(projectId, term.code()),
                tx -> tx.add(graphIri, graph));
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
     * method with {@code prefLabel}, {@code definition} and {@code actorFacet} all {@code null}.
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
            ActorFacet actorFacet, String language) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        TermConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            try {
                return attemptUpdate(projectId, code, prefLabel, definition, actorFacet, language);
            } catch (TermConcurrentlyModifiedException e) {
                // A concurrent writer advanced the head between our read and our write - retry
                // against the now-current state instead of surfacing a transient race.
                lastConflict = e;
            }
        }
        throw lastConflict;
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
     * code first) if all three field arguments are {@code null} - see the class-level "No-op
     * update" note on {@link #update}.</p>
     */
    private Term attemptUpdate(ProjectId projectId, TermCode code, String prefLabel, String definition,
            ActorFacet actorFacet, String language) {
        DatasetId dataset = new DatasetId(projectId.value());
        CurrentTerm currentTerm;
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            currentTerm = readCurrentByCode(handle.sparqlQuery()::select, code)
                    .orElseThrow(() -> new TermNotFoundException(projectId, code));
        }
        TermAssembly current = currentTerm.assembly();
        String currentHead = currentTerm.head();

        if (prefLabel == null && definition == null && actorFacet == null) {
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
        if (actorFacet != null) {
            String actorType = actorFacet.kind() == ActorKind.HUMAN ? HUMAN_ACTOR_TYPE : SYSTEM_ACTOR_TYPE;
            writeCandidate.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(actorType));
            if (actorFacet.role() != null) {
                writeCandidate.add(subjectIri, rdf.createIRI(ACTOR_ROLE_PROPERTY),
                        rdf.createLiteral(actorFacet.role()));
            } else if (current.actorFacet() != null && current.actorFacet().role() != null) {
                // A null role is "unchanged", not "cleared" (same contract as every other field
                // here) - assert the untouched existing role for the gate only.
                assertedContext.add(subjectIri, rdf.createIRI(ACTOR_ROLE_PROPERTY),
                        rdf.createLiteral(current.actorFacet().role()));
            }
        }
        // skos:definition carries no ulshapes PropertyShape at all (see class-level note) -
        // nothing to assert either way when it is left untouched.

        funnel.compareAndUpdate(dataset, TERMS_GRAPH, subjectIriString, currentHead,
                writeCandidate, assertedContext,
                () -> new TermNotFoundException(projectId, code),
                () -> new TermConcurrentlyModifiedException(projectId, code),
                tx -> {
                    if (prefLabel != null) {
                        tx.update(deleteTriplesOfLanguage(subject, PREF_LABEL_PROPERTY, language));
                        tx.add(graphIri, singleTriple(subjectIri, PREF_LABEL_PROPERTY, literalOf(prefLabel, language)));
                    }
                    if (definition != null) {
                        tx.update(deleteTriplesOfLanguage(subject, DEFINITION_PROPERTY, language));
                        tx.add(graphIri, singleTriple(subjectIri, DEFINITION_PROPERTY, literalOf(definition, language)));
                    }
                    if (actorFacet != null) {
                        tx.update(deleteType(subject, HUMAN_ACTOR_TYPE));
                        tx.update(deleteType(subject, SYSTEM_ACTOR_TYPE));
                        Graph actorGraph = rdf.createGraph();
                        String actorType = actorFacet.kind() == ActorKind.HUMAN ? HUMAN_ACTOR_TYPE : SYSTEM_ACTOR_TYPE;
                        actorGraph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(actorType));
                        // A null role leaves the existing arkproc:actorRole triple (if any) alone,
                        // instead of deleting it - correcting only the kind must not silently wipe
                        // an already-set role the caller never mentioned.
                        if (actorFacet.role() != null) {
                            tx.update(deleteAllTriplesOf(subject, ACTOR_ROLE_PROPERTY));
                            actorGraph.add(subjectIri, rdf.createIRI(ACTOR_ROLE_PROPERTY),
                                    rdf.createLiteral(actorFacet.role()));
                        }
                        tx.add(graphIri, actorGraph);
                    }
                });

        return resultingTerm(current, prefLabel, definition, actorFacet);
    }

    /** Deletes every existing triple of {@code subject} on {@code predicateIri} - a no-op if none exists. */
    private static String deleteAllTriplesOf(String subject, String predicateIri) {
        return "DELETE WHERE { GRAPH <" + TERMS_GRAPH + "> { " + subject + " <" + predicateIri + "> ?o } }";
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
     * @param language the BCP-47 tag of the literal being replaced, or {@code null} for untagged
     */
    private static String deleteTriplesOfLanguage(String subject, String predicateIri, String language) {
        // The DELETE WHERE {...} shorthand only accepts quad patterns, no FILTER - the general
        // DELETE {...} WHERE {...} form is required to scope the delete by language.
        String tag = language == null ? "" : SparqlTerms.escape(language);
        return "DELETE { GRAPH <" + TERMS_GRAPH + "> { " + subject + " <" + predicateIri + "> ?o } } "
                + "WHERE { GRAPH <" + TERMS_GRAPH + "> { " + subject + " <" + predicateIri + "> ?o . "
                + "FILTER(lang(?o) = \"" + tag + "\") } }";
    }

    /** Deletes {@code subject a <typeIri>} if present - a no-op if the subject does not carry it. */
    private static String deleteType(String subject, String typeIri) {
        return "DELETE WHERE { GRAPH <" + TERMS_GRAPH + "> { " + subject + " a <" + typeIri + "> } }";
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
            ActorFacet newActorFacet) {
        Term currentProjection = current.toTerm(displayLocale);
        String prefLabel = newPrefLabel != null ? newPrefLabel : currentProjection.prefLabel();
        String definition = newDefinition != null ? newDefinition : currentProjection.definition();
        ActorFacet actorFacet = resultingActorFacet(current.actorFacet(), newActorFacet);
        return new Term(current.id, current.code, prefLabel, definition, actorFacet);
    }

    /**
     * Merges the caller's {@code newActorFacet} onto {@code current}: a {@code null} facet leaves
     * {@code current} entirely unchanged; a non-{@code null} facet always replaces the kind, but a
     * {@code null} role within it keeps {@code current}'s own role rather than reporting it as
     * cleared - matching what {@link #update} actually persists.
     */
    private static ActorFacet resultingActorFacet(ActorFacet current, ActorFacet newActorFacet) {
        if (newActorFacet == null) {
            return current;
        }
        if (newActorFacet.role() != null) {
            return newActorFacet;
        }
        String preservedRole = current != null ? current.role() : null;
        return new ActorFacet(newActorFacet.kind(), preservedRole);
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
     * for one call, e.g. an explicit {@code term_get} {@code displayLocale} argument or a
     * project's own default language merged in by the caller (ADR-016-adjacent: the
     * ubiquitous-language MCP adapter combines an explicit override with
     * {@code ResolvedProject#defaultLanguage()} before this method ever sees it). The configured
     * {@code systemDefault} tier - and the rest of {@link DisplayLocale#select}'s fallback chain -
     * is unaffected, so an override that matches nothing still degrades exactly the way the
     * process-wide default already does.
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
     * identifier, prefLabel, definition) plus the blank-node subject guard and the three optional
     * actor-facet joins that scope a single-term read to one {@code code}. Extracted because both
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
                + "OPTIONAL { ?s a <" + HUMAN_ACTOR_TYPE + "> . BIND(true AS ?isHuman) } "
                + "OPTIONAL { ?s a <" + SYSTEM_ACTOR_TYPE + "> . BIND(true AS ?isSystem) } "
                + "OPTIONAL { ?s <" + ACTOR_ROLE_PROPERTY + "> ?actorRole } ";
    }

    /**
     * Reads one term's full current state by business code - used by {@link #findByCode} (reads
     * outside any transaction). A term missing either {@code skos:prefLabel} or
     * {@code skos:definition} entirely is invisible, exactly as before.
     */
    private Optional<TermAssembly> readAssemblyByCode(
            Function<String, Stream<BindingSet>> selectFn, TermCode code) {
        String query = "SELECT ?s ?prefLabel ?definition ?isHuman ?isSystem ?actorRole WHERE { GRAPH <"
                + TERMS_GRAPH + "> { "
                + termByCodeWhereClause(code)
                + "} }";

        Map<String, TermAssembly> bySubject = new LinkedHashMap<>();
        selectFn.apply(query).forEach(row -> {
            TermAssembly assembly = assemblyFor(bySubject, row, code);
            assembly.addPrefLabel(literalOf(row, "prefLabel"));
            assembly.addDefinition(literalOf(row, "definition").getLexicalForm());
            assembly.addActorRole(optionalLiteralOf(row, "actorRole"));
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
        String query = "SELECT ?s ?prefLabel ?definition ?isHuman ?isSystem ?actorRole ?head WHERE { GRAPH <"
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
            assembly.addDefinition(literalOf(row, "definition").getLexicalForm());
            assembly.addActorRole(optionalLiteralOf(row, "actorRole"));
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
    public List<Term> findAll(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

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

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Map<String, TermAssembly> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                TermAssembly assembly = assemblyFor(bySubject, row, null);
                assembly.addPrefLabel(literalOf(row, "prefLabel"));
                assembly.addDefinition(literalOf(row, "definition").getLexicalForm());
                assembly.addActorRole(optionalLiteralOf(row, "actorRole"));
            });
            return bySubject.values().stream().map(assembly -> assembly.toTerm(displayLocale)).toList();
        }
    }

    /**
     * Groups the (potentially several) rows of one concept - a mandatory but now
     * <em>multi-valued</em> {@code skos:prefLabel}/{@code skos:definition} join multiplies a
     * concept into one row per candidate value - into a single
     * {@link TermAssembly}, keyed by subject IRI. The remaining scalar fields (identity, code,
     * actor facet) are read once from the first row of a subject; every row contributes its
     * {@code prefLabel}/{@code definition} literal as a candidate via
     * {@link TermAssembly#addPrefLabel}/{@link TermAssembly#addDefinition}, called by the two
     * callers ({@link #findByCode}/{@link #findAll}) once per row.
     *
     * <p>{@code identifier} stays a single-valued read - it is already narrowed by the
     * {@code knownCode}/query filter to the code being looked up. {@code actorRole} is
     * <em>not</em> single-valued (see the class-level "Row multiplication on
     * {@code arkproc:actorRole}" note): only the actor <em>kind</em> ({@code isHuman}/
     * {@code isSystem}, single-valued in practice) is read once here, at construction; every row's
     * {@code actorRole} candidate is instead collected separately via
     * {@link TermAssembly#addActorRole}, called by the same three callers that call
     * {@link TermAssembly#addPrefLabel}/{@link TermAssembly#addDefinition}. Keeping {@code prefLabel}
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
                actorKindOf(row)));
    }

    /**
     * Mutable per-subject accumulator collecting a concept's {@code skos:prefLabel},
     * {@code skos:definition} and {@code arkproc:actorRole} candidates across rows, then choosing
     * one of each when the concept is finally materialised into a {@link Term}: {@code prefLabel}
     * via the {@link DisplayLocale} fallback chain, {@code definition}/{@code actorRole}
     * deterministically as the first-seen value (no display-language guarantee for either field),
     * logging a {@code WARN} if more than one distinct value was collected.
     */
    private static final class TermAssembly {

        private final TermId id;
        private final TermCode code;
        private final ActorKind actorKind;
        private final List<LocalizedLiteral> prefLabels = new ArrayList<>();
        private final List<String> definitions = new ArrayList<>();
        private final List<String> actorRoles = new ArrayList<>();

        private TermAssembly(TermId id, TermCode code, ActorKind actorKind) {
            this.id = id;
            this.code = code;
            this.actorKind = actorKind;
        }

        private void addPrefLabel(Literal literal) {
            prefLabels.add(new LocalizedLiteral(literal.getLexicalForm(), literal.getLanguageTag().orElse(null)));
        }

        private void addDefinition(String definition) {
            definitions.add(definition);
        }

        /** Collects one row's {@code arkproc:actorRole} candidate, or does nothing if the row bound none. */
        private void addActorRole(String actorRole) {
            if (actorRole != null) {
                actorRoles.add(actorRole);
            }
        }

        private Term toTerm(DisplayLocale displayLocale) {
            String prefLabel = displayLocale.select(prefLabels)
                    .map(LocalizedLiteral::value)
                    .orElseThrow(() -> new IllegalStateException(
                            "prefLabel is a required join, so at least one candidate must exist"));
            return new Term(id, code, prefLabel, firstDistinctDefinition(), actorFacet());
        }

        /**
         * Rebuilds the {@link ActorFacet} from {@link #actorKind} (single-valued, read once at
         * construction) and {@link #firstDistinctActorRole()} - {@code null} if the subject
         * carries neither {@code arkproc:HumanActor} nor {@code arkproc:SystemActor}.
         */
        private ActorFacet actorFacet() {
            if (actorKind == null) {
                return null;
            }
            return new ActorFacet(actorKind, firstDistinctActorRole());
        }

        /**
         * Returns the first-seen {@code skos:definition} candidate (stable across repeated calls,
         * since {@link LinkedHashMap}/row order preserves insertion order), logging a single
         * {@code WARN} when the subject carried more than one distinct value - a
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

        /**
         * Returns the first-seen {@code arkproc:actorRole} candidate, or {@code null} if the
         * subject carried none at all (the join is {@code OPTIONAL} - unlike {@code definition} an
         * empty candidate list is legal), logging a single {@code WARN} when more than one
         * distinct value was collected - the same row-multiplication policy as
         * {@link #firstDistinctDefinition()}.
         */
        private String firstDistinctActorRole() {
            if (actorRoles.isEmpty()) {
                return null;
            }
            long distinctCount = actorRoles.stream().distinct().count();
            if (distinctCount > 1) {
                LOG.warn("Term {}: field 'actorRole' had {} distinct values, returning the first",
                        id.value().value(), distinctCount);
            }
            return actorRoles.get(0);
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
     * Reconstructs the {@link ActorKind} of a term row, or {@code null} if the subject carries no
     * {@code arkproc:HumanActor}/{@code arkproc:SystemActor} type. The role itself is deliberately
     * not read here - see {@link TermAssembly#addActorRole} for why it is collected per row
     * instead of once at construction.
     */
    private static ActorKind actorKindOf(BindingSet row) {
        if (row.hasBinding("isHuman")) {
            return ActorKind.HUMAN;
        }
        if (row.hasBinding("isSystem")) {
            return ActorKind.SYSTEM;
        }
        return null;
    }

    private static String optionalLiteralOf(BindingSet row, String name) {
        return row.getValue(name).map(value -> ((Literal) value).getLexicalForm()).orElse(null);
    }
}
