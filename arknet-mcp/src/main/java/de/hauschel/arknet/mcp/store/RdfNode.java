package de.hauschel.arknet.mcp.store;

import java.util.Objects;

/**
 * A minimal, backend-neutral RDF object node: either a {@link Resource} (an IRI reference)
 * or a {@link Literal} (a lexical value with datatype and optional language tag).
 *
 * <p>The generic store read path maps every SPARQL result term onto this tiny model so the
 * renderers stay free of any kognio-rdf or RDF4J type. Blank nodes are represented as
 * {@link Resource}s carrying their internal reference, which is enough for display.</p>
 */
public sealed interface RdfNode permits RdfNode.Resource, RdfNode.Literal {

    /** An IRI (or blank-node) reference. */
    record Resource(String iri) implements RdfNode {
        public Resource {
            Objects.requireNonNull(iri, "iri");
        }
    }

    /** A literal value. {@code datatypeIri} and {@code languageTag} may be {@code null}. */
    record Literal(String lexicalForm, String datatypeIri, String languageTag) implements RdfNode {
        public Literal {
            Objects.requireNonNull(lexicalForm, "lexicalForm");
        }
    }
}
