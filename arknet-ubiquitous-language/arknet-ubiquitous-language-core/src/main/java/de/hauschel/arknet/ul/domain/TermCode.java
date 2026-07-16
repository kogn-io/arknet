package de.hauschel.arknet.ul.domain;

import java.util.Objects;

/**
 * Human-readable business label of a {@link Term}, e.g. {@code TERM-1}.
 *
 * <p>Value object wrapping the business identifier. Generation of the running number is a
 * policy concern of the application layer, not of this type. This is deliberately separate
 * from {@link TermId}: the code is what a human types (into {@code term_get}) and what
 * {@code dcterms:identifier} carries in the store - it is also how sibling bounded contexts
 * reference a term ({@code arkreq:usesTerm} resolves by {@code dcterms:identifier}, #36). It
 * may in principle be relabelled without touching the term's underlying identity.</p>
 *
 * @param value the non-blank code string
 */
public record TermCode(String value) {

    public TermCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("TermCode must not be blank");
        }
    }
}
