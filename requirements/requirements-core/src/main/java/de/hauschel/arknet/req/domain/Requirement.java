package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * A single requirement (functional or non-functional) under management.
 *
 * <p>Value object of the requirements component. All invariants are enforced
 * in the compact constructor; instances are immutable.</p>
 *
 * @param id     stable business identity (e.g. {@code FR-1})
 * @param title  short human-readable summary
 * @param type   functional vs. non-functional classification
 * @param status current lifecycle state
 */
public record Requirement(
        RequirementId id,
        String title,
        RequirementType type,
        RequirementStatus status) {

    public Requirement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
    }
}
