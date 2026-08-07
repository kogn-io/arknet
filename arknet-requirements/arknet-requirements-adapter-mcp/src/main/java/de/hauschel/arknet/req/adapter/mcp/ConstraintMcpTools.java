// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.mcp;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.req.application.port.in.AddConstraint;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.GetConstraint;
import de.hauschel.arknet.req.application.port.in.ListConstraints;
import de.hauschel.arknet.req.application.port.in.UpdateConstraint;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintType;

/**
 * Driving (in) adapter of the requirements component's constraint side: exposes the constraint
 * use-cases as MCP tools ({@code constraint_add}, {@code constraint_list}, {@code constraint_get},
 * {@code constraint_update}) and delegates each tool call to the corresponding in-port. A separate
 * class from {@link RequirementMcpTools} - not merged into it - because a {@link Constraint} is a
 * distinct resource type of this same hexagon (issue #223), not a facet of {@link
 * de.hauschel.arknet.req.domain.Requirement}; {@code req_link_constraint} itself stays on
 * {@link RequirementMcpTools} because it mutates the requirement, not the constraint.
 *
 * <p>Mirrors {@link RequirementMcpTools}'s conventions exactly: identity vs. code (every tool
 * takes a plain {@code String} code, never the opaque {@link
 * de.hauschel.arknet.req.domain.ConstraintId}), per-call project resolution via
 * {@link ProjectResolver}, and a separate {@link ConstraintPresenter} for rendering. There is
 * still no {@code constraint_set_status} tool - the ontology gives a constraint no status field -
 * and {@code constraint_update} corrects a constraint's text only, never its type or code (see
 * {@link UpdateConstraint}).</p>
 */
public final class ConstraintMcpTools {

    private final AddConstraint addConstraint;
    private final ListConstraints listConstraints;
    private final GetConstraint getConstraint;
    private final UpdateConstraint updateConstraint;
    private final ProjectResolver projects;
    private final ConstraintPresenter presenter = new ConstraintPresenter();

    /**
     * Creates the adapter with its four driving in-ports and the resolver that maps each call's
     * origin directory to a project.
     *
     * @param addConstraint    in-port backing {@code constraint_add}
     * @param listConstraints  in-port backing {@code constraint_list}
     * @param getConstraint    in-port backing {@code constraint_get}
     * @param updateConstraint in-port backing {@code constraint_update}
     * @param projects         resolves each call's target project from its origin directory
     */
    public ConstraintMcpTools(
            final AddConstraint addConstraint,
            final ListConstraints listConstraints,
            final GetConstraint getConstraint,
            final UpdateConstraint updateConstraint,
            final ProjectResolver projects) {
        this.addConstraint = Objects.requireNonNull(addConstraint, "addConstraint");
        this.listConstraints = Objects.requireNonNull(listConstraints, "listConstraints");
        this.getConstraint = Objects.requireNonNull(getConstraint, "getConstraint");
        this.updateConstraint = Objects.requireNonNull(updateConstraint, "updateConstraint");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /** {@code RequirementMcpTools#contextAnchor} - identical, duplicated per adapter class. */
    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }

    /** {@code RequirementMcpTools#resolveProject} - identical, duplicated per adapter class. */
    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    /**
     * {@code RequirementMcpTools#effectiveDisplayLocale} - identical, duplicated per adapter
     * class: merges an explicit, caller-supplied {@code displayLocale} argument with
     * {@code project}'s own configured default language for {@code constraint_get}. The write
     * tools never call this - a write resolves its language through
     * {@code LanguageTag#resolveWriteLanguage} in the application service instead, which rejects
     * rather than degrades when neither is available.
     */
    private static String effectiveDisplayLocale(final ResolvedProject project, final String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return project.defaultLanguage();
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "constraint_add", description = "Register a new constraint (technical, business or "
            + "regulatory) - a non-negotiable, externally-imposed boundary on the solution space (ISO 29148).")
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Short human-readable summary of the constraint") final String title,
            @McpToolParam(description = "The constraint in one sentence, e.g. 'Must run on the JVM' or "
                    + "'Personal data must stay in the EU'") final String statement,
            @McpToolParam(description = "Classification: TECHNICAL, BUSINESS or REGULATORY") final String type,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the title and statement "
                    + "are written in. Falls back to the project's configured default language "
                    + "(project_update) if omitted; if the project has no default either, the call is "
                    + "rejected rather than writing an untagged literal. To state the constraint in a "
                    + "second language, call constraint_update afterwards with that language.",
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
        final Constraint created = addConstraint.add(project.id(),
                new NewConstraint(title, statement, ConstraintType.valueOf(type), blankToNull(language)),
                project.defaultLanguage());
        return presenter.format(created);
    }

    @McpTool(name = "constraint_list", description = "List all managed constraints.",
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
        final ProjectId projectId = project.id();
        // No explicit displayLocale tool argument to merge against here, unlike constraint_get -
        // every listed constraint's title/statement is read straight under the resolved project's
        // own configured default language, the same value constraint_add/constraint_update already
        // pass through for the write side (mirrors req_list, issue #281).
        final List<Constraint> all = listConstraints.list(projectId, project.defaultLanguage());
        if (all.isEmpty()) {
            return "(no constraints)";
        }
        return all.stream().map(presenter::format).reduce((a, b) -> a + "\n" + b).orElse("(no constraints)");
    }

    @McpTool(name = "constraint_get",
            description = "Fetch a single constraint by its identity (e.g. TCON-1, BCON-1, RCON-1).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Constraint identity, e.g. TCON-1, BCON-1 or RCON-1") final String id,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') to display the title and "
                    + "statement in, overriding the project's own configured default language for this one "
                    + "call. Falls back to the project default, then to the server's own default, then to an "
                    + "untagged literal, then deterministically to any literal the constraint carries.",
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
        final ConstraintCode code = new ConstraintCode(id);
        final String effective = effectiveDisplayLocale(project, displayLocale);
        return getConstraint.get(project.id(), code, effective)
                .map(presenter::format)
                .orElse("Constraint not found: " + code.value());
    }

    @McpTool(name = "constraint_update",
            description = "Correct an already-created constraint's title and/or statement, or state either "
                    + "of them in a further language. Every text argument is optional - an omitted one leaves "
                    + "that field unchanged. Cannot change the constraint's type or code (TCON-/BCON-/RCON-): "
                    + "those are fixed at creation, and a retyped constraint would need a new code that "
                    + "everything already referencing it would not follow.")
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Constraint identity, e.g. TCON-1, BCON-1 or RCON-1") final String id,
            @McpToolParam(description = "New short human-readable summary (optional, unchanged if omitted)",
                    required = false)
            final String title,
            @McpToolParam(description = "New one-sentence statement (optional, unchanged if omitted)",
                    required = false)
            final String statement,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'en') a non-omitted title/"
                    + "statement is written in. Falls back to the project's configured default language "
                    + "(see constraint_add's same parameter) if omitted; if the project has no default "
                    + "either, the call is rejected rather than writing an untagged literal. Only the "
                    + "existing literal carrying the tag actually written is replaced - every other language "
                    + "variant of a field being corrected survives untouched, except a stale untagged one "
                    + "left over from before a language was ever supplied, which is swept away when the "
                    + "resolved tag equals the project's default. This is the way to make an existing, "
                    + "single-language constraint bilingual: restate its text under the second tag.",
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
        final ConstraintCode code = new ConstraintCode(id);
        final Constraint updated = updateConstraint.update(project.id(), code, blankToNull(title),
                blankToNull(statement), blankToNull(language), project.defaultLanguage());
        return presenter.format(updated);
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
