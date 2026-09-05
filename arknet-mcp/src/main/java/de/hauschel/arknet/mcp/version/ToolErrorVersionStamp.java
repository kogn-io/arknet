// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.version;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.beans.factory.config.BeanPostProcessor;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.Content;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

/**
 * Names the build that failed, on every failing tool call (issue #194).
 *
 * <p>A tool that throws reaches its caller through Spring AI's
 * {@code AbstractSyncMcpToolMethodCallback.createSyncErrorResult}, which renders the exception's
 * message and nothing else. On one shared, hand-started daemon that is one fact short: the same
 * message from a stale daemon and from a current one are indistinguishable, and telling them apart
 * used to mean {@code docker ps} and an image timestamp. This decorator appends
 * {@link ServerVersion#errorStamp()} to the failure text so the answer travels with the failure
 * itself.</p>
 *
 * <p><strong>Appends, never replaces.</strong> Several arknet exceptions carry a remedy in their
 * message - which tool to call instead, which process to stop (issue #137's
 * {@code DatasetLockConflictException}, {@code RegisteredAnchorProjectResolver}'s unknown-anchor
 * message). Those must survive intact, so the stamp is added to the end of the last text block and
 * nothing else about the result is touched.</p>
 *
 * <p><strong>Why a {@link BeanPostProcessor} and not an aspect.</strong> Spring AI's annotation
 * scanner turns every {@code @McpTool} method into one
 * {@link SyncToolSpecification} and publishes them as a single
 * {@code List<SyncToolSpecification>} bean; the MCP server bean consumes that list. Rewriting that
 * one list catches all tools of all seven hexagons at once, with no proxying (the {@code *McpTools}
 * classes are {@code final}), no new framework, and nothing for a new bounded context to remember.
 * The list is matched by content rather than by bean name so a renamed Spring AI bean does not
 * silently switch the stamp off.</p>
 *
 * <p>Exceptions the callback rethrows instead of converting (anything outside its configured
 * {@code toolCallExceptionClass}, and {@link Error}s) pass through unstamped: they never become a
 * tool result, so there is no message to append to.</p>
 */
public final class ToolErrorVersionStamp implements BeanPostProcessor {

    private final Supplier<ServerVersion> serverVersion;

    /**
     * @param serverVersion resolved lazily, on the first failing call rather than at
     *                      post-processor construction: a {@link BeanPostProcessor} is created
     *                      very early, and forcing an ordinary bean into existence from it would
     *                      exclude that bean from post-processing itself
     */
    public ToolErrorVersionStamp(final Supplier<ServerVersion> serverVersion) {
        this.serverVersion = Objects.requireNonNull(serverVersion, "serverVersion");
    }

    @Override
    public Object postProcessAfterInitialization(final Object bean, final String beanName) {
        if (bean instanceof List<?> candidates && !candidates.isEmpty()
                && candidates.stream().allMatch(SyncToolSpecification.class::isInstance)) {
            return candidates.stream()
                    .map(SyncToolSpecification.class::cast)
                    .map(this::stamp)
                    .toList();
        }
        return bean;
    }

    private SyncToolSpecification stamp(final SyncToolSpecification specification) {
        return new SyncToolSpecification(specification.tool(), (exchange, request) -> {
            final CallToolResult result = specification.callHandler().apply(exchange, request);
            if (result == null || !Boolean.TRUE.equals(result.isError())) {
                return result;
            }
            return stamped(result, serverVersion.get().errorStamp());
        });
    }

    /**
     * Appends {@code stamp} to the last text block of an error result, leaving structured content,
     * metadata and every other block untouched. A result carrying no text block at all gets the
     * stamp as its own block rather than losing it.
     *
     * @param result the failing result to stamp
     * @param stamp  the suffix, e.g. {@code " [arknet 0.7.0-SNAPSHOT]"}
     * @return the same result with the version named
     */
    static CallToolResult stamped(final CallToolResult result, final String stamp) {
        final List<Content> content = new ArrayList<>(result.content());
        final int last = lastTextBlock(content);
        if (last < 0) {
            content.add(new TextContent(stamp.strip()));
        } else {
            final TextContent text = (TextContent) content.get(last);
            content.set(last, new TextContent(text.annotations(), text.text() + stamp, text.meta()));
        }
        return new CallToolResult(content, result.isError(), result.structuredContent(), result.meta());
    }

    private static int lastTextBlock(final List<Content> content) {
        for (int i = content.size() - 1; i >= 0; i--) {
            if (content.get(i) instanceof TextContent) {
                return i;
            }
        }
        return -1;
    }
}
