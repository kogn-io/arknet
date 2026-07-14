package de.hauschel.arknet.mcp;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.adapter.mcp.UbiquitousLanguageMcpTools;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.out.TermRepository;

/**
 * Bean wiring for the arknet MCP composition root.
 *
 * <p>Every bean declared here that exposes {@code @McpTool} methods is picked up
 * automatically by the Spring AI MCP server annotation scanner and registered as an MCP
 * tool - there is no manual tool-specification bridging. Three hexagons are wired:</p>
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
 *   <li><strong>ubiquitous-language</strong> ({@link UbiquitousLanguageMcpTools} over
 *       {@link TermService} over an RDF/SKOS-persisted term repository) - the three
 *       term tools, assembled through {@link KognioRdfTermRepositoryFactory} (same
 *       RDF4J-free wiring as requirements). Both hexagons share the single
 *       {@link WorkspaceId} bean, so requirements and glossary terms of the same
 *       project land in the same workspace/dataset.</li>
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

    /**
     * The single workspace this server instance operates against. Resolved once at
     * startup from the explicit {@code arknet.workspace.id} property, else derived from
     * the git top-level / working-directory name (defaulting to {@code arknet.workspace.dir},
     * i.e. the launched project root). See {@link WorkspaceIdResolver}.
     */
    @Bean
    WorkspaceId workspaceId(
            @Value("${arknet.workspace.id:}") final String explicitId,
            @Value("${arknet.workspace.dir:${user.dir}}") final Path workingDir) {
        return new WorkspaceIdResolver().resolve(explicitId, workingDir);
    }

    @Bean
    RequirementMcpTools requirementMcpTools(
            final RequirementService service, final WorkspaceId workspaceId) {
        return new RequirementMcpTools(service, service, service, service, workspaceId);
    }

    // --- Ubiquitous-language hexagon -------------------------------------------

    @Bean
    TermRepository termRepository(
            @Value("${arknet.rdf.storage:${user.home}/.arknet/rdf}") final Path storageDir) {
        return KognioRdfTermRepositoryFactory.persistent(storageDir);
    }

    @Bean
    TermService termService(final TermRepository repository) {
        return new TermService(repository);
    }

    @Bean
    UbiquitousLanguageMcpTools ubiquitousLanguageMcpTools(
            final TermService service, final WorkspaceId workspaceId) {
        return new UbiquitousLanguageMcpTools(service, service, service, workspaceId);
    }
}
