package de.hauschel.arknet.req.adapter.mcp;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.SetRequirementStatus;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Driving (in) adapter of the requirements component: exposes the requirement
 * use-cases as MCP tools ({@code req_add}, {@code req_list}, {@code req_get},
 * {@code req_set_status}) and delegates each tool call to the corresponding
 * in-port.
 *
 * <p>This adapter belongs to the requirements hexagon (symmetric to the out-adapter
 * {@code arknet-requirements-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods - the tool name, description
 * and JSON input schema are derived from the annotations and method signature, not
 * hand-written. This adapter does <strong>not</strong> bootstrap an MCP server or wire
 * any transport; that remains the concern of the composition root (arknet-mcp).</p>
 *
 * <p><strong>Registration.</strong> The composition root (arknet-mcp, a Spring Boot MCP
 * server on Spring AI 2.0) declares this class as a bean; the Spring AI MCP annotation
 * scanner discovers the {@code @McpTool} methods and registers them automatically. No
 * manual tool-specification bridge is needed - the earlier {@code tools()} adapter (which
 * pre-built {@code SyncToolSpecification}s for a raw-SDK composition root) was removed
 * with the arknet-mcp migration (#27).</p>
 *
 * <p><strong>Scaffold:</strong> the delegated in-ports are currently stubs throwing
 * {@link UnsupportedOperationException}; Spring AI maps any such exception to an error
 * {@code CallToolResult} automatically, so no manual try/catch is needed here.</p>
 *
 * <p><strong>Workspace (one server = one workspace).</strong> Every in-port takes a
 * {@link WorkspaceId} routing key. This adapter is single-user/local: it operates
 * against exactly one workspace - the {@code workspaceId} injected at construction -
 * and does not expose the workspace as a tool argument. The composition root resolves
 * that id per project (explicit config, else the git/working-directory name); see
 * {@code WorkspaceIdResolver}. TODO: a remote/team mode may instead expose the
 * workspace as an explicit tool argument or via MCP session context.</p>
 */
public final class RequirementMcpTools {

    private final AddRequirement addRequirement;
    private final ListRequirements listRequirements;
    private final GetRequirement getRequirement;
    private final SetRequirementStatus setRequirementStatus;
    private final WorkspaceId workspaceId;

    /**
     * Creates the adapter with its four driving in-ports and the workspace it serves.
     *
     * @param addRequirement       in-port backing {@code req_add}
     * @param listRequirements     in-port backing {@code req_list}
     * @param getRequirement       in-port backing {@code req_get}
     * @param setRequirementStatus in-port backing {@code req_set_status}
     * @param workspaceId          the single workspace all tool calls route to
     */
    public RequirementMcpTools(
            final AddRequirement addRequirement,
            final ListRequirements listRequirements,
            final GetRequirement getRequirement,
            final SetRequirementStatus setRequirementStatus,
            final WorkspaceId workspaceId) {
        this.addRequirement = Objects.requireNonNull(addRequirement, "addRequirement");
        this.listRequirements = Objects.requireNonNull(listRequirements, "listRequirements");
        this.getRequirement = Objects.requireNonNull(getRequirement, "getRequirement");
        this.setRequirementStatus = Objects.requireNonNull(setRequirementStatus, "setRequirementStatus");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "req_add", description = "Register a new requirement (functional or non-functional).")
    public String add(
            @McpToolParam(description = "Short human-readable summary of the requirement") final String title,
            @McpToolParam(description = "The normative statement, e.g. 'The system shall ...'")
            final String description,
            @McpToolParam(description = "Classification: FUNCTIONAL or NON_FUNCTIONAL") final String type,
            @McpToolParam(description = "MoSCoW priority (optional): MUST_HAVE, SHOULD_HAVE, COULD_HAVE or "
                    + "WONT_HAVE", required = false)
            final String priority,
            @McpToolParam(description = "IRI of the arkreq:Goal this requirement is motivated by (optional)",
                    required = false)
            final String motivatedBy,
            @McpToolParam(description = "Free-text quality category (optional, e.g. performance, security, "
                    + "reliability); only meaningful for NON_FUNCTIONAL requirements", required = false)
            final String qualityCategory) {
        final RequirementType requirementType = RequirementType.valueOf(type);
        final Priority requirementPriority = blankToNull(priority) == null
                ? null
                : Priority.valueOf(priority.trim());
        final Requirement created = addRequirement.add(workspaceId,
                new NewRequirement(title, description, requirementType, requirementPriority,
                        blankToNull(motivatedBy), blankToNull(qualityCategory)));
        return format(created);
    }

    @McpTool(name = "req_list", description = "List all managed requirements.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list() {
        final List<Requirement> all = listRequirements.list(workspaceId);
        return all.stream().map(RequirementMcpTools::format)
                .reduce((a, b) -> a + "\n" + b).orElse("(no requirements)");
    }

    @McpTool(name = "req_get", description = "Fetch a single requirement by its identity (e.g. FR-1, NFR-7).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            @McpToolParam(description = "Requirement identity, e.g. FR-1 or NFR-7") final String id) {
        final RequirementId requirementId = new RequirementId(id);
        return getRequirement.get(workspaceId, requirementId)
                .map(RequirementMcpTools::format)
                .orElse("Requirement not found: " + requirementId.value());
    }

    @McpTool(name = "req_set_status", description = "Change the lifecycle status of a requirement.")
    public String setStatus(
            @McpToolParam(description = "Requirement identity, e.g. FR-1 or NFR-7") final String id,
            @McpToolParam(description = "Target status: PROPOSED or ACCEPTED") final String status) {
        final RequirementId requirementId = new RequirementId(id);
        final RequirementStatus requirementStatus = RequirementStatus.valueOf(status);
        final Requirement updated =
                setRequirementStatus.setStatus(workspaceId, requirementId, requirementStatus);
        return format(updated);
    }

    private static String format(final Requirement r) {
        final String priority = r.priority() == null ? "" : " {" + r.priority() + "}";
        return "%s [%s] %s (%s)%s".formatted(r.id().value(), r.type(), r.title(), r.status(), priority);
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
