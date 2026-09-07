// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Nails down the rules of the stale-translation signal (kogn-io/arknet#474): which combination of
 * written language, maintained language set and already-present language tags produces a hint at
 * all, and what it says.
 */
class StaleTranslationHintTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Builds the "field -> tags this field already carries" argument in a defined order. */
    private static LinkedHashMap<String, Set<String>> fields(final Object... pairs) {
        final LinkedHashMap<String, Set<String>> byField = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            @SuppressWarnings("unchecked")
            final Set<String> tags = (Set<String>) pairs[i + 1];
            byField.put((String) pairs[i], tags);
        }
        return byField;
    }

    @Test
    void namesTheFieldsWhoseOtherMaintainedLanguageThisCallDidNotWrite() {
        final String hint = StaleTranslationHint.render("de", List.of("de", "en"),
                fields("definition", Set.of("de", "en")));

        assertTrue(hint.contains("this call wrote 'de'"), hint);
        assertTrue(hint.contains("en: definition"), hint);
    }

    @Test
    void groupsSeveralFieldsUnderTheSameLanguageInTheOrderTheyWereWritten() {
        final String hint = StaleTranslationHint.render("de", List.of("de", "en"),
                fields("title", Set.of("de", "en"), "description", Set.of("de", "en")));

        assertTrue(hint.contains("en: title, description"), hint);
    }

    @Test
    void listsOneLineForEachMaintainedLanguageInTheOrderTheProjectDeclaredThem() {
        final String hint = StaleTranslationHint.render("de", List.of("de", "en", "fr"),
                fields("title", Set.of("de", "en", "fr"), "description", Set.of("de", "fr")));

        assertTrue(hint.contains("en: title"), hint);
        assertTrue(hint.contains("fr: title, description"), hint);
        assertTrue(hint.indexOf("en:") < hint.indexOf("fr:"), hint);
    }

    /** A project that maintains a single language has no other language to go stale. */
    @Test
    void staysSilentForASingleMaintainedLanguage() {
        assertEquals("", StaleTranslationHint.render("de", List.of("de"),
                fields("definition", Set.of("de", "en"))));
    }

    /** No declared set means no target state - the very reason issue #412 had to come first. */
    @Test
    void staysSilentWithoutAMaintainedLanguageSet() {
        assertEquals("", StaleTranslationHint.render("de", List.of(),
                fields("definition", Set.of("de", "en"))));
    }

    /**
     * A field carrying no other maintained language is a gap, not a staleness -
     * {@code store_check}'s LANGUAGE check reports it, this hint must not.
     */
    @Test
    void staysSilentWhenTheWrittenFieldCarriesNoOtherMaintainedLanguage() {
        assertEquals("", StaleTranslationHint.render("de", List.of("de", "en"),
                fields("definition", Set.of("de"))));
    }

    /**
     * The tags are the state before the write: a field that does not yet carry the written
     * language is being translated, and the variant it does carry is the source of that
     * translation, not something out of date - the second call of this repository's own
     * two-call workflow must end without a hint.
     */
    @Test
    void staysSilentWhenTheCallAddsATranslation() {
        assertEquals("", StaleTranslationHint.render("en", List.of("de", "en"),
                fields("definition", Set.of("de"))));
    }

    /** A field that never carried anything is being written for the first time - nothing is stale. */
    @Test
    void staysSilentForAFieldWrittenForTheFirstTime() {
        assertEquals("", StaleTranslationHint.render("en", List.of("de", "en"),
                fields("rationale", Set.of())));
    }

    /**
     * One call can correct one field and translate another: only the field that already carried
     * the written language is reported.
     */
    @Test
    void reportsOnlyTheFieldsThatAlreadyCarriedTheWrittenLanguage() {
        final String hint = StaleTranslationHint.render("en", List.of("de", "en"),
                fields("title", Set.of("de", "en"), "description", Set.of("de")));

        assertTrue(hint.contains("de: title"), hint);
        assertFalse(hint.contains("description"), hint);
    }

    /** A language the field carries but the project does not maintain is nobody's promise. */
    @Test
    void ignoresALanguageTheProjectDoesNotMaintain() {
        assertEquals("", StaleTranslationHint.render("de", List.of("de", "en"),
                fields("definition", Set.of("de", "it"))));
    }

    /** A call that touched no multilingual field at all writes no language to compare against. */
    @Test
    void staysSilentWithoutAWrittenField() {
        assertEquals("", StaleTranslationHint.render("de", List.of("de", "en"), fields()));
    }

    /** An untagged write (project_update without a language) has no written language to name. */
    @Test
    void staysSilentWithoutAWrittenLanguage() {
        assertEquals("", StaleTranslationHint.render(null, List.of("de", "en"),
                fields("description", Set.of("de", "en"))));
    }

    /** Tags are compared case-insensitively, exactly as {@code store_check}'s LANGUAGE check does. */
    @Test
    void comparesLanguageTagsCaseInsensitively() {
        assertEquals("", StaleTranslationHint.render("DE", List.of("de", "en"),
                fields("definition", Set.of("de"))));
        assertTrue(StaleTranslationHint.render("DE", List.of("de", "en"),
                fields("definition", Set.of("de", "en"))).contains("en: definition"));
        assertTrue(StaleTranslationHint.render("de", List.of("de", "EN"),
                fields("definition", Set.of("de", "en"))).contains("EN: definition"));
    }

    /** The wording never claims a per-literal revision - the store records none (see the javadoc). */
    @Test
    void doesNotClaimARevisionItCannotKnow() {
        final String hint = StaleTranslationHint.render("de", List.of("de", "en"),
                fields("definition", Set.of("de", "en")));

        assertFalse(hint.toLowerCase(java.util.Locale.ROOT).contains("revision"), hint);
    }

    // --- the lookup-backed entry points ---------------------------------------

    @Test
    void readsTheTagsOfAResourceThroughTheLookupAndPrependsABlankLine() {
        final StaleTranslationHint hints = new StaleTranslationHint(lookup(
                Map.of("definition", Set.of("de", "en"))));

        final String section = hints.forResource(PROJECT, "TERM-1", "de", List.of("de", "en"),
                List.of("definition"));

        assertTrue(section.startsWith("\n\n"), section);
        assertTrue(section.contains("en: definition"), section);
    }

    /** Nothing to report must not cost a store read. */
    @Test
    void doesNotConsultTheLookupWhenNoOtherLanguageIsMaintained() {
        final StaleTranslationHint hints = new StaleTranslationHint(new FieldLanguageLookup() {
            @Override
            public Map<String, Set<String>> ofResource(final ProjectId projectId, final String code) {
                throw new AssertionError("the lookup must not be consulted");
            }

            @Override
            public Map<String, Set<String>> ofProjectRegistration(final String projectLabel) {
                throw new AssertionError("the lookup must not be consulted");
            }
        });

        assertEquals("", hints.forResource(PROJECT, "TERM-1", "de", List.of("de"), List.of("definition")));
        assertEquals("", hints.forProjectRegistration("acme", "de", List.of("de"), List.of("description")));
    }

    @Test
    void readsTheProjectRegistrationThroughItsOwnLookupMethod() {
        final StaleTranslationHint hints = new StaleTranslationHint(new FieldLanguageLookup() {
            @Override
            public Map<String, Set<String>> ofResource(final ProjectId projectId, final String code) {
                throw new AssertionError("a project registration does not live in the project's own dataset");
            }

            @Override
            public Map<String, Set<String>> ofProjectRegistration(final String projectLabel) {
                return "acme".equals(projectLabel) ? Map.of("description", Set.of("de", "en")) : Map.of();
            }
        });

        assertTrue(hints.forProjectRegistration("acme", "de", List.of("de", "en"), List.of("description"))
                .contains("en: description"));
    }

    @Test
    void rejectsANullLookup() {
        assertThrows(NullPointerException.class, () -> new StaleTranslationHint(null));
    }

    private static FieldLanguageLookup lookup(final Map<String, Set<String>> byField) {
        return new FieldLanguageLookup() {
            @Override
            public Map<String, Set<String>> ofResource(final ProjectId projectId, final String code) {
                return byField;
            }

            @Override
            public Map<String, Set<String>> ofProjectRegistration(final String projectLabel) {
                return byField;
            }
        };
    }
}
