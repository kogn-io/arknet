package de.hauschel.arknet.uc.domain;

import java.util.Objects;

/**
 * Reference from a {@link Step} to a functional requirement it realises,
 * carried as a bare business label such as {@code FR5}.
 *
 * <p><strong>Deliberately not a link to the requirements bounded context.</strong>
 * The use-cases component must not depend on {@code requirements-core}; the two
 * BCs stay decoupled. This value object therefore holds only the requirement's
 * label as a string. Resolving the label to an actual requirement - and rejecting
 * unknown labels - is the job of a driven adapter (lookup-by-label against the
 * store), not of this pure domain type.</p>
 *
 * @param label the non-blank requirement label (e.g. {@code FR5})
 */
public record RequirementRef(String label) {

    public RequirementRef {
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("RequirementRef label must not be blank");
        }
    }
}
