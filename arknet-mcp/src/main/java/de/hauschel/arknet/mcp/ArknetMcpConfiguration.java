package de.hauschel.arknet.mcp;

import java.nio.file.Path;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.kogn.rdf.dataset.DatasetLifecycle;

import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.mcp.store.HtmlReportRenderer;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.StoreReader;
import de.hauschel.arknet.mcp.store.StoreReportTools;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfTermLookup;
import de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.TermLookup;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.adapter.mcp.UbiquitousLanguageMcpTools;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfActorLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfRequirementLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfUseCaseRepositoryFactory;
import de.hauschel.arknet.uc.adapter.mcp.UseCaseMcpTools;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.out.ActorLookup;
import de.hauschel.arknet.uc.application.port.out.RequirementLookup;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;

/**
 * Bean wiring for the arknet MCP composition root.
 *
 * <p>Every bean declared here that exposes {@code @McpTool} methods is picked up
 * automatically by the Spring AI MCP server annotation scanner and registered as an MCP
 * tool - there is no manual tool-specification bridging. Three hexagons are wired:</p>
 *
 * <ul>
 *   <li><strong>requirements</strong> ({@link RequirementMcpTools} over
 *       {@link RequirementService} over an RDF-persisted requirement repository) - the
 *       four requirement tools are registered, callable and backed by kognio-rdf
 *       persistence. The repository is assembled through
 *       {@link KognioRdfRequirementRepositoryFactory} so this composition root stays
 *       free of any direct RDF4J dependency; it only supplies the storage directory
 *       ({@code arknet.rdf.storage}). {@code req_link_term}'s cross-BC code-to-identity
 *       resolution (issue #77) is a separate {@link KognioRdfTermLookup} bean over the same
 *       shared dataset lifecycle. {@code req_get}/{@code req_list}'s reverse direction (identity
 *       back to a displayable business code) is not a second store adapter - it is the
 *       ubiquitous-language hexagon's own {@link ResolveTerms} in-port, wired straight into
 *       {@link RequirementMcpTools} (#77 nachtrag).</li>
 *   <li><strong>ubiquitous-language</strong> ({@link UbiquitousLanguageMcpTools} over
 *       {@link TermService} over an RDF/SKOS-persisted term repository) - the three
 *       term tools, assembled through {@link KognioRdfTermRepositoryFactory} (same
 *       RDF4J-free wiring as requirements).</li>
 *   <li><strong>use-cases</strong> ({@link UseCaseMcpTools} over {@link UseCaseService} over
 *       an RDF-persisted use-case repository) - the three use-case tools, assembled through
 *       {@link KognioRdfUseCaseRepositoryFactory}. {@code uc_add}'s cross-BC label-to-identity
 *       resolution (issue #89, the use-cases analogue of requirements' #77) is two separate
 *       {@link KognioRdfRequirementLookup}/{@link KognioRdfActorLookup} beans over the same
 *       shared dataset lifecycle, called once by {@link UseCaseService#add}. {@code uc_get}/
 *       {@code uc_list}'s reverse direction (identity back to a displayable business
 *       code/name) is not a second store adapter - it is the requirements hexagon's own
 *       {@link ResolveRequirements} and the ubiquitous-language hexagon's own
 *       {@link ResolveTerms} in-ports, wired straight into {@link UseCaseMcpTools} (#89).</li>
 * </ul>
 *
 * <p>All persistence hexagons share the single {@link DatasetLifecycle} bean (one store under
 * {@code arknet.rdf.storage}, no competing locks) and the single {@link WorkspaceId} bean, so
 * requirements, glossary terms and use cases of the same project land in the same
 * workspace/dataset and can reference each other.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ArknetMcpConfiguration {

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

    /**
     * Resolves a glossary term's human-typed business code (e.g. {@code TERM-1}) to its opaque
     * subject identity - the strict cross-BC lookup {@code req_link_term} needs (issue #77).
     * Acquires datasets from the same shared {@link DatasetLifecycle} as
     * {@link #requirementRepository}, so it reads the same workspace the ubiquitous-language
     * hexagon writes into.
     */
    @Bean
    TermLookup requirementTermLookup(final DatasetLifecycle datasetLifecycle) {
        return new KognioRdfTermLookup(datasetLifecycle);
    }

    /**
     * Mints the opaque {@link de.hauschel.arknet.kernel.ResourceId} of newly added resources
     * (requirements, glossary terms, use cases and their derived step nodes). A single bean so
     * every write path mints from the same kernel-owned scheme (see {@link UuidResourceIdFactory}).
     */
    @Bean
    ResourceIdFactory resourceIdFactory() {
        return new UuidResourceIdFactory();
    }

    @Bean
    RequirementService requirementService(
            final RequirementRepository repository, final ResourceIdFactory resourceIdFactory,
            final TermLookup requirementTermLookup) {
        return new RequirementService(repository, resourceIdFactory, requirementTermLookup);
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

    /**
     * {@code resolveTerms} is the ubiquitous-language hexagon's {@link ResolveTerms} in-port
     * (implemented by its {@code TermService} bean below) - borrowed here purely so
     * {@code req_get}/{@code req_list} can render a linked term's business code instead of its
     * bare IRI (issue #77 nachtrag). This wires an In-Adapter to a <em>different</em> hexagon's
     * In-Port, not to that hexagon's core - see the "kein *-core* haengt an einem anderen BC"
     * precision in CLAUDE.md.
     */
    @Bean
    RequirementMcpTools requirementMcpTools(
            final RequirementService service, final ResolveTerms resolveTerms, final WorkspaceId workspaceId) {
        return new RequirementMcpTools(service, service, service, service, service, resolveTerms, workspaceId);
    }

    // --- Ubiquitous-language hexagon -------------------------------------------

    @Bean
    TermRepository termRepository(final DatasetLifecycle datasetLifecycle) {
        return KognioRdfTermRepositoryFactory.over(datasetLifecycle);
    }

    @Bean
    TermService termService(final TermRepository repository, final ResourceIdFactory resourceIdFactory) {
        return new TermService(repository, resourceIdFactory);
    }

    @Bean
    UbiquitousLanguageMcpTools ubiquitousLanguageMcpTools(
            final TermService service, final WorkspaceId workspaceId) {
        return new UbiquitousLanguageMcpTools(service, service, service, workspaceId);
    }

    // --- Use-cases hexagon -----------------------------------------------------

    /**
     * The use-case out-adapter, assembled over the <em>shared</em> {@link DatasetLifecycle}
     * bean. Since issue #89, this adapter no longer performs any cross-BC lookup itself - it
     * persists the already-resolved {@link de.hauschel.arknet.uc.domain.ActorRef}/
     * {@link de.hauschel.arknet.uc.domain.RequirementRef} identities {@link UseCaseService}
     * hands it (resolved via {@link #useCaseRequirementLookup}/{@link #useCaseActorLookup}
     * below).
     */
    @Bean
    UseCaseRepository useCaseRepository(
            final DatasetLifecycle datasetLifecycle, final ResourceIdFactory resourceIdFactory) {
        return KognioRdfUseCaseRepositoryFactory.over(datasetLifecycle, resourceIdFactory);
    }

    /**
     * Resolves a requirement's human-typed business code (e.g. {@code FR-1}) to its opaque
     * subject identity - the strict cross-BC lookup {@code uc_add}'s step-level
     * {@code realises} references need (issue #89). Acquires datasets from the same shared
     * {@link DatasetLifecycle} as {@link #requirementRepository}, so it reads the same
     * workspace the requirements hexagon writes into.
     */
    @Bean
    RequirementLookup useCaseRequirementLookup(final DatasetLifecycle datasetLifecycle) {
        return new KognioRdfRequirementLookup(datasetLifecycle);
    }

    /**
     * Resolves an actor's human-typed name (e.g. {@code Customer}) to its opaque subject
     * identity - the strict cross-BC lookup {@code uc_add}'s {@code primaryActor}/
     * {@code supportingActors} references need (issue #89). Acquires datasets from the same
     * shared {@link DatasetLifecycle} as {@link #termRepository}, so it reads the same
     * workspace the ubiquitous-language hexagon writes into.
     */
    @Bean
    ActorLookup useCaseActorLookup(final DatasetLifecycle datasetLifecycle) {
        return new KognioRdfActorLookup(datasetLifecycle);
    }

    @Bean
    UseCaseService useCaseService(
            final UseCaseRepository repository, final ResourceIdFactory resourceIdFactory,
            final RequirementLookup useCaseRequirementLookup, final ActorLookup useCaseActorLookup) {
        return new UseCaseService(repository, resourceIdFactory, useCaseRequirementLookup, useCaseActorLookup);
    }

    /**
     * {@code resolveTerms}/{@code resolveRequirements} are the ubiquitous-language and
     * requirements hexagons' own driving ports (implemented by their {@code TermService}/
     * {@code RequirementService} beans) - borrowed here purely so {@code uc_get}/
     * {@code uc_list} can render a referenced actor's name / requirement's business code
     * instead of a bare IRI (issue #89). This wires an In-Adapter to two <em>different</em>
     * hexagons' In-Ports, not to those hexagons' cores - see the "kein *-core* haengt an einem
     * anderen BC" precision in CLAUDE.md.
     */
    @Bean
    UseCaseMcpTools useCaseMcpTools(
            final UseCaseService service, final ResolveTerms resolveTerms,
            final ResolveRequirements resolveRequirements, final WorkspaceId workspaceId) {
        return new UseCaseMcpTools(service, service, service, resolveTerms, resolveRequirements, workspaceId);
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
