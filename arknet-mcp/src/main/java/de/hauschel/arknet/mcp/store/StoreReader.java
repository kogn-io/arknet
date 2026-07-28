// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;

/**
 * The single generic read path into a workspace dataset: one {@code SELECT ?s ?p ?o} over
 * the kognio-rdf substrate, spanning the default and all named graphs.
 *
 * <p>Domain-agnostic by construction - it never mentions a requirement, term or any type,
 * so it feeds the store report for every bounded context alike. It is also the only store
 * class here that touches the kognio-rdf dataset API; every result term is mapped onto the
 * neutral {@link Triple} / {@link RdfNode} model so the snapshot and renderers stay free of
 * backend types. This class depends solely on the technology-neutral kognio-rdf ports, never
 * on RDF4J.</p>
 *
 * <p><strong>The infrastructure graphs are invisible here.</strong> Two named graphs inside a
 * project's dataset carry no model at all, and all three read methods exclude both through the
 * same filter, so this read path shows the model and never the machinery underneath it - an
 * infrastructure-graph exclusion, not domain knowledge:</p>
 *
 * <ul>
 *   <li>{@link ArkprovVocabulary#PROVENANCE_GRAPH} - every guarded write records a PROV-O
 *       revision there (ADR-014), so an unfiltered view would grow with the change history
 *       rather than with the model.</li>
 *   <li>{@link ArkprjVocabulary#IDENTITY_GRAPH} - the project's self-description (ADR-016
 *       decision 7): the anchors and label by which a client's call is routed to this dataset.
 *       Without the exclusion, every store report would open with its own routing record. The
 *       registry itself needs no exclusion, because it lives in a reserved dataset no ordinary
 *       call ever addresses; only the self-description sits inside the dataset being read.</li>
 * </ul>
 *
 * <p><strong>Why the head pointer is excluded too</strong>, even though exactly one
 * {@code arkprov:head} per resource would be bounded and cheap to show: the head only moves on
 * writes <em>through the write funnel</em>, and four user-reachable write paths still bypass it
 * ({@code req_update}, {@code req_set_status}, {@code req_link_term}, {@code term_update}; see
 * {@code WriteFunnel} and ADR-014 decision 4). A head rendered by {@code resource_get} would
 * therefore stand still while the resource changes, and a client reading it as a version or
 * change signal - which is what {@code arkprov:head} means - would be misled. The head becomes
 * visible again when it stops lying, i.e. once those paths are resolved into the funnel; until
 * then the trail accumulates in the store without any generic reader surfacing it.</p>
 */
public final class StoreReader {

    private static final String DCTERMS_IDENTIFIER = "http://purl.org/dc/terms/identifier";

    /**
     * The named graphs this read path hides, as SPARQL {@code IRIREF}s - see the class javadoc for
     * why each one is infrastructure rather than model. Adding a graph here is all it takes to hide
     * it from {@link #readSnapshot}, {@link #outgoing} and {@link #incoming} at once, which is the
     * point of the list: the three cannot drift apart.
     */
    private static final List<String> HIDDEN_GRAPHS = List.of(
            "<" + ArkprovVocabulary.PROVENANCE_GRAPH + ">",
            "<" + ArkprjVocabulary.IDENTITY_GRAPH + ">");

    private final DatasetLifecycle lifecycle;

    /**
     * @param lifecycle the shared kognio-rdf dataset lifecycle (must not be {@code null})
     */
    public StoreReader(DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /**
     * Reads every statement of the workspace and assembles a {@link StoreSnapshot}.
     *
     * @param projectId the workspace to read
     * @return the snapshot over all statements
     */
    public StoreSnapshot readSnapshot(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        String query = "SELECT DISTINCT ?s ?p ?o WHERE { " + excludingInfrastructure("?s ?p ?o") + " }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<Triple> triples = handle.sparqlQuery().select(query)
                    .map(StoreReader::toTriple)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
            return StoreSnapshot.of(triples);
        }
    }

    /**
     * Reads the outgoing statements of a resource ({@code <iri> ?p ?o}).
     *
     * @param projectId the workspace to read
     * @param iri         the subject IRI
     * @return the outgoing statements
     */
    public List<Triple> outgoing(ProjectId projectId, String iri) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(iri, "iri");
        String iriRef = SparqlTerms.iriRef(iri);
        String query = "SELECT DISTINCT ?p ?o WHERE { "
                + excludingInfrastructure(iriRef + " ?p ?o") + " }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> outgoingTriple(iri, row))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        }
    }

    /**
     * Reads the incoming statements of a resource ({@code ?s ?p <iri>}) - its neighbours.
     *
     * @param projectId the workspace to read
     * @param iri         the object IRI
     * @return the incoming statements (their object is always {@code iri})
     */
    public List<Triple> incoming(ProjectId projectId, String iri) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(iri, "iri");
        String iriRef = SparqlTerms.iriRef(iri);
        String query = "SELECT DISTINCT ?s ?p WHERE { "
                + excludingInfrastructure("?s ?p " + iriRef) + " }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> incomingTriple(iri, row))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        }
    }

    /**
     * Resolves subjects carrying a given {@code dcterms:identifier} literal (the bare
     * business id, e.g. {@code FR-1}). Returns all matches so the caller can reject an
     * ambiguous id spanning bounded contexts instead of guessing.
     *
     * @param projectId the workspace to read
     * @param identifier  the {@code dcterms:identifier} lexical value
     * @return the matching subject IRIs (distinct)
     */
    public List<String> findByIdentifier(ProjectId projectId, String identifier) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(identifier, "identifier");
        String literal = "\"" + identifier.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String query = "SELECT DISTINCT ?s WHERE { ?s <" + DCTERMS_IDENTIFIER + "> " + literal + " }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> row.getValue("s").orElse(null))
                    .filter(IRI.class::isInstance)
                    .map(term -> ((IRI) term).getIRIString())
                    .distinct()
                    .toList();
        }
    }

    /**
     * Wraps a triple pattern into the infrastructure-graph exclusion every read path here shares -
     * one helper rather than three hand-written filters, so the exclusion cannot drift apart
     * between snapshot and neighbour lists.
     *
     * <p>Two branches, because the plain pattern may already span every context on some backends
     * (see the {@code DISTINCT} note in {@code StoreReaderTest}): the {@code GRAPH} branch is
     * guarded by graph IRI, the plain branch by "this triple lives <em>only</em> in hidden
     * graphs". A triple that also exists in a model graph therefore survives via the
     * {@code GRAPH} branch, and {@code DISTINCT} collapses the overlap.</p>
     *
     * @param pattern a triple pattern; must bind neither {@code ?g} nor anything named like it
     * @return the pattern as a {@code UNION} group excluding every {@link #HIDDEN_GRAPHS} entry
     */
    private static String excludingInfrastructure(String pattern) {
        String notInAnyHiddenGraph = HIDDEN_GRAPHS.stream()
                .map(graph -> "FILTER NOT EXISTS { GRAPH " + graph + " { " + pattern + " } } ")
                .collect(Collectors.joining());
        String outsideEveryHiddenGraph = HIDDEN_GRAPHS.stream()
                .map(graph -> "?g != " + graph)
                .collect(Collectors.joining(" && "));
        return "{ " + pattern + " " + notInAnyHiddenGraph + "} "
                + "UNION { GRAPH ?g { " + pattern + " } FILTER(" + outsideEveryHiddenGraph + ") }";
    }

    private static Optional<Triple> toTriple(BindingSet row) {
        RDFTerm subject = row.getValue("s").orElse(null);
        RDFTerm predicate = row.getValue("p").orElse(null);
        RDFTerm object = row.getValue("o").orElse(null);
        if (!(subject instanceof IRI subjectIri) || !(predicate instanceof IRI predicateIri) || object == null) {
            return Optional.empty();
        }
        return Optional.of(new Triple(subjectIri.getIRIString(), predicateIri.getIRIString(), toNode(object)));
    }

    private static Optional<Triple> outgoingTriple(String subject, BindingSet row) {
        RDFTerm predicate = row.getValue("p").orElse(null);
        RDFTerm object = row.getValue("o").orElse(null);
        if (!(predicate instanceof IRI predicateIri) || object == null) {
            return Optional.empty();
        }
        return Optional.of(new Triple(subject, predicateIri.getIRIString(), toNode(object)));
    }

    private static Optional<Triple> incomingTriple(String object, BindingSet row) {
        RDFTerm subject = row.getValue("s").orElse(null);
        RDFTerm predicate = row.getValue("p").orElse(null);
        if (!(subject instanceof IRI subjectIri) || !(predicate instanceof IRI predicateIri)) {
            return Optional.empty();
        }
        return Optional.of(new Triple(subjectIri.getIRIString(), predicateIri.getIRIString(),
                new RdfNode.Resource(object)));
    }

    private static RdfNode toNode(RDFTerm term) {
        if (term instanceof IRI iri) {
            return new RdfNode.Resource(iri.getIRIString());
        }
        if (term instanceof BlankNode blankNode) {
            return new RdfNode.Resource("_:" + blankNode.uniqueReference());
        }
        if (term instanceof Literal literal) {
            String datatype = literal.getDatatype() == null ? null : literal.getDatatype().getIRIString();
            return new RdfNode.Literal(literal.getLexicalForm(), datatype,
                    literal.getLanguageTag().orElse(null));
        }
        throw new IllegalStateException("unexpected RDF term: " + term);
    }
}
