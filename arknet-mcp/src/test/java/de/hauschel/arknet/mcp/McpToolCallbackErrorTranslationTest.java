// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;
import org.springframework.ai.mcp.annotation.method.tool.ReturnMode;
import org.springframework.ai.mcp.annotation.method.tool.SyncMcpToolMethodCallback;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.mcp.dataset.LockConflictReportingDatasetLifecycle;
import de.hauschel.arknet.prj.application.port.in.ResolveProject;
import de.hauschel.arknet.prj.domain.UnknownAnchorException;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;

/**
 * Reproduces #137 at the layer the bug actually lived in: Spring AI's
 * {@link SyncMcpToolMethodCallback} does not render a thrown exception's own message. It walks
 * {@code findCauseUsingPlainJava}, which follows {@link Throwable#getCause()} to its deepest link,
 * and renders <em>that</em> link's message. A composed, actionable message that chains the original
 * failure as its {@code cause} therefore never reaches the {@link CallToolResult} the MCP caller
 * sees - only the original, uncomposed message does.
 *
 * <p>Unit tests against the domain exceptions directly ({@code RegisteredAnchorProjectResolverTest},
 * {@code LockConflictReportingDatasetLifecycleTest}) cannot catch this: they assert on
 * {@code getMessage()} of the exception actually thrown, not on what a caller behind the real
 * callback receives. This test drives that real callback class instead, over the exact production
 * translation code in {@link RegisteredAnchorProjectResolver} and
 * {@link LockConflictReportingDatasetLifecycle}.</p>
 */
class McpToolCallbackErrorTranslationTest {

    @TempDir
    Path storageDir;

    @Test
    void unknownAnchorRemedyReachesTheCaller() throws NoSuchMethodException {
        final Method method = Probe.class.getDeclaredMethod("unknownAnchor");
        final SyncMcpToolMethodCallback callback = new SyncMcpToolMethodCallback(ReturnMode.TEXT, method, new Probe());

        final CallToolResult result = callback.apply(null, new CallToolRequest("unknownAnchor", Map.of()));

        final String text = onlyText(result);
        assertThat(text).contains("project_add", "project_adopt", "project_list");
        // Distinguishes the composed remedy from arknet-project's own raw UnknownAnchorException
        // message, which names project_attach_anchor instead - the exact text that used to win.
        assertThat(text).doesNotContain("project_attach_anchor");
    }

    @Test
    void lockConflictRemedyReachesTheCaller() throws NoSuchMethodException {
        final DatasetId id = new DatasetId("mcp-boundary-lock-test");
        final DatasetLifecycle first = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        final DatasetHandle firstHandle = first.acquire(id);
        try {
            final DatasetLifecycle second = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
            final LockConflictReportingDatasetLifecycle guarded = new LockConflictReportingDatasetLifecycle(
                    second, storageDir, KognioRdfRequirementRepositoryFactory.DEFAULT_LOCK_CONFLICT);

            final Method method = LockProbe.class.getDeclaredMethod("acquireLocked");
            final SyncMcpToolMethodCallback callback =
                    new SyncMcpToolMethodCallback(ReturnMode.TEXT, method, new LockProbe(guarded, id));

            final CallToolResult result = callback.apply(null, new CallToolRequest("acquireLocked", Map.of()));

            assertThat(onlyText(result)).contains("Stop the other process", "shared daemon");
        } finally {
            firstHandle.close();
            first.close(id);
        }
    }

    private static String onlyText(final CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce((a, b) -> a + b)
                .orElseThrow();
    }

    /** Drives {@link RegisteredAnchorProjectResolver}'s real anchor-unknown translation. */
    static final class Probe {
        String unknownAnchor() {
            final ResolveProject alwaysUnknown = anchor -> {
                throw new UnknownAnchorException(anchor);
            };
            new RegisteredAnchorProjectResolver(alwaysUnknown).resolve("/x/y");
            return "unreachable";
        }
    }

    /** Drives {@link LockConflictReportingDatasetLifecycle}'s real lock-conflict translation. */
    private record LockProbe(DatasetLifecycle guarded, DatasetId id) {
        String acquireLocked() {
            guarded.acquire(id);
            return "unreachable";
        }
    }
}
