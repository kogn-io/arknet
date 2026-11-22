// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.Map;
import java.util.List;
import java.util.Objects;

/**
 * Renders a single resource for {@code resource_get}: the same statement information the
 * HTML card shows, as compact text - the subject's outgoing statements plus the incoming
 * statements (its neighbours).
 *
 * <p>Pure and domain-agnostic: consumes only the subject IRI, its out/in statement lists, a
 * {@link Prefixes} resolver, and (issue #594) the already-resolved display handle for each
 * incoming neighbour. Resolving that handle needs store access (the {@code ResourceHandles}
 * preference order can fall back to a neighbour's own {@code dcterms:identifier}, which this
 * renderer has no way to look up on its own), so {@code StoreReportTools} resolves it beforehand
 * and hands the finished text in - this class stays free of any store dependency.</p>
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
     * @param iri             the subject IRI
     * @param outgoing        statements with {@code iri} as subject
     * @param incoming        statements with {@code iri} as object (neighbours)
     * @param incomingHandles the display handle for every neighbour subject appearing in
     *                        {@code incoming} ({@code ResourceHandles}'s CURIE / bare-id /
     *                        full-IRI preference, resolved by the caller); a subject missing
     *                        here falls back to a plain CURIE, the same default this renderer
     *                        applied before issue #594
     * @return the resource text, or a not-found notice when the resource has no statements
     */
    public String render(String iri, List<Triple> outgoing, List<Triple> incoming,
            Map<String, String> incomingHandles) {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(outgoing, "outgoing");
        Objects.requireNonNull(incoming, "incoming");
        Objects.requireNonNull(incomingHandles, "incomingHandles");

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
            String subjectHandle = incomingHandles.getOrDefault(triple.subject(), prefixes.toCurie(triple.subject()));
            out.append(subjectHandle).append("  ")
                    .append(prefixes.toCurie(triple.predicate())).append("  -> (this)\n");
        }
        return out.toString();
    }

    /**
     * Renders the view of a deleted resource: that it was deleted, when, and the statements of the
     * last version the versioning kept, so a reader can tell what a surviving reference to the
     * resource's code pointed at. Only a tombstone's own record is shown - the resource is gone
     * from the model and has no neighbours.
     *
     * @param deleted what the versioning still records of the resource
     * @return the deleted-resource text
     */
    public String renderDeleted(DeletedResource deleted) {
        Objects.requireNonNull(deleted, "deleted");
        return prefixes.toCurie(deleted.iri()) + "\n<" + deleted.iri() + ">\n\n" + renderDeletedBody(deleted);
    }

    /**
     * Renders the deletion record without the resource's heading lines, for a caller that has
     * printed its own (the history view).
     *
     * @param deleted what the versioning still records of the resource
     * @return the deletion record text
     */
    public String renderDeletedBody(DeletedResource deleted) {
        Objects.requireNonNull(deleted, "deleted");
        StringBuilder out = new StringBuilder();
        out.append("# DELETED\n");
        if (deleted.deletedBeforeVersioning()) {
            out.append("- deleted before versioning: the deletion time is not recorded\n");
        } else {
            out.append("- deleted at ").append(deleted.deletedAt());
            deleted.optionalComment().ifPresent(comment -> out.append(" (\"").append(comment).append("\")"));
            out.append('\n');
        }
        deleted.identifier().ifPresent(code -> out.append("- code ").append(code)
                .append(" stays taken, it is never assigned to another resource\n"));
        if (deleted.deletedBeforeVersioning()) {
            out.append("- the content is not recorded: the storage layout of that time kept only the code,"
                    + " and the IRI above is the record of that code, not the resource itself\n");
            return out.toString();
        }
        if (deleted.lastStatements().isEmpty()) {
            out.append("- the content is not recorded: the resource was deleted without ever having been"
                    + " published\n");
            return out.toString();
        }
        out.append("\n# Last version (").append(deleted.lastStatements().size()).append(")\n");
        for (Triple triple : deleted.lastStatements()) {
            out.append(prefixes.toCurie(triple.predicate())).append("  ")
                    .append(renderObject(triple.object())).append('\n');
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
