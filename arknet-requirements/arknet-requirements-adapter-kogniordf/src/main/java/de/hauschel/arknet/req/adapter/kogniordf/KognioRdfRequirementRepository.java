package de.hauschel.arknet.req.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
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
 * <p><strong>Strict cross-BC term resolution (issue #36).</strong> Requirements and
 * ubiquitous-language terms share one per-workspace store. On write every {@link TermRef} is
 * resolved against that store <em>before</em> anything is written: the term identity (e.g.
 * {@code TERM-1}) is looked up by {@code dcterms:identifier} among the {@code skos:Concept}s of
 * the glossary. An unknown or ambiguous identity aborts the write with a didactic
 * {@link UnresolvedReferenceException}; no dangling {@code arkreq:usesTerm} edge is ever
 * persisted. Resolution goes via the identifier, never the {@code skos:prefLabel}, so the
 * edge survives relabelling a term.</p>
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
    // Mirrors the graph IRI the ubiquitous-language out-adapter writes into. The bounded
    // contexts share one workspace dataset; resolving a term means reading across into
    // that sibling graph (see class-level strict-resolution note).
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";

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
            // 1. Resolve every term reference strictly against the shared workspace store.
            List<IRI> termIris = requirement.usesTerms().stream()
                    .map(term -> resolveTerm(handle, workspaceId, term))
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
            //    here). This is safe: the strict lookup above already proved each term exists
            //    and is a concept - the lookup, not the shape, is what keeps the edge
            //    non-dangling.
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
                if (exists) {
                    tx.update(deleteExisting);
                }
                tx.add(graphIri, graph);
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

    // ---- usesTerm reading --------------------------------------------------------------

    /**
     * Reads the {@code arkreq:usesTerm} edges of one requirement back as term identities.
     *
     * <p>The edge points at the term's IRI; its {@code dcterms:identifier} lives in the
     * sibling terms graph, so this joins across both graphs. Ordered by identity, because RDF
     * has no intrinsic statement order and {@link Requirement} compares its {@code usesTerms}
     * list positionally.</p>
     *
     * <p><strong>The join is lossy, and the loss is not read-only.</strong> An edge whose
     * target carries no {@code dcterms:identifier} in the terms graph binds no row and drops
     * out here. The next {@link #update} then rewrites the requirement from the record it was
     * handed, so a read-modify-write ({@code req_set_status}, {@code req_link_term}) erases the
     * dropped edge from the store for good. Every edge written through {@link #resolveTerm}
     * is joinable by construction, so this cannot bite via the MCP tools; an edge that entered
     * the requirements graph some other way (store-first, ADR-005) can be lost. See issue #65.</p>
     */
    private List<TermRef> readUsesTerms(DatasetHandle handle, String subject) {
        String query = "SELECT ?termId WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { " + subject + " <" + USES_TERM_PROPERTY + "> ?term } "
                + "GRAPH <" + TERMS_GRAPH + "> { ?term <" + IDENTIFIER_PROPERTY + "> ?termId } } "
                + "ORDER BY ?termId";
        return handle.sparqlQuery().select(query)
                .map(row -> new TermRef(literalOf(row, "termId").getLexicalForm()))
                .toList();
    }

    /** Bulk variant of {@link #readUsesTerms}: all requirements' term identities in one query. */
    private Map<String, List<TermRef>> readUsesTermsBySubject(DatasetHandle handle) {
        String query = "SELECT ?s ?termId WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { ?s <" + USES_TERM_PROPERTY + "> ?term } "
                + "GRAPH <" + TERMS_GRAPH + "> { ?term <" + IDENTIFIER_PROPERTY + "> ?termId } } "
                + "ORDER BY ?s ?termId";
        Map<String, List<TermRef>> bySubject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(new TermRef(literalOf(row, "termId").getLexicalForm())));
        return bySubject;
    }

    // ---- strict reference resolution ---------------------------------------------------

    private IRI resolveTerm(DatasetHandle handle, WorkspaceId workspaceId, TermRef term) {
        String query = "SELECT ?term WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "?term a <" + CONCEPT_TYPE + "> ; "
                + "<" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(term.termId()) + "\" } }";
        List<IRI> matches = handle.sparqlQuery().select(query)
                .map(row -> iriOf(row, "term"))
                .distinct()
                .toList();
        if (matches.isEmpty()) {
            throw new UnresolvedReferenceException("Term '" + term.termId()
                    + "' does not exist in workspace '" + workspaceId.value()
                    + "'. Create it first with term_add before a requirement uses it.");
        }
        if (matches.size() > 1) {
            throw new UnresolvedReferenceException("Term identity '" + term.termId()
                    + "' is ambiguous in workspace '" + workspaceId.value() + "' (" + matches.size()
                    + " matches). Reference a term by its unique dcterms:identifier.");
        }
        return matches.get(0);
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
}
