package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * A single requirement (functional or non-functional) under management.
 *
 * <p>Value object of the requirements component. All invariants are enforced
 * in the compact constructor; instances are immutable.</p>
 *
 * @param id               stable business identity (e.g. {@code FR-1});
 *                         maps to {@code dcterms:identifier}
 * @param title            short human-readable summary; maps to {@code dcterms:title}
 * @param description      the normative statement ("The system shall ..."); maps to
 *                         {@code dcterms:description} and is required by the requirements
 *                         SHACL shape
 * @param type             functional vs. non-functional classification
 * @param status           current lifecycle state
 * @param priority         MoSCoW priority; maps to {@code arkreq:priority}. Optional (may be
 *                         {@code null}); applies to both functional and non-functional
 *                         requirements
 * @param motivatedBy      IRI of the {@code arkreq:Goal} this requirement is motivated by;
 *                         maps to {@code arkreq:motivatedBy}. Optional (may be {@code null});
 *                         no Goal aggregate exists yet, so this is carried as a plain IRI
 *                         reference rather than resolved to a domain object
 * @param qualityCategory  free-text quality category (e.g. "performance", "security"); maps
 *                         to {@code arkreq:qualityCategory}. Optional (may be {@code null});
 *                         only meaningful for {@link RequirementType#NON_FUNCTIONAL}
 */
public record Requirement(
        RequirementId id,
        String title,
        String description,
        RequirementType type,
        RequirementStatus status,
        Priority priority,
        String motivatedBy,
        String qualityCategory) {

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
        if (qualityCategory != null && type != RequirementType.NON_FUNCTIONAL) {
            throw new IllegalArgumentException("qualityCategory is only allowed for non-functional requirements");
        }
    }
}
