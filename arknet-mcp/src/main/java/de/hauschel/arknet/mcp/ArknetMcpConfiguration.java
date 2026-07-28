// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import java.nio.file.Path;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.kernel.WorkspaceResolver;
import de.hauschel.arknet.mcp.report.BoundedContextCards;
import de.hauschel.arknet.mcp.report.HtmlReportRenderer;
import de.hauschel.arknet.mcp.report.ModelViews;
import de.hauschel.arknet.mcp.report.RequirementCards;
import de.hauschel.arknet.mcp.report.TermCards;
import de.hauschel.arknet.mcp.report.UseCaseCards;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.adapter.mcp.BoundedContextMcpTools;
import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.prj.adapter.kogniordf.KognioRdfProjectRepositoryFactory;
import de.hauschel.arknet.prj.adapter.mcp.ProjectMcpTools;
import de.hauschel.arknet.prj.application.ProjectService;
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.application.port.out.ProjectSelfDescription;
import de.hauschel.arknet.mcp.store.StoreReader;
import de.hauschel.arknet.mcp.store.StoreReportTools;
import de.hauschel.arknet.mcp.trace.TraceabilityMcpTools;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfTermLookup;
import de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
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
 * tool - there is no manual tool-specification bridging. Five hexagons are wired:</p>
 *
 * <ul>
 *   <li><strong>requirements</strong> ({@link RequirementMcpTools} over
 *       {@link RequirementService} over an RDF-persisted requirement repository) - the
 *       requirement tools are registered, callable and backed by kognio-rdf
 *       persistence. The repository is assembled through
 *       {@link KognioRdfRequirementRepositoryFactory} so this composition root stays
 *       free of any direct RDF4J dependency; it only supplies the storage directory
 *       ({@code arknet.rdf.storage}). {@code req_link_term}'s cross-BC code-to-identity
 *       resolution (issue #77) is a separate {@link KognioRdfTermLookup} bean over the same
 *       shared dataset lifecycle. {@code req_get}/{@code req_list}'s reverse direction (identity
 *       back to a displayable business code) is not a second store adapter - it is the
 *       ubiquitous-language hexagon's own {@link ResolveTerms} in-port, wired straight into
 *       {@link RequirementMcpTools} (#77 nachtrag). {@code req_schema} (issue #31) is backed by
 *       a third {@link KognioRdfRequirementRepositoryFactory} product,
 *       {@link RequirementSchemaSource} - it reads only the classpath ontology, not the
 *       workspace store, so it needs no {@link DatasetLifecycle}.</li>
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
 *   <li><strong>bounded-context</strong> ({@link BoundedContextMcpTools} over
 *       {@link BoundedContextService} over an RDF-persisted bounded-context repository) - the
 *       four bounded-context tools ({@code bc_add}/{@code bc_list}/{@code bc_get}/
 *       {@code bc_link_term}), assembled through {@link KognioRdfBoundedContextRepositoryFactory}.
 *       {@code bc_link_term}'s cross-BC code-to-identity resolution (issue #62/#66) is a separate
 *       {@code KognioRdfTermLookup} bean over the same shared dataset lifecycle; {@code bc_get}/
 *       {@code bc_list}'s reverse direction (identity back to a displayable term code) is the
 *       ubiquitous-language hexagon's own {@link ResolveTerms} in-port, wired straight into
 *       {@link BoundedContextMcpTools} (ADR-008).</li>
 *   <li><strong>project</strong> ({@link ProjectMcpTools} over {@link ProjectService} over the
 *       RDF-persisted registry) - the four project tools ({@code project_add}/
 *       {@code project_attach_anchor}/{@code project_rename}/{@code project_list}), assembled
 *       through {@link KognioRdfProjectRepositoryFactory}. This one is shaped differently from the
 *       four above and deliberately so (ADR-016): it manages identity rather than model, its
 *       registry lives in one reserved dataset instead of a per-project one, and it is the only
 *       hexagon here wired <em>without</em> a {@link WorkspaceResolver} - it reads the caller's
 *       origin value as an opaque anchor and looks it up, which is the substance of ADR-016 rather
 *       than an omission. It is additive: registering these tools changes the routing of no other
 *       tool call.</li>
 * </ul>
 *
 * <p>All persistence hexagons share the single {@link DatasetLifecycle} bean (one store under
 * {@code arknet.rdf.storage}, no competing locks); the four model hexagons additionally share the
 * single {@link WorkspaceResolver} bean.
 * Every tool call resolves its {@link WorkspaceId} per request from the caller's origin directory
 * (issue #137: one shared HTTP server for every workspace on the machine, see
 * {@link WorkspaceHttpTransportConfiguration}), so requirements, glossary terms, use cases and
 * bounded contexts of the <em>same</em> project land in the same workspace/dataset and can
 * reference each other, while different projects stay isolated.</p>
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
    RequirementRepository requirementRepository(
            final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale) {
        return KognioRdfRequirementRepositoryFactory.over(datasetLifecycle, displayLocale);
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
     * Supplies the {@code arkreq:} requirement vocabulary as data, backing {@code req_schema}
     * (issue #31). Reads only the classpath ontology ({@code arknet-requirements.ttl}), not the
     * workspace store, so unlike every other bean in this hexagon it takes no
     * {@link DatasetLifecycle}.
     */
    @Bean
    RequirementSchemaSource requirementSchemaSource() {
        return KognioRdfRequirementRepositoryFactory.buildSchemaSource();
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
            final TermLookup requirementTermLookup, final RequirementSchemaSource requirementSchemaSource) {
        return new RequirementService(repository, resourceIdFactory, requirementTermLookup, requirementSchemaSource);
    }

    /**
     * Resolves each tool call's target workspace from the calling client's origin directory
     * (issue #137). arknet-mcp is one shared server for every workspace on the machine, so there
     * is no longer a single workspace fixed at boot; the {@link WorkspaceResolver} maps a
     * per-call origin directory - carried in the request header (see
     * {@link WorkspaceHttpTransportConfiguration}) - to a {@link WorkspaceId} via
     * {@link WorkspaceIdResolver} (explicit {@code arknet.workspace.id} override, else the git
     * top-level / working-directory name). A call without an origin falls back to
     * {@code arknet.workspace.dir} (the daemon's own working directory).
     */
    @Bean
    WorkspaceResolver workspaceResolver(
            @Value("${arknet.workspace.id:}") final String explicitId,
            @Value("${arknet.workspace.dir:${user.dir}}") final Path fallbackDir) {
        return new GitWorkspaceResolver(new WorkspaceIdResolver(), explicitId, fallbackDir);
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
            final RequirementService service, final ResolveTerms resolveTerms,
            final WorkspaceResolver workspaceResolver) {
        return new RequirementMcpTools(
                service, service, service, service, service, service, service, resolveTerms, workspaceResolver);
    }

    // --- Ubiquitous-language hexagon -------------------------------------------

    /**
     * The display language this server instance reads labels in - a consumer-supplied context,
     * exactly like {@link WorkspaceId}: one value per process, injected into the bounded context
     * (issue #80). A glossary concept may carry {@code skos:prefLabel} in several languages;
     * {@link DisplayLocale#select} then chooses which one the read paths surface, degrading
     * through a fixed fallback chain (requested language, {@code arknet.locale.requested} -> system
     * default, {@code arknet.locale.default} -> untagged literal -> a deterministic last resort) so
     * a term is never swallowed for lacking the requested language. Both properties default to
     * English; since {@code term_add} writes untagged labels today, the untagged step surfaces them
     * regardless.
     */
    @Bean
    DisplayLocale displayLocale(
            @Value("${arknet.locale.requested:en}") final String requested,
            @Value("${arknet.locale.default:en}") final String systemDefault) {
        return new DisplayLocale(Locale.forLanguageTag(requested), Locale.forLanguageTag(systemDefault));
    }

    @Bean
    TermRepository termRepository(final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale) {
        return KognioRdfTermRepositoryFactory.over(datasetLifecycle, displayLocale);
    }

    @Bean
    TermService termService(final TermRepository repository, final ResourceIdFactory resourceIdFactory) {
        return new TermService(repository, resourceIdFactory);
    }

    @Bean
    UbiquitousLanguageMcpTools ubiquitousLanguageMcpTools(
            final TermService service, final WorkspaceResolver workspaceResolver) {
        return new UbiquitousLanguageMcpTools(service, service, service, service, workspaceResolver);
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
            final DatasetLifecycle datasetLifecycle, final ResourceIdFactory resourceIdFactory,
            final DisplayLocale displayLocale) {
        return KognioRdfUseCaseRepositoryFactory.over(datasetLifecycle, resourceIdFactory, displayLocale);
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
            final ResolveRequirements resolveRequirements, final WorkspaceResolver workspaceResolver) {
        return new UseCaseMcpTools(
                service, service, service, resolveTerms, resolveRequirements, workspaceResolver);
    }

    // --- Bounded-context hexagon -----------------------------------------------

    @Bean
    BoundedContextRepository boundedContextRepository(
            final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale) {
        return KognioRdfBoundedContextRepositoryFactory.over(datasetLifecycle, displayLocale);
    }

    /**
     * Resolves a glossary term's human-typed business code (e.g. {@code TERM-1}) to its opaque
     * subject identity - the strict cross-BC lookup {@code bc_link_term} needs (issue #62/#66).
     * Acquires datasets from the same shared {@link DatasetLifecycle} as
     * {@link #termRepository}, so it reads the same workspace the ubiquitous-language hexagon
     * writes into.
     */
    @Bean
    de.hauschel.arknet.bc.application.port.out.TermLookup boundedContextTermLookup(
            final DatasetLifecycle datasetLifecycle) {
        return new de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfTermLookup(datasetLifecycle);
    }

    @Bean
    BoundedContextService boundedContextService(
            final BoundedContextRepository repository, final ResourceIdFactory resourceIdFactory,
            final de.hauschel.arknet.bc.application.port.out.TermLookup boundedContextTermLookup) {
        return new BoundedContextService(repository, resourceIdFactory, boundedContextTermLookup);
    }

    /**
     * {@code resolveTerms} is the ubiquitous-language hexagon's {@link ResolveTerms} in-port
     * (implemented by its {@code TermService} bean) - borrowed here purely so {@code bc_get}/
     * {@code bc_list} can render a linked term's business code instead of its bare IRI (ADR-008).
     * This wires an In-Adapter to a <em>different</em> hexagon's In-Port, not to that hexagon's
     * core - see the "kein *-core* haengt an einem anderen BC" precision in CLAUDE.md.
     */
    @Bean
    BoundedContextMcpTools boundedContextMcpTools(
            final BoundedContextService service, final ResolveTerms resolveTerms,
            final WorkspaceResolver workspaceResolver) {
        return new BoundedContextMcpTools(service, service, service, service, resolveTerms, workspaceResolver);
    }

    // --- Project hexagon (the registry, ADR-016) -------------------------------

    /**
     * The project registry: which opaque, client-sent anchor belongs to which project (ADR-016).
     *
     * <p><strong>The one hexagon that is not project-scoped.</strong> Every other repository bean
     * here takes a per-call {@link WorkspaceId} and acquires that project's dataset; this one
     * always addresses a single reserved dataset ({@code ProjectId.RESERVED_SYSTEM_DATASET}) and
     * therefore takes no routing key at all. It has to be that way round: the registry is what
     * answers the routing question, so it cannot itself be behind an answer to it. It shares the
     * same {@link DatasetLifecycle} bean as everything else - one store, one lock, one reserved
     * dataset inside it alongside the project datasets.</p>
     */
    @Bean
    ProjectRegistry projectRegistry(
            final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale) {
        return KognioRdfProjectRepositoryFactory.registryOver(datasetLifecycle, displayLocale);
    }

    /**
     * Writes each project's self-description into that project's <em>own</em> dataset (ADR-016
     * decision 7), so the registry stays a rebuildable index rather than a single point of failure
     * and a dataset restored from a backup carries its own identity with it. A second bean rather
     * than a method on {@link #projectRegistry}, because it writes to a different dataset - there
     * is no shared transaction between the two, and the ordering (registry first, self-description
     * second) is a policy of the application service, not of either adapter.
     */
    @Bean
    ProjectSelfDescription projectSelfDescription(
            final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale) {
        return KognioRdfProjectRepositoryFactory.selfDescriptionOver(datasetLifecycle, displayLocale);
    }

    @Bean
    ProjectService projectService(
            final ProjectRegistry projectRegistry, final ProjectSelfDescription projectSelfDescription) {
        return new ProjectService(projectRegistry, projectSelfDescription);
    }

    /**
     * The four project tools ({@code project_add}, {@code project_attach_anchor},
     * {@code project_rename}, {@code project_list}).
     *
     * <p>Note what is <em>not</em> injected: no {@link WorkspaceResolver}. Every other
     * {@code *McpTools} bean gets one to turn a call's origin directory into a {@link WorkspaceId}
     * by deriving it (git top-level, slugging); this adapter reads the very same origin value but
     * treats it as an opaque anchor and looks it up, which is the whole substance of ADR-016.
     * Wiring it without a resolver is therefore not an omission but the point.</p>
     *
     * <p>This bean is additive (issue #178): registering the tools does not change how any other
     * tool call is routed - the derived-workspace path in {@link #workspaceResolver} keeps running
     * untouched until issue #179 switches over and tears it down.</p>
     */
    @Bean
    ProjectMcpTools projectMcpTools(final ProjectService service) {
        return new ProjectMcpTools(service, service, service, service, service);
    }

    // --- Store read path: generic query, model-shaped report -------------------

    @Bean
    Prefixes storeReportPrefixes() {
        return Prefixes.defaults();
    }

    @Bean
    StoreReader storeReader(final DatasetLifecycle datasetLifecycle) {
        return new StoreReader(datasetLifecycle);
    }

    /**
     * Assembles the HTML report's per-bounded-context sections by borrowing all four hexagons'
     * read In-Ports - the same In-Adapter-as-gateway role ADR-008 grants {@code uc_get} when it
     * borrows {@link ResolveTerms}, here for the report rather than for a tool response. A use
     * case reconstructed from raw triples is not readable as a use case (its flow is a set of
     * opaque {@code arkreq:Step} subjects ordered by an {@code arkreq:position} literal), so the
     * report asks the context that wrote it instead of re-deriving the answer here. The generic
     * snapshot still backs every card's raw triples and catches whatever no context claims -
     * see the ADR-006 addendum.
     *
     * <p>The glossary arrives as {@code ListTerms} rather than {@link ResolveTerms}: besides
     * labelling references, the report marks the ubiquitous language up inside the other
     * contexts' prose, and telling a linked mention from an unlinked one needs every term, not
     * just the ones an edge already points at.</p>
     */
    @Bean
    ModelViews modelViews(
            final UseCaseService useCases, final RequirementService requirements, final TermService terms,
            final BoundedContextService boundedContexts,
            final ResolveRequirements resolveRequirements) {
        return new ModelViews(
                terms,
                new UseCaseCards(useCases, resolveRequirements),
                new RequirementCards(requirements),
                new BoundedContextCards(boundedContexts));
    }

    /**
     * The two read-only store tools ({@code store_overview}, {@code resource_get}). Both read
     * the workspace dataset through {@link StoreReader} - a single generic
     * {@code SELECT ?s ?p ?o} - so no bounded context needs a read tool of its own. The agent's
     * return value stays that domain-agnostic digest; the human-facing HTML additionally groups
     * the model per bounded context through {@link ModelViews}, with the generic snapshot as its
     * safety net. The HTML report is written into {@code arknet.report.dir} (default: the
     * launched project root / working directory). {@code arknet.report.host-dir} is the
     * host-reachable equivalent of that directory when it is a container-internal mount point
     * the calling agent cannot reach directly (issue #160); unset on the non-containerized path,
     * where {@code fallbackReportDir} is already host-reachable.
     */
    @Bean
    StoreReportTools storeReportTools(
            final StoreReader storeReader, final Prefixes prefixes, final ModelViews modelViews,
            final WorkspaceResolver workspaceResolver,
            @Value("${arknet.report.dir:${arknet.workspace.dir:${user.dir}}}") final Path fallbackReportDir,
            @Value("${arknet.report.host-dir:#{null}}") final Path reportHostDir) {
        return new StoreReportTools(
                storeReader, prefixes, new HtmlReportRenderer(prefixes), modelViews, workspaceResolver,
                fallbackReportDir, reportHostDir);
    }

    /**
     * The three traceability reporting tools ({@code trace_matrix}, {@code orphan_check},
     * {@code impact_analysis}; issue #131). Reuses the very same {@link #storeReader}/
     * {@link #storeReportPrefixes} beans as {@link #storeReportTools} instead of building a
     * second {@link StoreReader} - one generic read path, two presentations over it (a
     * full-snapshot digest vs. a graph traversal), per ADR-006.
     */
    @Bean
    TraceabilityMcpTools traceabilityMcpTools(
            final StoreReader storeReader, final Prefixes prefixes, final WorkspaceResolver workspaceResolver) {
        return new TraceabilityMcpTools(storeReader, prefixes, workspaceResolver);
    }
}
