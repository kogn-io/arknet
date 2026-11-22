// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.versioning.EntryKind;
import io.kogn.rdf.versioning.HistoryEntry;
import io.kogn.rdf.versioning.PublishState;
import io.kogn.rdf.versioning.ResourceVersioning;
import io.kogn.rdf.versioning.ResourceVersioningNamedGraphs;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * The single generic read path into a project dataset: one {@code SELECT ?s ?p ?o} over the
 * project's {@link ContentGraph content graph} on the kognio-rdf substrate.
 *
 * <p>Domain-agnostic by construction - it never mentions a requirement, term or any type,
 * so it feeds the store report for every bounded context alike. It is also the only class on
 * this model read path that touches the kognio-rdf dataset API ({@code StoreExporter} touches
 * it too, but for the backup/export path, not for reading the model); every result term is
 * mapped onto the neutral {@link Triple} / {@link RdfNode} model so the snapshot and renderers
 * stay free of backend types. This class depends solely on the technology-neutral kognio-rdf
 * ports, never on RDF4J.</p>
 *
 * <p><strong>Only the content graph is read.</strong> Every model resource of a project lives in
 * its one content graph, the draft view of the library versioning (ADR-61, ADR-63). Every other
 * graph of the dataset is machinery, not model, and stays invisible here: the published view and
 * the history the library keeps, the retained codes of deleted resources and the project's
 * self-description (listed in {@link InfrastructureGraphs}). Naming the content graph in every query is
 * what keeps a published copy of a resource from showing up twice.</p>
 *
 * <p><strong>{@link #history} is the one deliberate exception</strong>: it reads the library's
 * history of one resource - the publish entries and tombstones, never a draft.</p>
 */
public final class StoreReader {

    private static final String DCTERMS_IDENTIFIER = "http://purl.org/dc/terms/identifier";


    private final DatasetLifecycle lifecycle;
    private final ResourceVersioning versioning =
            new ResourceVersioningNamedGraphs(new SimpleRdf().createIRI(ContentGraph.GRAPH_IRI));

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
        String query = "SELECT DISTINCT ?s ?p ?o WHERE { " + inContentGraph("?s ?p ?o") + " }";
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
                + inContentGraph(iriRef + " ?p ?o") + " }";
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
                + inContentGraph("?s ?p " + iriRef) + " }";
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
     * this, such a subject was silently dropped: {@code DigestRenderer} still printed its
     * identifier as a drill-down handle, but resolving that handle here found nothing.
     *
     * <p>Scoped to the content graph like every other read here: the retained code of a deleted
     * resource and a published copy both carry the identifier too, and neither is a resource
     * to resolve.</p>
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
        String query = "SELECT DISTINCT ?s WHERE { " + inContentGraph("?s <" + DCTERMS_IDENTIFIER + "> " + literal)
                + " }";
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
     * Finds every statement whose literal object contains {@code needle}, case-insensitively,
     * regardless of language tag or datatype - the free-text search behind {@code text_search}
     * (kogn-io/arknet#594 Part 1). {@code impact_analysis} answers "what references this
     * resource" over a fixed set of edges; this answers "where does this text occur" over every
     * literal the project holds, a question the edge-only tools cannot answer at all.
     *
     * <p><strong>{@code needle} is bound, never concatenated.</strong> Unlike {@link
     * #findByIdentifier}, which validates a caller-typed handle against the narrow {@code
     * dcterms:identifier} shape before splicing it into the query text, this method's input is
     * unconstrained free text an agent chooses - so the match itself runs as {@code
     * FILTER(CONTAINS(LCASE(STR(?o)), LCASE(?q)))} with {@code ?q} substituted through {@link
     * io.kogn.rdf.dataset.SparqlQuery#select(String, Map)}'s pre-bound-variable overload, the
     * same discipline that overload's own Javadoc recommends for exactly this case.</p>
     *
     * @param projectId the project to search
     * @param needle    the substring to search for; must not be blank (an empty needle would
     *                  match every literal in the project, which answers no question a caller
     *                  could have meant to ask)
     * @return every matching statement (subject, predicate, literal object), in no particular
     *         order
     * @throws IllegalArgumentException if {@code needle} is blank
     */
    public List<Triple> literalContaining(ProjectId projectId, String needle) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(needle, "needle");
        if (needle.isBlank()) {
            throw new IllegalArgumentException("needle must not be blank");
        }
        String pattern = "?s ?p ?o . FILTER(isLiteral(?o) && CONTAINS(LCASE(STR(?o)), LCASE(?q)))";
        String query = "SELECT DISTINCT ?s ?p ?o WHERE { " + inContentGraph(pattern) + " }";
        Map<String, RDFTerm> bindings = Map.of("q", new SimpleRdf().createLiteral(needle));
        try (DatasetHandle handle = acquire(projectId)) {
            return handle.sparqlQuery().select(query, bindings)
                    .map(StoreReader::toTriple)
                    .filter(Optional::isPresent)
                    .map(Optional::get)
                    .toList();
        }
    }

    /**
     * The language tags each field of one resource carries - the store side of the
     * stale-translation signal every {@code *_update} tool appends after a single-language write
     * (kogn-io/arknet#474; contract and key vocabulary in {@link
     * de.hauschel.arknet.mcpsupport.FieldLanguageLookup}).
     *
     * <p><strong>Addressed by {@link DatasetId}, not by {@link ProjectId}</strong>, unlike every
     * other read here: {@code project_update} needs the very same answer for a project's registry
     * record, which lives in the reserved system dataset - an id {@link ProjectId} refuses to hold
     * by construction. Rather than a second, near-identical query method, the one method takes the
     * dataset directly and its callers name which one they mean.</p>
     *
     * <p><strong>One query, two hops.</strong> The second hop reaches the owned child resources
     * whose text is as multilingual as the parent's own fields - an acceptance criterion, a
     * use-case step, an ADR consequence - and pools them under the local name of the edge that
     * owns them, because a caller writes such a list wholesale. It is deliberately not restricted
     * to owned children: an edge to a neighbouring first-class resource (a linked term, an
     * addressed requirement) is followed too, and that neighbour's tags land under that edge's
     * name. Telling the two apart would take type knowledge this read path does not have and does
     * not want; the extra keys are inert, because a caller asks only for the fields it wrote and
     * never writes a neighbour through this tool.</p>
     *
     * <p><strong>One graph.</strong> A project dataset is read in its content graph, the
     * reserved system dataset in its registry graph - a published copy of a resource can carry an
     * older set of languages than its draft, and the draft is what a write just changed.</p>
     *
     * @param dataset    the dataset holding the resource
     * @param identifier the resource's {@code dcterms:identifier} lexical value (e.g. {@code FR-1},
     *                   or a project label for a registry record)
     * @return field key (predicate local name, or owning-edge local name for a child's fields) to
     *         the language tags found for it; empty for an identifier nothing carries
     */
    public Map<String, Set<String>> languageTagsByField(DatasetId dataset, String identifier) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(identifier, "identifier");
        String literal = "\"" + SparqlTerms.escape(identifier) + "\"";
        String graph = ProjectId.RESERVED_SYSTEM_DATASET.equals(dataset.value())
                ? ArkprjVocabulary.REGISTRY_GRAPH
                : ContentGraph.GRAPH_IRI;
        String query = "SELECT DISTINCT ?p ?lang WHERE { GRAPH <" + graph + "> { "
                + "?s <" + DCTERMS_IDENTIFIER + "> " + literal + " . "
                + "{ ?s ?p ?o . BIND(lang(?o) AS ?lang) FILTER(BOUND(?lang) && ?lang != \"\") } "
                + "UNION "
                + "{ ?s ?p ?child . ?child ?cp ?o . BIND(lang(?o) AS ?lang) "
                + "FILTER(BOUND(?lang) && ?lang != \"\") } } }";
        Map<String, Set<String>> byField = new LinkedHashMap<>();
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            handle.sparqlQuery().select(query).forEach(row -> {
                RDFTerm predicate = row.getValue("p").orElse(null);
                RDFTerm language = row.getValue("lang").orElse(null);
                if (predicate instanceof IRI predicateIri && language instanceof Literal languageLiteral) {
                    byField.computeIfAbsent(StoreResource.localName(predicateIri.getIRIString()),
                            key -> new LinkedHashSet<>()).add(languageLiteral.getLexicalForm());
                }
            });
        }
        return byField;
    }

    /**
     * Reads a resource's history: the library's publish entries and tombstones for it, oldest
     * first. Every write through the tools publishes, so only a resource written store-first has
     * no entry, and an empty list is not an error; telling that apart from "no such resource" is the
     * caller's job. A blank-node handle (see {@link #isBlankNodeReference(String)}) yields an empty
     * list: only a subject's own IRI is versioned.
     *
     * @param projectId the project to read
     * @param iri       the resource's IRI, exactly as {@link HandleResolver} resolves it
     * @return the history entries, oldest first
     */
    public List<VersionEntry> history(ProjectId projectId, String iri) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(iri, "iri");
        if (isBlankNodeReference(iri)) {
            return List.of();
        }
        IRI subject = new SimpleRdf().createIRI(iri);
        try (DatasetHandle handle = acquire(projectId)) {
            return ReadTransactions.read(handle, tx -> versioning.history(tx, subject)).stream()
                    .map(StoreReader::toVersionEntry)
                    .toList();
        }
    }

    /**
     * Tells whether a resource's draft was ever published and whether it changed since. Every
     * write through the tools publishes, so a resource they wrote reads {@code PUBLISHED_UNCHANGED}
     * or {@code DELETED}; the other two states remain for a store-first edit.
     *
     * @param projectId the project to read
     * @param iri       the resource's IRI, exactly as {@link HandleResolver} resolves it
     * @return the publish state's name ({@code NEVER_PUBLISHED}, {@code PUBLISHED_UNCHANGED},
     *         {@code PUBLISHED_WITH_UNPUBLISHED_CHANGES} or {@code DELETED}); {@code NEVER_PUBLISHED}
     *         for a blank-node handle
     */
    public String publishState(ProjectId projectId, String iri) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(iri, "iri");
        if (isBlankNodeReference(iri)) {
            return PublishState.NEVER_PUBLISHED.name();
        }
        IRI subject = new SimpleRdf().createIRI(iri);
        try (DatasetHandle handle = acquire(projectId)) {
            return ReadTransactions.read(handle, tx -> versioning.publishState(tx, subject)).name();
        }
    }

    private static VersionEntry toVersionEntry(HistoryEntry entry) {
        return new VersionEntry(entry.iri().getIRIString(), entry.sequence(),
                entry.kind() == EntryKind.TOMBSTONE, entry.created().toString(),
                entry.cid().map(IRI::getIRIString).orElse(null), entry.comment().orElse(null));
    }

    /** Acquires the project's dataset - the one line every read method here shares. */
    private DatasetHandle acquire(ProjectId projectId) {
        return lifecycle.acquire(new DatasetId(projectId.value()));
    }

    /**
     * Wraps a triple pattern into the content graph - the one helper every model read here shares,
     * so no read can forget to name its graph.
     *
     * @param pattern a triple pattern
     * @return the pattern inside {@code GRAPH <content graph>}
     */
    private static String inContentGraph(String pattern) {
        return "GRAPH " + ContentGraph.SPARQL_IRIREF + " { " + pattern + " }";
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
