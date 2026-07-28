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
 * Wires the calling client's workspace directory into every MCP tool call (issue #137).
 *
 * <p>Since arknet-mcp became one shared HTTP server for every workspace on the machine, a tool
 * call must carry which project it came from. Claude Code cannot set MCP {@code _meta} per call,
 * but a {@code .mcp.json} {@code "headers"} entry expands the {@code ${PWD}} environment
 * variable - so the client sends its project root in the {@value #WORKSPACE_DIR_HEADER} header.
 * This configuration overrides Spring AI's auto-configured Streamable-HTTP transport provider
 * (which wires no {@code contextExtractor}, leaving {@link McpTransportContext#EMPTY}) with one
 * that reads that header off each request and places it in the per-call transport context under
 * {@link ProjectResolver#WORKSPACE_DIR_KEY}, where the in-adapters pick it up.</p>
 *
 * <p>The bean otherwise reproduces the auto-configuration's provider verbatim (same JSON mapper,
 * endpoint, keep-alive and delete policy); it only adds the extractor. The header is not
 * authentication: on a loopback-only single-user server a local client could claim any path -
 * an accepted assumption at this trust boundary (see ADR-009).</p>
 */
@Configuration(proxyBeanMethods = false)
class WorkspaceHttpTransportConfiguration {

    /**
     * HTTP header the client carries its project root in. Paired with a {@code .mcp.json}
     * {@code "headers": { "X-Arknet-Workspace-Dir": "${PWD}" }} entry on the client side.
     */
    static final String WORKSPACE_DIR_HEADER = "X-Arknet-Workspace-Dir";

    /**
     * Overrides the auto-configured Streamable-HTTP transport provider with one that extracts the
     * workspace-directory header into the per-call transport context. Carries the same
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
                .contextExtractor(WorkspaceHttpTransportConfiguration::extractWorkspaceDir)
                .build();
    }

    /**
     * Reads the {@value #WORKSPACE_DIR_HEADER} header off the request and exposes it under
     * {@link ProjectResolver#WORKSPACE_DIR_KEY}. A missing or blank header yields
     * {@link McpTransportContext#EMPTY}, which the in-adapters resolve to the server's default
     * workspace.
     */
    static McpTransportContext extractWorkspaceDir(final ServerRequest request) {
        final String dir = request.headers().firstHeader(WORKSPACE_DIR_HEADER);
        if (dir == null || dir.isBlank()) {
            return McpTransportContext.EMPTY;
        }
        return McpTransportContext.create(Map.of(ProjectResolver.WORKSPACE_DIR_KEY, dir));
    }
}
