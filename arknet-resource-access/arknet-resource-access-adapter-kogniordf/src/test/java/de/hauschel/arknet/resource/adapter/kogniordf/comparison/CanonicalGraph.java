// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.Triple;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.ResourceSchema.OwnedField;

/**
 * The content graph of one project with every project-specific identity replaced by a name both
 * projects of a pair share, so the graph the typed core wrote and the graph {@code submit} wrote
 * compare as plain sets.
 *
 * <p>The renaming knows no namespace of a bounded context:</p>
 * <ul>
 *   <li>a resource IRI becomes {@code code:<dcterms:identifier>} - the code counters run per
 *       project, so the same steps give the same codes;</li>
 *   <li>a child, reached from its parent over a field the schema marks as owned, becomes
 *       {@code <parent>#<childKind>@<position>}, the position read over the field's
 *       {@code arkres:orderedBy} - so a child whose identity differs between the two writers
 *       (a text corrected in place by the core, removed and added by a document) still compares
 *       by what it says;</li>
 *   <li>a resource of a type without a code prefix (a context relationship) becomes
 *       {@code <type>{<its outgoing edges, renamed and sorted>}~<n>}: its content is its name, and
 *       resources of identical content are numbered, never merged - so a duplicate shows as one
 *       more resource on one side. Its edges to another resource without a code stay raw IRIs and
 *       fail the comparison; no shipped type has such an edge;</li>
 *   <li>any other IRI that extends a resource IRI by a fragment becomes
 *       {@code <parent>#<fragment>}.</li>
 * </ul>
 *
 * <p>The renaming is checked to be one-to-one. Two resources with one code, two children at one
 * position, a child with no or several positions, or a child owned by several edges fail the graph
 * with an {@link AssertionError} naming the colliding IRIs: a set would otherwise merge them and
 * hide exactly the duplicates a comparison has to find.</p>
 *
 * <p>Literals compare exactly: lexical form, datatype and language tag. A blank node fails the
 * graph: no writer stores one, and a label differs between stores, so it could only compare by
 * being dropped - which this class never does.</p>
 */
public final class CanonicalGraph {

    /** {@code dcterms:identifier} - the business code of a resource; not a namespace of a context. */
    static final String IDENTIFIER = "http://purl.org/dc/terms/identifier";

    /** The prefix of a resource name taken from its code. */
    static final String CODE_SCHEME = "code:";
    private static final String BLANK_NODE_PREFIX = "_:";
    private static final char FRAGMENT = '#';
    private static final char POSITION = '@';

    private static final String TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String DUPLICATE = "~";

    private final Set<String> statements;
    private final Map<String, String> resources;

    private CanonicalGraph(final Set<String> statements, final Map<String, String> resources) {
        this.statements = Set.copyOf(statements);
        this.resources = Map.copyOf(resources);
    }

    /**
     * Renames the given content-graph triples of one project.
     *
     * @param triples every triple of the project's content graph
     * @param schema  the schema that names the owned fields, their position property and the
     *                resource types without a code
     * @return the canonical graph
     */
    static CanonicalGraph of(final List<Triple> triples, final ResourceSchema schema) {
        Map<String, String> names = resourceNames(triples);
        Map<String, String> resources = new TreeMap<>();
        names.forEach((iri, name) -> resources.put(name, iri));
        names.putAll(childNames(triples, schema, names));
        Map<String, String> uncoded = uncodedNames(triples, schema, names);
        uncoded.forEach((iri, name) -> resources.put(name, iri));
        names.putAll(uncoded);
        assertOneToOne(names);
        Set<String> statements = triples.stream()
                .map(triple -> render(triple, names))
                .collect(Collectors.toCollection(TreeSet::new));
        return new CanonicalGraph(statements, resources);
    }

    /**
     * The resources of this graph - not their children - by canonical name, with the IRI each has
     * in its project; what the history of a resource is read by.
     *
     * @return IRI by canonical name
     */
    Map<String, String> resources() {
        return resources;
    }

    /**
     * This graph with further statements about it, such as the history of its resources.
     *
     * @param more the statements to add, one canonical line each
     * @return the extended graph
     */
    CanonicalGraph plus(final Set<String> more) {
        Set<String> all = new TreeSet<>(statements);
        all.addAll(more);
        return new CanonicalGraph(all, resources);
    }

    /**
     * The statements of this graph, one canonical line each, sorted.
     *
     * @return the statements
     */
    public Set<String> statements() {
        return statements;
    }

    /**
     * This graph without the statements the predicate matches - the one way a listed deviation
     * normalises a side; never applied without one.
     *
     * @param dropped matches a canonical statement line to drop
     * @return the reduced graph
     */
    public CanonicalGraph without(final Predicate<String> dropped) {
        return new CanonicalGraph(statements.stream().filter(dropped.negate())
                .collect(Collectors.toCollection(TreeSet::new)), resources);
    }

    /**
     * What one graph holds and the other does not, both ways, for a failure message.
     *
     * @param other the graph compared against
     * @return the difference, one statement per line, prefixed {@code core-only} or {@code submit-only}
     */
    String differenceTo(final CanonicalGraph other) {
        StringBuilder difference = new StringBuilder();
        statements.stream().filter(line -> !other.statements.contains(line))
                .forEach(line -> difference.append("\n  core-only:   ").append(line));
        other.statements.stream().filter(line -> !statements.contains(line))
                .forEach(line -> difference.append("\n  submit-only: ").append(line));
        return difference.toString();
    }

    @Override
    public boolean equals(final Object other) {
        return other instanceof CanonicalGraph graph && statements.equals(graph.statements);
    }

    @Override
    public int hashCode() {
        return statements.hashCode();
    }

    @Override
    public String toString() {
        return String.join("\n", statements);
    }

    private static Map<String, String> resourceNames(final List<Triple> triples) {
        Map<String, String> names = new HashMap<>();
        triples.stream()
                .filter(triple -> IDENTIFIER.equals(triple.predicate()))
                .forEach(triple -> {
                    String name = CODE_SCHEME + ((RdfNode.Literal) triple.object()).lexicalForm();
                    String earlier = names.put(triple.subject(), name);
                    if (earlier != null && !earlier.equals(name)) {
                        throw new AssertionError(triple.subject() + " carries two codes: " + earlier + ", " + name);
                    }
                });
        return names;
    }

    private static Map<String, String> childNames(final List<Triple> triples, final ResourceSchema schema,
            final Map<String, String> resources) {
        SimpleRdf rdf = new SimpleRdf();
        Map<String, List<Triple>> owners = triples.stream()
                .filter(triple -> triple.object() instanceof RdfNode.Resource)
                .filter(triple -> schema.ownedField(rdf.createIRI(triple.predicate())).isPresent())
                .collect(Collectors.groupingBy(triple -> ((RdfNode.Resource) triple.object()).iri()));
        Map<String, String> children = new HashMap<>();
        owners.forEach((child, edges) -> {
            if (edges.size() != 1) {
                throw new AssertionError("child " + child + " is owned " + edges.size() + " times: " + edges);
            }
            Triple edge = edges.get(0);
            OwnedField owned = schema.ownedField(rdf.createIRI(edge.predicate())).orElseThrow();
            String parent = resources.getOrDefault(edge.subject(), edge.subject());
            String position = position(triples, child, owned.orderedBy().getIRIString());
            children.put(child, parent + FRAGMENT + owned.childKind() + POSITION + position);
        });
        return children;
    }

    private static String position(final List<Triple> triples, final String child, final String orderedBy) {
        List<String> positions = triples.stream()
                .filter(triple -> child.equals(triple.subject()) && orderedBy.equals(triple.predicate()))
                .map(triple -> ((RdfNode.Literal) triple.object()).lexicalForm())
                .toList();
        if (positions.size() != 1) {
            throw new AssertionError("child " + child + " carries " + positions.size() + " values of " + orderedBy
                    + ": " + positions);
        }
        return positions.get(0);
    }

    private static Map<String, String> uncodedNames(final List<Triple> triples, final ResourceSchema schema,
            final Map<String, String> named) {
        SimpleRdf rdf = new SimpleRdf();
        Map<String, Set<IRI>> types = new HashMap<>();
        triples.stream()
                .filter(triple -> TYPE.equals(triple.predicate()) && triple.object() instanceof RdfNode.Resource)
                .filter(triple -> !named.containsKey(triple.subject()))
                .forEach(triple -> types.computeIfAbsent(triple.subject(), ignored -> new HashSet<>())
                        .add(rdf.createIRI(((RdfNode.Resource) triple.object()).iri())));
        Map<String, List<String>> byContent = new TreeMap<>();
        types.forEach((iri, typeSet) -> schema.resourceTypeOf(typeSet)
                .filter(type -> type.codePrefix().isEmpty())
                .ifPresent(type -> byContent.computeIfAbsent(content(iri, type.type().getIRIString(), triples, named),
                        ignored -> new ArrayList<>()).add(iri)));
        Map<String, String> names = new HashMap<>();
        byContent.forEach((content, iris) -> {
            List<String> sorted = iris.stream().sorted().toList();
            for (int index = 0; index < sorted.size(); index++) {
                names.put(sorted.get(index), content + DUPLICATE + (index + 1));
            }
        });
        return names;
    }

    private static String content(final String iri, final String type, final List<Triple> triples,
            final Map<String, String> named) {
        return "<" + type + ">{" + triples.stream()
                .filter(triple -> iri.equals(triple.subject()) && !TYPE.equals(triple.predicate()))
                .map(triple -> triple.predicate() + " " + node(triple.object(), named))
                .sorted()
                .collect(Collectors.joining("; ")) + "}";
    }

    private static void assertOneToOne(final Map<String, String> names) {
        Map<String, List<String>> byName = names.entrySet().stream().collect(Collectors.groupingBy(
                Map.Entry::getValue, TreeMap::new, Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        byName.forEach((name, iris) -> {
            if (iris.size() > 1) {
                throw new AssertionError(iris.size() + " nodes would be renamed to " + name + ": " + iris.stream()
                        .sorted().toList());
            }
        });
    }

    private static String render(final Triple triple, final Map<String, String> names) {
        return name(triple.subject(), names) + " " + triple.predicate() + " " + node(triple.object(), names);
    }

    private static String node(final RdfNode node, final Map<String, String> names) {
        return switch (node) {
            case RdfNode.Resource resource -> name(resource.iri(), names);
            case RdfNode.Literal literal -> "\"" + literal.lexicalForm() + "\""
                    + (literal.languageTag() == null ? "^^" + literal.datatypeIri() : "@" + literal.languageTag());
        };
    }

    private static String name(final String iri, final Map<String, String> names) {
        if (iri.startsWith(BLANK_NODE_PREFIX)) {
            throw new AssertionError("a blank node has no name both stores share: " + iri);
        }
        String known = names.get(iri);
        if (known != null) {
            return known;
        }
        int fragment = iri.indexOf(FRAGMENT);
        if (fragment > 0 && names.containsKey(iri.substring(0, fragment))) {
            return names.get(iri.substring(0, fragment)) + iri.substring(fragment);
        }
        return "<" + iri + ">";
    }
}
