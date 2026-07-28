// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.List;
import java.util.Objects;

/**
 * All cards contributed by one bounded context, under that context's own heading.
 *
 * <p>One section per bounded context is the report's top-level structure: the reader sees
 * "Use Cases", "Requirements", "Glossary", "Bounded Contexts" - not a list of
 * {@code rdf:type} IRIs.</p>
 *
 * @param title    the section heading (e.g. {@code Use Cases})
 * @param id       a stable, url-safe anchor id for the section (e.g. {@code use-cases})
 * @param subtitle a short line under the heading saying what this section shows; may be blank
 * @param cards    the cards in display order; never {@code null}
 */
public record ModelSection(String title, String id, String subtitle, List<ModelCard> cards) {

    public ModelSection {
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(subtitle, "subtitle");
        cards = cards == null ? List.of() : List.copyOf(cards);
    }

    /** @return {@code true} if this section has no cards and should be left out of the report. */
    public boolean isEmpty() {
        return cards.isEmpty();
    }
}
