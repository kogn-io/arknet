// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import java.nio.file.Path;
import java.time.Clock;
import java.util.Locale;
import java.util.Optional;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.info.BuildProperties;
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
import de.hauschel.arknet.mcp.report.ActorCards;
import de.hauschel.arknet.mcp.report.AdrCards;
import de.hauschel.arknet.mcp.report.BoundedContextCards;
import de.hauschel.arknet.mcp.report.ConstraintCards;
import de.hauschel.arknet.mcp.report.HtmlReportRenderer;
import de.hauschel.arknet.mcp.report.ModelViews;
import de.hauschel.arknet.mcp.report.RequirementCards;
import de.hauschel.arknet.mcp.report.RoleCards;
import de.hauschel.arknet.mcp.report.TermCards;
import de.hauschel.arknet.mcp.report.UseCaseCards;
import de.hauschel.arknet.mcp.store.ExportMetadata;
import de.hauschel.arknet.mcp.check.StoreCheckMcpTools;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.version.OntologyVersions;
import de.hauschel.arknet.mcp.version.ServerVersion;
import de.hauschel.arknet.mcp.version.ToolErrorVersionStamp;
import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfActorRepositoryFactory;
import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfRoleRepositoryFactory;
import de.hauschel.arknet.actor.adapter.mcp.ActorMcpTools;
import de.hauschel.arknet.actor.adapter.mcp.RoleMcpTools;
import de.hauschel.arknet.actor.application.ActorService;
import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.ResolveRoles;
import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfAdrRepositoryFactory;
import de.hauschel.arknet.adr.adapter.mcp.AdrCheckMcpTools;
import de.hauschel.arknet.adr.adapter.mcp.AdrMcpTools;
import de.hauschel.arknet.adr.application.AdrCheckService;
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
import de.hauschel.arknet.mcp.store.StoreReportController;
import de.hauschel.arknet.mcp.store.StoreReportTools;
import de.hauschel.arknet.mcp.trace.TraceabilityMcpTools;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfConstraintRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfTermLookup;
import de.hauschel.arknet.req.adapter.mcp.ConstraintMcpTools;
import de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools;
import de.hauschel.arknet.req.application.ConstraintService;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.application.port.out.TermLookup;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.adapter.mcp.UbiquitousLanguageMcpTools;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfRequirementLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfRoleLookup;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfUseCaseRepositoryFactory;
import de.hauschel.arknet.uc.adapter.mcp.UseCaseMcpTools;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.out.RequirementLookup;
import de.hauschel.arknet.uc.application.port.out.RoleLookup;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;

/**
 * Bean wiring for the arknet MCP composition root.
 *
 * <p>Every bean declared here that exposes {@code @McpTool} methods is picked up
 * automatically by the Spring AI MCP server annotation scanner and registered as an MCP
 * tool - there is no manual tool-specification bridging. Seven hexagons are wired:</p>
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
 *       project store, so it needs no {@link DatasetLifecycle}. This hexagon also carries a
 *       second resource type, {@code Constraint} (issue #223, not a bounded context of its own):
 *       {@link ConstraintMcpTools} exposes {@code constraint_add}/{@code constraint_list}/
 *       {@code constraint_get}/{@code constraint_update}/{@code constraint_delete} over
 *       {@link ConstraintService} over
 *       {@link KognioRdfConstraintRepositoryFactory}, sharing the requirement repository's own
 *       {@link WriteFunnel} bean ({@link #requirementsWriteFunnel}) rather than building a
 *       second, functionally identical one; {@code req_link_constraint} stays on
 *       {@link RequirementMcpTools} since it mutates the requirement.</li>
 *   <li><strong>ubiquitous-language</strong> ({@link UbiquitousLanguageMcpTools} over
 *       {@link TermService} over an RDF/SKOS-persisted term repository) - the four
 *       term tools ({@code term_add}, {@code term_list}, {@code term_get},
 *       {@code term_update}), assembled through {@link KognioRdfTermRepositoryFactory}
 *       (same RDF4J-free wiring as requirements).</li>
 *   <li><strong>use-cases</strong> ({@link UseCaseMcpTools} over {@link UseCaseService} over
 *       an RDF-persisted use-case repository) - the six use-case tools, assembled through
 *       {@link KognioRdfUseCaseRepositoryFactory}. {@code uc_add}'s cross-BC code-to-identity
 *       resolution, the use-cases analogue of requirements' own equivalent, is two separate
 *       {@link KognioRdfRequirementLookup}/{@link KognioRdfRoleLookup} beans over the same
 *       shared dataset lifecycle, called once by {@link UseCaseService#add} (ADR-37/
 *       kogn-io/arknet#405 Part C - the role lookup replaced the actor register's former
 *       name-based lookup here). {@code uc_link_term}/
 *       {@code uc_link_constraint} (issue #329) add two more such lookups, {@code
 *       KognioRdfTermLookup}/{@code KognioRdfConstraintLookup} - own implementations in the
 *       use-cases adapter module, not the sibling requirements/ubiquitous-language adapters'
 *       classes of the same simple name (the Borrowed In-Port pattern forbids the cross-BC adapter
 *       import that would be). {@code uc_get}/{@code uc_list}'s reverse direction (identity back to a displayable
 *       business code/name) is not a second store adapter - it is the actor hexagon's own
 *       {@link ResolveRoles}, the requirements hexagon's own
 *       {@link ResolveRequirements}/{@link ResolveConstraints} and the ubiquitous-language
 *       hexagon's own {@link ResolveTerms} in-ports, wired straight into
 *       {@link UseCaseMcpTools}.</li>
 *   <li><strong>bounded-context</strong> ({@link BoundedContextMcpTools} over
 *       {@link BoundedContextService} over an RDF-persisted bounded-context repository) - the
 *       five bounded-context tools ({@code bc_add}/{@code bc_list}/{@code bc_get}/
 *       {@code bc_link_term}/{@code bc_link_context}), assembled through
 *       {@link KognioRdfBoundedContextRepositoryFactory}. {@code bc_link_term}'s cross-BC
 *       code-to-identity resolution is a separate {@code KognioRdfTermLookup} bean over the same
 *       shared dataset lifecycle; {@code bc_get}/{@code bc_list}'s reverse direction (identity
 *       back to a displayable term code) is the ubiquitous-language hexagon's own
 *       {@link ResolveTerms} in-port, wired straight into {@link BoundedContextMcpTools}
 *       (Borrowed In-Port). {@code bc_link_context} records an {@code arkddd:ContextRelationship} between
 *       two existing bounded contexts, persisted through a second, separately assembled
 *       {@link ContextRelationshipRepository} bean
 *       ({@link KognioRdfContextRelationshipRepositoryFactory}) over the same shared dataset
 *       lifecycle - its own resource, not a field on either {@code BoundedContext}.</li>
 *   <li><strong>adr</strong> ({@link AdrMcpTools} over {@link AdrService} over an RDF-persisted
 *       ADR repository) - the eight ADR tools ({@code adr_add}/{@code adr_list}/{@code adr_get}/
 *       {@code adr_update}/{@code adr_set_status}/{@code adr_supersede}/{@code adr_unsupersede}/
 *       {@code adr_delete}, {@code adr_unsupersede} added by kogn-io/arknet#354), assembled through
 *       {@link KognioRdfAdrRepositoryFactory}. {@code adr_add}'s and {@code adr_update}'s two
 *       cross-BC code-to-identity
 *       resolutions are separate {@code KognioRdfRequirementLookup}/
 *       {@code KognioRdfBoundedContextLookup} beans over the same shared dataset lifecycle;
 *       {@code adr_get}/{@code adr_list}'s reverse direction (identity back to a displayable code)
 *       is the requirements hexagon's own {@link ResolveRequirements} and the bounded-context
 *       hexagon's own {@link ResolveBoundedContexts} in-port, wired straight into
 *       {@link AdrMcpTools} (Borrowed In-Port). Its other two relations, {@code supersededBy} and
 *       {@code relatedTo}, are self-referential and therefore resolved inside {@link AdrService} -
 *       no port is borrowed for either. Its reading check {@code adr_check} (kogn-io/arknet#387)
 *       sits in a tools class and an application service of its own
 *       ({@link AdrCheckMcpTools} over {@link AdrCheckService}) rather than in the two above,
 *       because it neither writes nor judges - see there.</li>
 *   <li><strong>project</strong> ({@link ProjectMcpTools} over {@link ProjectService} over the
 *       RDF-persisted registry) - the project tools ({@code project_add}/
 *       {@code project_adopt}/{@code project_attach_anchor}/{@code project_rename}/
 *       {@code project_update}/{@code project_list}), assembled
 *       through {@link KognioRdfProjectRepositoryFactory}. This one is shaped differently from the
 *       model hexagons above and deliberately so: it manages identity rather than model, its
 *       registry lives in one reserved dataset instead of a per-project one, and it is the only
 *       hexagon here wired <em>without</em> a {@link ProjectResolver} - it reads the caller's
 *       anchor raw and looks it up, which is deliberate rather than an omission.
 *       Since it answers the routing question for everyone else, it cannot itself be routed.</li>
 *   <li><strong>actor</strong> ({@link ActorMcpTools} over {@link ActorService} over an
 *       RDF-persisted actor repository) - the five actor tools ({@code actor_add}/
 *       {@code actor_list}/{@code actor_get}/{@code actor_update}/{@code actor_delete}), assembled
 *       through {@link KognioRdfActorRepositoryFactory}. The plainest wiring of the seven: no
 *       cross-BC lookup bean on the write side and no borrowed neighbour in-port on the read side,
 *       because an {@code arkproc:Actor} carries no reference to a term, a requirement or a bounded
 *       context in this scope - it exists as a resource of its own, independent of the actor facet
 *       the ubiquitous-language hexagon still sets on a {@code skos:Concept}. Its {@link
 *       WriteFunnel} is built once here as a bean of its own ({@link #actorWriteFunnel}) and
 *       <em>shared</em> with this hexagon's second resource type below, the same way
 *       {@link ConstraintMcpTools}'s repository shares the requirements funnel - the two write
 *       through one gate because they share both resource files ({@code actor-shapes.ttl},
 *       {@code arknet-actor.ttl}), while writing into two different named graphs.</li>
 *   <li><strong>role</strong> ({@link RoleMcpTools} over {@link RoleService} over an RDF-persisted
 *       role repository) - the five role tools ({@code role_add}/{@code role_list}/
 *       {@code role_get}/{@code role_update}/{@code role_delete}), assembled through
 *       {@link KognioRdfRoleRepositoryFactory} over the shared {@link #actorWriteFunnel}
 *       (ADR-37/kogn-io/arknet#405). Not an eighth hexagon but the actor hexagon's second resource
 *       type, which is why {@link RoleService} resolves an {@code arkproc:filledBy} occupant
 *       against {@link ActorRepository} directly instead of borrowing an in-port: no bounded-context
 *       boundary is crossed here.</li>
 * </ul>
 *
 * <p>All persistence hexagons share the single {@link DatasetLifecycle} bean (one store under
 * {@code arknet.rdf.storage}, no competing locks); the model hexagons additionally share the
 * single {@link ProjectResolver} bean. Every tool call resolves its {@link ProjectId} per request
 * by looking up the anchor the caller sent - in the request header (see
 * {@link AnchorHttpTransportConfiguration}) or as an explicit tool parameter - so requirements,
 * glossary terms, use cases, bounded contexts and architecture decisions of the <em>same</em>
 * project land in the same dataset and can reference each other, while different projects stay
 * isolated.</p>
 *
 * <p><strong>What is no longer wired here, and why that is the point.</strong>
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

    /**
     * The shared {@link WriteFunnel} every write path of the requirements hexagon runs through -
     * both {@link #requirementRepository} and {@link #constraintRepository} (issue #223): a
     * {@code Constraint} shares the requirements SHACL shapes and ontology axioms, so this bean is
     * built once and handed to both repositories rather than each building its own, functionally
     * identical one.
     */
    @Bean
    WriteFunnel requirementsWriteFunnel(final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale) {
        return KognioRdfRequirementRepositoryFactory.buildFunnel(datasetLifecycle, displayLocale);
    }

    @Bean
    RequirementRepository requirementRepository(
            final DatasetLifecycle datasetLifecycle, final ResourceIdFactory resourceIdFactory,
            final DisplayLocale displayLocale, final WriteFunnel requirementsWriteFunnel) {
        return KognioRdfRequirementRepositoryFactory.over(
                datasetLifecycle, resourceIdFactory, displayLocale, requirementsWriteFunnel);
    }

    /**
     * {@code Constraint} (issue #223) lives inside this same hexagon, not a bounded context of
     * its own - so this repository shares {@link #requirementsWriteFunnel} rather than getting a
     * SHACL write-gate/write-funnel pair of its own (see
     * {@code KognioRdfConstraintRepositoryFactory}'s javadoc).
     */
    @Bean
    ConstraintRepository constraintRepository(
            final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale,
            final WriteFunnel requirementsWriteFunnel) {
        return KognioRdfConstraintRepositoryFactory.over(
                datasetLifecycle, displayLocale, requirementsWriteFunnel);
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
            final TermLookup requirementTermLookup, final ConstraintRepository constraintRepository,
            final RequirementSchemaSource requirementSchemaSource) {
        return new RequirementService(
                repository, resourceIdFactory, requirementTermLookup, constraintRepository,
                requirementSchemaSource);
    }

    @Bean
    ConstraintService constraintService(
            final ConstraintRepository constraintRepository, final ResourceIdFactory resourceIdFactory) {
        return new ConstraintService(constraintRepository, resourceIdFactory);
    }

    /**
     * Resolves each tool call's target project by looking the caller's anchor up in the registry
     *. arknet-mcp is one shared server for every project on the machine, so there is no
     * single project fixed at boot; the anchor arrives per call in the request header (see
     * {@link AnchorHttpTransportConfiguration}) or as a tool parameter.
     *
     * <p>This bean is where the model hexagons meet the project hexagon: it adapts the
     * kernel's {@link ProjectResolver} port onto {@link ProjectService}'s {@code ResolveProject}
     * in-port, so the model hexagons depend on the neutral port and never on {@code arknet-project} - see
     * {@link RegisteredAnchorProjectResolver}. It takes no configuration at all, which is the
     * point: {@code arknet.workspace.id}, the working-directory fallback and the git derivation
     * they fed are gone, not made optional.</p>
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
            final ConstraintService constraintService, final ProjectResolver projectResolver) {
        return new RequirementMcpTools(
                service, service, service, service, service, service, service, service, service, service,
                resolveTerms, constraintService, projectResolver);
    }

    /**
     * The five constraint tools ({@code constraint_add}, {@code constraint_list},
     * {@code constraint_get}, {@code constraint_update}, {@code constraint_delete}).
     * {@code req_link_constraint} itself stays on {@link #requirementMcpTools} - it mutates the
     * requirement, not the constraint.
     */
    @Bean
    ConstraintMcpTools constraintMcpTools(
            final ConstraintService service, final ProjectResolver projectResolver) {
        return new ConstraintMcpTools(service, service, service, service, service, service, projectResolver);
    }

    // --- Ubiquitous-language hexagon -------------------------------------------

    /**
     * The display language this server instance reads labels in - a consumer-supplied context,
     * injected once per process as this single bean (unlike {@link ProjectId}, which arknet-mcp
     * resolves per call from the caller's anchor rather than fixing at boot).
     * A glossary concept may carry {@code skos:prefLabel} in several languages;
     * {@link DisplayLocale#select} then chooses which one the read paths surface, degrading
     * through a fixed fallback chain (requested language, {@code arknet.locale.requested} -> system
     * default, {@code arknet.locale.default} -> untagged literal -> a deterministic last resort) so
     * a term is never swallowed for lacking the requested language. Both properties default to
     * English; a project with a configured {@code defaultLanguage} now has {@code
     * term_add}/{@code term_update} write under that tag rather than untagged (issue #258), so the
     * untagged step here only surfaces a term for a project without one, or an older term written
     * before it had one.
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
        return new UbiquitousLanguageMcpTools(service, service, service, service, service, service, projectResolver);
    }

    // --- Use-cases hexagon -----------------------------------------------------

    /**
     * The use-case out-adapter, assembled over the <em>shared</em> {@link DatasetLifecycle}
     * bean. This adapter no longer performs any cross-BC lookup itself - it
     * persists the already-resolved {@link de.hauschel.arknet.uc.domain.RoleRef}/
     * {@link de.hauschel.arknet.uc.domain.RequirementRef} identities {@link UseCaseService}
     * hands it (resolved via {@link #useCaseRequirementLookup}/{@link #useCaseRoleLookup}
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
     * Resolves a role's human-typed business code (e.g. {@code ROLE-4}) to its opaque subject
     * identity - the strict cross-BC lookup {@code uc_add}'s {@code primaryRole}/
     * {@code supportingRoles} references need (ADR-37/kogn-io/arknet#405 Part C). Acquires
     * datasets from the same shared {@link DatasetLifecycle} as {@link #roleRepository}, so it
     * reads the same project the actor hexagon's role resource type writes into.
     */
    @Bean
    RoleLookup useCaseRoleLookup(final DatasetLifecycle datasetLifecycle) {
        return new KognioRdfRoleLookup(datasetLifecycle);
    }

    /**
     * Resolves a glossary term's human-typed business code (e.g. {@code TERM-1}) to its opaque
     * subject identity - the strict cross-BC lookup {@code uc_link_term} needs (issue #329).
     * Own implementation in the use-cases adapter module (Borrowed In-Port), not the requirements
     * hexagon's identically-named {@code KognioRdfTermLookup} bean ({@link #requirementTermLookup}).
     * Acquires datasets from the same shared {@link DatasetLifecycle} as {@link #termRepository},
     * so it reads the same project the ubiquitous-language hexagon writes into.
     */
    @Bean
    de.hauschel.arknet.uc.application.port.out.TermLookup useCaseTermLookup(
            final DatasetLifecycle datasetLifecycle) {
        return new de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfTermLookup(datasetLifecycle);
    }

    /**
     * Resolves a constraint's human-typed business code (e.g. {@code TCON-1}) to its opaque
     * subject identity - the strict cross-BC lookup {@code uc_link_constraint} needs
     * (issue #329). Acquires datasets from the same shared {@link DatasetLifecycle} as
     * {@link #constraintRepository}, so it reads the same project the requirements hexagon's
     * constraint resource type writes into.
     */
    @Bean
    de.hauschel.arknet.uc.application.port.out.ConstraintLookup useCaseConstraintLookup(
            final DatasetLifecycle datasetLifecycle) {
        return new de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfConstraintLookup(datasetLifecycle);
    }

    @Bean
    UseCaseService useCaseService(
            final UseCaseRepository repository, final ResourceIdFactory resourceIdFactory,
            final RequirementLookup useCaseRequirementLookup, final RoleLookup useCaseRoleLookup,
            final de.hauschel.arknet.uc.application.port.out.TermLookup useCaseTermLookup,
            final de.hauschel.arknet.uc.application.port.out.ConstraintLookup useCaseConstraintLookup) {
        return new UseCaseService(repository, resourceIdFactory, useCaseRequirementLookup, useCaseRoleLookup,
                useCaseTermLookup, useCaseConstraintLookup);
    }

    /**
     * {@code resolveRoles}/{@code resolveTerms}/{@code resolveRequirements}/{@code
     * resolveConstraints} are the actor (role resource type), ubiquitous-language and
     * requirements hexagons' own driving ports (implemented by their {@code RoleService}/
     * {@code TermService}/{@code RequirementService}/{@code ConstraintService} beans) - borrowed
     * here purely so {@code uc_get}/{@code uc_list} can render a referenced role's business code
     * (ADR-37/kogn-io/arknet#405 Part C; the role register replaced the old actor register as the
     * resolution source) / linked term's / requirement's business code / linked constraint's
     * business code instead of a bare IRI. This wires an In-Adapter to <em>different</em>
     * hexagons' In-Ports, not to those hexagons' cores - see the "kein *-core* haengt an einem
     * anderen BC" precision in CLAUDE.md.
     */
    @Bean
    UseCaseMcpTools useCaseMcpTools(
            final UseCaseService service, final ResolveRoles resolveRoles, final ResolveTerms resolveTerms,
            final ResolveRequirements resolveRequirements, final ConstraintService constraintService,
            final ProjectResolver projectResolver) {
        return new UseCaseMcpTools(service, service, service, service, service, service, service, resolveRoles,
                resolveTerms, resolveRequirements, constraintService, projectResolver);
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
     * {@code bc_list} can render a linked term's business code instead of its bare IRI.
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
    AdrRepository adrRepository(final DatasetLifecycle datasetLifecycle, final ResourceIdFactory resourceIdFactory,
            final DisplayLocale displayLocale) {
        return KognioRdfAdrRepositoryFactory.over(datasetLifecycle, resourceIdFactory, displayLocale);
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

    /**
     * Resolves a glossary term's human-typed business code (e.g. {@code TERM-1}) to its opaque
     * subject identity - the strict cross-BC lookup {@code adr_add}'s {@code usesTerms} references
     * need (kogn-io/arknet#393). A third, ADR-owned bean rather than a shared one with the
     * requirements/use-cases/bounded-context hexagons' identically-shaped lookups: each hexagon
     * declares the capability it needs on its own out-port. Acquires datasets from the same shared
     * {@link DatasetLifecycle} as {@link #termRepository}, so it reads the same project the
     * ubiquitous-language hexagon writes into.
     */
    @Bean
    de.hauschel.arknet.adr.application.port.out.TermLookup adrTermLookup(final DatasetLifecycle datasetLifecycle) {
        return new de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfTermLookup(datasetLifecycle);
    }

    /**
     * The clock supplies the day {@code adr_set_status} stamps onto a decision it accepts or rejects
     * (kogn-io/arknet#374). System default zone rather than UTC: the date recorded is the one the
     * person making the decision would write down, and this is a local single-user client,
     * not a service serving callers in other zones.
     */
    @Bean
    AdrService adrService(
            final AdrRepository repository, final ResourceIdFactory resourceIdFactory,
            final de.hauschel.arknet.adr.application.port.out.RequirementLookup adrRequirementLookup,
            final BoundedContextLookup adrBoundedContextLookup,
            final de.hauschel.arknet.adr.application.port.out.TermLookup adrTermLookup) {
        return new AdrService(repository, resourceIdFactory, adrRequirementLookup, adrBoundedContextLookup,
                adrTermLookup, Clock.systemDefaultZone());
    }

    /**
     * {@code resolveRequirements}/{@code resolveBoundedContexts}/{@code resolveTerms} are the
     * requirements, bounded-context and ubiquitous-language hexagons' own driving ports (implemented
     * by their {@code RequirementService}/{@code BoundedContextService}/{@code TermService} beans) -
     * borrowed here purely so {@code adr_get}/{@code adr_list} can render an addressed requirement's,
     * an affected context's or a used term's business code instead of a bare IRI
     * (kogn-io/arknet#393). This wires an In-Adapter to three <em>different</em> hexagons' In-Ports,
     * not to those hexagons' cores - see the "kein *-core* haengt an einem anderen BC" precision in
     * CLAUDE.md. The fourth relation, {@code supersededBy}, points back into the ADR hexagon itself
     * and is resolved by {@link AdrService}, so it needs no borrowed port at all.
     */
    @Bean
    AdrMcpTools adrMcpTools(
            final AdrService service, final ResolveRequirements resolveRequirements,
            final ResolveBoundedContexts resolveBoundedContexts, final ResolveTerms resolveTerms,
            final ProjectResolver projectResolver) {
        return new AdrMcpTools(service, service, service, service, service, service, service, service,
                service, service, service, service, resolveRequirements, resolveBoundedContexts, resolveTerms,
                projectResolver);
    }

    /**
     * The corpus-wide, non-blocking consistency and quality check behind {@code adr_check}
     * (kogn-io/arknet#387). Reads the decisions through the ADR hexagon's own {@code ListAdrs}
     * in-port - the very {@link AdrService} bean above - rather than through a repository of its
     * own: it needs both supersession directions and the merged {@code relatedTo} view that
     * {@code AdrDetail} already carries, and a second read path would have to rebuild them.
     * Deliberately no borrowed port here at all: this check reads no neighbour hexagon's resources,
     * only the ADR corpus and the codes it names in its own prose.
     */
    @Bean
    AdrCheckService adrCheckService(final AdrService service) {
        return new AdrCheckService(service);
    }

    /**
     * The one reading ADR tool ({@code adr_check}), in a tools class of its own beside
     * {@link #adrMcpTools} because it writes nothing and renders a report rather than a record.
     * Takes the same {@link AdrService} bean a second time as its {@code CountSkippedAdrs} in-port,
     * so a check over a project with unreadable store-first records says so instead of reporting a
     * clean corpus (kogn-io/arknet#359).
     */
    @Bean
    AdrCheckMcpTools adrCheckMcpTools(
            final AdrCheckService adrCheckService, final AdrService service,
            final ProjectResolver projectResolver) {
        return new AdrCheckMcpTools(adrCheckService, service, projectResolver);
    }

    // --- Actor hexagon -----------------------------------------------------------

    /**
     * The shared {@link WriteFunnel} every write path of the actor hexagon runs through - both
     * {@link #actorRepository} and {@link #roleRepository} (ADR-37/kogn-io/arknet#405 Part B): a
     * {@code Role} shares {@code actor-shapes.ttl}/{@code arknet-actor.ttl} with {@code Actor}, so
     * this bean is built once and handed to both repositories rather than each building its own,
     * functionally identical one - mirrors {@link #requirementsWriteFunnel} exactly. Its gate
     * reasons over its axioms - {@code actshapes:ActorShape} targets the abstract
     * {@code arkproc:Actor} while an instance is typed as one of the four concrete subclasses.
     */
    @Bean
    WriteFunnel actorWriteFunnel(final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale) {
        return KognioRdfActorRepositoryFactory.buildFunnel(datasetLifecycle, displayLocale);
    }

    /**
     * Persists {@code arkproc:Actor} resources of their own - not the actor facet the
     * ubiquitous-language hexagon sets on a {@code skos:Concept}, which is untouched by this
     * hexagon and keeps running as before. Acquires datasets from the same shared
     * {@link DatasetLifecycle} as every other model hexagon, so an actor lands in the same project
     * dataset as the requirements and use cases that will eventually refer to it.
     */
    @Bean
    ActorRepository actorRepository(final DatasetLifecycle datasetLifecycle, final WriteFunnel actorWriteFunnel) {
        return KognioRdfActorRepositoryFactory.over(datasetLifecycle, actorWriteFunnel);
    }

    @Bean
    ActorService actorService(final ActorRepository repository, final ResourceIdFactory resourceIdFactory) {
        return new ActorService(repository, resourceIdFactory);
    }

    /**
     * No {@code ResolveTerms}/{@code ResolveRequirements}-style borrowed port here, unlike
     * {@link #boundedContextMcpTools} or {@link #adrMcpTools}: an {@link ActorService} result
     * carries no opaque identity of a neighbour hexagon's resource, so there is nothing to render
     * as a business code and no Borrowed In-Port to justify.
     */
    @Bean
    ActorMcpTools actorMcpTools(final ActorService service, final ProjectResolver projectResolver) {
        return new ActorMcpTools(service, service, service, service, service, projectResolver);
    }

    /**
     * Persists {@code arkproc:Role} resources - the second resource type of this hexagon
     * (ADR-37/kogn-io/arknet#405 Part B), sharing {@link #actorWriteFunnel} with
     * {@link #actorRepository} rather than getting a SHACL write-gate/write-funnel pair of its own
     * (see {@code KognioRdfRoleRepositoryFactory}'s javadoc).
     */
    @Bean
    RoleRepository roleRepository(final DatasetLifecycle datasetLifecycle, final DisplayLocale displayLocale,
            final WriteFunnel actorWriteFunnel) {
        return KognioRdfRoleRepositoryFactory.over(datasetLifecycle, displayLocale, actorWriteFunnel);
    }

    /**
     * {@code RoleService} depends on {@link ActorRepository} directly, not on a
     * {@code ActorLookup}-style driven port: {@code Role} and {@code Actor} are two resource types
     * of one Bounded Context, not two contexts, so ADR-008's gateway rule does not bind here (see
     * {@code RoleService}'s own class-level javadoc).
     */
    @Bean
    RoleService roleService(final RoleRepository repository, final ActorRepository actorRepository,
            final ResourceIdFactory resourceIdFactory) {
        return new RoleService(repository, actorRepository, resourceIdFactory);
    }

    /** No borrowed neighbour port here either - mirrors {@link #actorMcpTools}'s own note. */
    @Bean
    RoleMcpTools roleMcpTools(final RoleService service, final ProjectResolver projectResolver) {
        return new RoleMcpTools(service, service, service, service, service, service, projectResolver);
    }

    // --- Project hexagon (the registry) -------------------------------

    /**
     * The project registry: which opaque, client-sent anchor belongs to which project.
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
     * Writes each project's self-description into that project's <em>own</em> dataset,
     * so the registry stays a rebuildable index rather than a single point of failure
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
     * because a dataset written before the registered-anchor model was never in it. Backs {@code project_adopt} and the
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
     * The project tools ({@code project_add}, {@code project_attach_anchor},
     * {@code project_rename}, {@code project_update}, {@code project_list}, {@code project_adopt}).
     *
     * <p>Note what is <em>not</em> injected: no {@link ProjectResolver}. Every other
     * {@code *McpTools} bean gets one to turn a call's anchor into a {@link ProjectId}
     * by looking it up in the registry; this adapter also treats the anchor as opaque and
     * looks it up, same as every other anchor-aware adapter. Wiring it without a separate
     * resolver is not an omission but intentional - this component answers the routing question
     * and therefore cannot itself sit behind a routing answer.</p>
     *
     * <p>This bean implements the anchor registry resolver: every
     * tool call routes through anchor lookup instead of directory derivation. The old
     * workspace-based path has been removed; registry lookup is now the sole routing
     * mechanism for all project-scoped tool calls.</p>
     */
    @Bean
    ProjectMcpTools projectMcpTools(final ProjectService service) {
        return new ProjectMcpTools(service, service, service, service, service, service, service, service);
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
     * Assembles the HTML report's per-bounded-context sections by borrowing all six hexagons'
     * read In-Ports - the same In-Adapter-as-gateway role the Borrowed In-Port pattern grants {@code uc_get} when it
     * borrows {@link ResolveTerms}, here for the report rather than for a tool response. A use
     * case reconstructed from raw triples is not readable as a use case (its flow is a set of
     * opaque {@code arkreq:Step} subjects ordered by an {@code arkreq:position} literal), so the
     * report asks the context that wrote it instead of re-deriving the answer here. The generic
     * snapshot still backs every card's raw triples and catches whatever no context claims -
     * see {@code arknet-mcp/CLAUDE.md} for the reasoning behind the per-bounded-context report.
     *
     * <p>The glossary arrives as {@code ListTerms} rather than {@link ResolveTerms}: besides
     * labelling references, the report marks the ubiquitous language up inside the other
     * contexts' prose, and telling a linked mention from an unlinked one needs every term, not
     * just the ones an edge already points at.</p>
     *
     * <p>{@link ActorCards} (issue #336) is the sixth: {@code actorService} was already wired for
     * {@link #actorMcpTools}, and {@link ActorService} implements {@code ListActors} too, so no
     * new bean is needed to read it a second time for the report.</p>
     *
     * <p>{@link ConstraintCards} (issue #390) reuses {@code constraintService} the same way:
     * {@link ConstraintService} already implements {@code ListConstraints} for
     * {@link #constraintMcpTools}, so reading it a second time here needs no new bean either.</p>
     *
     * <p>{@link RoleCards} (ADR-37/kogn-io/arknet#405) mirrors {@link ActorCards} exactly:
     * {@code roleService} was already wired for {@link #roleMcpTools}, and {@link RoleService}
     * implements {@code ListRoles} too, so no new bean is needed to read it a second time here.
     * {@link UseCaseCards} (ADR-37/kogn-io/arknet#405 Part C) borrows the very same
     * {@code roleService} bean a third time, as its {@code ResolveRoles} in-port, to resolve a
     * use case's {@code primaryRole}/{@code supportingRole} to a display name and business code
     * instead of the bare-IRI defect its former {@code Glossary}-based resolution carried.</p>
     */
    @Bean
    ModelViews modelViews(
            final UseCaseService useCases, final RequirementService requirements,
            final ConstraintService constraintService, final TermService terms,
            final BoundedContextService boundedContexts, final AdrService adrs, final ActorService actorService,
            final RoleService roleService, final ResolveRequirements resolveRequirements) {
        return new ModelViews(
                terms,
                new UseCaseCards(useCases, resolveRequirements, roleService),
                new RequirementCards(requirements),
                new ConstraintCards(constraintService),
                new BoundedContextCards(boundedContexts),
                new AdrCards(adrs, resolveRequirements, boundedContexts, adrs),
                new ActorCards(actorService),
                new RoleCards(roleService));
    }

    /**
     * The three read-only store tools ({@code store_overview}, {@code resource_get},
     * {@code resource_history}). {@code store_overview}/{@code resource_get} read the project
     * dataset through {@link StoreReader} - a single generic {@code SELECT ?s ?p ?o} - so no
     * bounded context needs a read tool of its own; {@code resource_history} (issue #251) reads
     * the same {@link StoreReader}, but via its own, deliberately separate query over the
     * provenance graph. The agent's return value stays that domain-agnostic digest; the
     * human-facing HTML additionally groups the model per bounded context through
     * {@link ModelViews}, with the generic snapshot as its safety net. The HTML report is
     * written into {@code arknet.report.dir} (default: the launched project root / working
     * directory). {@code arknet.report.host-dir} is the host-reachable equivalent of that
     * directory when it is a container-internal mount point the calling agent cannot reach
     * directly; unset on the non-containerized path, where {@code fallbackReportDir} is already
     * host-reachable.
     *
     * <p>{@code projectService} is passed as {@link de.hauschel.arknet.prj.application.port.in.FindProject},
     * not {@link ProjectRegistry} directly: the digest and HTML headers name the resolved
     * project's registered label instead of its raw id by borrowing the project
     * hexagon's driving port, the same gateway role the Borrowed In-Port pattern grants any other
     * in-adapter of a neighbour bounded context.</p>
     */
    @Bean
    StoreReportTools storeReportTools(
            final StoreReader storeReader, final Prefixes prefixes, final DisplayLocale displayLocale,
            final ModelViews modelViews, final ProjectResolver projectResolver, final ProjectService projectService,
            @Value("${arknet.report.dir:${user.dir}}") final Path fallbackReportDir,
            @Value("${arknet.report.host-dir:#{null}}") final Path reportHostDir) {
        return new StoreReportTools(
                storeReader, prefixes, displayLocale, new HtmlReportRenderer(prefixes), modelViews,
                projectResolver, projectService, fallbackReportDir, reportHostDir);
    }

    /**
     * A second, browser-reachable way to fetch the very same self-contained HTML
     * {@link #storeReportTools} renders: {@code GET /report?projectAnchor=...} returns it
     * directly as the HTTP response, instead of the file path a human would otherwise have to
     * open by hand (issue #391). Purely additive - {@code store_overview} keeps writing the file
     * exactly as before; this bean only registers a second, read-only consumer of the same
     * rendering, guarded by the same loopback allowlist as the MCP transport
     * ({@link LoopbackHostSecurity}).
     */
    @Bean
    StoreReportController storeReportController(final StoreReportTools storeReportTools) {
        return new StoreReportController(storeReportTools);
    }

    /**
     * The backup read path: a complete {@code SELECT ?g ?s ?p ?o} spanning every named graph of a
     * project's dataset, unlike {@link #storeReader} it hides neither the provenance nor the
     * project-identity graph, because a backup exists to restore both.
     */
    @Bean
    StoreExporter storeExporter(final DatasetLifecycle datasetLifecycle, final ExportMetadata exportMetadata) {
        return new StoreExporter(datasetLifecycle, exportMetadata);
    }

    /**
     * The build this daemon runs, as one string three places state (issue #194): the MCP
     * {@code initialize} handshake, every backup dump's envelope, and the suffix on every failing
     * tool call.
     *
     * <p>Read from {@code arknet.version}, the very property {@code application.properties}
     * feeds into {@code spring.ai.mcp.server.version}, rather than derived here a second time -
     * an error suffix that could disagree with the version the client was handed at connect time
     * would be worse than no suffix at all. The build time comes from Spring Boot's
     * {@link BuildProperties}, present only when {@code build-info.properties} was produced by the
     * Maven build; without it the version degrades to {@link ServerVersion#UNKNOWN} and no build
     * time is claimed.</p>
     *
     * <p>A blank value is treated as no value at all rather than passed on: Spring reads a
     * present-but-empty {@code ARKNET_VERSION} env var as a real value, and a daemon that refused
     * to start over an empty version string would fail far more expensively than it would inform.</p>
     */
    @Bean
    ServerVersion serverVersion(
            @Value("${arknet.version:" + ServerVersion.UNKNOWN + "}") final String version,
            final ObjectProvider<BuildProperties> buildProperties) {
        return new ServerVersion(version.isBlank() ? ServerVersion.UNKNOWN : version,
                Optional.ofNullable(buildProperties.getIfAvailable()).map(BuildProperties::getTime));
    }

    /**
     * The metadata graph appended to every {@code project_export} dump. Reads the shipped ontology
     * modules' {@code owl:versionInfo} once, at wiring time: they are classpath resources of a
     * running server and cannot change under it.
     */
    @Bean
    ExportMetadata exportMetadata(final ServerVersion serverVersion) {
        return new ExportMetadata(serverVersion, OntologyVersions.onClasspath(), Clock.systemUTC());
    }

    /**
     * Stamps the server version onto every failing tool call, across all seven hexagons at once
     * (issue #194). Declared {@code static} because a {@link org.springframework.beans.factory.config.BeanPostProcessor}
     * is instantiated before ordinary beans; {@link ServerVersion} is therefore taken as a
     * provider and resolved on the first failure rather than during wiring.
     */
    @Bean
    static ToolErrorVersionStamp toolErrorVersionStamp(final ObjectProvider<ServerVersion> serverVersion) {
        return new ToolErrorVersionStamp(() -> serverVersion.getIfAvailable(ServerVersion::unknown));
    }

    /**
     * The one backup tool ({@code project_export}). Project-scoped only on request: by default it
     * exports every project {@link ProjectService} (as its {@code ListProjects} in-port) reports as
     * registered, in one call, and narrows to this call's own project - resolved through
     * {@code projectResolver} like every other tool's - when the caller passes
     * {@code projectOnly=true}. {@code arknet.export.dir}/{@code arknet.export.host-dir} mirror
     * {@code arknet.report.dir}/{@code arknet.report.host-dir} for the same reason: on a
     * containerized daemon the export directory is a container-internal mount point the calling
     * agent cannot reach directly.
     */
    @Bean
    StoreExportTools storeExportTools(
            final ProjectService projectService, final ProjectResolver projectResolver,
            final StoreExporter storeExporter,
            @Value("${arknet.export.dir:${user.home}/.arknet/export}") final Path fallbackExportDir,
            @Value("${arknet.export.host-dir:#{null}}") final Path exportHostDir) {
        return new StoreExportTools(
                projectService, projectService, projectResolver, storeExporter, fallbackExportDir,
                exportHostDir);
    }

    /**
     * The five traceability reporting tools ({@code trace_matrix}, {@code orphan_check},
     * {@code impact_analysis}, {@code role_usecase_matrix}, {@code term_cooccurrence}). Reuses
     * the very same {@link #storeReader}/{@link #storeReportPrefixes} beans as
     * {@link #storeReportTools} instead of building a second {@link StoreReader} - one generic
     * read path, two presentations over it (a full-snapshot digest vs. a graph traversal).
     */
    /**
     * The one checking tool ({@code store_check}, kogn-io/arknet#412). Wired here rather than into
     * any hexagon for the same reason {@link #storeReportTools} and {@link #traceabilityMcpTools}
     * are: it reads whatever the seven bounded contexts wrote, through the very same
     * {@link #storeReader}/{@link #storeReportPrefixes} beans, and has no domain of its own. It
     * takes no {@link DisplayLocale}: a language check that resolved each field to one display
     * language would only ever see the language it resolved to, which is precisely the language it
     * must not assume.
     */
    @Bean
    StoreCheckMcpTools storeCheckMcpTools(
            final StoreReader storeReader, final Prefixes prefixes, final ProjectResolver projectResolver) {
        return new StoreCheckMcpTools(storeReader, prefixes, projectResolver);
    }

    @Bean
    TraceabilityMcpTools traceabilityMcpTools(
            final StoreReader storeReader, final Prefixes prefixes, final ProjectResolver projectResolver,
            final DisplayLocale displayLocale) {
        return new TraceabilityMcpTools(storeReader, prefixes, projectResolver, displayLocale);
    }
}
