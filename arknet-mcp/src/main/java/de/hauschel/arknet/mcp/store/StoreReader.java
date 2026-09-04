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
 * The single generic read path into a project dataset: one {@code SELECT ?s ?p ?o} over
 * the kognio-rdf substrate, spanning the default and all named graphs.
 *
 * <p>Domain-agnostic by construction - it never mentions a requirement, term or any type,
 * so it feeds the store report for every bounded context alike. It is also the only class on
 * this model read path that touches the kognio-rdf dataset API ({@link StoreExporter} touches
 * it too, but for the backup/export path, not for reading the model); every result term is
 * mapped onto the neutral {@link Triple} / {@link RdfNode} model so the snapshot and renderers
 * stay free of backend types. This class depends solely on the technology-neutral kognio-rdf
 * ports, never on RDF4J.</p>
 *
 * <p><strong>The infrastructure graphs are invisible here.</strong> Two named graphs inside a
 * project's dataset carry no model at all, and all three read methods exclude both through the
 * same filter, so this read path shows the model and never the machinery underneath it - an
 * infrastructure-graph exclusion, not domain knowledge:</p>
 *
 * <ul>
 *   <li>{@link ArkprovVocabulary#PROVENANCE_GRAPH} - every guarded write records a PROV-O
 *       revision there, so an unfiltered view would grow with the change history
 *       rather than with the model.</li>
 *   <li>{@link ArkprjVocabulary#IDENTITY_GRAPH} - the project's self-description:
 *       the anchors and label by which a client's call is routed to this dataset.
 *       Without the exclusion, every store report would open with its own routing record. The
 *       registry itself needs no exclusion, because it lives in a reserved dataset no ordinary
 *       call ever addresses; only the self-description sits inside the dataset being read.</li>
 * </ul>
 *
 * <p><strong>Why the head pointer is excluded too</strong>, even though it is bounded (exactly
 * one {@code arkprov:head} per resource) and a usable concurrency
 * token now that every user-reachable write path moves it through {@code WriteFunnel
 * #compareAndUpdate}: showing it here would mix the model with its change history the moment a
 * client read it as a version or change signal. That trail belongs to {@link #history}
 * (issue #251), not to this read path - {@code store_overview}/{@code resource_get} stay blind
 * to {@link ArkprovVocabulary#PROVENANCE_GRAPH} on purpose, showing the model and never its
 * provenance.</p>
 *
 * <p><strong>{@link #history} is the one deliberate exception</strong> (issue #251): unlike
 * {@link #readSnapshot}/{@link #outgoing}/{@link #incoming}, it reads exactly
 * {@link ArkprovVocabulary#PROVENANCE_GRAPH} on purpose - showing the trail, not the model, is
 * its whole job. It marks a revision "current" by reading the resource's actual
 * {@code arkprov:head} triple rather than assuming the newest timestamp is current, so it never
 * asserts more than the store itself does about which revision a write path last moved the head
 * to. That head test is folded into the very same {@code SELECT} that lists the revisions (one
 * {@code EXISTS} per row) rather than run as a separate query beforehand: each {@code select()}
 * call opens and closes its own {@code RepositoryConnection}
 * ({@code io.kogn.rdf.rdf4j.dataset.SparqlQueryRdf4j}), so two sequential queries share no
 * snapshot - a write moving the head between them would make the second query's row list disagree
 * with the first query's now-stale head, mismarking the true current revision as historical (or
 * vice versa). A single query cannot straddle such a write.</p>
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
     * Reads every statement of the project and assembles a {@link StoreSnapshot}.
     *
     * @param projectId the project to read
     * @return the snapshot over all statements
     */
    public StoreSnapshot readSnapshot(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        String query = "SELECT DISTINCT ?s ?p ?o WHERE { " + excludingInfrastructure("?s ?p ?o") + " }";
        try (DatasetHandle handle = acquire(projectId)) {
            List<Triple> triples = handle.sparqlQuery().select(query)
                    .map(StoreReader::toTriple)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
            return StoreSnapshot.of(triples);
        }
    }

    /**
     * Reads the outgoing statements of a resource ({@code <iri> ?p ?o}, or, for a blank-node
     * handle, every statement {@link #readSnapshot} grouped under that exact reference - see
     * {@link #isBlankNodeReference(String)} for why a blank node cannot use the targeted query
     * the IRI case below does).
     *
     * @param projectId the project to read
     * @param iri         the subject IRI, or a blank-node reference as rendered by {@link #toNode}
     * @return the outgoing statements
     */
    public List<Triple> outgoing(ProjectId projectId, String iri) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(iri, "iri");
        if (isBlankNodeReference(iri)) {
            return readSnapshot(projectId).resources().stream()
                    .filter(resource -> resource.iri().equals(iri))
                    .findFirst()
                    .map(StoreResource::outgoing)
                    .orElse(List.of());
        }
        String iriRef = SparqlTerms.iriRef(iri);
        String query = "SELECT DISTINCT ?p ?o WHERE { "
                + excludingInfrastructure(iriRef + " ?p ?o") + " }";
        try (DatasetHandle handle = acquire(projectId)) {
            return handle.sparqlQuery().select(query)
                    .map(row -> outgoingTriple(iri, row))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        }
    }

    /**
     * Reads the incoming statements of a resource ({@code ?s ?p <iri>}) - its neighbours. For a
     * blank-node handle this instead filters {@link #readSnapshot}'s outgoing statements for the
     * ones whose object is that exact reference - see {@link #isBlankNodeReference(String)}.
     *
     * @param projectId the project to read
     * @param iri         the object IRI, or a blank-node reference as rendered by {@link #toNode}
     * @return the incoming statements (their object is always {@code iri})
     */
    public List<Triple> incoming(ProjectId projectId, String iri) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(iri, "iri");
        if (isBlankNodeReference(iri)) {
            return readSnapshot(projectId).resources().stream()
                    .flatMap(resource -> resource.outgoing().stream())
                    .filter(triple -> triple.object() instanceof RdfNode.Resource resourceObject
                            && resourceObject.iri().equals(iri))
                    .toList();
        }
        String iriRef = SparqlTerms.iriRef(iri);
        String query = "SELECT DISTINCT ?s ?p WHERE { "
                + excludingInfrastructure("?s ?p " + iriRef) + " }";
        try (DatasetHandle handle = acquire(projectId)) {
            return handle.sparqlQuery().select(query)
                    .map(row -> incomingTriple(iri, row))
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        }
    }

    /**
     * {@code true} if {@code handle} is a blank-node reference ({@code "_:" + label}) rather than
     * an absolute IRI - see {@link Triple#subject()}.
     *
     * <p>{@link #outgoing}/{@link #incoming} cannot address such a handle with a targeted
     * {@code <iri> ?p ?o}-style query the way they do for an absolute IRI: per the SPARQL
     * grammar, a blank-node label written into a query's graph pattern is a fresh, query-scoped
     * variable, not a reference to the store's blank node carrying that same label - the query
     * text simply cannot select "the blank node this label was rendered for". Filtering
     * {@link #readSnapshot}'s already-correct result for the exact reference string instead
     * relies on nothing more than the identity {@link #toNode} already renders consistently
     * (regression-tested by {@code StoreReaderTest}), never on the query engine resolving a
     * blank-node label back to a specific node.</p>
     */
    private static boolean isBlankNodeReference(String handle) {
        return handle.startsWith("_:");
    }

    /**
     * Resolves subjects carrying a given {@code dcterms:identifier} literal (the bare
     * business id, e.g. {@code FR-1}). Returns all matches so the caller can reject an
     * ambiguous id spanning bounded contexts instead of guessing.
     *
     * <p>A subject may be a blank node - a store-first resource with no minted IRI is
     * RDF-legal and does carry a {@code dcterms:identifier} like any other resource - so a match
     * is mapped through {@link #subjectReference} rather than filtered down to {@link IRI}
     * subjects only; a blank-node match comes back as the same {@code "_:" + reference} handle
     * {@link #toNode}/{@link #outgoing}/{@link #incoming} already use for one (issue #299). Before
     * this, such a subject was silently dropped: {@link DigestRenderer} still printed its
     * identifier as a drill-down handle, but resolving that handle here found nothing.
     *
     * <p>Deliberately not run through {@link #excludingInfrastructure} like the other three read
     * methods: neither the shared write funnel nor the project self-description currently write
     * {@code dcterms:identifier} into a {@link #HIDDEN_GRAPHS} graph, so the gap is harmless today
     * - but it is real, and this method would start returning ids that live in a hidden graph the
     * moment something starts writing one there.
     *
     * @param projectId the project to read
     * @param identifier  the {@code dcterms:identifier} lexical value
     * @return the matching subject references (distinct) - absolute IRIs, or blank-node
     *         references as rendered by {@link #toNode}
     */
    public List<String> findByIdentifier(ProjectId projectId, String identifier) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(identifier, "identifier");
        String literal = "\"" + SparqlTerms.escape(identifier) + "\"";
        String query = "SELECT DISTINCT ?s WHERE { ?s <" + DCTERMS_IDENTIFIER + "> " + literal + " }";
        try (DatasetHandle handle = acquire(projectId)) {
            return handle.sparqlQuery().select(query)
                    .map(row -> row.getValue("s").orElse(null))
                    .map(StoreReader::subjectReference)
                    .filter(Objects::nonNull)
                    .distinct()
                    .toList();
        }
    }

    /**
     * Reads a resource's change history (issue #251): every {@code arkprov:Revision} the shared
     * write funnel has recorded for it, oldest first, with the one matching
     * the resource's current {@code arkprov:head} marked {@link Revision#current}.
     *
     * <p>A resource the funnel has never written through - written entirely store-first,
     * or predating the funnel - has recorded no revision and yields an empty list,
     * not an error; distinguishing that from "no such resource" is the caller's job (as
     * {@code resource_get} already does for {@link #outgoing}/{@link #incoming} being empty).
     * A blank-node handle (see {@link #isBlankNodeReference(String)}) also yields an empty list,
     * checked explicitly up front rather than left to fall out of the query: the funnel only
     * ever records a revision under a subject's own opaque IRI, never a blank node, so no
     * revision can name one via {@code prov:specializationOf} - but a blank-node label is not a
     * forbidden {@code IRIREF} character (see {@link SparqlTerms#isValidIriReference}), so
     * without this guard the query below would silently run against the literal string
     * {@code "<_:label>"} instead of being rejected or handled like {@link #outgoing}/
     * {@link #incoming} do.</p>
     *
     * @param projectId the project to read
     * @param iri       the resource's IRI, exactly as {@link HandleResolver} resolves it
     * @return the revisions, oldest first
     */
    public List<Revision> history(ProjectId projectId, String iri) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(iri, "iri");
        if (isBlankNodeReference(iri)) {
            return List.of();
        }
        String iriRef = SparqlTerms.iriRef(iri);
        String query = "SELECT ?revision ?time (EXISTS { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + iriRef + " <" + ArkprovVocabulary.HEAD + "> ?revision } } AS ?isCurrent) WHERE { "
                + "GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?revision <" + ArkprovVocabulary.SPECIALIZATION_OF + "> " + iriRef + " . "
                + "?revision <" + ArkprovVocabulary.GENERATED_AT_TIME + "> ?time . "
                + "} } ORDER BY ?time";
        try (DatasetHandle handle = acquire(projectId)) {
            return handle.sparqlQuery().select(query)
                    .map(StoreReader::toRevision)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        }
    }

    private static Optional<Revision> toRevision(BindingSet row) {
        RDFTerm revision = row.getValue("revision").orElse(null);
        RDFTerm time = row.getValue("time").orElse(null);
        RDFTerm isCurrent = row.getValue("isCurrent").orElse(null);
        if (!(revision instanceof IRI revisionIri) || !(time instanceof Literal timeLiteral)
                || !(isCurrent instanceof Literal currentLiteral)) {
            return Optional.empty();
        }
        return Optional.of(new Revision(revisionIri.getIRIString(), timeLiteral.getLexicalForm(),
                Boolean.parseBoolean(currentLiteral.getLexicalForm())));
    }

    /** Acquires the project's dataset - the one line every read method here shares. */
    private DatasetHandle acquire(ProjectId projectId) {
        return lifecycle.acquire(new DatasetId(projectId.value()));
    }

    /**
     * Wraps a triple pattern into the infrastructure-graph exclusion every read path here shares -
     * one helper rather than three hand-written filters, so the exclusion cannot drift apart
     * between snapshot and neighbour lists.
     *
     * <p>Two branches, because the plain pattern may already span every context on some backends
     * (see the {@code DISTINCT} note in {@code StoreReaderTest}): the {@code GRAPH} branch is
     * guarded by graph IRI, the plain branch by "this triple lives <em>only</em> in hidden
     * graphs". A triple that also exists in a <em>named</em> model graph therefore survives via
     * the {@code GRAPH} branch, and {@code DISTINCT} collapses the overlap - a triple living only
     * in the default graph plus a hidden graph would not survive either branch, but every BC
     * adapter writes named graphs, so that case is constructed rather than reachable today.</p>
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
        String subjectRef = subjectReference(subject);
        if (subjectRef == null || !(predicate instanceof IRI predicateIri) || object == null) {
            return Optional.empty();
        }
        return Optional.of(new Triple(subjectRef, predicateIri.getIRIString(), toNode(object)));
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
        String subjectRef = subjectReference(subject);
        if (subjectRef == null || !(predicate instanceof IRI predicateIri)) {
            return Optional.empty();
        }
        return Optional.of(new Triple(subjectRef, predicateIri.getIRIString(), new RdfNode.Resource(object)));
    }

    /**
     * Resolves a subject term to the same reference {@link #toNode} would render for it - an
     * IRI string, or {@code "_:" + reference} for a blank node, which is RDF-legal in subject
     * position (e.g. a store-first SKOS concept with no minted IRI) and must surface here exactly
     * like it does in object position, instead of being silently dropped.
     *
     * @param term the subject term, or {@code null} if the row bound nothing
     * @return the reference, or {@code null} if the term is neither an IRI nor a blank node
     *         (a literal cannot appear as an RDF subject, but the row is still skipped rather
     *         than trusted)
     */
    private static String subjectReference(RDFTerm term) {
        if (term instanceof IRI || term instanceof BlankNode) {
            return ((RdfNode.Resource) toNode(term)).iri();
        }
        return null;
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
