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
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintType;

/**
 * Driving (in) adapter of the requirements component's constraint side: exposes the constraint
 * use-cases as MCP tools ({@code constraint_add}, {@code constraint_list}, {@code constraint_get})
 * and delegates each tool call to the corresponding in-port. A separate class from
 * {@link RequirementMcpTools} - not merged into it - because a {@link Constraint} is a distinct
 * resource type of this same hexagon (issue #223), not a facet of {@link
 * de.hauschel.arknet.req.domain.Requirement}; {@code req_link_constraint} itself stays on
 * {@link RequirementMcpTools} because it mutates the requirement, not the constraint.
 *
 * <p>Mirrors {@link RequirementMcpTools}'s conventions exactly: identity vs. code (every tool
 * takes a plain {@code String} code, never the opaque {@link
 * de.hauschel.arknet.req.domain.ConstraintId}), per-call project resolution via
 * {@link ProjectResolver}, and a separate {@link ConstraintPresenter} for rendering. No
 * {@code constraint_update}/{@code constraint_set_status} tool exists in this scope: a
 * {@link Constraint} is immutable once created (see {@link
 * de.hauschel.arknet.req.application.ConstraintService}).</p>
 */
public final class ConstraintMcpTools {

    private final AddConstraint addConstraint;
    private final ListConstraints listConstraints;
    private final GetConstraint getConstraint;
    private final ProjectResolver projects;
    private final ConstraintPresenter presenter = new ConstraintPresenter();

    /**
     * Creates the adapter with its three driving in-ports and the resolver that maps each call's
     * origin directory to a project.
     *
     * @param addConstraint   in-port backing {@code constraint_add}
     * @param listConstraints in-port backing {@code constraint_list}
     * @param getConstraint   in-port backing {@code constraint_get}
     * @param projects        resolves each call's target project from its origin directory
     */
    public ConstraintMcpTools(
            final AddConstraint addConstraint,
            final ListConstraints listConstraints,
            final GetConstraint getConstraint,
            final ProjectResolver projects) {
        this.addConstraint = Objects.requireNonNull(addConstraint, "addConstraint");
        this.listConstraints = Objects.requireNonNull(listConstraints, "listConstraints");
        this.getConstraint = Objects.requireNonNull(getConstraint, "getConstraint");
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

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "constraint_add", description = "Register a new constraint (technical, business or "
            + "regulatory) - a non-negotiable, externally-imposed boundary on the solution space (ISO 29148).")
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Short human-readable summary of the constraint") final String title,
            @McpToolParam(description = "The constraint in one sentence, e.g. 'Must run on the JVM' or "
                    + "'Personal data must stay in the EU'") final String statement,
            @McpToolParam(description = "Classification: TECHNICAL, BUSINESS or REGULATORY") final String type,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final Constraint created = addConstraint.add(project.id(),
                new NewConstraint(title, statement, ConstraintType.valueOf(type)));
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
        final ProjectId projectId = resolveProject(context, projectAnchor).id();
        final List<Constraint> all = listConstraints.list(projectId);
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
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor).id();
        final ConstraintCode code = new ConstraintCode(id);
        return getConstraint.get(projectId, code)
                .map(presenter::format)
                .orElse("Constraint not found: " + code.value());
    }
}
