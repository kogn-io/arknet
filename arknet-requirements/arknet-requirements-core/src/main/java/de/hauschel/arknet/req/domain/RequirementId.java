package de.hauschel.arknet.req.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Opaque, unchanging identity of a {@link Requirement}.
 *
 * <p>Thin newtype over the shared-kernel {@link ResourceId} for type safety within the
 * requirements bounded context. Identity is deliberately independent of the human-readable
 * {@link RequirementCode} ({@code FR-1}): the code may be relabelled, this identity never
 * changes.</p>
 *
 * @param value the wrapped resource identity, never {@code null}
 */
public record RequirementId(ResourceId value) {

    public RequirementId {
        Objects.requireNonNull(value, "value");
    }
}
