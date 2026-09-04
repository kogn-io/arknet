// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.adr.application.port.in.CheckAdrs;
import de.hauschel.arknet.adr.application.port.in.CheckAdrs.CheckReport;
import de.hauschel.arknet.adr.application.port.in.CheckAdrs.Finding;
import de.hauschel.arknet.adr.application.port.in.CheckAdrs.Rule;
import de.hauschel.arknet.adr.application.port.in.CountSkippedAdrs;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;

/**
 * Scaffold- and presentation-level tests for {@link AdrCheckMcpTools} (kogn-io/arknet#387): that it
 * declares {@code adr_check} and nothing else, guards its in-ports, routes by anchor like every
 * other ADR tool, and renders a report the way the issue asks for - two separated classes, and the
 * boundary of the check in the output even when there is nothing to report.
 */
class AdrCheckMcpToolsTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final String ANCHOR = "/home/dev/projects/test-project";

    private static final ProjectResolver PROJECTS = anchor -> {
        if (ANCHOR.equals(anchor)) {
            return new ResolvedProject(PROJECT, "en");
        }
        throw new UnresolvedProjectAnchorException(anchor, "no project registered for '" + anchor + "'");
    };

    private final Stub stub = new Stub();
    private final AdrCheckMcpTools adapter = new AdrCheckMcpTools(stub, stub, PROJECTS);

    @Test
    void declaresTheOneCheckTool() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::name)
                .toList();

        assertEquals(List.of("adr_check"), names);
    }

    @Test
    void declaresItselfReadOnly() {
        McpTool tool = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .findFirst()
                .orElseThrow();

        assertTrue(tool.annotations().readOnlyHint());
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class, () -> new AdrCheckMcpTools(null, stub, PROJECTS));
        assertThrows(NullPointerException.class, () -> new AdrCheckMcpTools(stub, null, PROJECTS));
        assertThrows(NullPointerException.class, () -> new AdrCheckMcpTools(stub, stub, null));
    }

    @Test
    void routesByTheExplicitAnchorParameterWhenTheTransportCarriesNone() {
        adapter.check(null, null, ANCHOR);

        assertEquals(PROJECT, stub.lastProjectId);
    }

    @Test
    void rejectsACallThatCarriesNoAnchorAtAll() {
        assertThrows(UnresolvedProjectAnchorException.class, () -> adapter.check(null, null, null));
    }

    @Test
    void passesTheCallersDisplayLocaleThroughAndFallsBackToTheProjectDefault() {
        adapter.check(null, "de", ANCHOR);
        assertEquals("de", stub.lastDisplayLocale);

        adapter.check(null, null, ANCHOR);
        assertEquals("en", stub.lastDisplayLocale);
    }

    @Test
    void keepsFactsAndSuspicionsInSeparateBlocks() {
        // given one finding of each class
        stub.report = new CheckReport(2, List.of(
                new Finding(new AdrCode("ADR-1"), Rule.NO_CONSEQUENCE, null, null),
                new Finding(new AdrCode("ADR-2"), Rule.TRACKER_REFERENCE, "decision", "#123")));

        // when
        String rendered = adapter.check(null, null, ANCHOR);

        // then
        assertTrue(rendered.contains("2 decisions checked, 1 fact(s), 1 suspicion(s)."), rendered);
        assertTrue(rendered.contains("Facts"), rendered);
        assertTrue(rendered.contains("- ADR-1: no consequence recorded"), rendered);
        assertTrue(rendered.contains("Suspicions"), rendered);
        assertTrue(rendered.contains("- ADR-2: tracker reference (decision: #123)"), rendered);
        assertTrue(rendered.indexOf("Facts") < rendered.indexOf("Suspicions"), rendered);
    }

    @Test
    void namesWhatItDidNotCheckEvenWhenItFoundNothing() {
        stub.report = new CheckReport(17, List.of());

        String rendered = adapter.check(null, null, ANCHOR);

        assertTrue(rendered.contains("17 decisions checked, nothing found."), rendered);
        for (String entry : CheckAdrs.NOT_CHECKED) {
            assertTrue(rendered.contains(entry), rendered);
        }
    }

    @Test
    void saysSoWhenTheCorpusWasNotEvenFullyVisible() {
        stub.report = new CheckReport(3, List.of());
        stub.skipped = 2;

        String rendered = adapter.check(null, null, ANCHOR);

        assertTrue(rendered.contains("2 decisions skipped"), rendered);
    }

    /** One stub for both in-ports: the check and the skipped-decision count. */
    private static final class Stub implements CheckAdrs, CountSkippedAdrs {

        private CheckReport report = new CheckReport(0, List.of());
        private int skipped;
        private ProjectId lastProjectId;
        private String lastDisplayLocale;

        @Override
        public CheckReport check(final ProjectId projectId, final String displayLocale) {
            lastProjectId = projectId;
            lastDisplayLocale = displayLocale;
            return report;
        }

        @Override
        public int skippedCount(final ProjectId projectId, final int materialisedCount) {
            return skipped;
        }
    }
}
