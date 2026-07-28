// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

/**
 * Pins the normalisation an {@link Anchor} performs on its value - the property that makes it
 * usable as a lookup key at all.
 *
 * <p>The anchor is the only thing a client presents to say which project a call belongs to, and
 * the out-adapter derives a stable node identity straight from its value. Whitespace picked up in
 * transport (a header with a trailing newline, a shell-expanded path, a copied tool argument)
 * would therefore split one project's anchor into two distinct keys: registration would succeed
 * and the very next call would fail to resolve, with two strings that look identical in every log
 * and error message. Stripping in the record is what makes that impossible for every adapter at
 * once, rather than in whichever call sites happened to remember to trim.</p>
 */
class AnchorTest {

    @Test
    void stripsSurroundingWhitespaceSoTransportNoiseCannotSplitOneKeyIntoTwo() {
        Anchor padded = new Anchor("  /home/f/DEV/arknet\n", AnchorType.PATH);

        assertEquals("/home/f/DEV/arknet", padded.value());
        assertEquals(new Anchor("/home/f/DEV/arknet", AnchorType.PATH), padded,
                "an anchor arriving with transport whitespace must equal the same anchor without it - "
                        + "otherwise registering a project and resolving it later use different keys");
    }

    @Test
    void leavesInnerCharactersUntouchedBecauseTheValueStaysOpaque() {
        String odd = "/home/f/My Projects/a b";

        assertEquals(odd, new Anchor(odd, AnchorType.PATH).value(),
                "normalisation applies to the representation, never to the content - the server "
                        + "does not parse, validate or canonicalise what a client sends");
    }

    @Test
    void typeIsPartOfIdentityBecauseTheSameStringCanBeTwoKindsOfAnchor() {
        assertNotEquals(new Anchor("https://example.org/repo", AnchorType.URL),
                new Anchor("https://example.org/repo", AnchorType.PATH));
    }

    @Test
    void rejectsAValueThatCarriesNoKeyAtAll() {
        assertThrows(IllegalArgumentException.class, () -> new Anchor("   ", AnchorType.PATH));
        assertThrows(NullPointerException.class, () -> new Anchor(null, AnchorType.PATH));
        assertThrows(NullPointerException.class, () -> new Anchor("/home/f", null));
    }
}
