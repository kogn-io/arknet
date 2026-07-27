// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;

import de.hauschel.arknet.kernel.WorkspaceId;
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
 * <p><strong>The provenance graph is invisible here.</strong> Every guarded write also records
 * a PROV-O revision into {@link ArkprovVocabulary#PROVENANCE_GRAPH} (ADR-014). All three read
 * methods exclude that graph with the same filter, so this read path shows the model and never
 * its change history - an infrastructure-graph exclusion, not domain knowledge.</p>
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

    private static final String PROVENANCE_GRAPH = "<" + ArkprovVocabulary.PROVENANCE_GRAPH + ">";

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
     * @param workspaceId the workspace to read
     * @return the snapshot over all statements
     */
    public StoreSnapshot readSnapshot(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        String query = "SELECT DISTINCT ?s ?p ?o WHERE { " + excludingProvenance("?s ?p ?o") + " }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
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
     * @param workspaceId the workspace to read
     * @param iri         the subject IRI
     * @return the outgoing statements
     */
    public List<Triple> outgoing(WorkspaceId workspaceId, String iri) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(iri, "iri");
        String iriRef = SparqlTerms.iriRef(iri);
        String query = "SELECT DISTINCT ?p ?o WHERE { "
                + excludingProvenance(iriRef + " ?p ?o") + " }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
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
     * @param workspaceId the workspace to read
     * @param iri         the object IRI
     * @return the incoming statements (their object is always {@code iri})
     */
    public List<Triple> incoming(WorkspaceId workspaceId, String iri) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(iri, "iri");
        String iriRef = SparqlTerms.iriRef(iri);
        String query = "SELECT DISTINCT ?s ?p WHERE { "
                + excludingProvenance("?s ?p " + iriRef) + " }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
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
     * @param workspaceId the workspace to read
     * @param identifier  the {@code dcterms:identifier} lexical value
     * @return the matching subject IRIs (distinct)
     */
    public List<String> findByIdentifier(WorkspaceId workspaceId, String identifier) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(identifier, "identifier");
        String literal = "\"" + identifier.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        String query = "SELECT DISTINCT ?s WHERE { ?s <" + DCTERMS_IDENTIFIER + "> " + literal + " }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> row.getValue("s").orElse(null))
                    .filter(IRI.class::isInstance)
                    .map(term -> ((IRI) term).getIRIString())
                    .distinct()
                    .toList();
        }
    }

    /**
     * Wraps a triple pattern into the provenance-graph exclusion every read path here shares -
     * one helper rather than three hand-written filters, so the exclusion cannot drift apart
     * between snapshot and neighbour lists.
     *
     * <p>Two branches, because the plain pattern may already span every context on some backends
     * (see the {@code DISTINCT} note in {@code StoreReaderTest}): the {@code GRAPH} branch is
     * guarded by graph IRI, the plain branch by "this triple lives <em>only</em> in the
     * provenance graph". A triple that also exists in a model graph therefore survives via the
     * {@code GRAPH} branch, and {@code DISTINCT} collapses the overlap.</p>
     *
     * @param pattern a triple pattern; must bind neither {@code ?g} nor anything named like it
     * @return the pattern as a {@code UNION} group excluding the provenance graph
     */
    private static String excludingProvenance(String pattern) {
        return "{ " + pattern + " FILTER NOT EXISTS { GRAPH " + PROVENANCE_GRAPH + " { " + pattern + " } } } "
                + "UNION { GRAPH ?g { " + pattern + " } FILTER(?g != " + PROVENANCE_GRAPH + ") }";
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
