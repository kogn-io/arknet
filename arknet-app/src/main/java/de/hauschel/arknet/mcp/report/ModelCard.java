// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.List;
import java.util.Objects;

/**
 * One model element as a human reads it: a headline (business code + title), a few enum-like
 * {@link Badge badges}, and an ordered body of {@link Block blocks}.
 *
 * <p>Built from a bounded context's own domain object via its read in-ports, never
 * reconstructed from triples - the ordering of a use case's flow, the resolution of an opaque
 * actor identity to {@code Customer}, and the grouping of a requirement's acceptance criteria
 * are the owning context's answers, not the report's guesses.</p>
 *
 * @param code   the business code (e.g. {@code UC1}, {@code FR-1}) - what a human types
 * @param title  the headline text (use-case title, requirement title, term prefLabel, bounded
 *               context name)
 * @param iri    the subject IRI this card was built from; the report anchors the card on it,
 *               links other cards' references to it and hangs the raw triples off it
 * @param badges enum-like facts shown next to the headline; never {@code null}
 * @param blocks the card body in display order; never {@code null}
 */
public record ModelCard(String code, String title, String iri, List<Badge> badges, List<Block> blocks) {

    public ModelCard {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(iri, "iri");
        badges = badges == null ? List.of() : List.copyOf(badges);
        blocks = blocks == null ? List.of() : List.copyOf(blocks);
    }
}
