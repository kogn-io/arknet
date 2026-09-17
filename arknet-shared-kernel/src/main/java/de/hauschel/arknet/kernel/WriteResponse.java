// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The three shapes every writing MCP tool's answer is built from: the closing project line, the
 * short link/unlink confirmation, and the per-list-field diff line.
 *
 * <p><strong>Why one place.</strong> Thirteen driving adapters compose their own answers inline,
 * and each of the three shapes has to read the same in all of them or an agent cannot learn it
 * once. This class is to the answer what {@link StaleTranslationHint} is to the translation
 * signal, and sits beside it for the same reason: a pure-JDK helper every bounded context's
 * in-adapter already depends on, with no new module edge anywhere.</p>
 *
 * <p><strong>The project line (kogn-io/arknet#597).</strong> A tool call whose
 * {@code projectAnchor} was forgotten falls back to the anchor the transport carried and writes
 * into the session's project - correctly, silently, and into the wrong model. Nothing in the
 * answer used to say which project was hit. Every writing answer now ends with
 * {@code project: <name>}, so a wrong hit is visible in the same turn it happened rather than at
 * the next {@code store_overview}.</p>
 *
 * <p><strong>The link line (kogn-io/arknet#600).</strong> A link or unlink call is a pure edge
 * operation whose caller already holds both ends - echoing the whole resource back pays a full
 * rendering for an answer that carries one bit of news. {@code linked FR-3 -&gt; TERM-7
 * (usesTerm)} carries all of it.</p>
 *
 * <p><strong>The diff line (kogn-io/arknet#598).</strong> A wholesale list field ({@code terms},
 * {@code usesTermCodes}, ...) replaces what was there, so a caller restating an incomplete set
 * silently drops the rest. The diff is computed from the field's state before and after the write,
 * never from the request: the request only says what was asked for, and what actually left or
 * joined the field is the thing that was invisible.</p>
 */
public final class WriteResponse {

    /** Separates the project line from the body, so it reads as a trailer rather than as content. */
    private static final String PROJECT_SEPARATOR = "\n\n";

    private WriteResponse() {
    }

    /**
     * Closes a writing tool's answer with the project it hit.
     *
     * @param body    the answer as the tool rendered it, never {@code null} (an empty body yields
     *                the project line alone, which is still an answer that names its project)
     * @param project the project this call resolved to, never {@code null}
     * @return {@code body}, followed by a blank line and {@code project: <name>}
     */
    public static String withProject(final String body, final ResolvedProject project) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(project, "project");
        return body.isEmpty()
                ? "project: " + project.displayName()
                : body + PROJECT_SEPARATOR + "project: " + project.displayName();
    }

    /**
     * The short confirmation an edge-creating tool answers with, e.g.
     * {@code linked FR-3 -> TERM-7 (usesTerm)}.
     *
     * @param source the business code of the resource the edge starts at, e.g. {@code FR-3}
     * @param target the business code of the resource the edge points at, e.g. {@code TERM-7}
     * @param edge   what kind of edge was drawn - the local name of the predicate behind it, or,
     *               where the edge is classified rather than named, that classification (a DDD
     *               context relationship's {@code CUSTOMER_SUPPLIER})
     * @return the one-line confirmation, without the project line ({@link #withProject} adds that)
     */
    public static String linked(final String source, final String target, final String edge) {
        return edgeLine("linked", source, target, edge);
    }

    /** The counterpart of {@link #linked}, e.g. {@code unlinked FR-3 -> TERM-7 (usesTerm)}. */
    public static String unlinked(final String source, final String target, final String edge) {
        return edgeLine("unlinked", source, target, edge);
    }

    /**
     * The diff line for one wholesale list field, e.g.
     * {@code usesTerm: removed TERM-22, added TERM-9} - empty when the field came out of the write
     * holding exactly what it held before, so an unchanged field costs no line.
     *
     * <p>Both sides are compared as sets while each reported side keeps the order it was given in:
     * what left is named in the order the field held it, what joined in the order the field holds
     * it now. A duplicate entry on either side is one entry - a list field is a set of edges, and
     * reporting {@code TERM-9} twice would claim a change that did not happen.</p>
     *
     * @param field  the field's model name, the local name of the predicate behind it - the same
     *               vocabulary {@code store_check} and {@link StaleTranslationHint} name fields in
     * @param before the entries the field held before the write, never {@code null}
     * @param after  the entries it holds after it, never {@code null}
     * @return the diff line, or an empty string if nothing changed
     */
    public static String listFieldDiff(final String field, final Collection<String> before,
            final Collection<String> after) {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(before, "before");
        Objects.requireNonNull(after, "after");
        final Set<String> previous = new LinkedHashSet<>(before);
        final Set<String> current = new LinkedHashSet<>(after);
        final List<String> removed = previous.stream().filter(entry -> !current.contains(entry)).toList();
        final List<String> added = current.stream().filter(entry -> !previous.contains(entry)).toList();
        if (removed.isEmpty() && added.isEmpty()) {
            return "";
        }
        final List<String> parts = new ArrayList<>();
        if (!removed.isEmpty()) {
            parts.add("removed " + String.join(", ", removed));
        }
        if (!added.isEmpty()) {
            parts.add("added " + String.join(", ", added));
        }
        return field + ": " + String.join(", ", parts);
    }

    private static String edgeLine(final String verb, final String source, final String target, final String edge) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(edge, "edge");
        return "%s %s -> %s (%s)".formatted(verb, source, target, edge);
    }
}
