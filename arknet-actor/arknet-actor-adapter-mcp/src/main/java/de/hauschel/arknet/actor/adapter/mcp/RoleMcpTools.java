// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.actor.application.port.in.AddRole;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.DeleteRole;
import de.hauschel.arknet.actor.application.port.in.DescribeRoleDisplayFallback;
import de.hauschel.arknet.actor.application.port.in.GetRole;
import de.hauschel.arknet.actor.application.port.in.ListRoles;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.application.port.in.UpdateRole;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleDisplayFallback;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;

/**
 * Driving (in) adapter of the role resource type: exposes the role use-cases as MCP tools
 * ({@code role_add}, {@code role_list}, {@code role_get}, {@code role_update},
 * {@code role_delete}) and delegates each tool call to the corresponding in-port - the second
 * resource type of the actor hexagon (ADR-37/kogn-io/arknet#405), alongside {@link ActorMcpTools}
 * in the same package.
 *
 * <p><strong>Identity vs. code.</strong> Every tool takes a role identity as a plain {@code String}
 * - what a human types, e.g. {@code ROLE-1} - and maps it to a {@link RoleCode}, never to the
 * opaque {@link de.hauschel.arknet.actor.domain.RoleId}, mirroring {@link ActorMcpTools}. The
 * {@code filledBy} argument is likewise plain {@code String} business codes ({@code ACTOR-1}),
 * echoed back the same way in {@link RoleDetail#filledByActors()} - resolution to opaque identity
 * happens inside {@code RoleService}.</p>
 *
 * <p><strong>Language, mirroring {@link ConstraintMcpTools}-style adapters, not {@link
 * ActorMcpTools}.</strong> {@code role_add}/{@code role_update} take an optional {@code language};
 * {@code role_get}/{@code role_list} take an optional {@code displayLocale}, with the same
 * project-default fallback and inline {@code [fallback: ...]} marking {@code role_list} appends -
 * see {@link Role}'s own javadoc for why this hexagon's two resource types disagree here.</p>
 */
public final class RoleMcpTools {

    /** Mirrors {@link ActorMcpTools#PROSE_MARKUP} exactly. */
    private static final String PROSE_MARKUP = " Free-text fields accept a narrow Markdown subset:"
            + " **bold**, *italic*, `code`, lines starting with '- ' as a bullet list, and a blank line"
            + " for a new paragraph. Links, headings, tables and HTML are deliberately not interpreted -"
            + " a reference belongs in the model (an edge such as usesTerm), not in a hand-written link.";

    private final AddRole addRole;
    private final ListRoles listRoles;
    private final DescribeRoleDisplayFallback describeRoleDisplayFallback;
    private final GetRole getRole;
    private final UpdateRole updateRole;
    private final DeleteRole deleteRole;
    private final ProjectResolver projects;
    private final RolePresenter presenter = new RolePresenter();

    /**
     * Creates the adapter with its six driving in-ports and the resolver that maps each call's
     * anchor to a project.
     *
     * @param addRole                     in-port backing {@code role_add}
     * @param listRoles                   in-port backing {@code role_list}
     * @param describeRoleDisplayFallback in-port backing {@code role_list}'s fallback-visibility
     *                                    line
     * @param getRole                     in-port backing {@code role_get}
     * @param updateRole                  in-port backing {@code role_update}
     * @param deleteRole                  in-port backing {@code role_delete}
     * @param projects                    resolves each call's target project from the anchor it
     *                                    carries
     */
    public RoleMcpTools(
            final AddRole addRole,
            final ListRoles listRoles,
            final DescribeRoleDisplayFallback describeRoleDisplayFallback,
            final GetRole getRole,
            final UpdateRole updateRole,
            final DeleteRole deleteRole,
            final ProjectResolver projects) {
        this.addRole = Objects.requireNonNull(addRole, "addRole");
        this.listRoles = Objects.requireNonNull(listRoles, "listRoles");
        this.describeRoleDisplayFallback =
                Objects.requireNonNull(describeRoleDisplayFallback, "describeRoleDisplayFallback");
        this.getRole = Objects.requireNonNull(getRole, "getRole");
        this.updateRole = Objects.requireNonNull(updateRole, "updateRole");
        this.deleteRole = Objects.requireNonNull(deleteRole, "deleteRole");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /** Mirrors {@link ActorMcpTools#contextAnchor} exactly. */
    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }

    /** Mirrors {@link ActorMcpTools#resolveProject}, but resolves the full {@link ResolvedProject}. */
    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "role_add", description = "Register a new role: a named function in which someone or "
            + "something acts or holds an interest, named independently of who fills it (ADR-37). A role may "
            + "start unfilled - filledBy is optional. Use filledBy to name the actors (ACTOR-N) that occupy it "
            + "from the start; role_update replaces the occupancy later." + PROSE_MARKUP)
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "What this role is called, e.g. 'Requirements Engineer' (min. 2 characters)")
            final String name,
            @McpToolParam(description = "Free-text description of the role (optional)", required = false)
            final String description,
            @McpToolParam(description = "Business codes of the actors that fill this role from the start "
                    + "(e.g. ACTOR-1); optional, a role may start unfilled", required = false)
            final List<String> filledBy,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the name and description "
                    + "are written in. Falls back to the project's configured default language "
                    + "(project_update) if omitted; if the project has no default either, the call is "
                    + "rejected rather than writing an untagged literal. To state the role in a second "
                    + "language, call role_update afterwards with that language.", required = false)
            final String language,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final RoleDetail created = addRole.add(project.id(),
                new NewRole(name, blankToNull(description), filledBy, blankToNull(language)),
                project.defaultLanguage());
        return presenter.format(created);
    }

    @McpTool(name = "role_list", description = "List all managed roles. A role shown under a fallen-back "
            + "language (its name/description is missing in the requested/project-default language) carries "
            + "an inline [fallback: ...] tag naming the language actually shown - see displayLocale.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') to display every role's "
                    + "name and description in, overriding the project's own configured default language for "
                    + "this one call. Falls back to the project default, then to the server's own default, "
                    + "then to an untagged literal, then deterministically to any literal a role carries - a "
                    + "role whose shown variant is not this call's requested/project-default language is "
                    + "marked with an inline [fallback: ...] tag.", required = false)
            final String displayLocale,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final ProjectId projectId = project.id();
        final String effective = effectiveDisplayLocale(project, displayLocale);
        final List<RoleDetail> all = listRoles.list(projectId, effective);
        if (all.isEmpty()) {
            return "(no roles)";
        }
        final Map<RoleCode, RoleDisplayFallback> fallbacks = describeRoleDisplayFallback.describe(projectId, effective);
        return all.stream()
                .map(detail -> presenter.format(detail) + fallbackSuffix(fallbacks.get(detail.role().code())))
                .reduce((a, b) -> a + "\n" + b).orElse("(no roles)");
    }

    @McpTool(name = "role_get", description = "Fetch a single role by its identity (e.g. ROLE-1).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Role identity, e.g. ROLE-1") final String id,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') to display the name and "
                    + "description in, overriding the project's own configured default language for this one "
                    + "call. Falls back to the project default, then to the server's own default, then to an "
                    + "untagged literal, then deterministically to any literal the role carries.",
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
        final RoleCode code = new RoleCode(id);
        final String effective = effectiveDisplayLocale(project, displayLocale);
        return getRole.get(project.id(), code, effective)
                .map(presenter::format)
                .orElse("Role not found: " + code.value());
    }

    @McpTool(name = "role_update",
            description = "Correct an already-created role's name and/or description, state either in a "
                    + "further language, and/or replace who fills it. name/description are optional - an "
                    + "omitted one leaves that field unchanged; omitting the description does NOT remove it. "
                    + "filledBy uses its own tri-state: passing a list replaces the occupancy wholesale, "
                    + "passing an empty list removes every occupant, omitting it leaves it untouched. Cannot "
                    + "change the role's code (ROLE-N): it is fixed at creation, and everything already "
                    + "referring to the role refers to that code." + PROSE_MARKUP)
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Role identity, e.g. ROLE-1") final String id,
            @McpToolParam(description = "New name (optional, unchanged if omitted)", required = false)
            final String name,
            @McpToolParam(description = "New description (optional, unchanged if omitted)", required = false)
            final String description,
            @McpToolParam(description = "Business codes of the actors that should fill this role going "
                    + "forward (e.g. ACTOR-1), replacing the existing occupants wholesale. Pass an empty list "
                    + "to remove all of them; omit to leave them unchanged.", required = false)
            final List<String> filledBy,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'en') a non-omitted name/"
                    + "description is written in. Falls back to the project's configured default language "
                    + "(see role_add's same parameter) if omitted; if the project has no default either, the "
                    + "call is rejected rather than writing an untagged literal. Only the existing literal "
                    + "carrying the tag actually written is replaced - every other language variant of a "
                    + "field being corrected survives untouched, except a stale untagged one left over from "
                    + "before a language was ever supplied, which is swept away when the resolved tag equals "
                    + "the project's default. This is the way to make an existing, single-language role "
                    + "bilingual: restate its text under the second tag.", required = false)
            final String language,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final RoleCode code = new RoleCode(id);
        final RoleDetail updated = updateRole.update(project.id(), code, blankToNull(name), blankToNull(description),
                filledBy, blankToNull(language), project.defaultLanguage());
        return presenter.format(updated);
    }

    @McpTool(name = "role_delete",
            description = "Delete an already-created role and every triple it carries - not just a "
                    + "correction, the whole resource goes away. The code (ROLE-N) stays taken so it never "
                    + "names two different roles.")
    public String delete(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Role identity, e.g. ROLE-1") final String id,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final RoleCode code = new RoleCode(id);
        deleteRole.delete(project.id(), code);
        return "Deleted: " + code.value();
    }

    /** Mirrors {@code ConstraintMcpTools#fallbackSuffix} exactly, for {@link RoleDisplayFallback}. */
    private static String fallbackSuffix(final RoleDisplayFallback fallback) {
        if (fallback == null || fallback.isEmpty()) {
            return "";
        }
        final List<String> parts = new ArrayList<>();
        if (fallback.nameTag() != null) {
            parts.add("name=" + displayTag(fallback.nameTag()));
        }
        if (fallback.descriptionTag() != null) {
            parts.add("description=" + displayTag(fallback.descriptionTag()));
        }
        return " [fallback: " + String.join(", ", parts) + "]";
    }

    private static String displayTag(final String tag) {
        return tag.isEmpty() ? "untagged" : tag;
    }

    /** Mirrors {@code ToolArguments#effectiveDisplayLocale} exactly. */
    private static String effectiveDisplayLocale(final ResolvedProject project, final String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return project.defaultLanguage();
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
