// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.search;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link TextSearchRenderer}: grouping, the truncation notice and the no-hit
 * message - all with hand-built {@link SearchHit}s, so the cap behaviour ({@link
 * TextSearchMcpTools#MAX_HITS}) does not need 200 real store writes to exercise.
 */
class TextSearchRendererTest {

    private static SearchHit hit(String owner, String field) {
        return new SearchHit(owner, field, Optional.empty(), Optional.of("en"), "...snippet...");
    }

    @Test
    void reportsNoHitsWithoutAnEmptyGroupList() {
        String report = TextSearchRenderer.render("nothing", 0, List.of(), 200);

        assertThat(report).contains("No hits").contains("nothing");
    }

    @Test
    void groupsSeveralHitsOnTheSameOwnerUnderOneHeading() {
        List<SearchHit> hits = List.of(hit("FR-1", "dcterms:title"), hit("FR-1", "arkreq:rationale"));

        String report = TextSearchRenderer.render("login", 2, hits, 200);

        assertThat(report).containsOnlyOnce("## FR-1");
        assertThat(report).contains("dcterms:title").contains("arkreq:rationale");
    }

    @Test
    void keepsTwoDifferentOwnersInTwoHeadings() {
        List<SearchHit> hits = List.of(hit("FR-1", "dcterms:title"), hit("UC1", "arkreq:stepText"));

        String report = TextSearchRenderer.render("login", 2, hits, 200);

        assertThat(report).contains("## FR-1").contains("## UC1");
    }

    @Test
    void notesTheViaEdgeAndTheLanguageTagOnTheFieldLine() {
        SearchHit stepHit = new SearchHit("UC1", "arkreq:stepText", Optional.of("arkreq:mainStep"),
                Optional.of("en"), "...credentials...");

        String report = TextSearchRenderer.render("credentials", 1, List.of(stepHit), 200);

        assertThat(report).contains("arkreq:stepText (via arkreq:mainStep) [en]");
    }

    @Test
    void omitsTheLanguageTagWhenTheLiteralCarriesNone() {
        SearchHit untagged = new SearchHit("FR-1", "arknet:qualityCategory", Optional.empty(), Optional.empty(),
                "...snippet...");

        String report = TextSearchRenderer.render("snippet", 1, List.of(untagged), 200);

        assertThat(report).contains("arknet:qualityCategory: ...snippet...");
    }

    @Test
    void notesTruncationWhenMoreStatementsMatchedThanWereRendered() {
        String report = TextSearchRenderer.render("common", 350, List.of(hit("FR-1", "dcterms:title")), 200);

        assertThat(report).contains("showing the first 200 of 350").contains("narrow the query");
    }

    @Test
    void doesNotMentionTruncationWhenEveryMatchWasRendered() {
        String report = TextSearchRenderer.render("login", 1, List.of(hit("FR-1", "dcterms:title")), 200);

        assertThat(report).doesNotContain("showing the first");
    }
}
