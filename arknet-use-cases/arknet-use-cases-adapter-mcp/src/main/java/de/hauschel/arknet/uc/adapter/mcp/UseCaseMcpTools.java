package de.hauschel.arknet.uc.adapter.mcp;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;

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
 * {@code Customer}). Whether those labels resolve against the shared workspace store is
 * enforced by the out-adapter, not here.</p>
 *
 * <p><strong>Error hand-off.</strong> This adapter deliberately does not catch domain or
 * adapter exceptions. Spring AI maps any thrown exception to an error {@code CallToolResult}
 * carrying its message, so the didactic message of a failed reference resolution (e.g.
 * "Requirement 'FR-1' does not exist ... create it first with req_add") reaches the agent as
 * a tool error rather than a raw stack trace. Keeping the tool method thin preserves that
 * message verbatim.</p>
 *
 * <p><strong>Workspace (one server = one workspace).</strong> Every in-port takes a
 * {@link WorkspaceId} routing key. This adapter is single-user/local: it operates against
 * exactly one workspace - the {@code workspaceId} injected at construction - and does not
 * expose the workspace as a tool argument. The composition root resolves that id per project;
 * see {@code WorkspaceIdResolver}.</p>
 */
public final class UseCaseMcpTools {

    private final AddUseCase addUseCase;
    private final ListUseCases listUseCases;
    private final GetUseCase getUseCase;
    private final WorkspaceId workspaceId;

    /**
     * Creates the adapter with its three driving in-ports and the workspace it serves.
     *
     * @param addUseCase   in-port backing {@code uc_add}
     * @param listUseCases in-port backing {@code uc_list}
     * @param getUseCase   in-port backing {@code uc_get}
     * @param workspaceId  the single workspace all tool calls route to
     */
    public UseCaseMcpTools(
            final AddUseCase addUseCase,
            final ListUseCases listUseCases,
            final GetUseCase getUseCase,
            final WorkspaceId workspaceId) {
        this.addUseCase = Objects.requireNonNull(addUseCase, "addUseCase");
        this.listUseCases = Objects.requireNonNull(listUseCases, "listUseCases");
        this.getUseCase = Objects.requireNonNull(getUseCase, "getUseCase");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
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
        final NewUseCase command = new NewUseCase(
                title,
                goal,
                blankToNull(scope),
                blankToNull(trigger),
                new ActorRef(primaryActor),
                toActorRefs(supportingActors),
                blankToNull(precondition),
                blankToNull(postcondition),
                toSteps(steps),
                extensions == null ? List.of() : List.copyOf(extensions));
        final UseCase created = addUseCase.add(workspaceId, command);
        return formatFull(created);
    }

    @McpTool(name = "uc_list", description = "List all use cases in this workspace (id, title, goal).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list() {
        final List<UseCase> all = listUseCases.list(workspaceId);
        return all.stream().map(UseCaseMcpTools::formatShort)
                .reduce((a, b) -> a + "\n" + b).orElse("(no use cases)");
    }

    @McpTool(name = "uc_get",
            description = "Fetch a single use case by its code (e.g. UC1), with all fields, its ordered "
                    + "steps and their fulfilled requirement labels, and its extensions.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            @McpToolParam(description = "Use-case code, e.g. UC1") final String id) {
        final UseCaseCode code = new UseCaseCode(id);
        return getUseCase.get(workspaceId, code)
                .map(UseCaseMcpTools::formatFull)
                .orElse("Use case not found: " + code.value());
    }

    // --- mapping helpers -------------------------------------------------------

    private static List<ActorRef> toActorRefs(final List<String> labels) {
        if (labels == null) {
            return List.of();
        }
        return labels.stream().map(ActorRef::new).toList();
    }

    private static List<Step> toSteps(final List<StepInput> steps) {
        if (steps == null) {
            return List.of();
        }
        return steps.stream()
                .map(s -> new Step(s.position(), s.text(),
                        s.realises() == null ? List.of()
                                : s.realises().stream().map(RequirementRef::new).toList()))
                .toList();
    }

    private static String formatShort(final UseCase uc) {
        return "%s | %s | %s".formatted(uc.code().value(), uc.title(), uc.goal());
    }

    private static String formatFull(final UseCase uc) {
        final StringBuilder sb = new StringBuilder();
        sb.append(uc.code().value()).append(' ').append(uc.title()).append('\n');
        sb.append("  goal: ").append(uc.goal()).append('\n');
        appendOptional(sb, "scope", uc.scope());
        appendOptional(sb, "trigger", uc.trigger());
        sb.append("  primaryActor: ").append(uc.primaryActor().label()).append('\n');
        if (!uc.supportingActors().isEmpty()) {
            sb.append("  supportingActors: ")
                    .append(uc.supportingActors().stream().map(ActorRef::label).reduce((a, b) -> a + ", " + b)
                            .orElse(""))
                    .append('\n');
        }
        appendOptional(sb, "precondition", uc.precondition());
        appendOptional(sb, "postcondition", uc.postcondition());
        sb.append("  steps:").append('\n');
        for (final Step step : uc.steps()) {
            sb.append("    ").append(step.position()).append(". ").append(step.text());
            if (!step.realises().isEmpty()) {
                sb.append(" -> realises ")
                        .append(step.realises().stream().map(RequirementRef::label)
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

    private static void appendOptional(final StringBuilder sb, final String field, final String value) {
        if (value != null) {
            sb.append("  ").append(field).append(": ").append(value).append('\n');
        }
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
