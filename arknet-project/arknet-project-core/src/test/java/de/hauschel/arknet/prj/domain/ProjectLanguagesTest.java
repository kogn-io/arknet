// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.InvalidLanguageTagException;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Pins the two rules a project's language pair carries (kogn-io/arknet#412): what a maintained
 * language set is normalized to, and when it may not contradict the default language.
 *
 * <p>Both live in the domain rather than in an adapter because both are statements about the
 * model, not about its serialisation - and because the write path that could break the second one
 * reaches the store through two different out-port methods, so nothing further down sees both
 * halves of the resulting state at once.</p>
 */
class ProjectLanguagesTest {

    private static final ProjectId ID = new ProjectId("p-1");
    private static final List<Anchor> ANCHORS = List.of(new Anchor("/home/f/DEV/arknet", AnchorType.PATH));

    // --- canonicalLanguages -------------------------------------------------------------

    @Test
    void canonicalizesEveryTagSoTheSetAndTheDefaultLanguageCannotDisagreeOnCasingAlone() {
        assertEquals(List.of("de", "en"), Project.canonicalLanguages(List.of("DE", "EN")),
                "a set given in upper case must be stored in the same normalized form the single "
                        + "defaultLanguage literal already uses - otherwise the pair invariant would "
                        + "reject a matching pair purely because of how it was typed");
    }

    @Test
    void removesDuplicatesButKeepsTheOrderTheCallerStatedThemIn() {
        assertEquals(List.of("de", "en"), Project.canonicalLanguages(List.of("de", "EN", "de")),
                "the same language twice is one commitment, not two, and the caller's order is the "
                        + "only order there is - RDF gives none back");
    }

    @Test
    void treatsANullSetAsNoCommitmentRatherThanAsAnError() {
        assertEquals(List.of(), Project.canonicalLanguages(null));
    }

    @Test
    void rejectsAMalformedTagInsteadOfDegradingItSilently() {
        assertThrows(InvalidLanguageTagException.class,
                () -> Project.canonicalLanguages(List.of("de_DE")),
                "an underscore is Java Locale's own convention, not BCP-47 - accepting it would "
                        + "store a language nothing ever matches");
    }

    @Test
    void rejectsABlankTagAndSaysHowToClearTheSetInstead() {
        IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                () -> Project.canonicalLanguages(Arrays.asList("de", " ")));

        assertTrue(blank.getMessage().contains("empty list"),
                "a blank entry is a mistyped tag, and the message has to name the deliberate way to "
                        + "maintain nothing: " + blank.getMessage());
    }

    // --- requireDefaultLanguageMaintained -----------------------------------------------

    @Test
    void acceptsADefaultLanguageThatIsAMemberOfTheMaintainedSet() {
        assertDoesNotThrow(() -> Project.requireDefaultLanguageMaintained("de", List.of("de", "en")));
    }

    @Test
    void comparesTheDefaultLanguageAgainstTheSetInCanonicalFormOnBothSides() {
        assertDoesNotThrow(() -> Project.requireDefaultLanguageMaintained("DE", List.of("de", "en")),
                "casing is representation, not meaning - a pair that differs only in it is the same pair");
    }

    @Test
    void rejectsADefaultLanguageOutsideANonEmptyMaintainedSetAndNamesBothWaysOut() {
        DefaultLanguageNotMaintainedException rejected =
                assertThrows(DefaultLanguageNotMaintainedException.class,
                        () -> Project.requireDefaultLanguageMaintained("fr", List.of("de", "en")));

        assertEquals("fr", rejected.defaultLanguage());
        assertEquals(List.of("de", "en"), rejected.maintainedLanguages());
        assertTrue(rejected.getMessage().contains("add 'fr'")
                        && rejected.getMessage().contains("one of: de, en"),
                "the caller may have meant either half; the message has to offer both remedies: "
                        + rejected.getMessage());
    }

    @Test
    void acceptsAnyDefaultLanguageWhileTheMaintainedSetIsEmpty() {
        assertDoesNotThrow(() -> Project.requireDefaultLanguageMaintained("fr", List.of()),
                "an empty set is the absence of a commitment - there is nothing for a fallback to "
                        + "contradict, and this is exactly the behaviour of every project registered "
                        + "before the set existed");
    }

    @Test
    void acceptsAMaintainedSetOnAProjectThatConfiguredNoDefaultLanguageAtAll() {
        assertDoesNotThrow(() -> Project.requireDefaultLanguageMaintained(null, List.of("de", "en")),
                "no default language means no fallback that could point outside the set - declaring "
                        + "what a project maintains must not be gated on also configuring one");
    }

    // --- the record itself ---------------------------------------------------------------

    @Test
    void defaultsTheMaintainedSetToEmptyRatherThanNullSoNoReaderHasToNullCheckIt() {
        assertEquals(List.of(), new Project(ID, "arknet", ANCHORS, null, "de", null).maintainedLanguages());
        assertEquals(List.of(), new Project(ID, "arknet", ANCHORS).maintainedLanguages());
        assertEquals(List.of(), new Project(ID, "arknet", ANCHORS, "desc", "de").maintainedLanguages());
    }

    @Test
    void staysConstructibleFromAStoreFirstEntryWhoseDefaultLanguageLeftItsOwnMaintainedSet() {
        Project inconsistent = new Project(ID, "arknet", ANCHORS, null, "fr", List.of("de", "en"));

        assertEquals("fr", inconsistent.defaultLanguage(),
                "the pair invariant guards writes, not reads: a registry entry that violates it has "
                        + "to stay readable, or no tool could correct it");
    }

    @Test
    void deduplicatesTheMaintainedSetSoTwoReadsOfOneProjectCompareEqual() {
        assertEquals(List.of("de"),
                new Project(ID, "arknet", ANCHORS, null, "de", List.of("de", "de")).maintainedLanguages());
    }
}
