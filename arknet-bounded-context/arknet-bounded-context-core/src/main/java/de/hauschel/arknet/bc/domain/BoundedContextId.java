package de.hauschel.arknet.bc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Opaque, unchanging identity of a {@link BoundedContext}.
 *
 * <p>Thin newtype over the shared-kernel {@link ResourceId} for type safety within the
 * bounded-context bounded context. Identity is deliberately independent of the human-readable
 * {@link BoundedContextCode} ({@code BC-1}): the code may be relabelled, this identity never
 * changes.</p>
 *
 * @param value the wrapped resource identity, never {@code null}
 */
public record BoundedContextId(ResourceId value) {

    public BoundedContextId {
        Objects.requireNonNull(value, "value");
    }
}
