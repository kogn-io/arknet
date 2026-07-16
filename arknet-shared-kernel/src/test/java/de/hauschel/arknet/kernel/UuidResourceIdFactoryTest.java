package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Tests for {@link UuidResourceIdFactory}: every minted identity is a flat, opaque
 * {@code https://} IRI under the kernel-owned base, and no two calls ever collide.
 */
class UuidResourceIdFactoryTest {

    private final UuidResourceIdFactory factory = new UuidResourceIdFactory();

    @Test
    void mintsAFlatOpaqueHttpsIri() {
        ResourceId id = factory.newId();

        assertTrue(id.value().startsWith("https://w3id.org/arknet/id/"), id.value());
    }

    @Test
    void mintsADifferentIdentityEveryTime() {
        assertNotEquals(factory.newId(), factory.newId());
    }
}
