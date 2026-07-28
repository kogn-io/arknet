// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.ServerRequest;

import io.modelcontextprotocol.common.McpTransportContext;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;

import tools.jackson.databind.json.JsonMapper;

import de.hauschel.arknet.kernel.ProjectResolver;

/**
 * Pins the workspace-directory transport wiring (issue #137): the context extractor lifts the
 * client's {@value WorkspaceHttpTransportConfiguration#WORKSPACE_DIR_HEADER} header into the
 * per-call transport context (where the in-adapters read it), and the provider bean assembles.
 */
class WorkspaceHttpTransportConfigurationTest {

    @Test
    void extractsTheWorkspaceDirHeaderIntoTheTransportContext() {
        ServerRequest request = requestWithHeader("/home/dev/projects/noistill");

        McpTransportContext context = WorkspaceHttpTransportConfiguration.extractWorkspaceDir(request);

        assertThat(context.get(ProjectResolver.WORKSPACE_DIR_KEY)).isEqualTo("/home/dev/projects/noistill");
    }

    @Test
    void aMissingHeaderYieldsAnEmptyContext() {
        ServerRequest request = requestWithHeader(null);

        McpTransportContext context = WorkspaceHttpTransportConfiguration.extractWorkspaceDir(request);

        assertThat(context).isSameAs(McpTransportContext.EMPTY);
    }

    @Test
    void aBlankHeaderYieldsAnEmptyContext() {
        ServerRequest request = requestWithHeader("   ");

        McpTransportContext context = WorkspaceHttpTransportConfiguration.extractWorkspaceDir(request);

        assertThat(context).isSameAs(McpTransportContext.EMPTY);
    }

    /** The overriding provider bean assembles from the same inputs the auto-configuration uses. */
    @Test
    void buildsTheStreamableHttpTransportProvider() {
        WebMvcStreamableServerTransportProvider provider =
                new WorkspaceHttpTransportConfiguration().webMvcStreamableServerTransportProvider(
                        JsonMapper.builder().build(), new McpServerStreamableHttpProperties());

        assertThat(provider).isNotNull();
    }

    private static ServerRequest requestWithHeader(final String value) {
        ServerRequest request = mock(ServerRequest.class);
        ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
        when(request.headers()).thenReturn(headers);
        when(headers.firstHeader(WorkspaceHttpTransportConfiguration.WORKSPACE_DIR_HEADER)).thenReturn(value);
        return request;
    }
}
