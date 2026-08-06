// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.mcp;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase;
import de.hauschel.arknet.uc.domain.StepTextPatch;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;

/**
 * Driving (in) adapter of the use-cases component: exposes the use-case use-cases as MCP
 * tools ({@code uc_add}, {@code uc_list}, {@code uc_get}, {@code uc_update}) and delegates each
 * tool call to the corresponding in-port.
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
 * single call. The nested {@link StepInput} shape mirrors the domain
 * {@link de.hauschel.arknet.uc.domain.Step}; requirement/actor references are passed as bare
 * labels (e.g. {@code FR-1}, {@code Customer}) straight into {@link NewUseCase}/{@link NewStep} -
 * resolving them to opaque identities is the application service's job, not this
 * adapter's.</p>
 *
 * <p><strong>Error hand-off.</strong> This adapter deliberately does not catch domain or
 * adapter exceptions. Spring AI maps any thrown exception to an error {@code CallToolResult}
 * carrying its message, so the didactic message of a failed reference resolution (e.g.
 * "Requirement 'FR-1' does not exist ... create it first with req_add") reaches the agent as
 * a tool error rather than a raw stack trace. Keeping the tool method thin preserves that
 * message verbatim.</p>
 *
 * <p><strong>Project (resolved per call).</strong> Every in-port takes a
 * {@link ProjectId} routing key. arknet-mcp runs as one shared server for every
 * project on the machine, so there is no single injected project any
 * more: each tool call resolves its own project from the request's anchor,
 * carried in the MCP transport context under {@link ProjectResolver#ANCHOR_KEY}.
 * The framework hands this adapter that context as an {@link McpSyncRequestContext}
 * parameter - a framework type, excluded from the generated tool input schema, so it is
 * not a caller-facing argument. The anchor is looked up in the project registry (ADR-016):
 * it arrives opaque, is matched whole against what was registered, and either hits exactly
 * one project or fails with an error message naming the possible remedies.</p>
 *
 * <p><strong>Rendering.</strong> This class only dispatches tool calls to their in-port and
 * turns the result into the returned string via {@link UseCasePresenter} - it holds no
 * rendering logic of its own (issue #96). See {@link UseCasePresenter} for the actor/requirement
 * display resolution that borrows {@link ResolveTerms}/{@link ResolveRequirements}
 * purely for display.</p>
 */
public final class UseCaseMcpTools {

    private final AddUseCase addUseCase;
    private final ListUseCases listUseCases;
    private final GetUseCase getUseCase;
    private final UpdateUseCase updateUseCase;
    private final ProjectResolver projects;
    private final UseCasePresenter presenter;

    /**
     * Creates the adapter with its four driving in-ports, the two borrowed sibling-hexagon
     * display ports and the resolver that maps each call's origin anchor to a project.
     *
     * @param addUseCase          in-port backing {@code uc_add}
     * @param listUseCases        in-port backing {@code uc_list}
     * @param getUseCase          in-port backing {@code uc_get}
     * @param updateUseCase       in-port backing {@code uc_update}
     * @param resolveTerms        ubiquitous-language driving port used only to render a
     *                            referenced actor's business name instead of its bare IRI
     * @param resolveRequirements requirements driving port used only to render a referenced
     *                            requirement's business code instead of its bare IRI
     * @param projects          resolves each call's target project from its origin directory
     */
    public UseCaseMcpTools(
            final AddUseCase addUseCase,
            final ListUseCases listUseCases,
            final GetUseCase getUseCase,
            final UpdateUseCase updateUseCase,
            final ResolveTerms resolveTerms,
            final ResolveRequirements resolveRequirements,
            final ProjectResolver projects) {
        this.addUseCase = Objects.requireNonNull(addUseCase, "addUseCase");
        this.listUseCases = Objects.requireNonNull(listUseCases, "listUseCases");
        this.getUseCase = Objects.requireNonNull(getUseCase, "getUseCase");
        this.updateUseCase = Objects.requireNonNull(updateUseCase, "updateUseCase");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.presenter = new UseCasePresenter(resolveTerms, resolveRequirements);
    }

    /**
     * Extracts the calling client's project anchor from the per-call transport context - the value
     * the server's context extractor placed there off the request header (ADR-016). Null-tolerant
     * on every hop: a call without a context, without a transport context, or without the key
     * resolves to {@code null}, which is a caller error rather than a route to a default.
     */
    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }

    /**
     * Resolves the project this call targets: the explicit {@code projectAnchor} parameter if the
     * caller supplied one, otherwise the anchor its transport carried (ADR-016 decision 2 - both
     * delivery paths are open to every MCP client). Neither present is a caller error; there is no
     * default project and no fallback to a server-side working directory (decision 3).
     *
     * <p>Returns the full {@link ResolvedProject}, not just its {@link ProjectId}: this component
     * needs the resolved project's configured default language for three, independent purposes -
     * {@link #effectiveDisplayLocale} merges it into the read tool's ({@code uc_get}'s)
     * {@code displayLocale} default; {@code uc_add}/{@code uc_update} instead pass
     * {@link ResolvedProject#defaultLanguage()} straight through to their in-port as the
     * {@code defaultLanguage} a write falls back to when the caller omits {@code language}
     * (issue #258); and {@code uc_list} - which, unlike {@code uc_get}, exposes no explicit
     * {@code displayLocale} tool argument to merge against - likewise passes it straight through
     * as the display language every listed use case's text fields are read in (issue #281). Three
     * different consumers of the very same field, not one the other two skip.</p>
     */
    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    /**
     * Merges an explicit, caller-supplied {@code displayLocale} argument with {@code project}'s
     * own configured default language for {@code uc_get}: the explicit value wins if the caller
     * gave a non-blank one, otherwise the project's default is used (or {@code null} if it has
     * none, leaving the decision to {@link de.hauschel.arknet.kernel.DisplayLocale#select}'s own
     * remaining fallback chain). Mirrors {@code UbiquitousLanguageMcpTools#effectiveDisplayLocale}
     * - see that method's javadoc for why the write tools never call this.
     */
    private static String effectiveDisplayLocale(final ResolvedProject project, final String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return project.defaultLanguage();
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

    /**
     * A text-only correction for one existing main-flow step, as passed by the agent to
     * {@code uc_update}.
     *
     * @param position 1-based position of the existing step to correct - must match a step
     *                 already present in the use case
     * @param text     the corrected step text
     */
    public record StepPatchInput(int position, String text) {
    }

    /**
     * A correction to one existing main-flow step's {@code realises} references, as passed by the
     * agent to {@code uc_update}: {@code realises} replaces that step's entire realises set
     * wholesale - an empty list explicitly clears it, distinct from omitting the step's position
     * from {@code stepRealisesPatches} altogether (which leaves its realises untouched).
     *
     * <p>{@code realises} is mandatory for every listed position - unlike {@link StepInput#realises()},
     * which may be omitted. Once a position is listed here, {@code null}/omitted {@code realises}
     * is rejected rather than silently treated as "clear all references": that ambiguity is exactly
     * what would let a caller who simply forgot the field delete requirement links by accident
     * (issue #255). To leave a step's realises untouched, do not list its position at all.</p>
     *
     * @param position 1-based position of the existing step to correct - must match a step already
     *                 present in the use case
     * @param realises mandatory: labels of the functional requirements this step should realise
     *                 going forward (e.g. {@code FR-1}), replacing its current set wholesale; an
     *                 explicit empty list clears all references
     */
    public record StepRealisesPatchInput(int position, List<String> realises) {
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "uc_add",
            description = "Register a complete use case (Cockburn-style, goal + ordered main flow) in a "
                    + "single call. Requirement and actor references are given as bare labels that must "
                    + "already exist in this project (create requirements with req_add, actors with "
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
            final List<String> extensions,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the title, goal, scope, "
                    + "trigger, precondition, postcondition and every step's/extension's text are written in. "
                    + "Falls back to the project's configured default language (project_update) if omitted; if "
                    + "the project has no default either, the call is rejected rather than writing an untagged "
                    + "literal.", required = false)
            final String language,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
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
                extensions == null ? List.of() : List.copyOf(extensions),
                blankToNull(language));
        final UseCase created = addUseCase.add(project.id(), command, project.defaultLanguage());
        return presenter.formatFull(project.id(), created);
    }

    @McpTool(name = "uc_list", description = "List all use cases in this project (id, title, goal).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        // No explicit displayLocale tool argument to merge against here, unlike uc_get - every
        // listed use case's text fields are read straight under the resolved project's own
        // configured default language (issue #281), the same value uc_add/uc_update already pass
        // through for the write side.
        final List<UseCase> all = listUseCases.list(project.id(), project.defaultLanguage());
        return all.stream().map(UseCasePresenter::formatShort)
                .reduce((a, b) -> a + "\n" + b).orElse("(no use cases)");
    }

    @McpTool(name = "uc_get",
            description = "Fetch a single use case by its code (e.g. UC1), with all fields, its ordered "
                    + "steps and their fulfilled requirement labels, and its extensions.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Use-case code, e.g. UC1") final String id,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') to display the title/goal/"
                    + "step texts in, overriding the project's own configured default language for this one "
                    + "call. Falls back to the project default, then to the server's own default, then to an "
                    + "untagged literal, then deterministically to any literal the use case carries.",
                    required = false)
            final String displayLocale,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final UseCaseCode code = new UseCaseCode(id);
        final String effective = effectiveDisplayLocale(project, displayLocale);
        return getUseCase.get(project.id(), code, effective)
                .map(uc -> presenter.formatFull(project.id(), uc))
                .orElse("Use case not found: " + code.value());
    }

    @McpTool(name = "uc_update",
            description = "Correct an already-created use case's title, goal, scope, trigger, precondition "
                    + "and/or postcondition, and/or individual existing main-flow steps' text and/or realises "
                    + "references by position. Every argument is optional - an omitted one leaves that field "
                    + "unchanged; omitted extensions leave the existing ones unchanged, given extensions "
                    + "replace them wholesale. stepTextPatches corrects only a step's text; "
                    + "stepRealisesPatches replaces a step's entire realises set wholesale (an empty array "
                    + "clears it) - a position omitted from either list is left untouched, and a position with "
                    + "no matching step is rejected in either list. Neither can add, remove or reorder steps. "
                    + "Does not touch primaryActor, supportingActors or the step list's structure; use uc_add "
                    + "to recreate the use case if those need to change.")
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Use-case code, e.g. UC1") final String id,
            @McpToolParam(description = "New short human-readable name (optional, unchanged if omitted)",
                    required = false)
            final String title,
            @McpToolParam(description = "New goal the primary actor wants to achieve (optional, unchanged if "
                    + "omitted)", required = false)
            final String goal,
            @McpToolParam(description = "New system/design scope (optional, unchanged if omitted)",
                    required = false)
            final String scope,
            @McpToolParam(description = "New triggering event (optional, unchanged if omitted)", required = false)
            final String trigger,
            @McpToolParam(description = "New precondition (optional, unchanged if omitted)", required = false)
            final String precondition,
            @McpToolParam(description = "New postcondition (optional, unchanged if omitted)", required = false)
            final String postcondition,
            @McpToolParam(description = "New alternative/exception flows as free-text lines, replacing the "
                    + "existing ones wholesale (optional, unchanged if omitted)", required = false)
            final List<String> extensions,
            @McpToolParam(description = "Text corrections for individual existing main-flow steps: a JSON "
                    + "array of {position: 1-based int of the step to correct, text: the corrected text}. Only "
                    + "the named steps' text changes - their realises references (correct those separately via "
                    + "stepRealisesPatches) and every other step are untouched. A position with no matching "
                    + "step is rejected (optional, unchanged if omitted)",
                    required = false)
            final List<StepPatchInput> stepTextPatches,
            @McpToolParam(description = "Corrections to individual existing main-flow steps' realises "
                    + "references: a JSON array of {position: 1-based int of the step to correct, realises: "
                    + "array of requirement labels like 'FR-1' this step should realise, replacing its "
                    + "current set wholesale - an empty array explicitly clears all references for that "
                    + "step}. realises is REQUIRED for every listed position - omitting it is rejected "
                    + "rather than treated as clearing the step, precisely to avoid an accidental deletion; "
                    + "send realises: [] to clear on purpose. A position not listed here is left untouched; "
                    + "a position with no matching step is rejected (optional, unchanged if omitted)",
                    required = false)
            final List<StepRealisesPatchInput> stepRealisesPatches,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') every field this call "
                    + "actually touches (a non-omitted title/goal/scope/trigger/precondition/postcondition, "
                    + "each patched step's text, and, if extensions is given, every entry of it) is written "
                    + "in. Falls back to the project's configured default language (see uc_add's same "
                    + "parameter) if omitted; if the project has no default either, the call is rejected "
                    + "rather than writing an untagged literal. Only the existing literal carrying the tag "
                    + "actually written is replaced per field - every other language variant survives "
                    + "untouched, except a stale untagged one left over from before a language was ever "
                    + "supplied, which is swept away when the resolved tag equals the project's default.",
                    required = false)
            final String language,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final UseCaseCode code = new UseCaseCode(id);
        final UseCase updated = updateUseCase.update(project.id(), code, blankToNull(title), blankToNull(goal),
                blankToNull(scope), blankToNull(trigger), blankToNull(precondition), blankToNull(postcondition),
                extensions == null ? null : List.copyOf(extensions), toStepTextPatches(stepTextPatches),
                toStepRealisesPatches(stepRealisesPatches), blankToNull(language), project.defaultLanguage());
        return presenter.formatFull(project.id(), updated);
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

    private static List<StepTextPatch> toStepTextPatches(final List<StepPatchInput> patches) {
        if (patches == null) {
            return null;
        }
        return patches.stream().map(p -> new StepTextPatch(p.position(), p.text())).toList();
    }

    private static List<UpdateUseCase.StepRealisesPatch> toStepRealisesPatches(
            final List<StepRealisesPatchInput> patches) {
        if (patches == null) {
            return null;
        }
        return patches.stream()
                .map(p -> new UpdateUseCase.StepRealisesPatch(p.position(), requireRealises(p)))
                .toList();
    }

    /**
     * Rejects a listed {@link StepRealisesPatchInput} whose {@code realises} was omitted/{@code
     * null} instead of silently treating it as "clear all references" - the one place a
     * forgotten field would otherwise flip from uc_update's usual "omitted means unchanged" into
     * an unintended deletion (issue #255). A step this call is not patching at all must simply
     * not appear in {@code stepRealisesPatches}; a listed position always needs its own explicit
     * {@code realises}, {@code []} to clear it.
     */
    private static List<String> requireRealises(final StepRealisesPatchInput patch) {
        if (patch.realises() == null) {
            throw new IllegalArgumentException("stepRealisesPatches entry for position " + patch.position()
                    + " is missing realises - to clear all realises references for this step, send an "
                    + "explicit empty array (realises: []); to leave this step's realises untouched, omit "
                    + "its position from stepRealisesPatches entirely instead of listing it");
        }
        return List.copyOf(patch.realises());
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
