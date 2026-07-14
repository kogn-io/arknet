package de.hauschel.arknet.ul.domain;

import java.util.Objects;

/**
 * Identity of a {@link Term}, e.g. {@code TERM-1}.
 *
 * <p>Value object wrapping the term's stable business identifier. It is
 * deliberately <strong>independent of the term's {@code skos:prefLabel}</strong>:
 * the label may be edited or carry alternatives ({@code skos:altLabel}) without
 * changing identity, which is a core SKOS principle (the Concept IRI must not be
 * derived from a mutable label). Generation of the running number is a policy
 * concern of the application layer, not of this type.</p>
 *
 * @param value the non-blank identifier string
 */
public record TermId(String value) {

    public TermId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("TermId must not be blank");
        }
    }
}
