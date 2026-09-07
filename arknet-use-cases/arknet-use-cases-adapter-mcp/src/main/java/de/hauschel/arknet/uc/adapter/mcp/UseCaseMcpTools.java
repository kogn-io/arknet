// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.actor.application.port.in.ResolveRoles;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.StaleTranslationHint;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.in.DescribeUseCaseDisplayFallback;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.LinkConstraint;
import de.hauschel.arknet.uc.application.port.in.LinkTerm;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase.UseCaseCorrection;
import de.hauschel.arknet.uc.domain.RemovedPositions;
import de.hauschel.arknet.uc.domain.StepTextPatch;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseDisplayFallback;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;

/**
 * Driving (in) adapter of the use-cases component: exposes the use-case use-cases as MCP
 * tools ({@code uc_add}, {@code uc_list}, {@code uc_get}, {@code uc_update}, {@code uc_link_term},
 * {@code uc_link_constraint}) and delegates each tool call to the corresponding in-port.
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
 * including its ordered step list and its label references to requirements and roles - in a
 * single call. The nested {@link StepInput} shape mirrors the domain
 * {@link de.hauschel.arknet.uc.domain.Step}; requirement/role references are passed as bare
 * business codes (e.g. {@code FR-1}, {@code ROLE-4}) straight into {@link NewUseCase}/
 * {@link NewStep} - resolving them to opaque identities is the application service's job, not
 * this adapter's.</p>
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
 * not a caller-facing argument. The anchor is looked up in the project registry:
 * it arrives opaque, is matched whole against what was registered, and either hits exactly
 * one project or fails with an error message naming the possible remedies.</p>
 *
 * <p><strong>Rendering.</strong> This class only dispatches tool calls to their in-port and
 * turns the result into the returned string via {@link UseCasePresenter} - it holds no
 * rendering logic of its own (issue #96). See {@link UseCasePresenter} for the role/requirement/
 * term/constraint display resolution that borrows {@link ResolveRoles}/{@link ResolveTerms}/
 * {@link ResolveRequirements}/{@link ResolveConstraints} purely for display.</p>
 */
public final class UseCaseMcpTools {

    /**
     * The prose markup this tool's free-text fields accept, appended to every writing tool's
     * description (issue #388).
     *
     * <p>It belongs on the tool, not only in the module docs: the writing agent reads the tool
     * schema and nothing else, which is exactly why the {@code white-space:pre-line} mechanism of
     * issue #385 was never used by anyone. The same sentence is repeated in each bounded
     * context's MCP adapter rather than shared, because these adapters deliberately have no
     * common module - a shared string is not reason enough to create one.</p>
     */
    private static final String PROSE_MARKUP = " Free-text fields accept a narrow Markdown subset:"
            + " **bold**, *italic*, `code`, lines starting with '- ' as a bullet list, and a blank line"
            + " for a new paragraph. Links, headings, tables and HTML are deliberately not interpreted -"
            + " a reference belongs in the model (an edge such as usesTerm), not in a hand-written link.";

    /**
     * The stale-translation signal, announced on every update tool that writes a multilingual
     * field (kogn-io/arknet#474). It belongs in the tool description for the same reason
     * {@link #PROSE_MARKUP} does: the writing agent reads the tool schema and nothing else, and a
     * signal it does not expect is a signal it does not act on.
     */
    private static final String STALE_TRANSLATION_NOTE = " If the project maintains several languages,"
            + " the answer names the fields that still carry a maintained language this call did not"
            + " write; repeat the call under each of those languages to keep the translations in step."
            + " A field that did not carry the written language yet is being translated, not corrected,"
            + " and is not reported.";

    private static final String TITLE_FIELD = "title";
    private static final String GOAL_FIELD = "useCaseGoal";
    private static final String SCOPE_FIELD = "designScope";
    private static final String TRIGGER_FIELD = "trigger";
    private static final String PRECONDITION_FIELD = "useCasePrecondition";
    private static final String POSTCONDITION_FIELD = "useCasePostcondition";
    private static final String MAIN_STEP_FIELD = "mainStep";
    private static final String EXTENSION_STEP_FIELD = "extensionStep";

    /**
     * The multilingual fields {@code uc_update} can write, as {@code FieldLanguageLookup} keys - the
     * local names of the predicates behind them, or of the edge owning a child resource's text.
     * {@code arknet-architecture-tests} reads this list reflectively and holds it against the
     * {@code sh:uniqueLang} properties the shipped shapes declare for this resource, so a typo or
     * a renamed predicate fails a build instead of silently muting the signal for that field.
     */
    private static final List<String> MULTILINGUAL_FIELDS = List.of(TITLE_FIELD, GOAL_FIELD, SCOPE_FIELD,
            TRIGGER_FIELD, PRECONDITION_FIELD, POSTCONDITION_FIELD, MAIN_STEP_FIELD, EXTENSION_STEP_FIELD);

    private final AddUseCase addUseCase;
    private final ListUseCases listUseCases;
    private final DescribeUseCaseDisplayFallback describeUseCaseDisplayFallback;
    private final GetUseCase getUseCase;
    private final UpdateUseCase updateUseCase;
    private final LinkTerm linkTerm;
    private final LinkConstraint linkConstraint;
    private final ProjectResolver projects;
    private final UseCasePresenter presenter;
    private final StaleTranslationHint staleTranslations;

    /**
     * Creates the adapter with its seven driving in-ports, the four borrowed sibling-hexagon
     * display ports and the resolver that maps each call's origin anchor to a project.
     *
     * @param addUseCase          in-port backing {@code uc_add}
     * @param listUseCases        in-port backing {@code uc_list}
     * @param describeUseCaseDisplayFallback in-port backing {@code uc_list}'s fallback-visibility
     *                            line (kogn-io/arknet#475)
     * @param getUseCase          in-port backing {@code uc_get}
     * @param updateUseCase       in-port backing {@code uc_update}
     * @param linkTerm            in-port backing {@code uc_link_term}
     * @param linkConstraint      in-port backing {@code uc_link_constraint}
     * @param resolveRoles        the actor register's driving port used only to render a
     *                            referenced role's business code instead of its bare IRI
     *                            (ADR-37/kogn-io/arknet#405 Part C)
     * @param resolveTerms        ubiquitous-language driving port used only to render a linked
     *                            glossary term's business code instead of its bare IRI
     * @param resolveRequirements requirements driving port used only to render a referenced
     *                            requirement's business code instead of its bare IRI
     * @param resolveConstraints  requirements driving port used only to render a linked
     *                            constraint's business code instead of its bare IRI
     * @param projects          resolves each call's target project from its origin directory
     * @param staleTranslations   renders {@code uc_update}'s stale-translation signal
     *                            (kogn-io/arknet#474)
     */
    public UseCaseMcpTools(
            final AddUseCase addUseCase,
            final ListUseCases listUseCases,
            final DescribeUseCaseDisplayFallback describeUseCaseDisplayFallback,
            final GetUseCase getUseCase,
            final UpdateUseCase updateUseCase,
            final LinkTerm linkTerm,
            final LinkConstraint linkConstraint,
            final ResolveRoles resolveRoles,
            final ResolveTerms resolveTerms,
            final ResolveRequirements resolveRequirements,
            final ResolveConstraints resolveConstraints,
            final ProjectResolver projects,
            final StaleTranslationHint staleTranslations) {
        this.addUseCase = Objects.requireNonNull(addUseCase, "addUseCase");
        this.listUseCases = Objects.requireNonNull(listUseCases, "listUseCases");
        this.describeUseCaseDisplayFallback =
                Objects.requireNonNull(describeUseCaseDisplayFallback, "describeUseCaseDisplayFallback");
        this.getUseCase = Objects.requireNonNull(getUseCase, "getUseCase");
        this.updateUseCase = Objects.requireNonNull(updateUseCase, "updateUseCase");
        this.linkTerm = Objects.requireNonNull(linkTerm, "linkTerm");
        this.linkConstraint = Objects.requireNonNull(linkConstraint, "linkConstraint");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.presenter = new UseCasePresenter(resolveRoles, resolveTerms, resolveRequirements, resolveConstraints);
        this.staleTranslations = Objects.requireNonNull(staleTranslations, "staleTranslations");
    }

    /**
     * Extracts the calling client's project anchor from the per-call transport context - the value
     * the server's context extractor placed there off the request header. Null-tolerant
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
     * caller supplied one, otherwise the anchor its transport carried; both delivery paths are open
     * to every MCP client. Neither present is a caller error; there is no default project and no
     * fallback to a server-side working directory.
     *
     * <p>Returns the full {@link ResolvedProject}, not just its {@link ProjectId}: this component
     * needs the resolved project's configured default language for two, independent purposes -
     * {@link #effectiveDisplayLocale} merges it into the read tools' ({@code uc_get}'s and,
     * since kogn-io/arknet#475, {@code uc_list}'s own) {@code displayLocale} default;
     * {@code uc_add}/{@code uc_update} instead pass {@link ResolvedProject#defaultLanguage()}
     * straight through to their in-port as the {@code defaultLanguage} a write falls back to
     * when the caller omits {@code language} (issue #258) - and, for {@code uc_update} alone, as
     * the language its read-modify-write round trip reads the current state in, so a field this
     * call leaves alone is echoed back and rewritten in the project's own language rather than
     * the daemon's (issue #456).</p>
     */
    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    /**
     * Merges an explicit, caller-supplied {@code displayLocale} argument with {@code project}'s
     * own configured default language for {@code uc_get}/{@code uc_list} (the latter since
     * kogn-io/arknet#475): the explicit value wins if the caller gave a non-blank one, otherwise
     * the project's default is used (or {@code null} if it has none, leaving the decision to
     * {@link de.hauschel.arknet.kernel.DisplayLocale#select}'s own remaining fallback chain).
     * Mirrors {@code UbiquitousLanguageMcpTools#effectiveDisplayLocale} - see that method's
     * javadoc for why the write tools never call this.
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

    /**
     * A main-flow step to append, as passed by the agent to {@code uc_update} (kogn-io/arknet#513) -
     * the position-free counterpart of {@link StepInput}: the next position is assigned
     * automatically, continuing from the use case's current highest one.
     *
     * @param text     what happens in this step (an actor or system action)
     * @param realises business codes of the functional requirements this step fulfils (e.g.
     *                 {@code FR-1}); may be empty or omitted
     */
    public record NewMainStepInput(String text, List<String> realises) {
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "uc_add",
            description = "Register a complete use case (Cockburn-style, goal + ordered main flow) in a "
                    + "single call. Requirement and role references are given as bare business codes that "
                    + "must already exist in this project (create requirements with req_add, roles with "
                    + "role_add first; role_list shows what is registered)." + PROSE_MARKUP)
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
            @McpToolParam(description = "Business code of the primary role whose goal this use case serves, "
                    + "e.g. 'ROLE-4'. Must be an existing role (role_add) - see role_list for what is "
                    + "registered.")
            final String primaryRole,
            @McpToolParam(description = "Optional: business codes of supporting (secondary) roles; each must "
                    + "be an existing role (role_list)", required = false)
            final List<String> supportingRoles,
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
                primaryRole,
                supportingRoles == null ? List.of() : List.copyOf(supportingRoles),
                blankToNull(precondition),
                blankToNull(postcondition),
                toNewSteps(steps),
                extensions == null ? List.of() : List.copyOf(extensions),
                blankToNull(language));
        final UseCase created = addUseCase.add(project.id(), command, project.defaultLanguage());
        return presenter.formatFull(project.id(), created, null);
    }

    @McpTool(name = "uc_list", description = "List all use cases in this project (id, title, goal). A use "
            + "case shown under a fallen-back language (its title/goal is missing in the requested/"
            + "project-default language) carries an inline [fallback: ...] tag naming the language "
            + "actually shown - see displayLocale.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') to display every use "
                    + "case's title and goal in, overriding the project's own configured default language "
                    + "for this one call (kogn-io/arknet#475). Falls back to the project default, then to "
                    + "the server's own default, then to an untagged literal, then deterministically to any "
                    + "literal a use case carries - a use case whose shown variant is not this call's "
                    + "requested/project-default language is marked with an inline [fallback: ...] tag.",
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
        final String effective = effectiveDisplayLocale(project, displayLocale);
        final List<UseCase> all = listUseCases.list(project.id(), effective);
        if (all.isEmpty()) {
            return "(no use cases)";
        }
        final Map<UseCaseCode, UseCaseDisplayFallback> fallbacks =
                describeUseCaseDisplayFallback.describe(project.id(), effective);
        return all.stream()
                .map(uc -> UseCasePresenter.formatShort(uc) + fallbackSuffix(fallbacks.get(uc.code())))
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
                .map(uc -> presenter.formatFull(project.id(), uc, effective))
                .orElse("Use case not found: " + code.value());
    }

    @McpTool(name = "uc_update",
            description = "Correct an already-created use case's title, goal, scope, trigger, precondition "
                    + "and/or postcondition, and/or its main flow. Every argument is optional - an omitted "
                    + "one leaves that field unchanged; omitted extensions leave the existing ones unchanged, "
                    + "given extensions replace them wholesale. stepTextPatches corrects only a step's text; "
                    + "stepRealisesPatches replaces a step's entire realises set wholesale (an empty array "
                    + "clears it) - a position omitted from either list is left untouched, and a position with "
                    + "no matching step is rejected in either list. newMainSteps appends steps after the "
                    + "existing ones; removeMainStepPositions takes one or more out by position and moves the "
                    + "ones after up - a position cannot be both corrected (stepTextPatches/"
                    + "stepRealisesPatches) and removed in one call, and removing every remaining step is "
                    + "rejected (at least one must stay). Reordering the main flow is still out of scope - "
                    + "use uc_add for a replacement use case if the flow needs a different order, at the "
                    + "price of a new use-case code and no inbound references carried over. The role "
                    + "references are correctable too: a given primaryRole replaces the current one (it "
                    + "cannot be cleared - a use case always has exactly one), and a given supportingRoles "
                    + "array replaces the current list wholesale, an empty array clearing it. "
                    + "usesTermCodes replaces the use case's arkreq:usesTerm links wholesale: omit it to leave "
                    + "the existing links untouched, pass an empty list to remove them all, or pass the full "
                    + "set of TERM-N codes the use case should use going forward (uc_link_term remains the "
                    + "convenient way to add a single link without restating the rest)."
                    + PROSE_MARKUP + STALE_TRANSLATION_NOTE)
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
            @McpToolParam(description = "Business code of the role that should be this use case's primary "
                    + "role going forward, e.g. 'ROLE-4'. Must be an existing role (role_add) - see role_list "
                    + "for what is registered. Replaces the current primary role; it cannot be cleared, "
                    + "since a use case always has exactly one (optional, unchanged if omitted)",
                    required = false)
            final String primaryRole,
            @McpToolParam(description = "Business codes of the supporting (secondary) roles this use case "
                    + "should carry going forward, each an existing role (role_list), replacing the current "
                    + "list wholesale - an empty array explicitly clears every supporting role (optional, "
                    + "unchanged if omitted)", required = false)
            final List<String> supportingRoles,
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
            @McpToolParam(description = "Main-flow steps to append after the existing ones: a JSON array of "
                    + "{text: string, realises: array of requirement labels like 'FR-1' this step fulfils "
                    + "(optional)}. Position is assigned automatically, continuing from the current highest "
                    + "(optional, none appended if omitted)", required = false)
            final List<NewMainStepInput> newMainSteps,
            @McpToolParam(description = "1-based positions (as uc_get currently shows them) of main-flow "
                    + "steps to remove. The steps after a removed one move up so the survivors stay gap-free. "
                    + "A position named here must not also be named in stepTextPatches/stepRealisesPatches, "
                    + "and removing every remaining step is rejected - at least one must stay (optional, none "
                    + "removed if omitted)", required = false)
            final List<Integer> removeMainStepPositions,
            @McpToolParam(description = "Business codes of the glossary terms this use case should use going "
                    + "forward, e.g. ['TERM-1', 'TERM-2'] (resolved against the glossary, not skos:prefLabel "
                    + "or store IRIs). Omit to leave the existing arkreq:usesTerm links untouched; pass an "
                    + "empty list to remove every link; pass a non-empty list to replace the links wholesale - "
                    + "a term not named here is unlinked even if it was linked before (kogn-io/arknet#540).",
                    required = false)
            final List<String> usesTermCodes,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') every field this call "
                    + "actually touches (a non-omitted title/goal/scope/trigger/precondition/postcondition, "
                    + "each patched or newly appended step's text, and, if extensions is given, every entry "
                    + "of it) is written "
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
        final UseCaseCorrection correction = UseCaseCorrection.builder()
                .title(blankToNull(title))
                .goal(blankToNull(goal))
                .scope(blankToNull(scope))
                .trigger(blankToNull(trigger))
                .primaryRole(blankToNull(primaryRole))
                .supportingRoles(supportingRoles == null ? null : List.copyOf(supportingRoles))
                .precondition(blankToNull(precondition))
                .postcondition(blankToNull(postcondition))
                .extensions(extensions == null ? null : List.copyOf(extensions))
                .stepTextPatches(toStepTextPatches(stepTextPatches))
                .stepRealisesPatches(toStepRealisesPatches(stepRealisesPatches))
                .newMainSteps(toNewMainSteps(newMainSteps))
                .removeMainStepPositions(toRemovedPositions(removeMainStepPositions))
                .usesTermCodes(usesTermCodes == null ? null : List.copyOf(usesTermCodes))
                .language(blankToNull(language))
                .build();
        final String staleHint = staleTranslationHint(project, code, correction, extensions, stepTextPatches,
                newMainSteps, removeMainStepPositions);
        final UseCase updated = updateUseCase.update(project.id(), code, correction, project.defaultLanguage());
        return presenter.formatFull(project.id(), updated, null) + staleHint;
    }

    @McpTool(name = "uc_link_term",
            description = "Link a use case to a glossary term of the ubiquitous language it uses. The term "
                    + "must already exist (create it with term_add first). Linking the same term twice is a "
                    + "no-op. To remove a link (or replace the whole set), use uc_update's usesTermCodes "
                    + "instead.")
    public String linkTerm(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Use-case code, e.g. UC1") final String id,
            @McpToolParam(description = "Term code, e.g. TERM-1 (the term's business code, resolved "
                    + "against the glossary - not its skos:prefLabel or its store IRI)")
            final String termId,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final ProjectId projectId = project.id();
        // Touches no language-tagged field itself, but the read-modify-write round trip behind it
        // still needs the project's own default language to echo an untouched field back under it
        // rather than the process default (issue #468).
        final UseCase updated =
                linkTerm.linkTerm(projectId, new UseCaseCode(id), termId, project.defaultLanguage());
        return presenter.formatFull(projectId, updated, null);
    }

    @McpTool(name = "uc_link_constraint",
            description = "Link a use case to a constraint it is bound by. The constraint must already exist "
                    + "(create it first with constraint_add). Linking the same constraint twice is a no-op.")
    public String linkConstraint(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Use-case code, e.g. UC1") final String id,
            @McpToolParam(description = "Constraint code, e.g. TCON-1, BCON-1 or RCON-1 (the constraint's "
                    + "business code, not its store IRI)")
            final String constraintId,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final ProjectId projectId = project.id();
        // Touches no language-tagged field itself, but the read-modify-write round trip behind it
        // still needs the project's own default language to echo an untouched field back under it
        // rather than the process default (issue #468).
        final UseCase updated = linkConstraint.linkConstraint(
                projectId, new UseCaseCode(id), constraintId, project.defaultLanguage());
        return presenter.formatFull(projectId, updated, null);
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

    private static List<UpdateUseCase.NewMainStep> toNewMainSteps(final List<NewMainStepInput> steps) {
        if (steps == null) {
            return null;
        }
        return steps.stream()
                .map(s -> new UpdateUseCase.NewMainStep(s.text(),
                        s.realises() == null ? List.of() : List.copyOf(s.realises())))
                .toList();
    }

    /** Mirrors {@code AdrMcpTools#toRemovedPositions} (kogn-io/arknet#513). */
    private static RemovedPositions toRemovedPositions(final List<Integer> positions) {
        if (positions == null) {
            return RemovedPositions.NONE;
        }
        return new RemovedPositions(new java.util.LinkedHashSet<>(positions));
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

    /**
     * The {@code [fallback: ...]} suffix {@code uc_list} appends to a line whenever {@code
     * fallback} names at least one field that had to degrade past the requested/project-default
     * language (kogn-io/arknet#475) - empty string (no visible change) when {@code fallback} is
     * {@code null} or carries no fallen-back field, matching the requirement that the normal case
     * stays noise-free.
     */
    private static String fallbackSuffix(final UseCaseDisplayFallback fallback) {
        if (fallback == null || fallback.isEmpty()) {
            return "";
        }
        final List<String> parts = new ArrayList<>();
        if (fallback.titleTag() != null) {
            parts.add("title=" + displayTag(fallback.titleTag()));
        }
        if (fallback.goalTag() != null) {
            parts.add("goal=" + displayTag(fallback.goalTag()));
        }
        return " [fallback: " + String.join(", ", parts) + "]";
    }

    private static String displayTag(final String tag) {
        return tag.isEmpty() ? "untagged" : tag;
    }

    /**
     * The stale-translation signal for a {@code uc_update} (kogn-io/arknet#474): the multilingual
     * fields this call is about to write, named as {@code store_check} names them - the six prose
     * fields of the use case itself, plus the two step lists, each keyed by the edge that owns
     * them, because a step's text lives on its own resource. Asked before the write and appended
     * after it, because only the state before tells a correction from a translation (see
     * {@link StaleTranslationHint}).
     *
     * <p>{@code stepRealisesPatches} and the two role references are deliberately absent: none of
     * them writes text under a language, so none leaves anything behind to go stale.</p>
     *
     * <p>A call that also <em>removes</em> a main-flow step reports the main-step edge as
     * unwritten, whatever else it does to that list. The lookup pools the tags of every step
     * hanging off the edge, so a removal can take the last carrier of a language out from under a
     * snapshot that already counted it - and the hint would then name a language the answer the
     * caller is holding no longer has anywhere. Better one hint too few than one that is wrong
     * about the state it is printed next to (kogn-io/arknet#537 review).</p>
     *
     * <p>{@code extensionStep} has the same problem by a different route: {@code extensions} is a
     * wholesale replace, and whether it shortens or lengthens the stored list is not decidable
     * from this call's arguments at all - only a comparison against the stored count tells
     * ({@code UseCaseService#update}'s {@code stableExtensionPrefixLength}). A length change
     * collapses the prefix of positions that keep their identity, and anything beyond it loses
     * every language variant but the one this call writes - the same "answer next to the hint no
     * longer has it" failure as above. This call's extension count is therefore read once, before
     * the write, purely to compare lengths; the edge is reported only when the count stays the
     * same (kogn-io/arknet#538).</p>
     */
    private String staleTranslationHint(final ResolvedProject project, final UseCaseCode code,
            final UseCaseCorrection correction, final List<String> extensions,
            final List<StepPatchInput> stepTextPatches, final List<NewMainStepInput> newMainSteps,
            final List<Integer> removeMainStepPositions) {
        final List<String> fieldsWritten = new ArrayList<>();
        addIfWritten(fieldsWritten, TITLE_FIELD, correction.title());
        addIfWritten(fieldsWritten, GOAL_FIELD, correction.goal());
        addIfWritten(fieldsWritten, SCOPE_FIELD, correction.scope());
        addIfWritten(fieldsWritten, TRIGGER_FIELD, correction.trigger());
        addIfWritten(fieldsWritten, PRECONDITION_FIELD, correction.precondition());
        addIfWritten(fieldsWritten, POSTCONDITION_FIELD, correction.postcondition());
        final boolean removesAStep = removeMainStepPositions != null && !removeMainStepPositions.isEmpty();
        if (!removesAStep
                && (stepTextPatches != null && !stepTextPatches.isEmpty()
                        || newMainSteps != null && !newMainSteps.isEmpty())) {
            fieldsWritten.add(MAIN_STEP_FIELD);
        }
        if (extensions != null && !extensions.isEmpty() && !extensionCountChanges(project, code, extensions)) {
            fieldsWritten.add(EXTENSION_STEP_FIELD);
        }
        if (fieldsWritten.isEmpty()) {
            return "";
        }
        return staleTranslations.forResource(project.id(), code.value(),
                LanguageTag.writtenLanguage(correction.language(), project.defaultLanguage()),
                project.maintainedLanguages(), fieldsWritten);
    }

    /** Records {@code field} as written when the correction actually carries a value for it. */
    private static void addIfWritten(final List<String> fieldsWritten, final String field, final String value) {
        if (value != null) {
            fieldsWritten.add(field);
        }
    }

    /**
     * Whether {@code extensions} would replace the stored list with a different length - the one
     * thing a wholesale {@code extensions} replace does not say about itself (kogn-io/arknet#538).
     * Reads the current use case once, before the write the caller is about to make, purely to
     * compare counts; a use case the lookup cannot find (the write is about to fail anyway) counts
     * as a change, the same "say less rather than say it wrong" choice as elsewhere in this method.
     */
    private boolean extensionCountChanges(final ResolvedProject project, final UseCaseCode code,
            final List<String> extensions) {
        return getUseCase.get(project.id(), code, null)
                .map(current -> current.extensions().size() != extensions.size())
                .orElse(true);
    }

}
