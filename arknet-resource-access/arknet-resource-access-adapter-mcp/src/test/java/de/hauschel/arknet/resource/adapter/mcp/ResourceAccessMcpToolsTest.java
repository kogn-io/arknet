// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.resource.application.port.in.DescribeResourceType;
import de.hauschel.arknet.resource.application.port.in.FieldDescription;
import de.hauschel.arknet.resource.application.port.in.FieldDescription.Kind;
import de.hauschel.arknet.resource.application.port.in.FieldDescription.OwnedChildren;
import de.hauschel.arknet.resource.application.port.in.FieldDescription.Presence;
import de.hauschel.arknet.resource.application.port.in.ResourceCatalog;
import de.hauschel.arknet.resource.application.port.in.ResourceCatalog.VocabularyTerm;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeDescription;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeSummary;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;

class ResourceAccessMcpToolsTest {

    private static final ProjectId PROJECT = new ProjectId("p");
    private static final ResourceTypeSummary TYPE = new ResourceTypeSummary("https://example.org/m#Thing",
            "m:Thing", "Thing", "A thing worth keeping.", Optional.of("T-"));
    private static final FieldDescription POSITION = new FieldDescription("https://example.org/m#position",
            "m:position", "position", "", Kind.VALUE, Presence.REQUIRED, Optional.of(1), Optional.of("xsd:integer"),
            List.of(), List.of(), false, false, List.of(), Optional.empty());
    private static final FieldDescription TITLE = new FieldDescription("https://example.org/m#title", "m:title",
            "Title", "What it is called.", Kind.TEXT, Presence.REQUIRED, Optional.empty(), Optional.empty(),
            List.of(), List.of(), true, false, List.of("A thing needs a title."), Optional.empty());
    private static final FieldDescription PARTS = new FieldDescription("https://example.org/m#part", "m:part",
            "Part", "", Kind.CHILDREN, Presence.OPTIONAL, Optional.empty(), Optional.empty(), List.of("m:Part"),
            List.of(), false, false, List.of(), Optional.of(new OwnedChildren("Part", "m:position",
                    List.of(POSITION))));

    private final List<String> requestedLocales = new ArrayList<>();
    private final List<String> resolvedAnchors = new ArrayList<>();
    private final List<String> submitted = new ArrayList<>();

    private final DescribeResourceType describer = new DescribeResourceType() {
        @Override
        public ResourceCatalog catalog(final String displayLocale) {
            requestedLocales.add(displayLocale);
            return new ResourceCatalog(List.of(TYPE), List.of(new VocabularyTerm("arkres:Create", "Create",
                    "Creates a new resource.")), "@prefix m: <https://example.org/m#> .\n");
        }

        @Override
        public ResourceTypeDescription describe(final String type, final String displayLocale) {
            requestedLocales.add(displayLocale);
            return new ResourceTypeDescription(TYPE, List.of(TITLE, PARTS), List.of("Spans two fields."),
                    "@prefix m: <https://example.org/m#> .\n[] a arkres:Create .\n");
        }
    };

    private final ProjectResolver projects = anchor -> {
        resolvedAnchors.add(anchor);
        return new ResolvedProject(PROJECT, "de");
    };

    private final ResourceAccessMcpTools tools = new ResourceAccessMcpTools(describer, (projectId, document) -> {
        submitted.add(projectId.value() + ":" + document);
        return new SubmitResult(List.of(new SubmittedChange(SubmittedChange.Kind.CREATED, "https://x/1", "T-1"),
                new SubmittedChange(SubmittedChange.Kind.UPDATED, "https://x/2", null)));
    }, projects);

    @Test
    void describeWithoutATypeListsTypesActivitiesAndPrefixesInTheProjectLanguage() {
        // when
        String answer = tools.describe(null, null, null, "/anchor");

        // then
        assertEquals(List.of("de"), requestedLocales);
        assertEquals(List.of("/anchor"), resolvedAnchors);
        assertTrue(answer.contains("- m:Thing (Thing), codes T-N - A thing worth keeping."), answer);
        assertTrue(answer.contains("- arkres:Create (Create) - Creates a new resource."), answer);
        assertTrue(answer.contains("@prefix m: <https://example.org/m#> ."), answer);
    }

    @Test
    void describeWithATypeRendersFieldsChildrenRulesAndTheExample() {
        // when
        String answer = tools.describe(null, "m:Thing", "en", null);

        // then
        assertEquals(List.of("en"), requestedLocales);
        assertTrue(resolvedAnchors.isEmpty(), "an explicit display language needs no project");
        assertTrue(answer.contains("Business codes start with T- and are assigned by submit."), answer);
        assertTrue(answer.contains("- m:title (Title): required, text with a language tag, one value per language"),
                answer);
        assertTrue(answer.contains("Rule: A thing needs a title."), answer);
        assertTrue(answer.contains("its position in m:position"), answer);
        assertTrue(answer.contains("    - m:position (position): required, literal of xsd:integer, at most one value"),
                answer);
        assertTrue(answer.contains("Rules across fields:\n- Spans two fields."), answer);
        assertTrue(answer.endsWith("[] a arkres:Create .\n"), answer);
    }

    @Test
    void submitAppliesTheDocumentToTheResolvedProjectAndListsTheChanges() {
        // when
        String answer = tools.submit(null, "doc", "/anchor");

        // then
        assertEquals(List.of("p:doc"), submitted);
        assertEquals("Applied 2 change(s):\n- CREATED T-1 <https://x/1>\n- UPDATED <https://x/2>", answer);
    }

    @Test
    void theRegistrationPropertyIsNamed() {
        assertEquals("arknet.resource-access.enabled", ResourceAccessMcpTools.ENABLED_PROPERTY);
    }
}
