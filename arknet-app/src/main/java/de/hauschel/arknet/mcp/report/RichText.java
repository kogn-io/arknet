// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.List;
import java.util.Objects;

/**
 * A run of text split into {@link Span}s - the shape every prose field of a {@link ModelCard}
 * carries.
 *
 * <p>Splitting happens in the card builders, which know both the text and the model's edges
 * ({@link Glossary#markUp}); the renderer only decides what a span <em>looks</em> like. That is
 * the same division of labour {@link Block} rests on, and the reason a future Vaadin review UI
 * can become a second renderer without a second text analysis.</p>
 *
 * <p><strong>Why the source text is carried alongside its spans.</strong> Marking up glossary
 * mentions and code references only ever <em>splits</em> a text, so the spans used to be the
 * text: concatenating them reproduced the literal. Markdown markup (issue #388) breaks that -
 * {@code **bold**} renders as four characters fewer than it was written. The renderer still needs
 * the literal exactly as the store holds it, to find that same literal's other language variants
 * among the subject's raw triples, so this record keeps it rather than letting the renderer
 * reconstruct something that is no longer reconstructable.</p>
 *
 * @param text  the source text this rich text was built from, character for character as the
 *              store holds it
 * @param spans the runs in reading order; they reproduce {@code text} minus any markup syntax
 *              that was consumed while recognising it
 */
public record RichText(String text, List<Span> spans) {

    public RichText {
        Objects.requireNonNull(text, "text");
        spans = spans == null ? List.of() : List.copyOf(spans);
    }

    /**
     * Spans that consumed nothing, so the source text is what they concatenate to - the shape
     * every markup pass that only splits a text ({@link Glossary#markUp}, {@link CodeReferences})
     * produces.
     *
     * @param spans the runs in reading order
     */
    public RichText(final List<Span> spans) {
        this(concat(spans), spans);
    }

    /**
     * Text with nothing to mark up - the honest default for every field the report does not
     * analyse.
     *
     * @param text the text; never {@code null}
     * @return a rich text of exactly one plain span
     */
    public static RichText plain(final String text) {
        Objects.requireNonNull(text, "text");
        return new RichText(text, List.of(new Span.Plain(text)));
    }

    /** @return whether this text has no content at all. */
    public boolean isEmpty() {
        return spans.isEmpty();
    }

    private static String concat(final List<Span> spans) {
        if (spans == null) {
            return "";
        }
        final StringBuilder out = new StringBuilder();
        for (final Span span : spans) {
            out.append(span.text());
        }
        return out.toString();
    }
}
