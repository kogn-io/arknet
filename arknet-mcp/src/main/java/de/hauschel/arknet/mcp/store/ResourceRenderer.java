// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.List;
import java.util.Objects;

/**
 * Renders a single resource for {@code resource_get}: the same statement information the
 * HTML card shows, as compact text - the subject's outgoing statements plus the incoming
 * statements (its neighbours).
 *
 * <p>Pure and domain-agnostic: consumes only the subject IRI and its out/in statement lists
 * plus a {@link Prefixes} resolver.</p>
 */
public final class ResourceRenderer {

    private final Prefixes prefixes;

    /**
     * @param prefixes the CURIE resolver used to shorten IRIs for display
     */
    public ResourceRenderer(Prefixes prefixes) {
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
    }

    /**
     * Renders the resource view.
     *
     * @param iri      the subject IRI
     * @param outgoing statements with {@code iri} as subject
     * @param incoming statements with {@code iri} as object (neighbours)
     * @return the resource text, or a not-found notice when the resource has no statements
     */
    public String render(String iri, List<Triple> outgoing, List<Triple> incoming) {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(outgoing, "outgoing");
        Objects.requireNonNull(incoming, "incoming");

        if (outgoing.isEmpty() && incoming.isEmpty()) {
            return notFoundMessage(prefixes, iri);
        }

        StringBuilder out = new StringBuilder();
        out.append(prefixes.toCurie(iri)).append("\n<").append(iri).append(">\n\n");

        out.append("# Outgoing (").append(outgoing.size()).append(")\n");
        if (outgoing.isEmpty()) {
            out.append("- (none)\n");
        }
        for (Triple triple : outgoing) {
            out.append(prefixes.toCurie(triple.predicate())).append("  ")
                    .append(renderObject(triple.object())).append('\n');
        }

        out.append("\n# Incoming (").append(incoming.size()).append(")\n");
        if (incoming.isEmpty()) {
            out.append("- (none)\n");
        }
        for (Triple triple : incoming) {
            out.append(prefixes.toCurie(triple.subject())).append("  ")
                    .append(prefixes.toCurie(triple.predicate())).append("  -> (this)\n");
        }
        return out.toString();
    }

    /**
     * The "no such resource" notice, shared with {@code impact_analysis} (issue #135) so a
     * syntactically valid but unknown handle is reported identically regardless of which
     * read-path tool resolved it.
     *
     * @param prefixes the CURIE resolver used to shorten {@code iri} for display
     * @param iri      the unknown subject IRI
     * @return the not-found notice text
     */
    public static String notFoundMessage(Prefixes prefixes, String iri) {
        Objects.requireNonNull(prefixes, "prefixes");
        Objects.requireNonNull(iri, "iri");
        return "Resource not found (no statements): " + prefixes.toCurie(iri) + "\n<" + iri + ">";
    }

    private String renderObject(RdfNode object) {
        return switch (object) {
            case RdfNode.Resource resource -> prefixes.toCurie(resource.iri());
            case RdfNode.Literal literal -> renderLiteral(literal);
        };
    }

    private String renderLiteral(RdfNode.Literal literal) {
        StringBuilder rendered = new StringBuilder("\"").append(literal.lexicalForm()).append('"');
        if (literal.languageTag() != null) {
            rendered.append('@').append(literal.languageTag());
        } else if (literal.datatypeIri() != null && !isPlainStringDatatype(literal.datatypeIri())) {
            rendered.append("^^").append(prefixes.toCurie(literal.datatypeIri()));
        }
        return rendered.toString();
    }

    private static boolean isPlainStringDatatype(String datatypeIri) {
        return "http://www.w3.org/2001/XMLSchema#string".equals(datatypeIri);
    }
}
