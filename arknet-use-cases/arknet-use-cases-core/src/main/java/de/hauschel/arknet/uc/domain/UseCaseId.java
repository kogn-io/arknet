package de.hauschel.arknet.uc.domain;

import java.util.Objects;

/**
 * Identity of a {@link UseCase}, e.g. {@code UC1} or {@code UC7}.
 *
 * <p>Value object wrapping the human-readable business identifier. Generation
 * of the running number is a policy concern of the application layer, not of
 * this type.</p>
 *
 * @param value the non-blank identifier string
 */
public record UseCaseId(String value) {

    public UseCaseId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("UseCaseId must not be blank");
        }
    }
}
