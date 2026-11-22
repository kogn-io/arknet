// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.spike;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.servlet.function.ServerRequest;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.spec.McpSchema;

import tools.jackson.databind.json.JsonMapper;

import de.hauschel.arknet.mcp.LoopbackHostSecurity;
import de.hauschel.arknet.mcpsupport.ProjectResolver;

/**
 * SPIKE (issue #73), a measuring instrument, not a regression test: what does a real MCP session
 * hand a {@code @McpTool} method about who is calling?
 *
 * <p>Boots the real application on a random loopback port against a temporary store, adds one
 * probe tool and a probe transport extractor (the production extractor reads only the anchor
 * header), connects real MCP clients over Streamable HTTP and prints what arrives. Every
 * observation is printed with the prefix {@code SPIKE73}; the assertions only pin that the
 * measurement ran.</p>
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"spring.main.allow-bean-definition-overriding=true"})
class ClientIdentitySpikeTest {

    static final String ACTOR_HEADER = "X-Arknet-Actor";
    static final String ANCHOR_HEADER = "X-Arknet-Project-Anchor";
    static final String ACTOR_KEY = "spike.actor";
    static final String ALL_HEADERS_KEY = "spike.headers";

    @TempDir
    static Path storage;

    @DynamicPropertySource
    static void properties(final DynamicPropertyRegistry registry) {
        registry.add("arknet.rdf.storage", () -> storage.toString());
    }

    @LocalServerPort
    int port;

    /** Probe tool: echoes everything the per-call request context exposes about the caller. */
    static class ProbeTools {

        @McpTool(name = "spike_probe", description = "Spike 73: echoes the caller identity the request context exposes.")
        public String probe(final McpSyncRequestContext context) {
            final Map<String, Object> seen = new TreeMap<>();
            final McpSchema.Implementation info = context.clientInfo();
            seen.put("clientInfo.name", info == null ? null : info.name());
            seen.put("clientInfo.title", info == null ? null : info.title());
            seen.put("clientInfo.version", info == null ? null : info.version());
            seen.put("sessionId", context.sessionId());
            seen.put("requestMeta", context.requestMeta());
            final McpTransportContext transport = context.transportContext();
            seen.put("transport.anchor", transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY));
            seen.put("transport.actor", transport == null ? null : transport.get(ACTOR_KEY));
            seen.put("transport.allHeaders", transport == null ? null : transport.get(ALL_HEADERS_KEY));
            return seen.toString();
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProbeConfiguration {

        @Bean
        ProbeTools probeTools() {
            return new ProbeTools();
        }

        /** Same provider as production, but its extractor lifts a second header and the full header set. */
        @Bean
        WebMvcStreamableServerTransportProvider webMvcStreamableServerTransportProvider(
                @Qualifier("mcpServerJsonMapper") final JsonMapper jsonMapper,
                final McpServerStreamableHttpProperties properties) {
            return WebMvcStreamableServerTransportProvider.builder()
                    .jsonMapper(new JacksonMcpJsonMapper(jsonMapper))
                    .mcpEndpoint(properties.getMcpEndpoint())
                    .keepAliveInterval(properties.getKeepAliveInterval())
                    .disallowDelete(properties.isDisallowDelete())
                    .contextExtractor(ProbeConfiguration::extract)
                    .securityValidator(LoopbackHostSecurity.hostValidator())
                    .build();
        }

        static McpTransportContext extract(final ServerRequest request) {
            final Map<String, Object> values = new LinkedHashMap<>();
            final String anchor = request.headers().firstHeader(ANCHOR_HEADER);
            if (anchor != null) {
                values.put(ProjectResolver.ANCHOR_KEY, anchor);
            }
            final String actor = request.headers().firstHeader(ACTOR_HEADER);
            if (actor != null) {
                values.put(ACTOR_KEY, actor);
            }
            final Map<String, String> names = new TreeMap<>();
            request.headers().asHttpHeaders().forEach((k, v) -> names.put(k, String.join(",", v)));
            names.keySet().removeIf(k -> k.equalsIgnoreCase("Mcp-Session-Id"));
            values.put(ALL_HEADERS_KEY, names.keySet().toString());
            return McpTransportContext.create(values);
        }
    }

    private McpSyncClient connect(final String name, final String title, final String version, final String actorHeader) {
        final HttpClientStreamableHttpTransport transport = HttpClientStreamableHttpTransport
                .builder("http://127.0.0.1:" + port)
                .addHttpRequestCustomizer((builder, method, uri, body, ctx) -> {
                    builder.header(ANCHOR_HEADER, "/spike/anchor");
                    if (actorHeader != null) {
                        builder.header(ACTOR_HEADER, actorHeader);
                    }
                })
                .build();
        final McpSyncClient client = McpClient.sync(transport)
                .clientInfo(new McpSchema.Implementation(name, title, version))
                .requestTimeout(Duration.ofSeconds(30))
                .build();
        client.initialize();
        return client;
    }

    private static String sessionId(final String line) {
        final int start = line.indexOf("sessionId=") + "sessionId=".length();
        return line.substring(start, line.indexOf(',', start));
    }

    private String probe(final McpSyncClient client, final Map<String, Object> meta) {
        final McpSchema.CallToolRequest.Builder request = McpSchema.CallToolRequest.builder("spike_probe");
        if (meta != null) {
            request.meta(meta);
        }
        final McpSchema.CallToolResult result = client.callTool(request.build());
        return ((McpSchema.TextContent) result.content().get(0)).text();
    }

    @Test
    void measuresWhatTheSessionCarriesAboutTheCaller() {
        // Names/versions below are what the SDK default and the common clients are known to send;
        // here they are synthetic - the point is that the server sees whatever the client claims.
        final Map<String, String> observed = new LinkedHashMap<>();

        final McpSyncClient agent = connect("claude-code", "Claude Code", "9.9.9", "agent:claude-code");
        try {
            observed.put("A agent, 1st call", probe(agent, null));
            observed.put("A agent, 2nd call same session", probe(agent, null));
            observed.put("A agent, per-request _meta", probe(agent, Map.of("io.example/actor", "someone")));
        } finally {
            agent.closeGracefully();
        }

        final McpSyncClient human = connect("claude-code", "Claude Code", "9.9.9", "human:fred");
        try {
            observed.put("B same client name, other actor header", probe(human, null));
        } finally {
            human.closeGracefully();
        }

        final McpSyncClient noHeader = connect("claude-code", "Claude Code", "9.9.9", null);
        try {
            observed.put("C same client name, no actor header", probe(noHeader, null));
        } finally {
            noHeader.closeGracefully();
        }

        final McpSyncClient inspector = connect("mcp-inspector", null, "0.0.0", null);
        try {
            observed.put("D inspector-style client, no title", probe(inspector, null));
        } finally {
            inspector.closeGracefully();
        }

        // E: two sessions open at the same time, probes interleaved - each must see its own identity.
        final McpSyncClient first = connect("claude-code", "Claude Code", "9.9.9", "agent:one");
        final McpSyncClient second = connect("claude-code", "Claude Code", "9.9.9", "agent:two");
        try {
            observed.put("E1 concurrent session one", probe(first, null));
            observed.put("E2 concurrent session two", probe(second, null));
            observed.put("E3 concurrent session one again", probe(first, null));
            assertThat(observed.get("E1 concurrent session one")).contains("transport.actor=agent:one");
            assertThat(observed.get("E2 concurrent session two")).contains("transport.actor=agent:two");
            assertThat(observed.get("E3 concurrent session one again")).contains("transport.actor=agent:one");
            assertThat(sessionId(observed.get("E1 concurrent session one")))
                    .isNotEqualTo(sessionId(observed.get("E2 concurrent session two")));
        } finally {
            first.closeGracefully();
            second.closeGracefully();
        }

        observed.forEach((k, v) -> System.out.println("SPIKE73 " + k + " -> " + v));
        assertThat(observed).hasSize(9);
        assertThat(observed.get("A agent, 1st call")).contains("clientInfo.name=claude-code");
    }

}
