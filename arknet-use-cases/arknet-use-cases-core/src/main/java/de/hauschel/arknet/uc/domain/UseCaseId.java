package de.hauschel.arknet.uc.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Opaque, unchanging identity of a {@link UseCase}.
 *
 * <p>Thin newtype over the shared-kernel {@link ResourceId} for type safety within the
 * use-cases bounded context. Identity is deliberately independent of the human-readable
 * {@link UseCaseCode} ({@code UC1}): the code may be relabelled, this identity never changes.
 * A use case is the aggregate root - it carries this stable identity; its {@link Step steps}
 * are value objects inside the aggregate and have no identity of their own.</p>
 *
 * @param value the wrapped resource identity, never {@code null}
 */
public record UseCaseId(ResourceId value) {

    public UseCaseId {
        Objects.requireNonNull(value, "value");
    }
}
