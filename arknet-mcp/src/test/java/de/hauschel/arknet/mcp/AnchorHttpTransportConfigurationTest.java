// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.ServerRequest;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;

import tools.jackson.databind.json.JsonMapper;

import de.hauschel.arknet.kernel.ProjectResolver;

/**
 * Pins the project-anchor transport wiring (ADR-016): the context extractor lifts the client's
 * {@value AnchorHttpTransportConfiguration#ANCHOR_HEADER} header into the per-call transport
 * context (where the in-adapters read it), and the provider bean assembles.
 *
 * <p>A missing header yielding an <em>empty</em> context is the load-bearing case here. It used to
 * mean "fall back to the server's own directory"; it now means "this call names no project", which
 * the in-adapters turn into a caller error. The two tests below therefore pin behaviour that has
 * changed meaning without changing shape.</p>
 */
class AnchorHttpTransportConfigurationTest {

    @Test
    void extractsTheAnchorHeaderIntoTheTransportContext() {
        ServerRequest request = requestWithHeader("/home/dev/projects/sample-project");

        McpTransportContext context = AnchorHttpTransportConfiguration.extractAnchor(request);

        assertThat(context.get(ProjectResolver.ANCHOR_KEY)).isEqualTo("/home/dev/projects/sample-project");
    }

    @Test
    void aMissingHeaderYieldsAnEmptyContext() {
        ServerRequest request = requestWithHeader(null);

        McpTransportContext context = AnchorHttpTransportConfiguration.extractAnchor(request);

        assertThat(context).isSameAs(McpTransportContext.EMPTY);
    }

    @Test
    void aBlankHeaderYieldsAnEmptyContext() {
        ServerRequest request = requestWithHeader("   ");

        McpTransportContext context = AnchorHttpTransportConfiguration.extractAnchor(request);

        assertThat(context).isSameAs(McpTransportContext.EMPTY);
    }

    /** The overriding provider bean assembles from the same inputs the auto-configuration uses. */
    @Test
    void buildsTheStreamableHttpTransportProvider() {
        WebMvcStreamableServerTransportProvider provider =
                new AnchorHttpTransportConfiguration().webMvcStreamableServerTransportProvider(
                        JsonMapper.builder().build(), new McpServerStreamableHttpProperties());

        assertThat(provider).isNotNull();
    }

    /**
     * The regression this class exists to prevent: a provider built without the anchor
     * extractor wired in would fall back to {@link McpTransportContextExtractor}'s default
     * ({@code serverRequest -> McpTransportContext.EMPTY}), so every real request would silently
     * carry no project - exactly the production failure ADR-016 routing depends on this bean to
     * avoid. The extractor is package-private state with no accessor, so this pins the built
     * object's actual field rather than trusting the builder call alone.
     */
    @Test
    void wiresTheAnchorExtractorIntoTheBuiltTransportProvider() throws ReflectiveOperationException {
        WebMvcStreamableServerTransportProvider provider =
                new AnchorHttpTransportConfiguration().webMvcStreamableServerTransportProvider(
                        JsonMapper.builder().build(), new McpServerStreamableHttpProperties());

        Field field = WebMvcStreamableServerTransportProvider.class.getDeclaredField("contextExtractor");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        McpTransportContextExtractor<ServerRequest> wiredExtractor =
                (McpTransportContextExtractor<ServerRequest>) field.get(provider);

        McpTransportContext extracted =
                wiredExtractor.extract(requestWithHeader("/home/dev/projects/sample-project"));

        assertThat(extracted.get(ProjectResolver.ANCHOR_KEY)).isEqualTo("/home/dev/projects/sample-project");
    }

    private static ServerRequest requestWithHeader(final String value) {
        ServerRequest request = mock(ServerRequest.class);
        ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
        when(request.headers()).thenReturn(headers);
        when(headers.firstHeader(AnchorHttpTransportConfiguration.ANCHOR_HEADER)).thenReturn(value);
        return request;
    }
}
