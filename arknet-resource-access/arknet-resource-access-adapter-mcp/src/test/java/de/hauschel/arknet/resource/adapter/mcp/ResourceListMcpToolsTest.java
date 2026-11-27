// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.resource.application.port.in.ListResources;
import de.hauschel.arknet.resource.application.port.in.ListedResource;
import de.hauschel.arknet.resource.application.port.in.RenderedValue;
import de.hauschel.arknet.resource.application.port.in.ResourceListing;
import de.hauschel.arknet.resource.application.port.in.ResourceReading;

class ResourceListMcpToolsTest {

    private static final ProjectId PROJECT = new ProjectId("p");
    private static final RenderedValue.Reference DRAFT = new RenderedValue.Reference("https://example.org/m#Draft",
            "m:Draft", "Draft");
    private static final ListedResource FIRST = new ListedResource("https://example.org/id/1", "T-1", "Thing",
            Optional.of(new RenderedValue.Text("Titel", Optional.of("de"), Optional.empty(), true)),
            Optional.of(DRAFT));
    private static final ListedResource BARE = new ListedResource("https://example.org/id/2",
            "https://example.org/id/2", "Link", Optional.empty(), Optional.empty());

    private final List<String> resolvedAnchors = new ArrayList<>();
    private final List<String> requests = new ArrayList<>();
    private ResourceListing answer = new ResourceListing(0, 0, ListResources.COMPACT_LIMIT, List.of(), List.of());

    private final ProjectResolver projects = anchor -> {
        resolvedAnchors.add(anchor);
        return new ResolvedProject(PROJECT, "de");
    };

    private final ListResources lister = (projectId, type, status, displayLocale, full, offset) -> {
        requests.add(projectId.value() + " " + type + " " + status + " " + displayLocale + " " + full + " "
                + offset);
        return answer;
    };

    private final ResourceListMcpTools tools = new ResourceListMcpTools(lister, projects);

    @Test
    void theArgumentsReachTheInPortWithTheProjectLanguageAndOffsetZeroAsDefault() {
        tools.resourceList(null, null, null, null, null, null, "/anchor");
        tools.resourceList(null, "Thing", "Draft", true, 50, "en", null);

        assertEquals(List.of("/anchor"), resolvedAnchors.subList(0, 1));
        assertEquals(List.of("p null null de false 0", "p Thing Draft en true 50"), requests);
    }

    @Test
    void aCompactListingIsOneLinePerResourceWithLabelStatusAndFallbackMarks() {
        answer = new ResourceListing(2, 0, ListResources.COMPACT_LIMIT, List.of(FIRST, BARE), List.of());

        String text = tools.resourceList(null, null, null, null, null, null, null);

        assertEquals("""
                # Resources 1-2 of 2, in code order
                - T-1 [Thing] "Titel"@de [fallback: de] - Status: m:Draft "Draft"
                - https://example.org/id/2 [Link]
                """, text);
    }

    @Test
    void aListingThatDoesNotFitNamesWhatIsLeftAndWhereToContinue() {
        answer = new ResourceListing(205, 0, ListResources.COMPACT_LIMIT, List.of(FIRST), List.of());

        String text = tools.resourceList(null, null, null, null, null, null, null);

        assertEquals("""
                # Resources 1-1 of 205, in code order
                - T-1 [Thing] "Titel"@de [fallback: de] - Status: m:Draft "Draft"

                NOT SHOWN: 204 more of 205 (at most 200 per call). Call again with offset=1 to continue.
                """, text);
    }

    @Test
    void anEmptyListingAndAnOffsetPastTheEndSaySo() {
        assertEquals("No resources match.\n", tools.resourceList(null, null, null, null, null, null, null));

        answer = new ResourceListing(3, 10, ListResources.COMPACT_LIMIT, List.of(), List.of());

        assertEquals("# 3 resources match; the offset 10 is past the last one. Call again with a smaller offset.\n",
                tools.resourceList(null, null, null, null, 10, null, null));
    }

    @Test
    void aFullListingRendersEachResourceLikeResourceGetAndStillNamesTheRest() {
        answer = new ResourceListing(51, 0, ListResources.FULL_LIMIT, List.of(FIRST),
                List.of(new ResourceReading.Unresolved("T-1",
                        "no resource carries the code T-1")));

        String text = tools.resourceList(null, null, null, true, null, null, null);

        assertEquals("""
                # Resources 1-1 of 51, in code order

                # T-1 NOT FOUND
                - no resource carries the code T-1

                NOT SHOWN: 50 more of 51 (at most 50 per call). Call again with offset=1 to continue.
                """, text);
    }

    @Test
    void aRejectionOfTheInPortReachesTheCaller() {
        ResourceListMcpTools rejecting = new ResourceListMcpTools((projectId, type, status, locale, full, offset) -> {
            throw new IllegalArgumentException("No resource class is named");
        }, projects);

        assertEquals("No resource class is named", assertThrows(IllegalArgumentException.class,
                () -> rejecting.resourceList(null, "x", null, null, null, null, null)).getMessage());
    }
}
