package de.hauschel.arknet.req.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
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
 * (identifier, type, title, description, status) plus up to three optional triples for
 * {@code priority}, {@code motivatedBy} and {@code qualityCategory} - written only when the
 * corresponding field is non-{@code null} and read back via {@code OPTIONAL} SPARQL clauses so
 * that requirements without them still match. The {@code dcterms:identifier} triple carries the
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
 */
public class KognioRdfRequirementRepository implements RequirementRepository {

    private static final String ARKREQ_NAMESPACE = "https://w3id.org/arknet/requirements#";
    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";

    private static final String CONCEPT_TYPE = SKOS_NAMESPACE + "Concept";
    private static final String USES_TERM_PROPERTY = ARKREQ_NAMESPACE + "usesTerm";
    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String FUNCTIONAL_REQUIREMENT_TYPE = ARKREQ_NAMESPACE + "FunctionalRequirement";
    private static final String NON_FUNCTIONAL_REQUIREMENT_TYPE = ARKREQ_NAMESPACE + "NonFunctionalRequirement";
    private static final String STATUS_PROPERTY = ARKREQ_NAMESPACE + "status";
    private static final String PROPOSED_STATUS = ARKREQ_NAMESPACE + "Proposed";
    private static final String ACCEPTED_STATUS = ARKREQ_NAMESPACE + "Accepted";
    private static final String TITLE_PROPERTY = VocabDct.NAMESPACE + "title";
    private static final String DESCRIPTION_PROPERTY = VocabDct.NAMESPACE + "description";
    private static final String PRIORITY_PROPERTY = ARKREQ_NAMESPACE + "priority";
    private static final String MOTIVATED_BY_PROPERTY = ARKREQ_NAMESPACE + "motivatedBy";
    private static final String QUALITY_CATEGORY_PROPERTY = ARKREQ_NAMESPACE + "qualityCategory";
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

        // Defense-in-depth: ResourceId's own validation is looser than SPARQL's IRIREF grammar.
        // Reject an impossible identity before it ever reaches SPARQL string concatenation.
        String subjectIriString = requirement.id().value().value();
        if (!SparqlTerms.isValidIriReference(subjectIriString)) {
            throw new IllegalArgumentException(
                    "requirement id yields an invalid IRI for SPARQL: " + subjectIriString);
        }
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            // 1. Every term reference already carries its resolved identity (see class-level
            //    note) - just validate it is SPARQL-safe, the same defense-in-depth applied to
            //    the subject above.
            List<IRI> termIris = requirement.usesTerms().stream()
                    .map(this::termIriFor)
                    .toList();

            // 2. Build the candidate graph.
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

            // 3. Structural gate, then create/update. The usesTerm shape carries an
            //    sh:class skos:Concept constraint, but the type triples of the referenced
            //    terms live in the sibling terms graph, not in this candidate graph. They are
            //    handed to the gate as a validation-only asserted context (never persisted
            //    here). This is safe: the term was already proven to exist and be a concept at
            //    the moment it was resolved (KognioRdfTermLookup, called once from the
            //    application service when the term was linked) - the lookup, not the shape, is
            //    what keeps the edge non-dangling; this adapter no longer re-verifies it.
            Graph assertedContext = rdf.createGraph();
            for (IRI termIri : termIris) {
                assertedContext.add(termIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
            }
            gate.enforce(graph, assertedContext);

            String askExists = "ASK { GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " ?p ?o } }";
            String askCodeExists = "ASK { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                    + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(requirement.code().value())
                    + "\" } }";
            String deleteExisting = "DELETE WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " ?p ?o } }";
            // 4. Reduced complement (issue #77) of what readUsesTerms can now read: since reading
            //    no longer joins into the terms graph (a usesTerm edge's target IRI *is* the
            //    TermRef, no re-derivation needed), the only edges Requirement#usesTerms() can
            //    never carry are ones whose target is not an IRI at all - a store-first
            //    (ADR-005) edge may legally point at a blank node ([ a skos:Concept ]), which
            //    ResourceId cannot represent. This finds exactly those. Only ever run on update -
            //    see the exists-guard below, where exists == true implies expectAbsent == false
            //    because the expectAbsent branch throws before reaching it, so create's subject
            //    (which by contract cannot exist yet) never runs this query.
            String selectUnjoinableUsesTerms = "SELECT ?term WHERE { "
                    + "GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " <" + USES_TERM_PROPERTY + "> ?term } "
                    + "FILTER(!isIRI(?term)) }";
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
                // Capture what a replacing update is about to destroy but could never have read
                // (see selectUnjoinableUsesTerms above) before deleteExisting wipes it, inside
                // this same transaction - a separate read beforehand would leave a TOCTOU window
                // the ASKs above deliberately avoid. The binding is read as a bare RDFTerm, not
                // cast to IRI: arkreq:usesTerm carries no sh:nodeKind constraint, so its target
                // is RDF-legally allowed to be a blank node, and a store-first edge can and does
                // point at one - exactly the non-IRI target selectUnjoinableUsesTerms filters
                // for. Casting here would trade #65's silent data loss for a crash on every
                // update of the affected requirement - a regression, not a fix.
                List<RDFTerm> unjoinableUsesTerms = exists
                        ? tx.select(selectUnjoinableUsesTerms).map(row -> termOf(row, "term")).toList()
                        : List.of();
                if (exists) {
                    tx.update(deleteExisting);
                }
                tx.add(graphIri, graph);
                // 5. Re-attach the preserved edges only after the gate has already run and the
                //    rewritten graph is committed - never mixed into `graph` before
                //    gate.enforce above. A preserved edge's target is, by construction, not an
                //    IRI and therefore cannot appear in this write's assertedContext (built from
                //    the termIris in requirement.usesTerms() only, which are always IRIs); feeding
                //    it to the gate would fail the usesTerm shape's sh:class skos:Concept
                //    constraint and block every future update of this requirement. Appending it
                //    here instead is safe precisely because nothing new is introduced - the edge
                //    already existed in the store and is carried forward untouched. A blank-node
                //    target keeps its identity across this delete-and-readd cycle: deleteExisting
                //    only removes triples whose subject is the requirement, never the target
                //    node's own triples, and the RDFTerm captured by the select above is the same
                //    object tx.add below writes back - RDF4J compares blank nodes by id, so this
                //    re-attaches to the very node the store already knows, not a fresh one.
                if (!unjoinableUsesTerms.isEmpty()) {
                    Graph preservedEdges = rdf.createGraph();
                    for (RDFTerm termNode : unjoinableUsesTerms) {
                        preservedEdges.add(subjectIri, rdf.createIRI(USES_TERM_PROPERTY), termNode);
                    }
                    tx.add(graphIri, preservedEdges);
                }
                return null;
            });
        }
    }

    @Override
    public Optional<Requirement> findByCode(WorkspaceId workspaceId, RequirementCode code) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(code, "code");

        String query = "SELECT ?s ?type ?title ?description ?status ?priority ?motivatedBy ?qualityCategory "
                + "WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" ; "
                + "a ?type ; "
                + "<" + TITLE_PROPERTY + "> ?title ; "
                + "<" + DESCRIPTION_PROPERTY + "> ?description ; "
                + "<" + STATUS_PROPERTY + "> ?status . "
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
                    readUsesTerms(handle, SparqlTerms.iriRef(subjectIriString))));
        }
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
            return handle.sparqlQuery().select(query)
                    .map(row -> {
                        String subjectIriString = iriOf(row, "s").getIRIString();
                        return new Requirement(
                                new RequirementId(ResourceId.of(subjectIriString)),
                                new RequirementCode(literalOf(row, "identifier").getLexicalForm()),
                                literalOf(row, "title").getLexicalForm(),
                                literalOf(row, "description").getLexicalForm(),
                                typeFromIri(iriOf(row, "type").getIRIString()),
                                statusFromIri(iriOf(row, "status").getIRIString()),
                                priorityOf(row),
                                motivatedByOf(row),
                                qualityCategoryOf(row),
                                termsBySubject.getOrDefault(subjectIriString, List.of()));
                    })
                    .toList();
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

        // Defense-in-depth, same rationale as the subject check in write(): reject an impossible
        // identity before it ever reaches SPARQL string concatenation.
        String values = ids.stream()
                .map(id -> {
                    String iriString = id.value();
                    if (!SparqlTerms.isValidIriReference(iriString)) {
                        throw new IllegalArgumentException(
                                "requirement id yields an invalid IRI for SPARQL: " + iriString);
                    }
                    return SparqlTerms.iriRef(iriString);
                })
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
    private List<TermRef> readUsesTerms(DatasetHandle handle, String subject) {
        String query = "SELECT ?term WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " <" + USES_TERM_PROPERTY + "> ?term } "
                + "FILTER(isIRI(?term)) } ORDER BY ?term";
        return handle.sparqlQuery().select(query)
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

    /**
     * Converts an already-resolved {@link TermRef} to an {@link IRI} for writing, applying the
     * same defense-in-depth SPARQL-safety check as the subject identity in {@link #write}:
     * {@link de.hauschel.arknet.kernel.ResourceId}'s own validation is looser than SPARQL's
     * IRIREF grammar.
     */
    private IRI termIriFor(TermRef term) {
        String termIriString = term.value().value();
        if (!SparqlTerms.isValidIriReference(termIriString)) {
            throw new IllegalArgumentException(
                    "term reference yields an invalid IRI for SPARQL: " + termIriString);
        }
        return rdf.createIRI(termIriString);
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
