package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * Human-readable business label of a {@link Requirement}, e.g. {@code FR-1} or {@code NFR-7}.
 *
 * <p>Value object wrapping the business identifier. Generation of the running number is a
 * policy concern of the application layer, not of this type. This is deliberately separate
 * from {@link RequirementId}: the code is what a human types (into {@code req_get},
 * {@code req_set_status}, ...) and what {@code dcterms:identifier} carries in the store; it may
 * in principle be relabelled without touching the requirement's underlying identity.</p>
 *
 * @param value the non-blank code string
 */
public record RequirementCode(String value) {

    public RequirementCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("RequirementCode must not be blank");
        }
    }
}
