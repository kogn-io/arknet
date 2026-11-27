// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.resource.application.port.in.HistoryReading;
import de.hauschel.arknet.resource.application.port.in.ReadResources;
import de.hauschel.arknet.resource.application.port.in.RenderedField;
import de.hauschel.arknet.resource.application.port.in.RenderedResource;
import de.hauschel.arknet.resource.application.port.in.RenderedValue;
import de.hauschel.arknet.resource.application.port.in.ResourceHistory;
import de.hauschel.arknet.resource.application.port.in.ResourceReading;

class ResourceReadMcpToolsTest {

    private static final ProjectId PROJECT = new ProjectId("p");
    private static final String IRI = "https://example.org/id/1";
    private static final RenderedValue.Reference THING = new RenderedValue.Reference("https://example.org/m#Thing",
            "m:Thing", "Thing");
    private static final RenderedValue.Reference DRAFT = new RenderedValue.Reference("https://example.org/m#Draft",
            "m:Draft", "Draft");
    private static final RenderedValue.Text TITLE = new RenderedValue.Text("Titel", Optional.of("de"),
            Optional.empty(), true);
    private static final RenderedField TITLE_FIELD = new RenderedField("https://example.org/m#title", "m:title",
            "Title", List.of(TITLE));
    private static final RenderedField PARTS = new RenderedField("https://example.org/m#part", "m:part", "Part",
            List.of(new RenderedValue.Child(IRI + "#Part-b", Optional.of(1), List.of(new RenderedField(
                    "https://example.org/m#text", "m:text", "Text", List.of(new RenderedValue.Text("first",
                            Optional.of("en"), Optional.empty(), false)))), List.of()),
                    new RenderedValue.Child(IRI + "#Part-a", Optional.of(2), List.of(), List.of())));
    private static final RenderedField MENTIONS = new RenderedField("https://example.org/m#mentions", "m:mentions",
            "Mentions", List.of(new RenderedValue.Reference("https://example.org/id/2", "T-2", "Other"),
                    new RenderedValue.Reference("https://example.org/id/3", "https://example.org/id/3",
                            "Link https://example.org/id/3")));
    private static final RenderedField DATED = new RenderedField("https://example.org/m#on", "m:on", "On",
            List.of(new RenderedValue.Text("2026-01-01", Optional.empty(), Optional.of("xsd:date"), false)));
    private static final RenderedResource RESOURCE = new RenderedResource(IRI, "T-1", "Thing", List.of(THING),
            Optional.of(TITLE), Optional.of(DRAFT), Optional.of("2026-01-01T00:00:00Z"),
            Optional.of("2026-02-01T00:00:00Z"), List.of(TITLE_FIELD, PARTS, DATED), List.of(MENTIONS),
            List.of(new RenderedField("https://example.org/old#legacy", "<https://example.org/old#legacy>",
                    "legacy", List.of(new RenderedValue.Text("flat", Optional.empty(), Optional.empty(), false)))));

    private final List<String> resolvedAnchors = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private final List<ResourceReading> answers = new ArrayList<>();
    private HistoryReading history;

    private final ProjectResolver projects = anchor -> {
        resolvedAnchors.add(anchor);
        return new ResolvedProject(PROJECT, "de");
    };

    private final ReadResources reader = (projectId, handles, displayLocale, allLanguages) -> {
        requests.add(projectId.value() + " " + handles + " " + displayLocale + " " + allLanguages);
        return List.copyOf(answers);
    };

    private final ResourceHistory histories = (projectId, handle, displayLocale) -> {
        requests.add(projectId.value() + " " + handle + " " + displayLocale);
        return history;
    };

    private final ResourceReadMcpTools tools = new ResourceReadMcpTools(reader, histories, projects);

    @Test
    void handlesAreSplitAtCommasAndWhiteSpaceAndReadInTheProjectLanguage() {
        // given
        answers.add(new ResourceReading.Unresolved("T-1", "no resource carries the code T-1"));

        // when
        tools.resourceGet(null, " T-1, T-2\n<https://example.org/id/3>  ", null, null, "/anchor");

        // then
        assertEquals(List.of("/anchor"), resolvedAnchors);
        assertEquals(List.of("p [T-1, T-2, <https://example.org/id/3>] de false"), requests);
    }

    @Test
    void anExplicitDisplayLanguageAndAllLanguagesReachTheInPort() {
        // when
        tools.resourceGet(null, "T-1", "en", true, null);

        // then
        assertEquals(List.of("p [T-1] en true"), requests);
    }

    @Test
    void noHandleOrMoreThanTheCapIsRejectedBeforeAnyRead() {
        String tooMany = String.join(",", IntStream.rangeClosed(1, ResourceReadMcpTools.MAX_HANDLES + 1)
                .mapToObj(number -> "T-" + number).toList());

        assertTrue(assertThrows(IllegalArgumentException.class, () -> tools.resourceGet(null, " , ", null, null, null))
                .getMessage().contains("at least one handle"));
        assertTrue(assertThrows(IllegalArgumentException.class, () -> tools.resourceGet(null, tooMany, null, null,
                null)).getMessage().contains("At most 50 handles"));
        assertEquals(ResourceReadMcpTools.MAX_HANDLES, ResourceReadMcpTools.handles(tooMany.substring(0,
                tooMany.lastIndexOf(','))).size());
        assertTrue(requests.isEmpty());
    }

    @Test
    void aFoundResourceRendersHeadingFieldsChildrenInOrderOtherStatementsAndIncoming() {
        // given
        answers.add(new ResourceReading.Found("T-1", RESOURCE));

        // when
        String answer = tools.resourceGet(null, "T-1", null, null, null);

        // then
        assertEquals("""
                # T-1 [Thing] "Titel"@de [fallback: de]
                IRI: <https://example.org/id/1>
                Type: m:Thing "Thing"
                Status: m:Draft "Draft"
                Created: 2026-01-01T00:00:00Z
                Last changed: 2026-02-01T00:00:00Z
                ## Fields
                - Title (m:title): "Titel"@de [fallback: de]
                - Part (m:part):
                  - [1] <https://example.org/id/1#Part-b>
                    - Text (m:text): "first"@en
                  - [2] <https://example.org/id/1#Part-a>
                - On (m:on): "2026-01-01"^^xsd:date
                ## Other statements (outside the shapes)
                - legacy (<https://example.org/old#legacy>): "flat"
                ## Incoming (what points here)
                - Mentions (m:mentions):
                  - T-2 "Other"
                  - https://example.org/id/3 "Link https://example.org/id/3"
                """, answer);
    }

    @Test
    void severalReadingsRenderAsBlocksInTheOrderOfTheHandles() {
        // given
        answers.add(new ResourceReading.Unresolved("_:b1", "a blank node has no handle"));
        answers.add(new ResourceReading.Found("T-1", RESOURCE));
        answers.add(new ResourceReading.Deleted("T-9", "https://example.org/id/9", Optional.of("2026-03-01T00:00:00Z"),
                Optional.of("gone"), Optional.of("T-9"), List.of(TITLE_FIELD)));

        // when
        String answer = tools.resourceGet(null, "_:b1 T-1 T-9", null, null, null);

        // then
        assertTrue(answer.startsWith("# _:b1 NOT FOUND\n- a blank node has no handle\n\n# T-1 [Thing]"), answer);
        assertTrue(answer.endsWith("""

                # T-9 DELETED
                IRI: <https://example.org/id/9>
                - deleted at 2026-03-01T00:00:00Z ("gone")
                - code T-9 stays taken, it is never assigned to another resource
                ## Last version
                - Title (m:title): "Titel"@de [fallback: de]
                """), answer);
    }

    @Test
    void aDeletionWithoutContentSaysWhyTheContentIsNotRecorded() {
        // given
        answers.add(new ResourceReading.Deleted("T-8", "https://example.org/id/8", Optional.of("2026-03-01T00:00:00Z"),
                Optional.empty(), Optional.empty(), List.of()));
        answers.add(new ResourceReading.Deleted("T-7", "https://example.org/id/7", Optional.empty(),
                Optional.empty(), Optional.of("T-7"), List.of()));

        // when
        String answer = tools.resourceGet(null, "T-8 T-7", null, null, null);

        // then
        assertTrue(answer.contains("- the content is not recorded: the resource was deleted without ever having been"
                + " published\n"), answer);
        assertTrue(answer.contains("- deleted before versioning: the deletion time is not recorded\n"
                + "- code T-7 stays taken, it is never assigned to another resource\n"
                + "- the content is not recorded: the storage layout of that time kept only the code"), answer);
        assertFalse(answer.contains("## Last version"), answer);
    }

    @Test
    void theHistoryNamesPublishStateCreatedLastChangedAndEveryEntry() {
        // given
        history = new HistoryReading("T-1", IRI, "PUBLISHED_UNCHANGED", List.of(
                new HistoryReading.Entry(1, false, "2026-01-01T00:00:00Z", Optional.of("bafy1"), Optional.empty()),
                new HistoryReading.Entry(2, false, "2026-02-01T00:00:00Z", Optional.of("bafy2"),
                        Optional.of("renamed"))), Optional.empty());

        // when
        String answer = tools.resourceHistory(null, "T-1", null, "/anchor");

        // then
        assertEquals(List.of("p T-1 de"), requests);
        assertEquals("""
                # T-1
                IRI: <https://example.org/id/1>
                Publish state: PUBLISHED_UNCHANGED
                Created: 2026-01-01T00:00:00Z
                Last changed: 2026-02-01T00:00:00Z

                ## History (2)
                1. 2026-01-01T00:00:00Z  publish  bafy1
                2. 2026-02-01T00:00:00Z  publish  bafy2  "renamed"
                """, answer);
    }

    @Test
    void theHistoryOfADeletedResourceShowsTheDeletionBeforeTheEntries() {
        // given
        history = new HistoryReading("T-9", IRI, "DELETED", List.of(
                new HistoryReading.Entry(1, false, "2026-01-01T00:00:00Z", Optional.of("bafy1"), Optional.empty()),
                new HistoryReading.Entry(2, true, "2026-03-01T00:00:00Z", Optional.empty(), Optional.empty())),
                Optional.of(new ResourceReading.Deleted("T-9", IRI, Optional.of("2026-03-01T00:00:00Z"),
                        Optional.empty(), Optional.of("T-9"), List.of(TITLE_FIELD))));

        // when
        String answer = tools.resourceHistory(null, "T-9", "en", null);

        // then
        assertTrue(answer.contains("Publish state: DELETED\n"), answer);
        assertTrue(answer.contains("\n## DELETED\n- deleted at 2026-03-01T00:00:00Z\n"), answer);
        assertTrue(answer.contains("## Last version\n- Title (m:title)"), answer);
        assertTrue(answer.endsWith("## History (2)\n1. 2026-01-01T00:00:00Z  publish  bafy1\n"
                + "2. 2026-03-01T00:00:00Z  tombstone\n"), answer);
    }

    @Test
    void anEmptyHistorySaysWhyItIsEmpty() {
        // given
        history = new HistoryReading("T-1", IRI, "NEVER_PUBLISHED", List.of(), Optional.empty());

        // when
        String unpublished = tools.resourceHistory(null, "T-1", null, null);
        history = new HistoryReading("T-7", IRI, "DELETED", List.of(), Optional.of(new ResourceReading.Deleted("T-7",
                IRI, Optional.empty(), Optional.empty(), Optional.of("T-7"), List.of())));
        String beforeVersioning = tools.resourceHistory(null, "T-7", null, null);

        // then
        assertTrue(unpublished.endsWith("## History (0)\n- never published - every write through the tools publishes"
                + " a version, so this resource was only ever written past the tools\n"), unpublished);
        assertFalse(unpublished.contains("Created:"), unpublished);
        assertTrue(beforeVersioning.endsWith("## History (0)\n- no entries - the resource was deleted before the store"
                + " recorded a history\n"), beforeVersioning);
    }
}
