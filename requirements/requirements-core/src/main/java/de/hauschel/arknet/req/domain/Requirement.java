package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * A single requirement (functional or non-functional) under management.
 *
 * <p>Value object of the requirements component. All invariants are enforced
 * in the compact constructor; instances are immutable.</p>
 *
 * @param id          stable business identity (e.g. {@code FR-1});
 *                    maps to {@code dcterms:identifier}
 * @param title       short human-readable summary; maps to {@code dcterms:title}
 * @param description the normative statement ("The system shall ..."); maps to
 *                    {@code dcterms:description} and is required by the requirements
 *                    SHACL shape
 * @param type        functional vs. non-functional classification
 * @param status      current lifecycle state
 */
public record Requirement(
        RequirementId id,
        String title,
        String description,
        RequirementType type,
        RequirementStatus status) {

    public Requirement {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(status, "status");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
    }
}
