package de.hauschel.arknet.req.adapter.mcp;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.SetRequirementStatus;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.WorkspaceId;

import io.modelcontextprotocol.server.McpServerFeatures.SyncToolSpecification;

/**
 * Driving (in) adapter of the requirements component: exposes the requirement
 * use-cases as MCP tools ({@code req_add}, {@code req_list}, {@code req_get},
 * {@code req_set_status}) and delegates each tool call to the corresponding
 * in-port.
 *
 * <p>This adapter belongs to the requirements hexagon (symmetric to the out-adapter
 * {@code requirements-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods - the tool name, description
 * and JSON input schema are derived from the annotations and method signature, not
 * hand-written. This adapter does <strong>not</strong> bootstrap an MCP server or wire
 * any transport; that remains the concern of the composition root (arknet-mcp).</p>
 *
 * <p><strong>Bridge to the (still raw-SDK) composition root.</strong> {@link #tools()}
 * turns the {@code @McpTool}-annotated methods on this instance into the same
 * {@link SyncToolSpecification} collectible unit the composition root already knows how
 * to register, using {@link SyncMcpToolProvider} (from {@code spring-ai-mcp-annotations})
 * instead of hand-built {@code McpSchema.Tool}/{@code JsonSchema} objects. Once arknet-mcp
 * itself migrates to Spring AI, this bridge method can likely be dropped in favor of
 * Spring's own bean auto-detection of {@code @McpTool} methods.</p>
 *
 * <p><strong>Scaffold:</strong> the delegated in-ports are currently stubs throwing
 * {@link UnsupportedOperationException}; {@link SyncMcpToolProvider} maps any such
 * exception to an error {@code CallToolResult} automatically, so no manual try/catch is
 * needed here.</p>
 *
 * <p><strong>Workspace (single-user default).</strong> Every in-port now takes a
 * {@link WorkspaceId} routing key. This adapter is single-user/local, so it always
 * calls the in-ports with {@link WorkspaceId#DEFAULT} and does not expose the
 * workspace as a tool argument. TODO: a remote/team mode will need to expose the
 * workspace, either as an explicit tool argument or via MCP session context.</p>
 */
public final class RequirementMcpTools {

    private final AddRequirement addRequirement;
    private final ListRequirements listRequirements;
    private final GetRequirement getRequirement;
    private final SetRequirementStatus setRequirementStatus;

    /**
     * Creates the adapter with its four driving in-ports.
     *
     * @param addRequirement       in-port backing {@code req_add}
     * @param listRequirements     in-port backing {@code req_list}
     * @param getRequirement       in-port backing {@code req_get}
     * @param setRequirementStatus in-port backing {@code req_set_status}
     */
    public RequirementMcpTools(
            final AddRequirement addRequirement,
            final ListRequirements listRequirements,
            final GetRequirement getRequirement,
            final SetRequirementStatus setRequirementStatus) {
        this.addRequirement = Objects.requireNonNull(addRequirement, "addRequirement");
        this.listRequirements = Objects.requireNonNull(listRequirements, "listRequirements");
        this.getRequirement = Objects.requireNonNull(getRequirement, "getRequirement");
        this.setRequirementStatus = Objects.requireNonNull(setRequirementStatus, "setRequirementStatus");
    }

    /**
     * Provider contract: the MCP tool specifications contributed by the requirements
     * component. A composition root collects these (together with other components')
     * and registers them on an MCP server.
     *
     * @return the four requirement tool specifications, never {@code null}
     */
    public List<SyncToolSpecification> tools() {
        return new SyncMcpToolProvider(List.of(this)).getToolSpecifications();
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "req_add", description = "Register a new requirement (functional or non-functional).")
    public String add(
            @McpToolParam(description = "Short human-readable summary of the requirement") final String title,
            @McpToolParam(description = "Classification: FUNCTIONAL or NON_FUNCTIONAL") final String type) {
        final RequirementType requirementType = RequirementType.valueOf(type);
        // TODO: single-user default; a remote/team mode must expose the workspace
        // as a tool argument or MCP session context instead of hard-coding DEFAULT.
        final Requirement created =
                addRequirement.add(WorkspaceId.DEFAULT, new NewRequirement(title, requirementType));
        return format(created);
    }

    @McpTool(name = "req_list", description = "List all managed requirements.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list() {
        // TODO: single-user default; a remote/team mode must expose the workspace
        // as a tool argument or MCP session context instead of hard-coding DEFAULT.
        final List<Requirement> all = listRequirements.list(WorkspaceId.DEFAULT);
        return all.stream().map(RequirementMcpTools::format)
                .reduce((a, b) -> a + "\n" + b).orElse("(no requirements)");
    }

    @McpTool(name = "req_get", description = "Fetch a single requirement by its identity (e.g. FR-1, NFR-7).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            @McpToolParam(description = "Requirement identity, e.g. FR-1 or NFR-7") final String id) {
        final RequirementId requirementId = new RequirementId(id);
        // TODO: single-user default; a remote/team mode must expose the workspace
        // as a tool argument or MCP session context instead of hard-coding DEFAULT.
        return getRequirement.get(WorkspaceId.DEFAULT, requirementId)
                .map(RequirementMcpTools::format)
                .orElse("Requirement not found: " + requirementId.value());
    }

    @McpTool(name = "req_set_status", description = "Change the lifecycle status of a requirement.")
    public String setStatus(
            @McpToolParam(description = "Requirement identity, e.g. FR-1 or NFR-7") final String id,
            @McpToolParam(description = "Target status: PROPOSED or ACCEPTED") final String status) {
        final RequirementId requirementId = new RequirementId(id);
        final RequirementStatus requirementStatus = RequirementStatus.valueOf(status);
        // TODO: single-user default; a remote/team mode must expose the workspace
        // as a tool argument or MCP session context instead of hard-coding DEFAULT.
        final Requirement updated =
                setRequirementStatus.setStatus(WorkspaceId.DEFAULT, requirementId, requirementStatus);
        return format(updated);
    }

    private static String format(final Requirement r) {
        return "%s [%s] %s (%s)".formatted(r.id().value(), r.type(), r.title(), r.status());
    }
}
