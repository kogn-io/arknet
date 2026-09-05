// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.version;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.spring.SyncMcpAnnotationProviders;

/**
 * Proves the promise of issue #194's third part end to end at the boundary it is made on: an
 * exception thrown inside a tool must reach the MCP caller naming the server version.
 *
 * <p>Drives the real pipeline rather than a stand-in for it: {@link SyncMcpAnnotationProviders}
 * turns an {@link McpTool}-annotated bean into exactly the {@code List<SyncToolSpecification>}
 * Spring AI's auto-configuration publishes, the post-processor rewrites that list the same way it
 * does in the running daemon, and the assertion reads the {@link CallToolResult} a client would
 * receive. A test that stamped a hand-built {@link CallToolResult} instead would pass even if the
 * post-processor never matched the list Spring AI actually produces - the one thing that can
 * silently switch this feature off.</p>
 */
class ToolErrorVersionStampTest {

    private static final String REMEDY = "Stop the other process, or use the shared daemon.";

    private final ToolErrorVersionStamp stamp =
            new ToolErrorVersionStamp(() -> new ServerVersion("1.2.3", Optional.empty()));

    @Test
    void aThrownExceptionReachesTheCallerWithTheServerVersionAppended() {
        final CallToolResult result = call("probe_fail");

        assertThat(result.isError()).isTrue();
        assertThat(onlyText(result)).endsWith(" [arknet 1.2.3]");
    }

    /**
     * The remedy messages issue #137 put into several arknet exceptions are the reason this stamps
     * rather than replaces: the version is worth nothing if it costs the caller the sentence that
     * says what to do next.
     */
    @Test
    void theExceptionsOwnRemedyMessageSurvivesTheStamp() {
        assertThat(onlyText(call("probe_fail"))).contains(REMEDY);
    }

    /** A successful call must look exactly as it did before - the stamp is for failures only. */
    @Test
    void aSuccessfulCallIsLeftAlone() {
        final CallToolResult result = call("probe_succeed");

        assertThat(result.isError()).isNotEqualTo(Boolean.TRUE);
        assertThat(onlyText(result)).isEqualTo("all good");
    }

    /** Every other bean in the context passes through untouched. */
    @Test
    void anUnrelatedBeanIsNotRewritten() {
        final List<String> unrelated = List.of("not", "a", "tool");

        assertThat(stamp.postProcessAfterInitialization(unrelated, "unrelated")).isSameAs(unrelated);
    }

    /** An error result carrying no text block at all still names the version. */
    @Test
    void anErrorWithoutTextStillCarriesTheVersion() {
        final CallToolResult bare = CallToolResult.builder().isError(true).build();

        assertThat(onlyText(ToolErrorVersionStamp.stamped(bare, " [arknet 1.2.3]"))).isEqualTo("[arknet 1.2.3]");
    }

    private CallToolResult call(final String tool) {
        final Object rewritten = stamp.postProcessAfterInitialization(
                SyncMcpAnnotationProviders.toolSpecifications(List.<Object>of(new Probe())), "toolSpecs");
        return specification(rewritten, tool).callHandler().apply(null, new CallToolRequest(tool, Map.of()));
    }

    private static SyncToolSpecification specification(final Object specifications, final String tool) {
        return ((List<?>) specifications).stream()
                .map(SyncToolSpecification.class::cast)
                .filter(specification -> tool.equals(specification.tool().name()))
                .findFirst()
                .orElseThrow();
    }

    private static String onlyText(final CallToolResult result) {
        return result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce((a, b) -> a + b)
                .orElseThrow();
    }

    /** Stands in for any of the seven hexagons' {@code *McpTools} beans. */
    public static final class Probe {

        @McpTool(name = "probe_fail", description = "Fails on purpose.")
        public String fail() {
            throw new IllegalStateException(REMEDY);
        }

        @McpTool(name = "probe_succeed", description = "Succeeds on purpose.")
        public String succeed() {
            return "all good";
        }
    }
}
