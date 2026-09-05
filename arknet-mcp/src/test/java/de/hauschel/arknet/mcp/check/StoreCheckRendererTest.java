// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.check;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.mcp.check.LanguageGapCheck.Gap;
import de.hauschel.arknet.mcp.store.Prefixes;

/** Unit tests for {@code store_check}'s text output. */
class StoreCheckRendererTest {

    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String ID = "https://w3id.org/arknet/id/";

    private final StoreCheckRenderer renderer = new StoreCheckRenderer(Prefixes.defaults());

    /**
     * The distinction the whole issue turns on: with no declared set there is no target state, so
     * "no gaps" would answer a question nobody asked. The section has to say it did not check, and
     * name the way to make it checkable.
     */
    @Test
    void saysItDidNotCheckRatherThanReportingACleanResultWhenNoLanguagesAreMaintained() {
        String rendered = renderer.languageSection(List.of(), List.of());

        assertThat(rendered).contains("not checked").contains("project_update");
        assertThat(rendered).doesNotContain("No field is missing");
    }

    @Test
    void reportsACleanResultOnlyWhenThereIsSomethingToBeCleanAgainst() {
        String rendered = renderer.languageSection(List.of("de", "en"), List.of());

        assertThat(rendered).contains("maintained languages de, en").contains("No field is missing");
    }

    @Test
    void rendersOneRowPerFieldWithTheShortenedPredicateAndASummaryLine() {
        String rendered = renderer.languageSection(List.of("de", "en"), List.of(
                new Gap(ID + "t1", "TERM-1", "Concept", SKOS + "definition", List.of("en")),
                new Gap(ID + "t2", "TERM-2", "Concept", SKOS + "definition", List.of("en"))));

        assertThat(rendered)
                .contains("| Resource | Type | Field | Missing |")
                .contains("| TERM-1 | Concept | skos:definition | en |")
                .contains("2 fields on 2 resources missing a maintained language.");
    }

    /** A resource with no code of its own must still be addressable, so the IRI is shortened, not dropped. */
    @Test
    void fallsBackToTheShortenedIriForAResourceWithoutAHandle() {
        String rendered = renderer.languageSection(List.of("de", "en"),
                List.of(new Gap(ID + "abc", null, null, SKOS + "definition", List.of("en"))));

        assertThat(rendered).contains("| " + ID + "abc | - | skos:definition | en |");
    }

    /** An empty section that does not say what it could not see reads as "reviewed". */
    @Test
    void alwaysNamesItsBlindSpotWhetherItFoundSomethingOrNot() {
        assertThat(renderer.languageSection(List.of("de", "en"), List.of()))
                .contains(StoreCheckRenderer.BLIND_SPOT);
        assertThat(renderer.languageSection(List.of("de", "en"),
                List.of(new Gap(ID + "t1", "TERM-1", "Concept", SKOS + "definition", List.of("en")))))
                .contains(StoreCheckRenderer.BLIND_SPOT);
    }

    @Test
    void headsTheReportWithTheChecksThatActuallyRan() {
        assertThat(renderer.report(List.of(StoreCheckKind.LANGUAGE), List.of("body")))
                .startsWith("store_check: LANGUAGE")
                .endsWith("body");
    }
}
