// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.function.ServerRequest;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;

import tools.jackson.databind.json.JsonMapper;

import de.hauschel.arknet.kernel.ProjectResolver;

/**
 * Wires the calling client's project anchor into every MCP tool call (ADR-016).
 *
 * <p>arknet-mcp is one shared HTTP server for every project on the machine, so a tool call must
 * carry which project it came from. Claude Code cannot set MCP {@code _meta} per call, but a
 * {@code .mcp.json} {@code "headers"} entry expands environment variables - so the client sends its
 * anchor in the {@value #ANCHOR_HEADER} header. This configuration overrides Spring AI's
 * auto-configured Streamable-HTTP transport provider (which wires no {@code contextExtractor},
 * leaving {@link McpTransportContext#EMPTY}) with one that reads that header off each request and
 * places it in the per-call transport context under {@link ProjectResolver#ANCHOR_KEY}, where the
 * in-adapters pick it up.</p>
 *
 * <p><strong>An anchor, not a directory.</strong> The header used to carry the client's working
 * directory, from which the server derived an id by slugging its git top-level's basename - the
 * derivation ADR-016 removes and issue #175 records the damage of. What travels here now is opaque:
 * whatever string the client registered. A path is still the natural thing for a filesystem client
 * to send ({@code ${PWD}}), but the server no longer treats it as a path - it does not parse it,
 * shorten it, or fall back to anything when it does not recognise it.</p>
 *
 * <p>The bean otherwise reproduces the auto-configuration's provider verbatim (same JSON mapper,
 * endpoint, keep-alive and delete policy); it only adds the extractor. The header is not
 * authentication: on a loopback-only single-user server a local client could claim any anchor - an
 * accepted assumption at this trust boundary, and unchanged by ADR-016, which routes on the anchor
 * rather than vouching for it (ADR-009 decision 4).</p>
 */
@Configuration(proxyBeanMethods = false)
class AnchorHttpTransportConfiguration {

    /**
     * HTTP header the client carries its project anchor in. Paired with a {@code .mcp.json}
     * {@code "headers": { "X-Arknet-Project-Anchor": "${PWD}" }} entry on the client side.
     */
    static final String ANCHOR_HEADER = "X-Arknet-Project-Anchor";

    /**
     * Overrides the auto-configured Streamable-HTTP transport provider with one that extracts the
     * project-anchor header into the per-call transport context. Carries the same
     * {@code @Qualifier("mcpServerJsonMapper")} the auto-configuration uses so it binds the same
     * JSON mapper bean.
     */
    @Bean
    WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
            @Qualifier("mcpServerJsonMapper") final JsonMapper jsonMapper,
            final McpServerStreamableHttpProperties properties) {
        return WebMvcStreamableServerTransportProvider.builder()
                .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                .mcpEndpoint(properties.getMcpEndpoint())
                .keepAliveInterval(properties.getKeepAliveInterval())
                .disallowDelete(properties.isDisallowDelete())
                .contextExtractor(AnchorHttpTransportConfiguration::extractAnchor)
                .build();
    }

    /**
     * Reads the {@value #ANCHOR_HEADER} header off the request and exposes it under
     * {@link ProjectResolver#ANCHOR_KEY}. A missing or blank header yields
     * {@link McpTransportContext#EMPTY} - which no longer means "the default project" but "this
     * call has no project yet": the in-adapters then require the tool's explicit
     * {@code projectAnchor} parameter, and failing that reject the call (ADR-016 decision 3).
     */
    static McpTransportContext extractAnchor(final ServerRequest request) {
        final String anchor = request.headers().firstHeader(ANCHOR_HEADER);
        if (anchor == null || anchor.isBlank()) {
            return McpTransportContext.EMPTY;
        }
        return McpTransportContext.create(Map.of(ProjectResolver.ANCHOR_KEY, anchor));
    }
}
