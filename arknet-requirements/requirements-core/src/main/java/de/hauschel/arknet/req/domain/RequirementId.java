package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * Identity of a {@link Requirement}, e.g. {@code FR-1} or {@code NFR-7}.
 *
 * <p>Value object wrapping the human-readable business identifier. Generation
 * of the running number is a policy concern of the application layer, not of
 * this type.</p>
 *
 * @param value the non-blank identifier string
 */
public record RequirementId(String value) {

    public RequirementId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("RequirementId must not be blank");
        }
    }
}
