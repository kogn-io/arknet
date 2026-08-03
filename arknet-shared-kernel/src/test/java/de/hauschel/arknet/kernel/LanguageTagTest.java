// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * Domain invariant tests for {@link LanguageTag}.
 *
 * <p>The one behavior this class exists for: reject a not-well-formed tag instead of silently
 * degrading it the way {@link java.util.Locale#forLanguageTag(String)} would (see that class's
 * javadoc) - {@code "de_DE"}, the underscore-instead-of-hyphen typo a Java-{@link
 * java.util.Locale}-habituated caller is likely to make, is the case that must throw.</p>
 */
class LanguageTagTest {

    @Test
    void returnsNullUnchangedForANullTag() {
        assertNull(LanguageTag.canonicalize(null));
    }

    @Test
    void canonicalizesAnAlreadyLowercaseTagUnchanged() {
        assertEquals("de", LanguageTag.canonicalize("de"));
    }

    @Test
    void canonicalizesAnUppercaseTagToLowercase() {
        assertEquals("de", LanguageTag.canonicalize("DE"));
    }

    @Test
    void canonicalizesARegionSubtagToUppercase() {
        assertEquals("de-DE", LanguageTag.canonicalize("de-de"));
    }

    @Test
    void acceptsAnAlreadyWellFormedLanguageRegionTag() {
        assertEquals("en-US", LanguageTag.canonicalize("en-US"));
    }

    @Test
    void rejectsAJavaLocaleStyleUnderscoreTag() {
        InvalidLanguageTagException e =
                assertThrows(InvalidLanguageTagException.class, () -> LanguageTag.canonicalize("de_DE"));

        assertEquals("de_DE", e.tag());
    }

    @Test
    void rejectsFreeText() {
        assertThrows(InvalidLanguageTagException.class, () -> LanguageTag.canonicalize("not a tag"));
    }

    @Test
    void rejectsAnEmptyTag() {
        assertThrows(InvalidLanguageTagException.class, () -> LanguageTag.canonicalize(""));
    }
}
