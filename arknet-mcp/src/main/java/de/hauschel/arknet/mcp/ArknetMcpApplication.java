package de.hauschel.arknet.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Composition root of the arknet MCP server.
 *
 * <p>Bootstraps a Spring Boot application that runs as a single-user MCP server over
 * <strong>stdio</strong> (see {@code application.properties}). The Spring AI MCP server
 * auto-configuration discovers every Spring bean that carries
 * {@link org.springframework.ai.mcp.annotation.McpTool}-annotated methods and registers
 * those as MCP tools - both the arknet engine tools ({@link ArknetTools}) and the
 * requirements tools contributed by the requirements hexagon
 * ({@code de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools}). The bean wiring lives
 * in {@link ArknetMcpConfiguration}.</p>
 *
 * <p><strong>stdio discipline.</strong> stdout is the MCP transport channel, so nothing
 * must be printed there. The Spring banner is disabled and all logging is routed to
 * stderr via {@code logback.xml}; the web server is switched off
 * ({@code spring.main.web-application-type=none}).</p>
 */
@SpringBootApplication
public class ArknetMcpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ArknetMcpApplication.class, args);
    }
}
