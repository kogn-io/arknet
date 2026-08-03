// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import java.nio.file.Path;
import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.mcp.dataset.DaemonStorageLock;
import de.hauschel.arknet.mcp.dataset.LockConflictReportingDatasetLifecycle;
import de.hauschel.arknet.mcp.report.AdrCards;
import de.hauschel.arknet.mcp.report.BoundedContextCards;
import de.hauschel.arknet.mcp.report.HtmlReportRenderer;
import de.hauschel.arknet.mcp.report.ModelViews;
import de.hauschel.arknet.mcp.report.RequirementCards;
import de.hauschel.arknet.mcp.report.TermCards;
import de.hauschel.arknet.mcp.report.UseCaseCards;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfAdrRepositoryFactory;
import de.hauschel.arknet.adr.adapter.mcp.AdrMcpTools;
import de.hauschel.arknet.adr.application.AdrService;
import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.application.port.out.BoundedContextLookup;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfContextRelationshipRepositoryFactory;
import de.hauschel.arknet.bc.adapter.mcp.BoundedContextMcpTools;
import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.prj.adapter.kogniordf.KognioRdfDatasetInventory;
import de.hauschel.arknet.prj.adapter.kogniordf.KognioRdfProjectRepositoryFactory;
import de.hauschel.arknet.prj.adapter.mcp.ProjectMcpTools;
import de.hauschel.arknet.prj.application.ProjectService;
import de.hauschel.arknet.prj.application.port.out.DatasetInventory;
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.application.port.out.ProjectSelfDescription;
import de.hauschel.arknet.mcp.store.StoreExportTools;
import de.hauschel.arknet.mcp.store.StoreExporter;
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
 * tool - there is no manual tool-specification bridging. Six hexagons are wired:</p>
 *
 * <ul>
 *   <li><strong>requirements</strong> ({@link RequirementMcpTools} over
 *       {@link RequirementService} over an RDF-persisted requirement repository) - the
 *       requirement tools are registered, callable and backed by kognio-rdf
 *       persistence. The repository is assembled through
 *       {@link KognioRdfRequirementRepositoryFactory} so this composition root stays
 *       free of any direct RDF4J dependency; it only supplies the storage directory
 *       ({@code arknet.rdf.storage}). {@code req_link_term}'s cross-BC code-to-identity
 *       resolution is a separate {@link KognioRdfTermLookup} bean over the same
 *       shared dataset lifecycle. {@code req_get}/{@code req_list}'s reverse direction (identity
 *       back to a displayable business code) is not a second store adapter - it is the
 *       ubiquitous-language hexagon's own {@link ResolveTerms} in-port, wired straight into
 *       {@link RequirementMcpTools}. {@code req_schema} is backed by
 *       a third {@link KognioRdfRequirementRepositoryFactory} product,
 *       {@link RequirementSchemaSource} - it reads only the classpath ontology, not the
 *       project store, so it needs no {@link DatasetLifecycle}.</li>
 *   <li><strong>ubiquitous-language</strong> ({@link UbiquitousLanguageMcpTools} over
 *       {@link TermService} over an RDF/SKOS-persisted term repository) - the four
 *       term tools ({@code term_add}, {@code term_list}, {@code term_get},
 *       {@code term_update}), assembled through {@link KognioRdfTermRepositoryFactory}
 *       (same RDF4J-free wiring as requirements).</li>
 *   <li><strong>use-cases</strong> ({@link UseCaseMcpTools} over {@link UseCaseService} over
 *       an RDF-persisted use-case repository) - the four use-case tools, assembled through
 *       {@link KognioRdfUseCaseRepositoryFactory}. {@code uc_add}'s cross-BC label-to-identity
 *       resolution, the use-cases analogue of requirements' own equivalent, is two separate
 *       {@link KognioRdfRequirementLookup}/{@link KognioRdfActorLookup} beans over the same
 *       shared dataset lifecycle, called once by {@link UseCaseService#add}. {@code uc_get}/
 *       {@code uc_list}'s reverse direction (identity back to a displayable business
 *       code/name) is not a second store adapter - it is the requirements hexagon's own
 *       {@link ResolveRequirements} and the ubiquitous-language hexagon's own
 *       {@link ResolveTerms} in-ports, wired straight into {@link UseCaseMcpTools}.</li>
 *   <li><strong>bounded-context</strong> ({@link BoundedContextMcpTools} over
 *       {@link BoundedContextService} over an RDF-persisted bounded-context repository) - the
 *       five bounded-context tools ({@code bc_add}/{@code bc_list}/{@code bc_get}/
 *       {@code bc_link_term}/{@code bc_link_context}), assembled through
 *       {@link KognioRdfBoundedContextRepositoryFactory}. {@code bc_link_term}'s cross-BC
 *       code-to-identity resolution is a separate {@code KognioRdfTermLookup} bean over the same
 *       shared dataset lifecycle; {@code bc_get}/{@code bc_list}'s reverse direction (identity
 *       back to a displayable term code) is the ubiquitous-language hexagon's own
 *       {@link ResolveTerms} in-port, wired straight into {@link BoundedContextMcpTools}
 *       (ADR-008). {@code bc_link_context} records an {@code arkddd:ContextRelationship} between
 *       two existing bounded contexts, persisted through a second, separately assembled
 *       {@link ContextRelationshipRepository} bean
 *       ({@link KognioRdfContextRelationshipRepositoryFactory}) over the same shared dataset
 *       lifecycle - its own resource, not a field on either {@code BoundedContext}.</li>
 *   <li><strong>adr</strong> ({@link AdrMcpTools} over {@link AdrService} over an RDF-persisted
 *       ADR repository) - the five ADR tools ({@code adr_add}/{@code adr_list}/{@code adr_get}/
 *       {@code adr_set_status}/{@code adr_supersede}), assembled through
 *       {@link KognioRdfAdrRepositoryFactory}. {@code adr_add}'s two cross-BC code-to-identity
 *       resolutions are separate {@code KognioRdfRequirementLookup}/
 *       {@code KognioRdfBoundedContextLookup} beans over the same shared dataset lifecycle;
 *       {@code adr_get}/{@code adr_list}'s reverse direction (identity back to a displayable code)
 *       is the requirements hexagon's own {@link ResolveRequirements} and the bounded-context
 *       hexagon's own {@link ResolveBoundedContexts} in-port, wired straight into
 *       {@link AdrMcpTools} (ADR-008). Its third relation, {@code supersedes}, is self-referential
 *       and therefore resolved inside {@link AdrService} - no port is borrowed for it.</li>
 *   <li><strong>project</strong> ({@link ProjectMcpTools} over {@link ProjectService} over the
 *       RDF-persisted registry) - the five project tools ({@code project_add}/
 *       {@code project_adopt}/{@code project_attach_anchor}/{@code project_rename}/
 *       {@code project_list}), assembled
 *       through {@link KognioRdfProjectRepositoryFactory}. This one is shaped differently from the
 *       four above and deliberately so (ADR-016): it manages identity rather than model, its
 *       registry lives in one reserved dataset instead of a per-project one, and it is the only
 *       hexagon here wired <em>without</em> a {@link ProjectResolver} - it reads the caller's
 *       anchor raw and looks it up, which is the substance of ADR-016 rather than an omission.
 *       Since it answers the routing question for everyone else, it cannot itself be routed.</li>
 * </ul>
 *
 * <p>All persistence hexagons share the single {@link DatasetLifecycle} bean (one store under
 * {@code arknet.rdf.storage}, no competing locks); the five model hexagons additionally share the
 * single {@link ProjectResolver} bean. Every tool call resolves its {@link ProjectId} per request
 * by looking up the anchor the caller sent - in the request header (see
 * {@link AnchorHttpTransportConfiguration}) or as an explicit tool parameter - so requirements,
 * glossary terms, use cases, bounded contexts and architecture decisions of the <em>same</em>
 * project land in the same dataset and can reference each other, while different projects stay
 * isolated.</p>
 *
 * <p><strong>What is no longer wired here, and why that is the point (ADR-016 decision 9).</strong>
 * There used to be a resolver bean deriving that identity from the caller's directory
 * ({@code arknet.workspace.id}, a git top-level lookup, slugging, a fallback to the daemon's own
 * working directory). It is gone in full rather than kept as a fallback: a second resolution path
 * that quietly takes over when the first finds nothing is precisely how two different projects came
 * to share one dataset, and a fallback would have reinstated that failure while
 * looking like a safety net.</p>
 */
@Configuration(proxyBeanMethods = false)
public class ArknetMcpConfiguration {

    // --- Shared store ----------------------------------------------------------

    /**
     * The daemon-wide guard against a second arknet process starting against the same storage
     * root (issue #139): an exclusive OS file lock over {@code storageDir}, acquired once here,
     * before {@link #datasetLifecycle} - and therefore anything able to open a project dataset -
     * is even constructed. {@link DependsOn} on {@link #datasetLifecycle} makes Spring build this
     * bean first, so a losing second daemon fails the whole application context at startup with
     * {@code DaemonAlreadyRunningException} instead of racing the winner to open a freshly
     * registered project's dataset - see {@link DaemonStorageLock} for why only a lock scoped to
     * the whole root, not to one dataset, closes that race. Released via
     * {@code destroyMethod = "close"} when the context shuts down, so a subsequent daemon start
     * against the same root succeeds again.
     */
    @Bean(destroyMethod = "close")
    DaemonStorageLock daemonStorageLock(
            @Value("${arknet.rdf.storage:${user.home}/.arknet/rdf}") final Path storageDir) {
        return DaemonStorageLock.acquire(storageDir);
    }

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
     *
     * <p>Wrapped in {@link LockConflictReportingDatasetLifecycle}: if this shared
     * instance is not actually the only one holding {@code storageDir} open - a client/subagent
     * MCP config that spawns arknet as a local {@code stdio} subprocess instead of pointing at the
     * one running daemon, say - the first {@code acquire} call fails with a raw RDF4J lock
     * exception. The wrapper recognises that failure via the injected {@code isLockConflict}
     * predicate ({@link KognioRdfRequirementRepositoryFactory#DEFAULT_LOCK_CONFLICT}, the one
     * place RDF4J is allowed to be named) and translates it into a message naming the actual cause
     * and its remedy instead of leaving a caller to decode an RDF4J internal; a failure the
     * predicate does not recognise - a permissions problem, a full disk, a corrupted store -
     * passes through unchanged rather than being misdiagnosed as a lock conflict. A second full
     * daemon instance no longer reaches this path at all - {@link #daemonStorageLock} above fails
     * it first, at startup. arknet-mcp itself stays free of any direct RDF4J dependency either
     * way.</p>
     *
     * <p>{@code destroyMethod = "close"} (issue #140): {@link LockConflictReportingDatasetLifecycle}
     * is {@link AutoCloseable}, closing every dataset {@code list()} reports through the neutral
     * port so a daemon shutdown releases them in an orderly way instead of relying on crash
     * recovery. Best-effort only - it does not wait for an in-flight request's open lease to be
     * released, see that class's own Javadoc.</p>
     */
    @Bean(destroyMethod = "close")
    @DependsOn("daemonStorageLock")
    DatasetLifecycle datasetLifecycle(
            @Value("${arknet.rdf.storage:${user.home}/.arknet/rdf}") final Path storageDir) {
        return new LockConflictReportingDatasetLifecycle(
                KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir), storageDir,
                KognioRdfRequirementRepositoryFactory.DEFAULT_LOCK_CONFLICT);
    }

    // --- Requirements hexagon --------------------------------------------------

    @Bean
    RequirementRepository requirementRepository(
            final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale) {
        return KognioRdfRequirementRepositoryFactory.over(datasetLifecycle, displayLocale);
    }

    /**
     * Resolves a glossary term's human-typed business code (e.g. {@code TERM-1}) to its opaque
     * subject identity - the strict cross-BC lookup {@code req_link_term} needs.
     * Acquires datasets from the same shared {@link DatasetLifecycle} as
     * {@link #requirementRepository}, so it reads the same project the ubiquitous-language
     * hexagon writes into.
     */
    @Bean
    TermLookup requirementTermLookup(final DatasetLifecycle datasetLifecycle) {
        return new KognioRdfTermLookup(datasetLifecycle);
    }

    /**
     * Supplies the {@code arkreq:} requirement vocabulary as data, backing {@code req_schema}.
     * Reads only the classpath ontology ({@code arknet-requirements.ttl}), not the
     * project store, so unlike every other bean in this hexagon it takes no
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
     * Resolves each tool call's target project by looking the caller's anchor up in the registry
     * (ADR-016). arknet-mcp is one shared server for every project on the machine, so there is no
     * single project fixed at boot; the anchor arrives per call in the request header (see
     * {@link AnchorHttpTransportConfiguration}) or as a tool parameter.
     *
     * <p>This bean is where the four model hexagons meet the project hexagon: it adapts the
     * kernel's {@link ProjectResolver} port onto {@link ProjectService}'s {@code ResolveProject}
     * in-port, so those four depend on the neutral port and never on {@code arknet-project} - see
     * {@link RegisteredAnchorProjectResolver}. It takes no configuration at all, which is the
     * point: {@code arknet.workspace.id}, the working-directory fallback and the git derivation
     * they fed are gone, not made optional (ADR-016 decision 9).</p>
     */
    @Bean
    ProjectResolver projectResolver(final ProjectService projectService) {
        return new RegisteredAnchorProjectResolver(projectService);
    }

    /**
     * {@code resolveTerms} is the ubiquitous-language hexagon's {@link ResolveTerms} in-port
     * (implemented by its {@code TermService} bean below) - borrowed here purely so
     * {@code req_get}/{@code req_list} can render a linked term's business code instead of its
     * bare IRI. This wires an In-Adapter to a <em>different</em> hexagon's
     * In-Port, not to that hexagon's core - see the "kein *-core* haengt an einem anderen BC"
     * precision in CLAUDE.md.
     */
    @Bean
    RequirementMcpTools requirementMcpTools(
            final RequirementService service, final ResolveTerms resolveTerms,
            final ProjectResolver projectResolver) {
        return new RequirementMcpTools(
                service, service, service, service, service, service, service, resolveTerms, projectResolver);
    }

    // --- Ubiquitous-language hexagon -------------------------------------------

    /**
     * The display language this server instance reads labels in - a consumer-supplied context,
     * exactly like {@link ProjectId}: one value per process, injected into the bounded context
     * A glossary concept may carry {@code skos:prefLabel} in several languages;
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
            final TermService service, final ProjectResolver projectResolver) {
        return new UbiquitousLanguageMcpTools(service, service, service, service, projectResolver);
    }

    // --- Use-cases hexagon -----------------------------------------------------

    /**
     * The use-case out-adapter, assembled over the <em>shared</em> {@link DatasetLifecycle}
     * bean. This adapter no longer performs any cross-BC lookup itself - it
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
     * {@code realises} references need. Acquires datasets from the same shared
     * {@link DatasetLifecycle} as {@link #requirementRepository}, so it reads the same
     * project the requirements hexagon writes into.
     */
    @Bean
    RequirementLookup useCaseRequirementLookup(final DatasetLifecycle datasetLifecycle) {
        return new KognioRdfRequirementLookup(datasetLifecycle);
    }

    /**
     * Resolves an actor's human-typed name (e.g. {@code Customer}) to its opaque subject
     * identity - the strict cross-BC lookup {@code uc_add}'s {@code primaryActor}/
     * {@code supportingActors} references need. Acquires datasets from the same
     * shared {@link DatasetLifecycle} as {@link #termRepository}, so it reads the same
     * project the ubiquitous-language hexagon writes into.
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
     * instead of a bare IRI. This wires an In-Adapter to two <em>different</em>
     * hexagons' In-Ports, not to those hexagons' cores - see the "kein *-core* haengt an einem
     * anderen BC" precision in CLAUDE.md.
     */
    @Bean
    UseCaseMcpTools useCaseMcpTools(
            final UseCaseService service, final ResolveTerms resolveTerms,
            final ResolveRequirements resolveRequirements, final ProjectResolver projectResolver) {
        return new UseCaseMcpTools(
                service, service, service, service, resolveTerms, resolveRequirements, projectResolver);
    }

    // --- Bounded-context hexagon -----------------------------------------------

    @Bean
    BoundedContextRepository boundedContextRepository(
            final DatasetLifecycle datasetLifecycle, final ResourceIdFactory resourceIdFactory,
            final DisplayLocale displayLocale) {
        return KognioRdfBoundedContextRepositoryFactory.over(datasetLifecycle, resourceIdFactory, displayLocale);
    }

    /**
     * Persists {@code bc_link_context}'s {@code arkddd:ContextRelationship} resources - its own
     * bean, not folded into {@link #boundedContextRepository}, since a relationship is its own
     * resource rather than a field on either {@code BoundedContext} it references (see
     * {@link de.hauschel.arknet.bc.domain.ContextRelationship}'s javadoc). Acquires datasets from
     * the same shared {@link DatasetLifecycle} as {@link #boundedContextRepository}, so both write
     * into the same project.
     */
    @Bean
    ContextRelationshipRepository contextRelationshipRepository(
            final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale) {
        return KognioRdfContextRelationshipRepositoryFactory.over(datasetLifecycle, displayLocale);
    }

    /**
     * Resolves a glossary term's human-typed business code (e.g. {@code TERM-1}) to its opaque
     * subject identity - the strict cross-BC lookup {@code bc_link_term} needs.
     * Acquires datasets from the same shared {@link DatasetLifecycle} as
     * {@link #termRepository}, so it reads the same project the ubiquitous-language hexagon
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
            final de.hauschel.arknet.bc.application.port.out.TermLookup boundedContextTermLookup,
            final ContextRelationshipRepository contextRelationshipRepository) {
        return new BoundedContextService(
                repository, resourceIdFactory, boundedContextTermLookup, contextRelationshipRepository);
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
            final ProjectResolver projectResolver) {
        return new BoundedContextMcpTools(
                service, service, service, service, service, resolveTerms, projectResolver);
    }

    // --- ADR hexagon -----------------------------------------------------------

    @Bean
    AdrRepository adrRepository(final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale) {
        return KognioRdfAdrRepositoryFactory.over(datasetLifecycle, displayLocale);
    }

    /**
     * Resolves a requirement's human-typed business code (e.g. {@code FR-1}) to its opaque subject
     * identity - the strict cross-BC lookup {@code adr_add}'s {@code addressesRequirements}
     * references need. A second, ADR-owned bean rather than a shared one with the use-cases hexagon:
     * each hexagon declares the capability it needs on its own out-port, and the two implementations
     * only happen to read the same sibling graph. Acquires datasets from the same shared
     * {@link DatasetLifecycle} as {@link #requirementRepository}, so it reads the same project the
     * requirements hexagon writes into.
     */
    @Bean
    de.hauschel.arknet.adr.application.port.out.RequirementLookup adrRequirementLookup(
            final DatasetLifecycle datasetLifecycle) {
        return new de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfRequirementLookup(datasetLifecycle);
    }

    /**
     * Resolves a bounded context's human-typed business code (e.g. {@code BC-1}) to its opaque
     * subject identity - the strict cross-BC lookup {@code adr_add}'s {@code affectsContexts}
     * references need. Acquires datasets from the same shared {@link DatasetLifecycle} as
     * {@link #boundedContextRepository}, so it reads the same project the bounded-context hexagon
     * writes into.
     */
    @Bean
    BoundedContextLookup adrBoundedContextLookup(final DatasetLifecycle datasetLifecycle) {
        return new de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfBoundedContextLookup(datasetLifecycle);
    }

    @Bean
    AdrService adrService(
            final AdrRepository repository, final ResourceIdFactory resourceIdFactory,
            final de.hauschel.arknet.adr.application.port.out.RequirementLookup adrRequirementLookup,
            final BoundedContextLookup adrBoundedContextLookup) {
        return new AdrService(repository, resourceIdFactory, adrRequirementLookup, adrBoundedContextLookup);
    }

    /**
     * {@code resolveRequirements}/{@code resolveBoundedContexts} are the requirements and
     * bounded-context hexagons' own driving ports (implemented by their {@code RequirementService}/
     * {@code BoundedContextService} beans) - borrowed here purely so {@code adr_get}/
     * {@code adr_list} can render an addressed requirement's or an affected context's business code
     * instead of a bare IRI (ADR-008). This wires an In-Adapter to two <em>different</em> hexagons'
     * In-Ports, not to those hexagons' cores - see the "kein *-core* haengt an einem anderen BC"
     * precision in CLAUDE.md. The third relation, {@code supersedes}, points back into the ADR
     * hexagon itself and is resolved by {@link AdrService}, so it needs no borrowed port at all.
     */
    @Bean
    AdrMcpTools adrMcpTools(
            final AdrService service, final ResolveRequirements resolveRequirements,
            final ResolveBoundedContexts resolveBoundedContexts, final ProjectResolver projectResolver) {
        return new AdrMcpTools(service, service, service, service, service, service, service,
                resolveRequirements, resolveBoundedContexts, projectResolver);
    }

    // --- Project hexagon (the registry, ADR-016) -------------------------------

    /**
     * The project registry: which opaque, client-sent anchor belongs to which project (ADR-016).
     *
     * <p><strong>The one hexagon that is not project-scoped.</strong> Every other repository bean
     * here takes a per-call {@link ProjectId} and acquires that project's dataset; this one
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

    /**
     * Lists which datasets the store physically holds - the one question the registry cannot answer,
     * because a dataset written before ADR-016 was never in it. Backs {@code project_adopt} and the
     * "unregistered datasets" section of {@code project_list}.
     *
     * <p>Reads over the same shared {@link DatasetLifecycle} as everything else, and only its
     * {@code list()}: no dataset is acquired or opened here.</p>
     */
    @Bean
    DatasetInventory datasetInventory(final DatasetLifecycle datasetLifecycle) {
        return new KognioRdfDatasetInventory(datasetLifecycle);
    }

    @Bean
    ProjectService projectService(
            final ProjectRegistry projectRegistry, final ProjectSelfDescription projectSelfDescription,
            final DatasetInventory datasetInventory) {
        return new ProjectService(projectRegistry, projectSelfDescription, datasetInventory);
    }

    /**
     * The five project tools ({@code project_add}, {@code project_attach_anchor},
     * {@code project_rename}, {@code project_list}, {@code project_adopt}).
     *
     * <p>Note what is <em>not</em> injected: no {@link ProjectResolver}. Every other
     * {@code *McpTools} bean gets one to turn a call's anchor into a {@link ProjectId}
     * by looking it up in the registry; this adapter also treats the anchor as opaque and
     * looks it up, which is the whole substance of ADR-016. Wiring it without a separate
     * resolver is not an omission but intentional - this component answers the routing question
     * and therefore cannot itself sit behind a routing answer.</p>
     *
     * <p>This bean implements the anchor registry resolver (ADR-016): every
     * tool call routes through anchor lookup instead of directory derivation. The old
     * workspace-based path has been removed; registry lookup is now the sole routing
     * mechanism for all project-scoped tool calls.</p>
     */
    @Bean
    ProjectMcpTools projectMcpTools(final ProjectService service) {
        return new ProjectMcpTools(service, service, service, service, service, service, service);
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
     * Assembles the HTML report's per-bounded-context sections by borrowing all five hexagons'
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
            final BoundedContextService boundedContexts, final AdrService adrs,
            final ResolveRequirements resolveRequirements) {
        return new ModelViews(
                terms,
                new UseCaseCards(useCases, resolveRequirements),
                new RequirementCards(requirements),
                new BoundedContextCards(boundedContexts),
                new AdrCards(adrs, resolveRequirements, boundedContexts));
    }

    /**
     * The two read-only store tools ({@code store_overview}, {@code resource_get}). Both read
     * the project dataset through {@link StoreReader} - a single generic
     * {@code SELECT ?s ?p ?o} - so no bounded context needs a read tool of its own. The agent's
     * return value stays that domain-agnostic digest; the human-facing HTML additionally groups
     * the model per bounded context through {@link ModelViews}, with the generic snapshot as its
     * safety net. The HTML report is written into {@code arknet.report.dir} (default: the
     * launched project root / working directory). {@code arknet.report.host-dir} is the
     * host-reachable equivalent of that directory when it is a container-internal mount point
     * the calling agent cannot reach directly; unset on the non-containerized path,
     * where {@code fallbackReportDir} is already host-reachable.
     *
     * <p>{@code projectService} is passed as {@link de.hauschel.arknet.prj.application.port.in.FindProject},
     * not {@link ProjectRegistry} directly: the digest and HTML headers name the resolved
     * project's registered label instead of its raw id by borrowing the project
     * hexagon's driving port, the same gateway role ADR-008 grants any other in-adapter of a
     * neighbour bounded context.</p>
     */
    @Bean
    StoreReportTools storeReportTools(
            final StoreReader storeReader, final Prefixes prefixes, final DisplayLocale displayLocale,
            final ModelViews modelViews, final ProjectResolver projectResolver, final ProjectService projectService,
            @Value("${arknet.report.dir:${user.dir}}") final Path fallbackReportDir,
            @Value("${arknet.report.host-dir:#{null}}") final Path reportHostDir) {
        return new StoreReportTools(
                storeReader, prefixes, displayLocale, new HtmlReportRenderer(prefixes, displayLocale), modelViews,
                projectResolver, projectService, fallbackReportDir, reportHostDir);
    }

    /**
     * The backup read path: a complete {@code SELECT ?g ?s ?p ?o} spanning every named graph of a
     * project's dataset, unlike {@link #storeReader} it hides neither the provenance nor the
     * project-identity graph, because a backup exists to restore both.
     */
    @Bean
    StoreExporter storeExporter(final DatasetLifecycle datasetLifecycle) {
        return new StoreExporter(datasetLifecycle);
    }

    /**
     * The one backup tool ({@code project_export}). Not project-scoped like every
     * other {@code *McpTools} bean here - it exports every project {@link ProjectService} (as its
     * {@code ListProjects} in-port) reports as registered, in one call. {@code arknet.export.dir}/
     * {@code arknet.export.host-dir} mirror {@code arknet.report.dir}/{@code arknet.report.host-dir}
     * for the same reason: on a containerized daemon the export directory is a
     * container-internal mount point the calling agent cannot reach directly.
     */
    @Bean
    StoreExportTools storeExportTools(
            final ProjectService projectService, final StoreExporter storeExporter,
            @Value("${arknet.export.dir:${user.home}/.arknet/export}") final Path fallbackExportDir,
            @Value("${arknet.export.host-dir:#{null}}") final Path exportHostDir) {
        return new StoreExportTools(projectService, storeExporter, fallbackExportDir, exportHostDir);
    }

    /**
     * The three traceability reporting tools ({@code trace_matrix}, {@code orphan_check},
     * {@code impact_analysis}). Reuses the very same {@link #storeReader}/
     * {@link #storeReportPrefixes} beans as {@link #storeReportTools} instead of building a
     * second {@link StoreReader} - one generic read path, two presentations over it (a
     * full-snapshot digest vs. a graph traversal), per ADR-006.
     */
    @Bean
    TraceabilityMcpTools traceabilityMcpTools(
            final StoreReader storeReader, final Prefixes prefixes, final ProjectResolver projectResolver,
            final DisplayLocale displayLocale) {
        return new TraceabilityMcpTools(storeReader, prefixes, projectResolver, displayLocale);
    }
}
