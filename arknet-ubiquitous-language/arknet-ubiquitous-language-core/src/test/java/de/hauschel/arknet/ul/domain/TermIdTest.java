package de.hauschel.arknet.ul.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Domain invariant tests for {@link TermId}: an opaque newtype over a shared-kernel
 * {@link ResourceId}, deliberately decoupled from the business code and the label.
 */
class TermIdTest {

    private static final ResourceId IRI = ResourceId.of("https://w3id.org/arknet/id/abc-123");

    @Test
    void holdsItsValue() {
        TermId id = new TermId(IRI);

        assertEquals(IRI, id.value());
    }

    @Test
    void rejectsNullValue() {
        assertThrows(NullPointerException.class, () -> new TermId(null));
    }
}
