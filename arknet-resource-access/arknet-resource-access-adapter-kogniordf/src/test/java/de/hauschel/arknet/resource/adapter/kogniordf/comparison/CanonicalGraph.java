// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.stream.Collectors;

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
 *   <li>any other IRI that extends a resource IRI by a fragment becomes
 *       {@code <parent>#<fragment>}.</li>
 * </ul>
 *
 * <p>Literals compare exactly: lexical form, datatype and language tag. A blank node fails the
 * graph: no writer stores one, and a label differs between stores, so it could only compare by
 * being dropped - which this class never does.</p>
 */
public final class CanonicalGraph {

    /** {@code dcterms:identifier} - the business code of a resource; not a namespace of a context. */
    static final String IDENTIFIER = "http://purl.org/dc/terms/identifier";

    private static final String CODE_SCHEME = "code:";
    private static final String BLANK_NODE_PREFIX = "_:";
    private static final char FRAGMENT = '#';
    private static final char POSITION = '@';

    private final Set<String> statements;

    private CanonicalGraph(final Set<String> statements) {
        this.statements = Set.copyOf(statements);
    }

    /**
     * Renames the given content-graph triples of one project.
     *
     * @param triples every triple of the project's content graph
     * @param schema  the schema that names the owned fields and their position property
     * @return the canonical graph
     */
    static CanonicalGraph of(final List<Triple> triples, final ResourceSchema schema) {
        Map<String, String> names = resourceNames(triples);
        names.putAll(childNames(triples, schema, names));
        Set<String> statements = triples.stream()
                .map(triple -> render(triple, names))
                .collect(Collectors.toCollection(TreeSet::new));
        return new CanonicalGraph(statements);
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
                .collect(Collectors.toCollection(TreeSet::new)));
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
                .forEach(triple -> names.put(triple.subject(),
                        CODE_SCHEME + ((RdfNode.Literal) triple.object()).lexicalForm()));
        return names;
    }

    private static Map<String, String> childNames(final List<Triple> triples, final ResourceSchema schema,
            final Map<String, String> resources) {
        SimpleRdf rdf = new SimpleRdf();
        Map<String, String> children = new HashMap<>();
        for (Triple triple : triples) {
            Optional<OwnedField> owned = schema.ownedField(rdf.createIRI(triple.predicate()));
            if (owned.isEmpty() || !(triple.object() instanceof RdfNode.Resource child)) {
                continue;
            }
            String parent = resources.getOrDefault(triple.subject(), triple.subject());
            String position = position(triples, child.iri(), owned.get().orderedBy().getIRIString());
            children.put(child.iri(), parent + FRAGMENT + owned.get().childKind() + POSITION + position);
        }
        return children;
    }

    private static String position(final List<Triple> triples, final String child, final String orderedBy) {
        return triples.stream()
                .filter(triple -> child.equals(triple.subject()) && orderedBy.equals(triple.predicate()))
                .map(triple -> ((RdfNode.Literal) triple.object()).lexicalForm())
                .findFirst()
                .orElseThrow(() -> new AssertionError("child " + child + " carries no " + orderedBy));
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
