package de.hauschel.arknet.req.adapter.kogniordf;

import java.util.List;
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

import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
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
 * <p><strong>SHACL write-gate (deferred, "Weg 2b").</strong> Validation on write
 * is intentionally NOT wired here. kognio-rdf does not yet expose a standalone,
 * technology-neutral ShaclValidation port (tracked as kogn-io/rdf-core#3). Until
 * that is released we do not depend on {@code rdf4j-shacl} directly, to avoid
 * leaking RDF4J into arknet. See {@link #enforceWriteConstraints(Requirement)}.</p>
 */
public class KognioRdfRequirementRepository implements RequirementRepository {

    private static final String ARKREQ_NAMESPACE = "https://w3id.org/arknet/requirements#";
    private static final String REQUIREMENT_INSTANCE_NAMESPACE = "https://w3id.org/arknet/model/requirement/";
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";

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
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from (must not be
     *                  {@code null})
     */
    public KognioRdfRequirementRepository(DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public void save(WorkspaceId workspaceId, Requirement requirement) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(requirement, "requirement");
        enforceWriteConstraints(requirement);

        IRI subjectIri = rdf.createIRI(requirementIri(requirement.id()));
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

        String deleteExisting = "DELETE WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { <"
                + subjectIri.getIRIString() + "> ?p ?o } }";
        IRI graphIri = rdf.createIRI(REQUIREMENTS_GRAPH);

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
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
            return handle.sparqlQuery().select(query)
                    .findFirst()
                    .map(row -> new Requirement(
                            id,
                            literalOf(row, "title").getLexicalForm(),
                            literalOf(row, "description").getLexicalForm(),
                            typeFromIri(iriOf(row, "type").getIRIString()),
                            statusFromIri(iriOf(row, "status").getIRIString()),
                            priorityOf(row),
                            motivatedByOf(row),
                            qualityCategoryOf(row)));
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
                + "?s <" + VocabDct.IDENTIFIER.getIRIString() + "> ?identifier . "
                + "?s <" + TITLE_PROPERTY + "> ?title . "
                + "?s <" + DESCRIPTION_PROPERTY + "> ?description . "
                + "?s <" + STATUS_PROPERTY + "> ?status . "
                + "OPTIONAL { ?s <" + PRIORITY_PROPERTY + "> ?priority } "
                + "OPTIONAL { ?s <" + MOTIVATED_BY_PROPERTY + "> ?motivatedBy } "
                + "OPTIONAL { ?s <" + QUALITY_CATEGORY_PROPERTY + "> ?qualityCategory } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> new Requirement(
                            new RequirementId(literalOf(row, "identifier").getLexicalForm()),
                            literalOf(row, "title").getLexicalForm(),
                            literalOf(row, "description").getLexicalForm(),
                            typeFromIri(iriOf(row, "type").getIRIString()),
                            statusFromIri(iriOf(row, "status").getIRIString()),
                            priorityOf(row),
                            motivatedByOf(row),
                            qualityCategoryOf(row)))
                    .toList();
        }
    }

    /**
     * SHACL write-gate stub (MVP: no-op).
     *
     * <p>TODO(kogn-io/rdf-core#3): SHACL write-gate scharf schalten, sobald der standalone
     * ShaclValidation-Port + RDFS-Reasoning-Option verfuegbar ist. Bis dahin bewusst kein
     * Gate ("Weg 2b").</p>
     *
     * @param requirement the requirement about to be persisted
     */
    private void enforceWriteConstraints(Requirement requirement) {
        Objects.requireNonNull(requirement, "requirement");
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
