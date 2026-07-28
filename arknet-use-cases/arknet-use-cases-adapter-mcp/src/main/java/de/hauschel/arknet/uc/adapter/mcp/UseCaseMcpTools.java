// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;

/**
 * Driving (in) adapter of the use-cases component: exposes the use-case use-cases as MCP
 * tools ({@code uc_add}, {@code uc_list}, {@code uc_get}) and delegates each tool call to
 * the corresponding in-port.
 *
 * <p>This adapter belongs to the use-cases hexagon (symmetric to the out-adapter
 * {@code arknet-use-cases-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods - the tool name, description and JSON
 * input schema are derived from the annotations and method signature, not hand-written. This
 * adapter does <strong>not</strong> bootstrap an MCP server or wire any transport; that
 * remains the concern of the composition root (arknet-mcp), which declares this class as a
 * bean so the Spring AI MCP annotation scanner discovers the {@code @McpTool} methods
 * automatically.</p>
 *
 * <p><strong>Coarse-grained write.</strong> {@code uc_add} takes the complete use case -
 * including its ordered step list and its label references to requirements and actors - in a
 * single call (issue #41). The nested {@link StepInput} shape mirrors the domain
 * {@link Step}; requirement/actor references are passed as bare labels (e.g. {@code FR-1},
 * {@code Customer}) straight into {@link NewUseCase}/{@link NewStep} - resolving them to opaque
 * identities is the application service's job (issue #89), not this adapter's.</p>
 *
 * <p><strong>Error hand-off.</strong> This adapter deliberately does not catch domain or
 * adapter exceptions. Spring AI maps any thrown exception to an error {@code CallToolResult}
 * carrying its message, so the didactic message of a failed reference resolution (e.g.
 * "Requirement 'FR-1' does not exist ... create it first with req_add") reaches the agent as
 * a tool error rather than a raw stack trace. Keeping the tool method thin preserves that
 * message verbatim.</p>
 *
 * <p><strong>Workspace (resolved per call).</strong> Every in-port takes a
 * {@link ProjectId} routing key. arknet-mcp runs as one shared server for every
 * workspace on the machine (issue #137), so there is no single injected workspace any
 * more: each tool call resolves its own workspace from the request's origin directory,
 * carried in the MCP transport context under {@link ProjectResolver#WORKSPACE_DIR_KEY}.
 * The framework hands this adapter that context as an {@link McpSyncRequestContext}
 * parameter - a framework type, excluded from the generated tool input schema, so it is
 * not a caller-facing argument. The concrete resolution (git top-level, slugging,
 * explicit-id override) stays behind {@link ProjectResolver} in the composition root.</p>
 *
 * <p><strong>Actor/requirement display resolution (issue #89).</strong> {@link ActorRef} and
 * {@link RequirementRef} carry an opaque subject identity, not a business label - but a human
 * who typed {@code Customer}/{@code FR-1} into {@code uc_add} expects to see those again, not a
 * raw IRI they cannot re-type. This adapter is the gate into the use-cases hexagon, not part of
 * its core, so it may borrow driving ports of <em>different</em> hexagons
 * ({@link ResolveTerms}, owned by ubiquitous-language, and {@link ResolveRequirements}, owned by
 * requirements) to answer that purely for display - the use-cases core itself still never
 * depends on {@code arknet-ubiquitous-language-core}/{@code arknet-requirements-core}, and
 * {@code uc_add}'s own write path still resolves via the decoupled {@code ActorLookup}/
 * {@code RequirementLookup} out-ports (ADR-008). {@link #formatFull} calls
 * {@link ResolveTerms#getById}/{@link ResolveRequirements#getById} exactly once each per
 * rendering, batched across every {@link ActorRef}/{@link RequirementRef} involved; an id either
 * port could not resolve simply falls back to the bare IRI - rendering never throws and never
 * drops a reference.</p>
 */
public final class UseCaseMcpTools {

    private final AddUseCase addUseCase;
    private final ListUseCases listUseCases;
    private final GetUseCase getUseCase;
    private final ResolveTerms resolveTerms;
    private final ResolveRequirements resolveRequirements;
    private final ProjectResolver workspaces;

    /**
     * Creates the adapter with its three driving in-ports, the two borrowed sibling-hexagon
     * display ports and the resolver that maps each call's origin directory to a workspace.
     *
     * @param addUseCase          in-port backing {@code uc_add}
     * @param listUseCases        in-port backing {@code uc_list}
     * @param getUseCase          in-port backing {@code uc_get}
     * @param resolveTerms        ubiquitous-language driving port used only to render a
     *                            referenced actor's business name instead of its bare IRI
     * @param resolveRequirements requirements driving port used only to render a referenced
     *                            requirement's business code instead of its bare IRI
     * @param workspaces          resolves each call's target workspace from its origin directory
     */
    public UseCaseMcpTools(
            final AddUseCase addUseCase,
            final ListUseCases listUseCases,
            final GetUseCase getUseCase,
            final ResolveTerms resolveTerms,
            final ResolveRequirements resolveRequirements,
            final ProjectResolver workspaces) {
        this.addUseCase = Objects.requireNonNull(addUseCase, "addUseCase");
        this.listUseCases = Objects.requireNonNull(listUseCases, "listUseCases");
        this.getUseCase = Objects.requireNonNull(getUseCase, "getUseCase");
        this.resolveTerms = Objects.requireNonNull(resolveTerms, "resolveTerms");
        this.resolveRequirements = Objects.requireNonNull(resolveRequirements, "resolveRequirements");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    /**
     * Extracts the calling client's origin directory from the per-call transport context -
     * the value the server's context extractor placed there off the request header (issue
     * #137). Null-tolerant on every hop: a call without a context, without a transport
     * context, or without the key resolves to {@code null}, which {@link ProjectResolver}
     * turns into the server's default workspace.
     */
    private static String originDir(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object dir = transport == null ? null : transport.get(ProjectResolver.WORKSPACE_DIR_KEY);
        return dir == null ? null : dir.toString();
    }

    /**
     * One step of a use case's main flow, as passed by the agent.
     *
     * @param position 1-based position in the flow; the flow must be numbered {@code 1..n}
     *                 with no gaps and no duplicates
     * @param text     what happens in this step (an actor or system action)
     * @param realises labels of the functional requirements this step fulfils (e.g.
     *                 {@code FR-1}); may be empty or omitted
     */
    public record StepInput(int position, String text, List<String> realises) {
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "uc_add",
            description = "Register a complete use case (Cockburn-style, goal + ordered main flow) in a "
                    + "single call. Requirement and actor references are given as bare labels that must "
                    + "already exist in this workspace (create requirements with req_add, actors with "
                    + "term_add using actorKind first).")
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Short human-readable name of the use case, e.g. 'Place order'")
            final String title,
            @McpToolParam(description = "The goal the primary actor wants to achieve (goal-in-context)")
            final String goal,
            @McpToolParam(description = "Optional: the system/design scope under consideration", required = false)
            final String scope,
            @McpToolParam(description = "Optional: the event that triggers the use case", required = false)
            final String trigger,
            @McpToolParam(description = "Label of the primary actor whose goal this use case serves, e.g. "
                    + "'Customer'. Must be an existing actor term (term_add with actorKind).")
            final String primaryActor,
            @McpToolParam(description = "Optional: labels of supporting (secondary) actors; each must be an "
                    + "existing actor term", required = false)
            final List<String> supportingActors,
            @McpToolParam(description = "Optional: state that must hold before the use case runs",
                    required = false)
            final String precondition,
            @McpToolParam(description = "Optional: guaranteed state after a successful run", required = false)
            final String postcondition,
            @McpToolParam(description = "The ordered main flow. A JSON array of steps, each "
                    + "{position: 1-based int (gap-free, ascending, starting at 1), text: string, "
                    + "realises: array of requirement labels like 'FR-1' this step fulfils (optional)}. "
                    + "At least one step is required.")
            final List<StepInput> steps,
            @McpToolParam(description = "Optional: alternative/exception flows as free-text lines, e.g. "
                    + "'2a. Payment declined -> use case ends in failure'", required = false)
            final List<String> extensions) {
        final ProjectId projectId = workspaces.resolve(originDir(context));
        final NewUseCase command = new NewUseCase(
                title,
                goal,
                blankToNull(scope),
                blankToNull(trigger),
                primaryActor,
                supportingActors == null ? List.of() : List.copyOf(supportingActors),
                blankToNull(precondition),
                blankToNull(postcondition),
                toNewSteps(steps),
                extensions == null ? List.of() : List.copyOf(extensions));
        final UseCase created = addUseCase.add(projectId, command);
        return formatFull(projectId, created);
    }

    @McpTool(name = "uc_list", description = "List all use cases in this workspace (id, title, goal).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(final McpSyncRequestContext context) {
        final ProjectId projectId = workspaces.resolve(originDir(context));
        final List<UseCase> all = listUseCases.list(projectId);
        return all.stream().map(UseCaseMcpTools::formatShort)
                .reduce((a, b) -> a + "\n" + b).orElse("(no use cases)");
    }

    @McpTool(name = "uc_get",
            description = "Fetch a single use case by its code (e.g. UC1), with all fields, its ordered "
                    + "steps and their fulfilled requirement labels, and its extensions.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Use-case code, e.g. UC1") final String id) {
        final ProjectId projectId = workspaces.resolve(originDir(context));
        final UseCaseCode code = new UseCaseCode(id);
        return getUseCase.get(projectId, code)
                .map(uc -> formatFull(projectId, uc))
                .orElse("Use case not found: " + code.value());
    }

    // --- mapping helpers -------------------------------------------------------

    private static List<NewStep> toNewSteps(final List<StepInput> steps) {
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
                .map(s -> new NewStep(s.position(), s.text(),
                        s.realises() == null ? List.of() : List.copyOf(s.realises())))
                .toList();
    }

    private static String formatShort(final UseCase uc) {
        return "%s | %s | %s".formatted(uc.code().value(), uc.title(), uc.goal());
    }

    private String formatFull(final ProjectId projectId, final UseCase uc) {
        final Map<ResourceId, ResolvedTerm> actorsById = resolveActorsFor(projectId, uc);
        final Map<ResourceId, ResolvedRequirement> requirementsById = resolveRequirementsFor(projectId, uc);

        final StringBuilder sb = new StringBuilder();
        sb.append(uc.code().value()).append(' ').append(uc.title()).append('\n');
        sb.append("  goal: ").append(uc.goal()).append('\n');
        appendOptional(sb, "scope", uc.scope());
        appendOptional(sb, "trigger", uc.trigger());
        sb.append("  primaryActor: ").append(renderActor(uc.primaryActor(), actorsById)).append('\n');
        if (!uc.supportingActors().isEmpty()) {
            sb.append("  supportingActors: ")
                    .append(uc.supportingActors().stream().map(ref -> renderActor(ref, actorsById))
                            .reduce((a, b) -> a + ", " + b).orElse(""))
                    .append('\n');
        }
        appendOptional(sb, "precondition", uc.precondition());
        appendOptional(sb, "postcondition", uc.postcondition());
        sb.append("  steps:").append('\n');
        for (final Step step : uc.steps()) {
            sb.append("    ").append(step.position()).append(". ").append(step.text());
            if (!step.realises().isEmpty()) {
                sb.append(" -> realises ")
                        .append(step.realises().stream().map(ref -> renderRequirement(ref, requirementsById))
                                .reduce((a, b) -> a + ", " + b).orElse(""));
            }
            sb.append('\n');
        }
        if (!uc.extensions().isEmpty()) {
            sb.append("  extensions:").append('\n');
            for (final String extension : uc.extensions()) {
                sb.append("    - ").append(extension).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /** Renders one actor reference: its resolved business name, or its bare IRI as a fallback. */
    private static String renderActor(final ActorRef ref, final Map<ResourceId, ResolvedTerm> actorsById) {
        final ResolvedTerm term = actorsById.get(ref.value());
        return term != null ? term.code().value() : ref.value().value();
    }

    /**
     * Renders one requirement reference: its resolved business code, or its bare IRI as a
     * fallback.
     */
    private static String renderRequirement(
            final RequirementRef ref, final Map<ResourceId, ResolvedRequirement> requirementsById) {
        final ResolvedRequirement requirement = requirementsById.get(ref.value());
        return requirement != null ? requirement.code().value() : ref.value().value();
    }

    /**
     * Batch-resolves every actor referenced by {@code uc} (its primary actor plus its supporting
     * actors) in exactly one call to {@link ResolveTerms#getById}.
     *
     * <p><strong>Structurally cannot throw on a duplicate key (mirrors
     * {@code RequirementMcpTools#resolveTermsFor}).</strong> {@link ResolveTerms} promises at
     * most one {@link ResolvedTerm} per identity, but this method must not rely on every
     * implementation upholding that: a plain {@code Collectors.toMap(t -> t.id(), t -> t)} throws
     * {@code IllegalStateException} the moment two returned {@link ResolvedTerm}s share an
     * identity, turning a display concern into a thrown exception - the very thing this rendering
     * path exists to avoid. The merge function below keeps the first entry for a duplicate key
     * instead; which one is kept is immaterial here, since rendering only ever reads
     * {@link ResolvedTerm#code()}.</p>
     */
    private Map<ResourceId, ResolvedTerm> resolveActorsFor(final ProjectId projectId, final UseCase uc) {
        final ResourceId[] ids = Stream.concat(
                        Stream.of(uc.primaryActor()), uc.supportingActors().stream())
                .map(ActorRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveTerms.getById(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedTerm::id, t -> t, (first, second) -> first));
    }

    /**
     * Batch-resolves every requirement referenced by {@code uc}'s steps (the union of all
     * {@code realises} references) in exactly one call to {@link ResolveRequirements#getById} -
     * same merge-function reasoning as {@link #resolveActorsFor}.
     */
    private Map<ResourceId, ResolvedRequirement> resolveRequirementsFor(
            final ProjectId projectId, final UseCase uc) {
        final ResourceId[] ids = uc.steps().stream()
                .flatMap(step -> step.realises().stream())
                .map(RequirementRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveRequirements.getById(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedRequirement::id, r -> r, (first, second) -> first));
    }

    private static void appendOptional(final StringBuilder sb, final String field, final String value) {
        if (value != null) {
            sb.append("  ").append(field).append(": ").append(value).append('\n');
        }
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
