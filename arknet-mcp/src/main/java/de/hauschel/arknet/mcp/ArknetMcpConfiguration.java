package de.hauschel.arknet.mcp;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.kogn.rdf.dataset.DatasetLifecycle;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.mcp.store.HtmlReportRenderer;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.StoreReader;
import de.hauschel.arknet.mcp.store.StoreReportTools;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.adapter.mcp.UbiquitousLanguageMcpTools;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfUseCaseRepositoryFactory;
import de.hauschel.arknet.uc.adapter.mcp.UseCaseMcpTools;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;

/**
 * Bean wiring for the arknet MCP composition root.
 *
 * <p>Every bean declared here that exposes {@code @McpTool} methods is picked up
 * automatically by the Spring AI MCP server annotation scanner and registered as an MCP
 * tool - there is no manual tool-specification bridging. Four hexagons are wired:</p>
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
 *       RDF4J-free wiring as requirements).</li>
 *   <li><strong>use-cases</strong> ({@link UseCaseMcpTools} over {@link UseCaseService} over
 *       an RDF-persisted use-case repository) - the three use-case tools, assembled through
 *       {@link KognioRdfUseCaseRepositoryFactory}. Its out-adapter strictly resolves a use
 *       case's requirement/actor label references against the shared store, so it must read
 *       what the other two hexagons wrote.</li>
 * </ul>
 *
 * <p>All persistence hexagons share the single {@link DatasetLifecycle} bean (one store under
 * {@code arknet.rdf.storage}, no competing locks) and the single {@link WorkspaceId} bean, so
 * requirements, glossary terms and use cases of the same project land in the same
 * workspace/dataset and can reference each other.</p>
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

    // --- Shared store ----------------------------------------------------------

    /**
     * The single kognio-rdf dataset lifecycle shared by every store consumer: the
     * requirements and ubiquitous-language repositories and the generic store report.
     *
     * <p>Extracted into one bean so all consumers acquire datasets from the <em>same</em>
     * persistent store under {@code arknet.rdf.storage} instead of each factory building its
     * own {@code DatasetLifecycleRdf4j} over the same directory (a lock risk). The lifecycle
     * is created via {@link KognioRdfRequirementRepositoryFactory#persistentLifecycle(Path)},
     * which returns the technology-neutral {@link DatasetLifecycle} - so this composition root
     * stays free of any direct RDF4J dependency.</p>
     */
    @Bean
    DatasetLifecycle datasetLifecycle(
            @Value("${arknet.rdf.storage:${user.home}/.arknet/rdf}") final Path storageDir) {
        return KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
    }

    // --- Requirements hexagon --------------------------------------------------

    @Bean
    RequirementRepository requirementRepository(final DatasetLifecycle datasetLifecycle) {
        return KognioRdfRequirementRepositoryFactory.over(datasetLifecycle);
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
        return new RequirementMcpTools(service, service, service, service, service, workspaceId);
    }

    // --- Ubiquitous-language hexagon -------------------------------------------

    @Bean
    TermRepository termRepository(final DatasetLifecycle datasetLifecycle) {
        return KognioRdfTermRepositoryFactory.over(datasetLifecycle);
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

    // --- Use-cases hexagon -----------------------------------------------------

    /**
     * The use-case out-adapter, assembled over the <em>shared</em> {@link DatasetLifecycle}
     * bean. This is what makes the strict cross-bounded-context resolution work: when a use
     * case is saved, its step-level {@code stepRealises} labels (e.g. {@code FR-1}) and its
     * {@code primaryActor}/{@code supportingActor} labels are looked up against the same
     * per-workspace store the requirements and ubiquitous-language repositories write into.
     */
    @Bean
    UseCaseRepository useCaseRepository(final DatasetLifecycle datasetLifecycle) {
        return KognioRdfUseCaseRepositoryFactory.over(datasetLifecycle);
    }

    @Bean
    UseCaseService useCaseService(final UseCaseRepository repository) {
        return new UseCaseService(repository);
    }

    @Bean
    UseCaseMcpTools useCaseMcpTools(
            final UseCaseService service, final WorkspaceId workspaceId) {
        return new UseCaseMcpTools(service, service, service, workspaceId);
    }

    // --- Generic store report (domain-agnostic read path) ----------------------

    @Bean
    Prefixes storeReportPrefixes() {
        return Prefixes.defaults();
    }

    @Bean
    StoreReader storeReader(final DatasetLifecycle datasetLifecycle) {
        return new StoreReader(datasetLifecycle);
    }

    /**
     * The two generic, read-only store tools ({@code store_overview}, {@code resource_get}).
     * They read the workspace dataset through {@link StoreReader} - a single generic
     * {@code SELECT ?s ?p ?o} - and render domain-agnostic views, so they work for every
     * bounded context (requirements, ubiquitous-language, ...) without type-to-tool mapping.
     * The HTML report is written into {@code arknet.report.dir} (default: the launched project
     * root / working directory).
     */
    @Bean
    StoreReportTools storeReportTools(
            final StoreReader storeReader, final Prefixes prefixes, final WorkspaceId workspaceId,
            @Value("${arknet.report.dir:${arknet.workspace.dir:${user.dir}}}") final Path reportDir) {
        return new StoreReportTools(
                storeReader, prefixes, new HtmlReportRenderer(prefixes), workspaceId, reportDir);
    }
}
