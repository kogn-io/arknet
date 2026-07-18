package de.hauschel.arknet.req.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.DuplicateRequirementCodeException;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Out-adapter: {@link RequirementRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF dataset).
 *
 * <p>Maps a {@link Requirement} to its opaque {@link RequirementId} as the subject IRI (minted
 * once by a {@link de.hauschel.arknet.kernel.ResourceIdFactory}, never derived from the
 * business code), stored in one named graph shared by all requirements: five mandatory triples
 * (identifier, type, title, description, status) plus one or more mandatory
 * {@code arkreq:acceptanceCriterion} literal triples (issue #91, {@code 1..n}, testable
 * "Done when ..." criteria) plus up to three optional triples for {@code priority},
 * {@code motivatedBy} and {@code qualityCategory} - written only when the corresponding field is
 * non-{@code null} and read back via {@code OPTIONAL} SPARQL clauses so that requirements without
 * them still match. The {@code dcterms:identifier} triple carries the
 * human-readable {@link RequirementCode} ({@code FR-1}) - identity and label are deliberately
 * different triples on the same subject. This class depends only on the neutral kognio-rdf
 * ports ({@code terms} + {@code dataset}) and {@link SimpleRdf} - it never imports RDF4J or any
 * other backend-specific type. The backend ({@link DatasetLifecycle} implementation) is
 * supplied by the composition root.</p>
 *
 * <p><strong>WorkspaceId (local, single-user).</strong> Each {@link WorkspaceId}
 * is mapped 1:1 to a kognio-rdf {@link DatasetId}, so distinct workspaces are
 * fully isolated datasets. A future remote/team adapter (against kognio-memory)
 * would use the same routing key differently (e.g. as a server-side project
 * selector), but the local embedded adapter already keeps workspaces separate.</p>
 *
 * <p><strong>Create vs. update (opaque identity).</strong> Because identity is opaque and
 * minted once, "insert or replace by identity" is no longer one coherent operation.
 * {@link #create} and {@link #update} each check whether the subject already exists
 * <em>inside</em> the write transaction (an {@code ASK}) before writing - not via a separate
 * {@code findByCode} call beforehand, which would leave a check-then-act race between the check
 * and the write. {@link #create} rejects an existing subject with
 * {@link ResourceAlreadyExistsException}; {@link #update} rejects a missing one with
 * {@link RequirementNotFoundException}. An {@link #update} otherwise replaces the subject's
 * triples wholesale (the same replace-by-identity mechanic the previous save-only contract
 * used).</p>
 *
 * <p><strong>Identity collision vs. code collision.</strong> {@link #create} runs a second
 * {@code ASK} in the same transaction - by {@code dcterms:identifier}, not by subject - and
 * rejects a match with {@link DuplicateRequirementCodeException}. This is deliberately a
 * separate check and a separate exception from {@link ResourceAlreadyExistsException}: an
 * opaque-identity collision is a programming error (identities are minted once and never
 * reused), while a business-code collision (two requirements both claiming {@code FR-1}) is an
 * expected, rejectable outcome a human can cause and must be told about by name.</p>
 *
 * <p><strong>Term references arrive pre-resolved (issue #36, identity-carrying since #77).</strong>
 * {@link TermRef} carries the term's opaque subject {@link ResourceId} directly - resolving a
 * human-typed term code (e.g. {@code TERM-1}) against the shared workspace store, and rejecting
 * an unknown or ambiguous code, is done once by {@code KognioRdfTermLookup} at the moment a term
 * is linked (in the application service), not here on every write. This adapter therefore neither
 * queries the sibling terms graph nor re-verifies that a referenced subject still denotes a
 * {@code skos:Concept}; it trusts the identity it was handed, the same way it trusts {@code
 * motivatedBy} without re-resolving it. It still asserts each referenced subject's type as
 * {@code skos:Concept} in the SHACL write-gate's validation-only context (see below), because the
 * shape needs that type to fire correctly against a candidate graph that does not itself carry
 * the term's type triple.</p>
 *
 * <p><strong>That assertion is trusted, not verified.</strong> For a ref that {@code
 * KognioRdfTermLookup} produced the type did hold when the link was made - that query requires
 * it. For a ref that {@link #readUsesTerms} produced it may never have held: that read filters
 * for IRI-ness only and states no type condition, so a store-first (ADR-005) edge can carry a
 * non-{@code Concept} target into the context, where asserting the type satisfies the gate's
 * {@code sh:class} with the very fact under test. The MCP tools cannot reach the case, and it is
 * no worse than before #77 (such an edge survived there too, preserved by #65 and equally
 * unscrutinised) - but what makes it safe is that it is unreachable, not that anything checked
 * it.</p>
 *
 * <p><strong>SHACL write-gate.</strong> Every write call validates the candidate instance graph
 * against the requirements SHACL shapes via {@link ShaclWriteGate} before starting the write
 * transaction; a violation throws {@link WriteConstraintViolationException} and nothing is
 * persisted. The gate itself is technology-neutral - only
 * {@link KognioRdfRequirementRepositoryFactory} names RDF4J.</p>
 *
 * <p><strong>Row multiplication on {@code priority}/{@code qualityCategory} (issue #81).</strong>
 * Both properties carry no {@code sh:Violation}-severity {@code sh:maxCount} (unlike
 * {@code title}/{@code description}/{@code motivatedBy}, hardened by #99): {@code priority}'s
 * {@code sh:maxCount 1} is {@code sh:Warning}-severity only (never blocks a write), and
 * {@code qualityCategory} carries no {@code sh:maxCount} at all. A store-first (ADR-005)
 * requirement with two triples on either predicate therefore legally multiplies {@link #findAll}'s
 * SPARQL rows for one subject. {@link #findAll} groups rows per subject (the same
 * {@code LinkedHashMap} + {@code computeIfAbsent} pattern {@link #findByIds} already used) and
 * takes the first-seen value deterministically for each, logging a single {@code WARN} per
 * assembled {@link Requirement} when more than one distinct value was collected - visible instead
 * of silently duplicating the requirement in the result list. {@link #findByCode} is unaffected:
 * its single-row {@code findFirst()} is already internally consistent (one row = one coherent
 * value combination), it just returns a value combination the store cannot guarantee is "the"
 * one.</p>
 */
public class KognioRdfRequirementRepository implements RequirementRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KognioRdfRequirementRepository.class);

    private static final String ARKREQ_NAMESPACE = "https://w3id.org/arknet/requirements#";
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";

    private static final String CONCEPT_TYPE = ArkreqVocabulary.CONCEPT_TYPE;
    private static final String USES_TERM_PROPERTY = ArkreqVocabulary.USES_TERM;
    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String FUNCTIONAL_REQUIREMENT_TYPE = ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE;
    private static final String NON_FUNCTIONAL_REQUIREMENT_TYPE = ArkreqVocabulary.NON_FUNCTIONAL_REQUIREMENT_TYPE;
    private static final String STATUS_PROPERTY = ARKREQ_NAMESPACE + "status";
    private static final String PROPOSED_STATUS = ARKREQ_NAMESPACE + "Proposed";
    private static final String ACCEPTED_STATUS = ARKREQ_NAMESPACE + "Accepted";
    private static final String TITLE_PROPERTY = VocabDct.NAMESPACE + "title";
    private static final String DESCRIPTION_PROPERTY = VocabDct.NAMESPACE + "description";
    private static final String PRIORITY_PROPERTY = ARKREQ_NAMESPACE + "priority";
    private static final String MOTIVATED_BY_PROPERTY = ARKREQ_NAMESPACE + "motivatedBy";
    private static final String QUALITY_CATEGORY_PROPERTY = ARKREQ_NAMESPACE + "qualityCategory";
    private static final String ACCEPTANCE_CRITERION_PROPERTY = ARKREQ_NAMESPACE + "acceptanceCriterion";

    /**
     * Stands in for a requirement that predates #91: {@code arkreq:acceptanceCriterion} became
     * mandatory ({@code sh:minCount 1}) only with this field, so a requirement written by an
     * older {@code req_add} carries none. The gate blocks that state on the next <em>write</em>,
     * but reading is not gated - and {@link Requirement}'s constructor rejects an empty list
     * unconditionally, so without this substitution {@link #findByCode}/{@link #findAll} would
     * throw for every such pre-existing requirement instead of returning it. Substituting here,
     * at the adapter boundary, keeps that domain invariant intact (it never sees an empty list)
     * while surfacing the gap instead of crashing.
     *
     * <p>Since issue #103 this same substitution also catches the case where the read result is
     * non-empty yet still constructor-illegal after {@link #sanitizeAcceptanceCriteria}
     * filters/dedupes it down to nothing - see that method's javadoc.</p>
     */
    private static final List<String> LEGACY_ACCEPTANCE_CRITERION_PLACEHOLDER =
            List.of("(Altdatensatz vor #91 - kein Akzeptanzkriterium hinterlegt)");

    private static final String MUST_HAVE_PRIORITY = ARKREQ_NAMESPACE + "MustHave";
    private static final String SHOULD_HAVE_PRIORITY = ARKREQ_NAMESPACE + "ShouldHave";
    private static final String COULD_HAVE_PRIORITY = ARKREQ_NAMESPACE + "CouldHave";
    private static final String WONT_HAVE_PRIORITY = ARKREQ_NAMESPACE + "WontHave";

    private final DatasetLifecycle lifecycle;
    private final ShaclWriteGate gate;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from (must not be
     *                  {@code null})
     * @param gate      the SHACL write-gate validating candidate graphs before persistence
     *                  (must not be {@code null})
     */
    KognioRdfRequirementRepository(DatasetLifecycle lifecycle, ShaclWriteGate gate) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    @Override
    public void create(WorkspaceId workspaceId, Requirement requirement) {
        write(workspaceId, requirement, true);
    }

    @Override
    public void update(WorkspaceId workspaceId, Requirement requirement) {
        write(workspaceId, requirement, false);
    }

    private void write(WorkspaceId workspaceId, Requirement requirement, boolean expectAbsent) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(requirement, "requirement");

        // ResourceId#of (issue #83) validates IRIREF-safety at construction, so requirement.id()'s
        // wrapped IRI is already guaranteed safe to embed here - no separate check needed.
        String subjectIriString = requirement.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            // 1. Every term reference already carries its resolved identity (see class-level
            //    note), guaranteed IRIREF-safe by ResourceId#of (issue #83) same as the subject
            //    above.
            List<IRI> termIris = requirement.usesTerms().stream()
                    .map(this::termIriFor)
                    .toList();

            // 2. Build the candidate graph and, from it, the structural gate check. The usesTerm
            //    shape carries an sh:class skos:Concept constraint, but the type triples of the
            //    referenced terms live in the sibling terms graph, not in this candidate graph.
            //    They are handed to the gate as a validation-only asserted context (never
            //    persisted here). This is safe: the term was already proven to exist and be a
            //    concept at the moment it was resolved (KognioRdfTermLookup, called once from the
            //    application service when the term was linked) - the lookup, not the shape, is
            //    what keeps the edge non-dangling; this adapter no longer re-verifies it.
            Graph graph = buildCandidateGraph(subjectIri, requirement, termIris);
            Graph assertedContext = rdf.createGraph();
            for (IRI termIri : termIris) {
                assertedContext.add(termIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
            }
            gate.enforce(graph, assertedContext);

            String askExists = "ASK { GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " ?p ?o } }";
            String askCodeExists = "ASK { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                    + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(requirement.code().value())
                    + "\" } }";
            IRI graphIri = rdf.createIRI(REQUIREMENTS_GRAPH);

            handle.transactor().inTransaction(tx -> {
                boolean exists = tx.ask(askExists);
                if (expectAbsent) {
                    if (exists) {
                        throw new ResourceAlreadyExistsException(workspaceId, requirement.id().value());
                    }
                    // Identity is opaque and unique by construction, but the human-readable code
                    // is a separate triple this ASK alone cannot rule out - check it here, inside
                    // the same write transaction, so no other create() can race in between.
                    if (tx.ask(askCodeExists)) {
                        throw new DuplicateRequirementCodeException(workspaceId, requirement.code());
                    }
                } else if (!exists) {
                    throw new RequirementNotFoundException(workspaceId, requirement.code());
                }
                replaceTriples(tx, graphIri, subjectIri, subject, graph, exists);
                return null;
            });
        }
    }

    /**
     * Optimistic-concurrency variant of {@link #update} (issue #108, Befund 1): replaces the
     * requirement's triples only if the stored requirement, read fresh inside this same write
     * transaction, still equals {@code expected} - closing the lost-update window a plain read
     * (e.g. {@code findByCode}) followed by {@link #update} otherwise leaves open between the
     * read and the write.
     */
    @Override
    public boolean compareAndUpdate(WorkspaceId workspaceId, Requirement expected, Requirement updated) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(expected, "expected");
        Objects.requireNonNull(updated, "updated");
        if (!expected.id().equals(updated.id())) {
            throw new IllegalArgumentException("expected and updated must carry the same identity");
        }

        String subjectIriString = updated.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            List<IRI> termIris = updated.usesTerms().stream()
                    .map(this::termIriFor)
                    .toList();
            Graph graph = buildCandidateGraph(subjectIri, updated, termIris);
            Graph assertedContext = rdf.createGraph();
            for (IRI termIri : termIris) {
                assertedContext.add(termIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
            }
            gate.enforce(graph, assertedContext);

            String askExists = "ASK { GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " ?p ?o } }";
            IRI graphIri = rdf.createIRI(REQUIREMENTS_GRAPH);

            return handle.transactor().inTransaction(tx -> {
                if (!tx.ask(askExists)) {
                    throw new RequirementNotFoundException(workspaceId, updated.code());
                }
                // Re-reads the requirement's full current state through the same tx that is
                // about to overwrite it - not via a separate call beforehand, which would leave
                // exactly the TOCTOU window this method exists to close. A mismatch means some
                // other writer committed a change since expected was read; nothing is written and
                // the caller (RequirementService's retry loop) re-reads and tries again.
                Optional<Requirement> current = readCurrentBySubject(tx::select, subjectIriString, subject);
                if (current.isEmpty() || !current.get().equals(expected)) {
                    return false;
                }
                replaceTriples(tx, graphIri, subjectIri, subject, graph, true);
                return true;
            });
        }
    }

    /**
     * Builds the candidate graph for one requirement's triples: five mandatory triples
     * (identifier, type, title, description, status), one or more mandatory
     * {@code arkreq:acceptanceCriterion} literals, up to three optional triples ({@code priority},
     * {@code motivatedBy}, {@code qualityCategory}), and zero or more {@code arkreq:usesTerm}
     * edges to {@code termIris}. Shared by {@link #write} and {@link #compareAndUpdate} so both
     * write paths serialise a {@link Requirement} identically.
     */
    private Graph buildCandidateGraph(IRI subjectIri, Requirement requirement, List<IRI> termIris) {
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(typeIriFor(requirement.type())));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(requirement.code().value()));
        graph.add(subjectIri, rdf.createIRI(TITLE_PROPERTY), rdf.createLiteral(requirement.title()));
        graph.add(subjectIri, rdf.createIRI(DESCRIPTION_PROPERTY), rdf.createLiteral(requirement.description()));
        graph.add(subjectIri, rdf.createIRI(STATUS_PROPERTY), rdf.createIRI(statusIriFor(requirement.status())));
        if (requirement.priority() != null) {
            graph.add(subjectIri, rdf.createIRI(PRIORITY_PROPERTY),
                    rdf.createIRI(priorityIriFor(requirement.priority())));
        }
        if (requirement.motivatedBy() != null) {
            graph.add(subjectIri, rdf.createIRI(MOTIVATED_BY_PROPERTY), rdf.createIRI(requirement.motivatedBy()));
        }
        if (requirement.qualityCategory() != null) {
            graph.add(subjectIri, rdf.createIRI(QUALITY_CATEGORY_PROPERTY),
                    rdf.createLiteral(requirement.qualityCategory()));
        }
        for (IRI termIri : termIris) {
            graph.add(subjectIri, rdf.createIRI(USES_TERM_PROPERTY), termIri);
        }
        for (String criterion : requirement.acceptanceCriteria()) {
            graph.add(subjectIri, rdf.createIRI(ACCEPTANCE_CRITERION_PROPERTY), rdf.createLiteral(criterion));
        }
        return graph;
    }

    /**
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write
     * transaction - the tail shared by {@link #write} and {@link #compareAndUpdate} once each has
     * decided (via its own existence/comparison check) that the write should proceed.
     *
     * <p>Reduced complement (issue #77) of what {@link #readUsesTerms} can now read: since reading
     * no longer joins into the terms graph (a usesTerm edge's target IRI <em>is</em> the
     * {@code TermRef}, no re-derivation needed), the only edges {@code Requirement#usesTerms()}
     * can never carry are ones whose target is not an IRI at all - a store-first (ADR-005) edge
     * may legally point at a blank node ({@code [ a skos:Concept ]}), which {@code ResourceId}
     * cannot represent. The preservation query below finds exactly those, but only when
     * {@code exists} (there is nothing to preserve on a fresh {@code create}).</p>
     */
    private void replaceTriples(DatasetTx tx, IRI graphIri, IRI subjectIri, String subject, Graph graph,
            boolean exists) {
        String selectUnjoinableUsesTerms = "SELECT ?term WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " <" + USES_TERM_PROPERTY + "> ?term } "
                + "FILTER(!isIRI(?term)) }";
        String deleteExisting = "DELETE WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " ?p ?o } }";

        // Capture what a replacing write is about to destroy but could never have read (see
        // selectUnjoinableUsesTerms above) before deleteExisting wipes it, inside this same
        // transaction - a separate read beforehand would leave a TOCTOU window the caller's own
        // exists/comparison check deliberately avoids. The binding is read as a bare RDFTerm, not
        // cast to IRI: arkreq:usesTerm carries no sh:nodeKind constraint, so its target is
        // RDF-legally allowed to be a blank node, and a store-first edge can and does point at
        // one - exactly the non-IRI target selectUnjoinableUsesTerms filters for. Casting here
        // would trade #65's silent data loss for a crash on every update of the affected
        // requirement - a regression, not a fix.
        List<RDFTerm> unjoinableUsesTerms = exists
                ? tx.select(selectUnjoinableUsesTerms).map(row -> termOf(row, "term")).toList()
                : List.of();
        if (exists) {
            tx.update(deleteExisting);
        }
        tx.add(graphIri, graph);
        // Re-attach the preserved edges only after the gate has already run and the rewritten
        // graph is committed - never mixed into `graph` before gate.enforce ran on it. A
        // preserved edge's target is, by construction, not an IRI and therefore cannot appear in
        // the write's assertedContext (built from the requirement's termIris only, which are
        // always IRIs); feeding it to the gate would fail the usesTerm shape's sh:class
        // skos:Concept constraint and block every future update of this requirement. Appending it
        // here instead is safe precisely because nothing new is introduced - the edge already
        // existed in the store and is carried forward untouched. A blank-node target keeps its
        // identity across this delete-and-readd cycle: deleteExisting only removes triples whose
        // subject is the requirement, never the target node's own triples, and the RDFTerm
        // captured by the select above is the same object tx.add below writes back - RDF4J
        // compares blank nodes by id, so this re-attaches to the very node the store already
        // knows, not a fresh one.
        if (!unjoinableUsesTerms.isEmpty()) {
            Graph preservedEdges = rdf.createGraph();
            for (RDFTerm termNode : unjoinableUsesTerms) {
                preservedEdges.add(subjectIri, rdf.createIRI(USES_TERM_PROPERTY), termNode);
            }
            tx.add(graphIri, preservedEdges);
        }
    }

    @Override
    public Optional<Requirement> findByCode(WorkspaceId workspaceId, RequirementCode code) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");

        // The type join is filtered to the two known requirement types (same FILTER findAll
        // already uses below), rather than an unfiltered "a ?type": a store-first (ADR-005)
        // subject carrying a third rdf:type triple alongside its real one would otherwise bind an
        // extra, unpredictable row, and typeFromIri throws IllegalStateException for any type
        // that is neither FunctionalRequirement nor NonFunctionalRequirement - findFirst() below
        // has no way to prefer the "real" row over the spurious one.
        String query = "SELECT ?s ?type ?title ?description ?status ?priority ?motivatedBy ?qualityCategory "
                + "WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "?s a ?type . "
                + "FILTER(?type = <" + FUNCTIONAL_REQUIREMENT_TYPE + "> || ?type = <"
                + NON_FUNCTIONAL_REQUIREMENT_TYPE + ">) "
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . "
                + "?s <" + TITLE_PROPERTY + "> ?title . "
                + "?s <" + DESCRIPTION_PROPERTY + "> ?description . "
                + "?s <" + STATUS_PROPERTY + "> ?status . "
                + "OPTIONAL { ?s <" + PRIORITY_PROPERTY + "> ?priority } "
                + "OPTIONAL { ?s <" + MOTIVATED_BY_PROPERTY + "> ?motivatedBy } "
                + "OPTIONAL { ?s <" + QUALITY_CATEGORY_PROPERTY + "> ?qualityCategory } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            Optional<BindingSet> head = handle.sparqlQuery().select(query).findFirst();
            if (head.isEmpty()) {
                return Optional.empty();
            }
            BindingSet row = head.get();
            String subjectIriString = iriOf(row, "s").getIRIString();
            return Optional.of(new Requirement(
                    new RequirementId(ResourceId.of(subjectIriString)),
                    code,
                    literalOf(row, "title").getLexicalForm(),
                    literalOf(row, "description").getLexicalForm(),
                    typeFromIri(iriOf(row, "type").getIRIString()),
                    statusFromIri(iriOf(row, "status").getIRIString()),
                    priorityOf(row),
                    motivatedByOf(row),
                    qualityCategoryOf(row),
                    readUsesTerms(handle.sparqlQuery()::select, SparqlTerms.iriRef(subjectIriString)),
                    acceptanceCriteriaOrLegacyPlaceholder(
                            readAcceptanceCriteria(handle.sparqlQuery()::select,
                                    SparqlTerms.iriRef(subjectIriString)))));
        }
    }

    /**
     * Reads one requirement's full current state by subject IRI - the tx-scoped counterpart to
     * {@link #findByCode} (which is keyed by business code and reads outside any transaction),
     * used by {@link #compareAndUpdate} to compare the stored state against {@code expected}
     * inside the very transaction that is about to overwrite it. Reconstructs the {@link
     * Requirement} with the same field-by-field logic as {@link #findByCode} (including the
     * acceptance-criteria placeholder substitution), so a {@code Requirement} obtained from
     * {@code findByCode} compares equal here whenever nothing has changed in between.
     */
    private Optional<Requirement> readCurrentBySubject(
            Function<String, Stream<BindingSet>> selectFn, String subjectIriString, String subject) {
        String query = "SELECT ?identifier ?type ?title ?description ?status ?priority ?motivatedBy "
                + "?qualityCategory WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + subject + " <" + IDENTIFIER_PROPERTY + "> ?identifier ; "
                + "a ?type ; "
                + "<" + TITLE_PROPERTY + "> ?title ; "
                + "<" + DESCRIPTION_PROPERTY + "> ?description ; "
                + "<" + STATUS_PROPERTY + "> ?status . "
                + "OPTIONAL { " + subject + " <" + PRIORITY_PROPERTY + "> ?priority } "
                + "OPTIONAL { " + subject + " <" + MOTIVATED_BY_PROPERTY + "> ?motivatedBy } "
                + "OPTIONAL { " + subject + " <" + QUALITY_CATEGORY_PROPERTY + "> ?qualityCategory } } }";

        Optional<BindingSet> head = selectFn.apply(query).findFirst();
        if (head.isEmpty()) {
            return Optional.empty();
        }
        BindingSet row = head.get();
        return Optional.of(new Requirement(
                new RequirementId(ResourceId.of(subjectIriString)),
                new RequirementCode(literalOf(row, "identifier").getLexicalForm()),
                literalOf(row, "title").getLexicalForm(),
                literalOf(row, "description").getLexicalForm(),
                typeFromIri(iriOf(row, "type").getIRIString()),
                statusFromIri(iriOf(row, "status").getIRIString()),
                priorityOf(row),
                motivatedByOf(row),
                qualityCategoryOf(row),
                readUsesTerms(selectFn, subject),
                acceptanceCriteriaOrLegacyPlaceholder(readAcceptanceCriteria(selectFn, subject))));
    }

    @Override
    public List<Requirement> findAll(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");

        String query = "SELECT ?s ?identifier ?title ?description ?type ?status ?priority ?motivatedBy "
                + "?qualityCategory WHERE { GRAPH <"
                + REQUIREMENTS_GRAPH + "> { "
                + "?s a ?type . "
                + "FILTER(?type = <" + FUNCTIONAL_REQUIREMENT_TYPE + "> || ?type = <"
                + NON_FUNCTIONAL_REQUIREMENT_TYPE + ">) "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "?s <" + TITLE_PROPERTY + "> ?title . "
                + "?s <" + DESCRIPTION_PROPERTY + "> ?description . "
                + "?s <" + STATUS_PROPERTY + "> ?status . "
                + "OPTIONAL { ?s <" + PRIORITY_PROPERTY + "> ?priority } "
                + "OPTIONAL { ?s <" + MOTIVATED_BY_PROPERTY + "> ?motivatedBy } "
                + "OPTIONAL { ?s <" + QUALITY_CATEGORY_PROPERTY + "> ?qualityCategory } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            Map<String, List<TermRef>> termsBySubject = readUsesTermsBySubject(handle);
            Map<String, List<String>> criteriaBySubject = readAcceptanceCriteriaBySubject(handle);
            // Grouped by subject (issue #81 - see the class-level note above): priority/
            // qualityCategory are OPTIONAL joins without an enforced sh:maxCount, so a store-first
            // requirement with two triples on either predicate binds a cross-product of rows for
            // the same subject. Mapping each row straight to a Requirement (the pre-#81 code)
            // would have surfaced that subject twice in the result list instead of once.
            Map<String, RequirementAssembly> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                RequirementAssembly assembly = assemblyFor(bySubject, row);
                assembly.addPriorityCandidate(priorityOf(row));
                assembly.addQualityCategoryCandidate(qualityCategoryOf(row));
            });
            return bySubject.entrySet().stream()
                    .map(entry -> entry.getValue().toRequirement(
                            termsBySubject.getOrDefault(entry.getKey(), List.of()),
                            acceptanceCriteriaOrLegacyPlaceholder(
                                    criteriaBySubject.getOrDefault(entry.getKey(), List.of()))))
                    .toList();
        }
    }

    /**
     * Groups the (potentially several) rows of one requirement - an {@code OPTIONAL} join on
     * {@code priority}/{@code qualityCategory} without an enforced {@code sh:maxCount} multiplies a
     * requirement into a row per candidate value combination (issue #81) - into a single
     * {@link RequirementAssembly}, keyed by subject IRI. The single-valued fields (identity, code,
     * title, description, type, status, motivatedBy - all either {@code sh:maxCount 1} at
     * {@code sh:Violation} severity or otherwise guaranteed single-triple by the domain) are read
     * once from the first row of a subject; every row contributes its {@code priority}/
     * {@code qualityCategory} binding (if present) as a candidate via
     * {@link RequirementAssembly#addPriorityCandidate}/{@link RequirementAssembly#addQualityCategoryCandidate},
     * called by {@link #findAll} once per row.
     */
    private static RequirementAssembly assemblyFor(Map<String, RequirementAssembly> bySubject, BindingSet row) {
        String subjectIri = iriOf(row, "s").getIRIString();
        return bySubject.computeIfAbsent(subjectIri, iri -> new RequirementAssembly(
                new RequirementId(ResourceId.of(iri)),
                new RequirementCode(literalOf(row, "identifier").getLexicalForm()),
                literalOf(row, "title").getLexicalForm(),
                literalOf(row, "description").getLexicalForm(),
                typeFromIri(iriOf(row, "type").getIRIString()),
                statusFromIri(iriOf(row, "status").getIRIString()),
                motivatedByOf(row)));
    }

    /**
     * Mutable per-subject accumulator collecting a requirement's {@code priority} and
     * {@code qualityCategory} candidates across rows (issue #81), then choosing one of each
     * deterministically (first-seen) when the requirement is finally materialised, logging a
     * {@code WARN} if more than one distinct value was collected for a field.
     */
    private static final class RequirementAssembly {

        private final RequirementId id;
        private final RequirementCode code;
        private final String title;
        private final String description;
        private final RequirementType type;
        private final RequirementStatus status;
        private final String motivatedBy;
        private final List<Priority> priorities = new ArrayList<>();
        private final List<String> qualityCategories = new ArrayList<>();

        private RequirementAssembly(RequirementId id, RequirementCode code, String title, String description,
                RequirementType type, RequirementStatus status, String motivatedBy) {
            this.id = id;
            this.code = code;
            this.title = title;
            this.description = description;
            this.type = type;
            this.status = status;
            this.motivatedBy = motivatedBy;
        }

        private void addPriorityCandidate(Priority priority) {
            if (priority != null) {
                priorities.add(priority);
            }
        }

        private void addQualityCategoryCandidate(String qualityCategory) {
            if (qualityCategory != null) {
                qualityCategories.add(qualityCategory);
            }
        }

        private Requirement toRequirement(List<TermRef> usesTerms, List<String> acceptanceCriteria) {
            return new Requirement(id, code, title, description, type, status,
                    firstDistinct(priorities, "priority"), motivatedBy,
                    firstDistinct(qualityCategories, "qualityCategory"), usesTerms, acceptanceCriteria);
        }

        /**
         * Returns the first-seen candidate for {@code fieldName} (stable across repeated calls,
         * since {@link LinkedHashMap}/row order preserves insertion order), or {@code null} if the
         * {@code OPTIONAL} join never bound - logging a single {@code WARN} when more than one
         * distinct value was collected, issue #81's "stille Luege" this makes visible instead of
         * silently overwriting/duplicating.
         */
        private <T> T firstDistinct(List<T> candidates, String fieldName) {
            if (candidates.isEmpty()) {
                return null;
            }
            long distinctCount = candidates.stream().distinct().count();
            if (distinctCount > 1) {
                LOG.warn("Requirement {}: field '{}' had {} distinct values, returning the first",
                        id.value().value(), fieldName, distinctCount);
            }
            return candidates.get(0);
        }
    }

    /**
     * Batch variant of {@link #findByCode}, keyed by opaque identity instead of business code -
     * backs {@link ResolveRequirements} (issue #88). One {@code VALUES}-bound query for the whole
     * batch, not one query per id: the caller (a sibling bounded context's driving adapter,
     * rendering several requirement references at once) must not pay an N+1 store round-trip.
     *
     * <p>Returns the slim {@link ResolveRequirements.ResolvedRequirement} projection, not the full
     * {@link Requirement} aggregate: the query below therefore joins only {@code identifier}, not
     * {@code title}/{@code description} - fields {@link ResolveRequirements} never reads.</p>
     *
     * <p><strong>No type filter, unlike the sibling ubiquitous-language adapter.</strong>
     * {@code KognioRdfTermRepository#findByIds} joins {@code ?s a <skos:Concept>} because every
     * subject in the terms graph carries that one type. Requirements, in contrast, are typed
     * either {@code arkreq:FunctionalRequirement} or {@code arkreq:NonFunctionalRequirement}; a
     * type filter here would either need both alternatives (no benefit - {@code dcterms:identifier}
     * alone already scopes the join to requirements graph subjects that carry a code) or would
     * arbitrarily exclude one requirement type. The join is therefore only over
     * {@code VALUES ?s} + {@code dcterms:identifier}.</p>
     *
     * <p><strong>Exactly one {@link ResolveRequirements.ResolvedRequirement} per resolved
     * subject.</strong> {@code RequirementShape}'s identifier constraint carries no
     * {@code sh:maxCount}, so the single mandatory join below (identifier) is not guaranteed to
     * bind exactly one row per subject. Grouping by subject and keeping the first row's binding
     * turns that cardinality back into "the requirements" the port promises, not "one row per
     * predicate combination" - which is what a naive per-row mapping would leak to every caller.
     * Which identifier ends up chosen in that (pathological, store-first-only) case is
     * deliberately unspecified.</p>
     */
    @Override
    public List<ResolveRequirements.ResolvedRequirement> findByIds(WorkspaceId workspaceId, List<ResourceId> ids) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return List.of();
        }

        // ResourceId#of (issue #83) validates IRIREF-safety at construction, so every id here is
        // already guaranteed safe to embed - restores ResolveRequirements#getById's "never
        // rejects" contract, which this used to violate by throwing on an impossible identity.
        String values = ids.stream()
                .map(id -> SparqlTerms.iriRef(id.value()))
                .collect(Collectors.joining(" "));

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "VALUES ?s { " + values + " } "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            Map<String, ResolveRequirements.ResolvedRequirement> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                String subjectIri = iriOf(row, "s").getIRIString();
                // putIfAbsent, not put: the first row wins if a subject has several identifiers.
                bySubject.putIfAbsent(subjectIri, new ResolveRequirements.ResolvedRequirement(
                        ResourceId.of(subjectIri),
                        new RequirementCode(literalOf(row, "identifier").getLexicalForm())));
            });
            return List.copyOf(bySubject.values());
        }
    }

    // ---- usesTerm reading --------------------------------------------------------------

    /**
     * Reads the {@code arkreq:usesTerm} edges of one requirement back as term references.
     *
     * <p><strong>No longer a join (issue #77).</strong> The edge's target IRI <em>is</em> the
     * {@link TermRef} - {@link TermRef#value()} wraps it directly - so this reads only the
     * requirements graph; the sibling terms graph is never consulted here. Ordered by the
     * target IRI, because RDF has no intrinsic statement order and {@link Requirement} compares
     * its {@code usesTerms} list positionally.</p>
     *
     * <p><strong>Still lossy for one, narrower case.</strong> {@code arkreq:usesTerm} carries no
     * {@code sh:nodeKind} constraint, so a store-first (ADR-005) edge may legally target a blank
     * node - which {@link de.hauschel.arknet.kernel.ResourceId} cannot represent. The
     * {@code FILTER(isIRI(?term))} below excludes exactly that case; {@link
     * Requirement#usesTerms()} never reflects such an edge. {@link #write} nonetheless survives
     * it: on an update it separately queries, inside the same write transaction, for exactly the
     * edges that are not IRIs and re-attaches them after rewriting the subject's triples (issue
     * #65) - so a read-modify-write ({@code req_set_status}, {@code req_link_term}) carries the
     * dropped edge along instead of erasing it. Every edge written through {@code req_link_term}
     * targets a resolved subject IRI by construction, so this cannot bite via the MCP tools.</p>
     *
     * <p><strong>The "identifier but no {@code skos:Concept} type" category is gone (issue
     * #77).</strong> While this read still joined the terms graph by {@code dcterms:identifier},
     * a target carrying an identifier but not the type bound a row here, yet the resolution query
     * demanded the type and so rejected, on the next {@link #update}, the very {@link TermRef}
     * this read had produced - the requirement became unwritable, and #65 could not preserve the
     * edge because the read did bind it. Carrying identity removes that mismatch at its root
     * rather than reconciling it: there is no second query stating a different condition, because
     * there is no resolution on the read path at all.</p>
     */
    private List<TermRef> readUsesTerms(Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?term WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " <" + USES_TERM_PROPERTY + "> ?term } "
                + "FILTER(isIRI(?term)) } ORDER BY ?term";
        return selectFn.apply(query)
                .map(row -> new TermRef(ResourceId.of(iriOf(row, "term").getIRIString())))
                .toList();
    }

    /** Bulk variant of {@link #readUsesTerms}: all requirements' term references in one query. */
    private Map<String, List<TermRef>> readUsesTermsBySubject(DatasetHandle handle) {
        String query = "SELECT ?s ?term WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { ?s <" + USES_TERM_PROPERTY + "> ?term } "
                + "FILTER(isIRI(?term)) } ORDER BY ?s ?term";
        Map<String, List<TermRef>> bySubject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(new TermRef(ResourceId.of(iriOf(row, "term").getIRIString()))));
        return bySubject;
    }

    // ---- acceptanceCriterion reading ---------------------------------------------------

    /** Reads the {@code arkreq:acceptanceCriterion} literals of one requirement, in lexical order. */
    private List<String> readAcceptanceCriteria(Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?criterion WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " <" + ACCEPTANCE_CRITERION_PROPERTY
                + "> ?criterion } } ORDER BY ?criterion";
        return selectFn.apply(query)
                .map(row -> literalOf(row, "criterion").getLexicalForm())
                .toList();
    }

    /** Bulk variant of {@link #readAcceptanceCriteria}: all requirements' criteria in one query. */
    private Map<String, List<String>> readAcceptanceCriteriaBySubject(DatasetHandle handle) {
        String query = "SELECT ?s ?criterion WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { ?s <" + ACCEPTANCE_CRITERION_PROPERTY + "> ?criterion } "
                + "} ORDER BY ?s ?criterion";
        Map<String, List<String>> bySubject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(literalOf(row, "criterion").getLexicalForm()));
        return bySubject;
    }

    /**
     * Substitutes {@link #LEGACY_ACCEPTANCE_CRITERION_PLACEHOLDER} for a read result that is
     * empty, or becomes empty once {@link #sanitizeAcceptanceCriteria} has filtered/deduped it -
     * see that method's and the placeholder constant's javadoc for why an empty list must never
     * reach {@link Requirement}'s constructor.
     */
    private static List<String> acceptanceCriteriaOrLegacyPlaceholder(List<String> criteria) {
        List<String> sanitized = sanitizeAcceptanceCriteria(criteria);
        return sanitized.isEmpty() ? LEGACY_ACCEPTANCE_CRITERION_PLACEHOLDER : sanitized;
    }

    /**
     * Filters blank entries and deduplicates by lexical form - issue #103. {@link
     * #readAcceptanceCriteria}/{@link #readAcceptanceCriteriaBySubject} read each {@code
     * arkreq:acceptanceCriterion} literal via {@code literalOf(...).getLexicalForm()}, which
     * discards its language tag and datatype; {@code RequirementShape} places no {@code
     * sh:languageIn}/uniqueness constraint on the property, so a store-first (ADR-005) requirement
     * can legally carry two literals that normalize to the same string (e.g. the same text tagged
     * {@code @en} and {@code @de}) or a whitespace-only literal alongside a valid one. {@link
     * Requirement}'s constructor rejects both a duplicate and a blank entry unconditionally, so
     * without this step {@link #findByCode}/{@link #findAll} would throw for such a requirement
     * instead of returning it - the same read-path-crashes-on-write-time-only-invariant class of
     * bug #91 already fixed for the all-empty case, one level deeper: here the list is non-empty
     * yet still constructor-illegal. {@code distinct()} keeps the first occurrence in the query's
     * {@code ORDER BY ?criterion} order, so which of two colliding literals "wins" is deterministic
     * per read but otherwise unspecified - store-first duplicate/blank criteria are a gap to
     * surface, not a case worth resolving more cleverly than that.
     */
    private static List<String> sanitizeAcceptanceCriteria(List<String> criteria) {
        return criteria.stream()
                .filter(criterion -> !criterion.isBlank())
                .distinct()
                .toList();
    }

    /**
     * Converts an already-resolved {@link TermRef} to an {@link IRI} for writing.
     * {@link de.hauschel.arknet.kernel.ResourceId#of(String)} validates IRIREF-safety at
     * construction (issue #83), so the wrapped IRI is already guaranteed safe here.
     */
    private IRI termIriFor(TermRef term) {
        return rdf.createIRI(term.value().value());
    }

    // ---- helpers -----------------------------------------------------------------------

    private static String typeIriFor(RequirementType type) {
        return switch (type) {
            case FUNCTIONAL -> FUNCTIONAL_REQUIREMENT_TYPE;
            case NON_FUNCTIONAL -> NON_FUNCTIONAL_REQUIREMENT_TYPE;
        };
    }

    private static RequirementType typeFromIri(String iri) {
        if (FUNCTIONAL_REQUIREMENT_TYPE.equals(iri)) {
            return RequirementType.FUNCTIONAL;
        }
        if (NON_FUNCTIONAL_REQUIREMENT_TYPE.equals(iri)) {
            return RequirementType.NON_FUNCTIONAL;
        }
        throw new IllegalStateException("unexpected requirement type " + iri);
    }

    private static String statusIriFor(RequirementStatus status) {
        return switch (status) {
            case PROPOSED -> PROPOSED_STATUS;
            case ACCEPTED -> ACCEPTED_STATUS;
        };
    }

    private static RequirementStatus statusFromIri(String iri) {
        if (PROPOSED_STATUS.equals(iri)) {
            return RequirementStatus.PROPOSED;
        }
        if (ACCEPTED_STATUS.equals(iri)) {
            return RequirementStatus.ACCEPTED;
        }
        throw new IllegalStateException("unexpected status " + iri);
    }

    private static String priorityIriFor(Priority priority) {
        return switch (priority) {
            case MUST_HAVE -> MUST_HAVE_PRIORITY;
            case SHOULD_HAVE -> SHOULD_HAVE_PRIORITY;
            case COULD_HAVE -> COULD_HAVE_PRIORITY;
            case WONT_HAVE -> WONT_HAVE_PRIORITY;
        };
    }

    private static Priority priorityFromIri(String iri) {
        if (MUST_HAVE_PRIORITY.equals(iri)) {
            return Priority.MUST_HAVE;
        }
        if (SHOULD_HAVE_PRIORITY.equals(iri)) {
            return Priority.SHOULD_HAVE;
        }
        if (COULD_HAVE_PRIORITY.equals(iri)) {
            return Priority.COULD_HAVE;
        }
        if (WONT_HAVE_PRIORITY.equals(iri)) {
            return Priority.WONT_HAVE;
        }
        throw new IllegalStateException("unexpected priority " + iri);
    }

    private static Priority priorityOf(BindingSet row) {
        return row.getValue("priority")
                .map(value -> priorityFromIri(((IRI) value).getIRIString()))
                .orElse(null);
    }

    private static String motivatedByOf(BindingSet row) {
        return row.getValue("motivatedBy")
                .map(value -> ((IRI) value).getIRIString())
                .orElse(null);
    }

    private static String qualityCategoryOf(BindingSet row) {
        return row.getValue("qualityCategory")
                .map(value -> ((Literal) value).getLexicalForm())
                .orElse(null);
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
     * Reads a binding as the bare {@link RDFTerm} it is, without narrowing it to {@link IRI} -
     * unlike {@link #iriOf}, deliberately used where the binding's kind is not known in advance
     * (e.g. an {@code arkreq:usesTerm} target, which may legally be a blank node).
     */
    private static RDFTerm termOf(BindingSet row, String name) {
        return row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
