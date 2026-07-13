package de.hauschel.arknet.mcp;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepository;
import de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;

/**
 * Bean wiring for the arknet MCP composition root.
 *
 * <p>Every bean declared here that exposes {@code @McpTool} methods is picked up
 * automatically by the Spring AI MCP server annotation scanner and registered as an MCP
 * tool - there is no manual tool-specification bridging. Two hexagons are wired:</p>
 *
 * <ul>
 *   <li><strong>arknet engine</strong> ({@link ArknetTools} over {@link ArknetEngine}) -
 *       fully functional today (load / validate / query / generate).</li>
 *   <li><strong>requirements</strong> ({@link RequirementMcpTools} over
 *       {@link RequirementService} over {@link KognioRdfRequirementRepository}) - the
 *       four requirement tools are registered and callable, but the underlying
 *       use-case and persistence bodies are still scaffold stubs that throw
 *       {@link UnsupportedOperationException}; Spring AI maps that to an error result
 *       until the requirements hexagon is implemented.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class ArknetMcpConfiguration {

    @Bean
    ArknetEngine arknetEngine() {
        return new ArknetEngine();
    }

    @Bean
    ArknetTools arknetTools(final ArknetEngine engine) {
        return new ArknetTools(engine);
    }

    // --- Requirements hexagon (scaffold; see class Javadoc) --------------------

    @Bean
    RequirementRepository requirementRepository() {
        return new KognioRdfRequirementRepository();
    }

    @Bean
    RequirementService requirementService(final RequirementRepository repository) {
        return new RequirementService(repository);
    }

    @Bean
    RequirementMcpTools requirementMcpTools(final RequirementService service) {
        return new RequirementMcpTools(service, service, service, service);
    }
}
