// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.resource.application.port.in.FindText;
import de.hauschel.arknet.resource.application.port.in.TextHit;
import de.hauschel.arknet.resource.application.port.in.TextSearchResult;

class TextSearchMcpToolsTest {

    private static final ProjectId PROJECT = new ProjectId("p");

    private final List<String> resolvedAnchors = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private TextSearchResult answer = new TextSearchResult("q", 0, FindText.LIMIT, List.of());

    private final ProjectResolver projects = anchor -> {
        resolvedAnchors.add(anchor);
        return new ResolvedProject(PROJECT, "de");
    };

    private final FindText finder = (projectId, query, type) -> {
        requests.add(projectId.value() + " " + query + " " + type);
        return answer;
    };

    private final TextSearchMcpTools tools = new TextSearchMcpTools(finder, projects);

    private static TextHit hit(final String handle, final String field) {
        return new TextHit(handle, field, Optional.empty(), Optional.of("en"), "...snippet...");
    }

    @Test
    void theArgumentsReachTheInPortAndTheAnchorTheResolver() {
        tools.textSearch(null, "login", "Requirement", "/anchor");

        assertEquals(List.of("/anchor"), resolvedAnchors);
        assertEquals(List.of("p login Requirement"), requests);
    }

    @Test
    void noHitsReportsCleanlyInsteadOfAnEmptyGroupList() {
        assertEquals("No hits for \"q\".\n", tools.textSearch(null, "q", null, null));
    }

    @Test
    void severalHitsOnOneResourceShareOneHeadingAndDifferentOnesGetTheirOwn() {
        answer = new TextSearchResult("login", 3, FindText.LIMIT, List.of(hit("FR-1", "dcterms:title"),
                hit("FR-1", "arkreq:rationale"), hit("UC1", "arkreq:goal")));

        String report = tools.textSearch(null, "login", null, null);

        assertEquals("""
                3 hit(s) for "login" across 2 resource(s):

                ## FR-1
                - dcterms:title [en]: ...snippet...
                - arkreq:rationale [en]: ...snippet...

                ## UC1
                - arkreq:goal [en]: ...snippet...

                """, report);
    }

    @Test
    void theViaFieldAndTheLanguageTagShowOnTheFieldLineAndAMissingTagIsLeftOut() {
        answer = new TextSearchResult("c", 2, FindText.LIMIT, List.of(
                new TextHit("UC1", "arkreq:stepText", Optional.of("arkreq:step"), Optional.of("en"), "s"),
                new TextHit("FR-1", "arknet:category", Optional.empty(), Optional.empty(), "t")));

        String report = tools.textSearch(null, "c", null, null);

        assertTrue(report.contains("- arkreq:stepText (via arkreq:step) [en]: s\n"), report);
        assertTrue(report.contains("- arknet:category: t\n"), report);
    }

    @Test
    void aCutResultNamesHowManyAreNotShownAndHowToNarrowIt() {
        answer = new TextSearchResult("common", 350, FindText.LIMIT, List.of(hit("FR-1", "dcterms:title")));

        String report = tools.textSearch(null, "common", null, null);

        assertTrue(report.endsWith("NOT SHOWN: 349 more of 350 matching statements (at most 200 per call)."
                + " Narrow the query or pass a type to see them.\n"), report);
    }

    @Test
    void aCompleteResultSaysNothingAboutCutting() {
        answer = new TextSearchResult("login", 1, FindText.LIMIT, List.of(hit("FR-1", "dcterms:title")));

        assertTrue(!tools.textSearch(null, "login", null, null).contains("NOT SHOWN"));
    }

    @Test
    void aRejectionOfTheInPortReachesTheCaller() {
        TextSearchMcpTools rejecting = new TextSearchMcpTools((projectId, query, type) -> {
            throw new IllegalArgumentException("query must not be blank");
        }, projects);

        assertEquals("query must not be blank",
                assertThrows(IllegalArgumentException.class, () -> rejecting.textSearch(null, " ", null, null))
                        .getMessage());
    }

    @Test
    void declaresExactlyOneReadOnlyToolThatSaysWhatItSeesImpactAnalysisDoesNot() {
        List<McpTool> declared = Arrays.stream(TextSearchMcpTools.class.getDeclaredMethods())
                .map((Method method) -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .toList();

        assertEquals(1, declared.size());
        assertEquals("text_search", declared.get(0).name());
        assertTrue(declared.get(0).annotations().readOnlyHint());
        assertTrue(declared.get(0).description().contains("impact_analysis"));
    }
}
