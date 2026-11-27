// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.mcpsupport.ToolParameterDescriptions;
import de.hauschel.arknet.resource.application.port.in.ListResources;

/**
 * The MCP list tool of generic resource access: {@code resource_list} lists the resources of a
 * project, of one type or of all, compact or in full (ADR-60).
 *
 * <p>It renders what {@link ListResources} returns and names no resource type: which types exist,
 * how they are labelled and which carry a status come from the shapes and the ontology.</p>
 *
 * <p><strong>Always offered</strong>, like {@link ResourceReadMcpTools}: it does not depend on
 * {@value ResourceAccessMcpTools#ENABLED_PROPERTY} and writes nothing.</p>
 */
public final class ResourceListMcpTools {

    private final ListResources lister;
    private final ProjectResolver projects;

    /**
     * Creates the tool.
     *
     * @param lister   the listing in-port
     * @param projects resolves each call's target project from its anchor
     */
    public ResourceListMcpTools(final ListResources lister, final ProjectResolver projects) {
        this.lister = Objects.requireNonNull(lister, "lister");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /**
     * Lists resources.
     *
     * @param context       the request context carrying the anchor
     * @param type          a resource class by prefixed name or label, blank for every type
     * @param status        a status by prefixed name or label, blank for any
     * @param full          whether each resource is rendered as {@code resource_get} renders it
     * @param offset        how many matching resources to skip
     * @param displayLocale the display language, blank for the project's default
     * @param projectAnchor an explicit anchor overriding the context's
     * @return the listing
     */
    @McpTool(name = "resource_list",
            description = "List the resources of the project in the order of their business codes: one line"
                    + " each with code, type, label and status, for one type or - without a type - for all"
                    + " of them. A type is a resource class by prefixed name or by label (describe lists"
                    + " them); a class above several types lists all of them. A status filter needs a type"
                    + " whose resources have a status field (describe shows its rules). With full=true each"
                    + " resource is rendered as resource_get renders it, " + ListResources.FULL_LIMIT
                    + " at most per call; a compact listing shows " + ListResources.COMPACT_LIMIT
                    + " at most. A listing never cuts silently: it says how many are not shown and the offset"
                    + " that continues it. A label not in the display language is marked [fallback: tag]; a"
                    + " resource without a label shows its type and code.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String resourceList(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional: the resource class to list, as prefixed name or label;"
                    + " omit for every type", required = false)
            final String type,
            @McpToolParam(description = "Optional: only resources in this status, as prefixed name or label;"
                    + " needs a type with a status field", required = false)
            final String status,
            @McpToolParam(description = "Optional: true renders each resource in full, as resource_get does, "
                    + ListResources.FULL_LIMIT + " at most per call", required = false)
            final Boolean full,
            @McpToolParam(description = "Optional: how many matching resources to skip, to continue a listing;"
                    + " default 0", required = false)
            final Integer offset,
            @McpToolParam(description = ToolParameterDescriptions.DISPLAY_LOCALE_DESCRIPTION, required = false)
            final String displayLocale,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        ResolvedProject project = ProjectAnchors.resolve(projects, context, projectAnchor);
        boolean wantFull = Boolean.TRUE.equals(full);
        return ListingRenderer.listing(lister.list(project.id(), type, status,
                ResourceReadMcpTools.locale(displayLocale, project), wantFull, offset == null ? 0 : offset),
                wantFull);
    }
}
