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
 * the same division of labour {@link Block} rests on, and the reason the Vaadin review UI of
 * ADR-010 can become a second renderer without a second text analysis.</p>
 *
 * @param spans the runs in reading order; concatenating their {@link Span#text()} reproduces
 *              the original text exactly, so nothing can be lost or reordered by marking it up
 */
public record RichText(List<Span> spans) {

    public RichText {
        spans = spans == null ? List.of() : List.copyOf(spans);
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
        return new RichText(List.of(new Span.Plain(text)));
    }

    /** @return the concatenated text of every span, i.e. the original unmarked text. */
    public String text() {
        final StringBuilder out = new StringBuilder();
        for (final Span span : spans) {
            out.append(span.text());
        }
        return out.toString();
    }
}
