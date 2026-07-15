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

import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Out-adapter: {@link RequirementRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF dataset).
 *
 * <p>Maps a {@link Requirement} to a fixed subject IRI
 * ({@code https://w3id.org/arknet/model/requirement/<id>}), stored in one named
 * graph shared by all requirements: five mandatory triples (identifier, type, title,
 * description, status) plus up to three optional triples for {@code priority},
 * {@code motivatedBy} and {@code qualityCategory} - written only when the corresponding
 * field is non-{@code null} and read back via {@code OPTIONAL} SPARQL clauses so that
 * requirements without them still match. This class depends only on the neutral
 * kognio-rdf ports ({@code terms} + {@code dataset}) and {@link SimpleRdf} - it
 * never imports RDF4J or any other backend-specific type. The backend
 * ({@link DatasetLifecycle} implementation) is supplied by the composition
 * root.</p>
 *
 * <p><strong>WorkspaceId (local, single-user).</strong> Each {@link WorkspaceId}
 * is mapped 1:1 to a kognio-rdf {@link DatasetId}, so distinct workspaces are
 * fully isolated datasets. A future remote/team adapter (against kognio-memory)
 * would use the same routing key differently (e.g. as a server-side project
 * selector), but the local embedded adapter already keeps workspaces separate.</p>
 *
 * <p><strong>Strict cross-BC term resolution (issue #36).</strong> Requirements and
 * ubiquitous-language terms share one per-workspace store. On
 * {@link #save(WorkspaceId, Requirement)} every {@link TermRef} is resolved against that
 * store <em>before</em> anything is written: the term identity (e.g. {@code TERM-1}) is
 * looked up by {@code dcterms:identifier} among the {@code skos:Concept}s of the glossary.
 * An unknown or ambiguous identity aborts the write with a didactic
 * {@link UnresolvedReferenceException}; no dangling {@code arkreq:usesTerm} edge is ever
 * persisted. Resolution goes via the identifier, never the {@code skos:prefLabel}, so the
 * edge survives relabelling a term.</p>
 *
 * <p><strong>SHACL write-gate.</strong> Every {@link #save(WorkspaceId, Requirement)} call
 * validates the candidate instance graph against the requirements SHACL shapes via
 * {@link ShaclWriteGate} before starting the write transaction; a violation throws
 * {@link WriteConstraintViolationException} and nothing is persisted. The gate itself is
 * technology-neutral - only {@link KognioRdfRequirementRepositoryFactory} names RDF4J.</p>
 */
public class KognioRdfRequirementRepository implements RequirementRepository {

    private static final String ARKREQ_NAMESPACE = "https://w3id.org/arknet/requirements#";
    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    private static final String REQUIREMENT_INSTANCE_NAMESPACE = "https://w3id.org/arknet/model/requirement/";
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
    public void save(WorkspaceId workspaceId, Requirement requirement) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(requirement, "requirement");

        IRI subjectIri = rdf.createIRI(requirementIri(requirement.id()));

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            // 1. Resolve every term reference strictly against the shared workspace store.
            List<IRI> termIris = requirement.usesTerms().stream()
                    .map(term -> resolveTerm(handle, workspaceId, term))
                    .toList();

            // 2. Build the candidate graph.
            Graph graph = rdf.createGraph();
            graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(typeIriFor(requirement.type())));
            graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(requirement.id().value()));
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

            // 3. Structural gate, then replace-by-identity. The usesTerm shape carries an
            //    sh:class skos:Concept constraint, but the type triples of the referenced
            //    terms live in the sibling terms graph, not in this candidate graph, so they
            //    are supplied to the validation graph ONLY (never persisted here). This is
            //    safe: the strict lookup above already proved each term exists and is a
            //    concept - the lookup, not the shape, is what keeps the edge non-dangling.
            Graph validationGraph = rdf.createGraph();
            graph.stream().forEach(validationGraph::add);
            for (IRI termIri : termIris) {
                validationGraph.add(termIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
            }
            gate.enforce(validationGraph);

            String deleteExisting = "DELETE WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { <"
                    + subjectIri.getIRIString() + "> ?p ?o } }";
            IRI graphIri = rdf.createIRI(REQUIREMENTS_GRAPH);

            handle.transactor().inTransaction(tx -> {
                tx.update(deleteExisting);
                tx.add(graphIri, graph);
                return null;
            });
        }
    }

    @Override
    public Optional<Requirement> findById(WorkspaceId workspaceId, RequirementId id) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(id, "id");

        String subject = "<" + requirementIri(id) + ">";
        String query = "SELECT ?type ?title ?description ?status ?priority ?motivatedBy ?qualityCategory WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + subject + " a ?type ; "
                + "<" + TITLE_PROPERTY + "> ?title ; "
                + "<" + DESCRIPTION_PROPERTY + "> ?description ; "
                + "<" + STATUS_PROPERTY + "> ?status . "
                + "OPTIONAL { " + subject + " <" + PRIORITY_PROPERTY + "> ?priority } "
                + "OPTIONAL { " + subject + " <" + MOTIVATED_BY_PROPERTY + "> ?motivatedBy } "
                + "OPTIONAL { " + subject + " <" + QUALITY_CATEGORY_PROPERTY + "> ?qualityCategory } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            Optional<BindingSet> head = handle.sparqlQuery().select(query).findFirst();
            if (head.isEmpty()) {
                return Optional.empty();
            }
            BindingSet row = head.get();
            return Optional.of(new Requirement(
                    id,
                    literalOf(row, "title").getLexicalForm(),
                    literalOf(row, "description").getLexicalForm(),
                    typeFromIri(iriOf(row, "type").getIRIString()),
                    statusFromIri(iriOf(row, "status").getIRIString()),
                    priorityOf(row),
                    motivatedByOf(row),
                    qualityCategoryOf(row),
                    readUsesTerms(handle, subject)));
        }
    }

    @Override
    public List<Requirement> findAll(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");

        String query = "SELECT ?identifier ?title ?description ?type ?status ?priority ?motivatedBy "
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
            Map<String, List<TermRef>> termsByRequirement = readUsesTermsByRequirement(handle);
            return handle.sparqlQuery().select(query)
                    .map(row -> {
                        String identifier = literalOf(row, "identifier").getLexicalForm();
                        return new Requirement(
                                new RequirementId(identifier),
                                literalOf(row, "title").getLexicalForm(),
                                literalOf(row, "description").getLexicalForm(),
                                typeFromIri(iriOf(row, "type").getIRIString()),
                                statusFromIri(iriOf(row, "status").getIRIString()),
                                priorityOf(row),
                                motivatedByOf(row),
                                qualityCategoryOf(row),
                                termsByRequirement.getOrDefault(identifier, List.of()));
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
     * out here. {@link #save} then rewrites the requirement from the record it was handed, so
     * the next read-modify-write ({@code req_set_status}, {@code req_link_term}) erases the
     * dropped edge from the store for good. Every edge written through {@link #resolveTerm}
     * is joinable by construction, so this cannot bite via the MCP tools; an edge that entered
     * the requirements graph some other way (store-first, ADR-005) can be lost. See issue #63.</p>
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
    private Map<String, List<TermRef>> readUsesTermsByRequirement(DatasetHandle handle) {
        String query = "SELECT ?identifier ?termId WHERE { "
                + "GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier ; <" + USES_TERM_PROPERTY + "> ?term } "
                + "GRAPH <" + TERMS_GRAPH + "> { ?term <" + IDENTIFIER_PROPERTY + "> ?termId } } "
                + "ORDER BY ?identifier ?termId";
        Map<String, List<TermRef>> byRequirement = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> byRequirement
                .computeIfAbsent(literalOf(row, "identifier").getLexicalForm(), key -> new ArrayList<>())
                .add(new TermRef(literalOf(row, "termId").getLexicalForm())));
        return byRequirement;
    }

    // ---- strict reference resolution ---------------------------------------------------

    private IRI resolveTerm(DatasetHandle handle, WorkspaceId workspaceId, TermRef term) {
        String query = "SELECT ?term WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "?term a <" + CONCEPT_TYPE + "> ; "
                + "<" + IDENTIFIER_PROPERTY + "> \"" + escape(term.termId()) + "\" } }";
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

    private static String escape(String literal) {
        return literal.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static String requirementIri(RequirementId id) {
        return REQUIREMENT_INSTANCE_NAMESPACE + id.value();
    }

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
