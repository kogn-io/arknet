// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PrefixBindingsTest {

    private static final String NS = "https://example.org/a#";
    private static final String OTHER = "https://example.org/b/";
    private static final PrefixBindings BINDINGS = new PrefixBindings(Map.of("a", NS, "b", OTHER));

    @Test
    void expandsAPrefixedNameAndPassesAnIriThrough() {
        assertEquals(Optional.of(NS + "Thing"), BINDINGS.expand("a:Thing"));
        assertEquals(Optional.of(NS + "Thing"), BINDINGS.expand("<" + NS + "Thing>"));
        assertEquals(Optional.of(OTHER + "x"), BINDINGS.expand(OTHER + "x"));
        assertTrue(BINDINGS.expand("c:Thing").isEmpty());
    }

    @Test
    void abbreviatesOnlyWhatAPrefixedNameCanCarry() {
        assertEquals("a:Thing", BINDINGS.abbreviate(NS + "Thing"));
        assertEquals("<" + OTHER + "x/y>", BINDINGS.abbreviate(OTHER + "x/y"));
        assertEquals("<https://example.org/c#Thing>", BINDINGS.abbreviate("https://example.org/c#Thing"));
    }

    @Test
    void aTextUsesThePrefixesItWritesAndTheNamespacesItNames() {
        // given
        String text = "a:title is missing on " + OTHER + "x; see https://example.org/c";

        // when
        PrefixBindings used = BINDINGS.usedIn(text);

        // then
        assertEquals(Map.of("a", NS, "b", OTHER), used.byPrefix());
        assertTrue(BINDINGS.usedIn("data:value, http://x").isEmpty());
        assertEquals("@prefix a: <" + NS + "> .\n", BINDINGS.only(Set.of("a")).declarations());
    }
}
