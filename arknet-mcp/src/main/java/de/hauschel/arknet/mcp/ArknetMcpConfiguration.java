package de.hauschel.arknet.mcp;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
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
 *       load / validate / query / generate.</li>
 *   <li><strong>requirements</strong> ({@link RequirementMcpTools} over
 *       {@link RequirementService} over an RDF-persisted requirement repository) - the
 *       four requirement tools are registered, callable and backed by kognio-rdf
 *       persistence. The repository is assembled through
 *       {@link KognioRdfRequirementRepositoryFactory} so this composition root stays
 *       free of any direct RDF4J dependency; it only supplies the storage directory
 *       ({@code arknet.rdf.storage}).</li>
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

    // --- Requirements hexagon --------------------------------------------------

    @Bean
    RequirementRepository requirementRepository(
            @Value("${arknet.rdf.storage:${user.home}/.arknet/rdf}") final Path storageDir) {
        return KognioRdfRequirementRepositoryFactory.persistent(storageDir);
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
