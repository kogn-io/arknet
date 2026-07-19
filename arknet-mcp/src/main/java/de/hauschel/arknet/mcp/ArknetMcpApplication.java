package de.hauschel.arknet.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition root of the arknet MCP server.
 *
 * <p>Bootstraps a Spring Boot application that runs as a single, long-running MCP server
 * per workspace over <strong>Streamable HTTP, bound to loopback only</strong> (see
 * {@code application.properties}) - a daemon an admin starts once and leaves running,
 * rather than a process Claude Code spawns per session (issue #137: the latter had every
 * session open its own {@code NativeStore}, colliding on the directory lock as soon as two
 * processes shared a workspace). The Spring AI MCP server auto-configuration discovers every
 * Spring bean that carries {@link org.springframework.ai.mcp.annotation.McpTool}-annotated
 * methods and registers those as MCP tools - the tools contributed by the hexagonal bounded
 * contexts (requirements, ubiquitous-language, use-cases), e.g.
 * {@code de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools}. The bean wiring lives
 * in {@link ArknetMcpConfiguration}.</p>
 *
 * <p><strong>Loopback only.</strong> {@code server.address=127.0.0.1} - never a public or
 * LAN interface. That keeps the trust boundary identical to the stdio spawn it replaces (any
 * local process can already reach it), so no authentication is required.</p>
 */
@SpringBootApplication
public class ArknetMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArknetMcpApplication.class, args);
    }
}
