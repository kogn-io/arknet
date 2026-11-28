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
import de.hauschel.arknet.resource.application.port.in.FindText;

/**
 * The MCP search tool of generic resource access: {@code text_search} finds a text in every literal
 * of a project and names the resource each match belongs to.
 *
 * <p>It renders what {@link FindText} returns and names no resource type. <strong>Always
 * offered</strong>, like {@link ResourceReadMcpTools}: it does not depend on
 * {@value ResourceAccessMcpTools#ENABLED_PROPERTY} and writes nothing.</p>
 */
public final class TextSearchMcpTools {

    private final FindText finder;
    private final ProjectResolver projects;

    /**
     * Creates the tool.
     *
     * @param finder   the search in-port
     * @param projects resolves each call's target project from its anchor
     */
    public TextSearchMcpTools(final FindText finder, final ProjectResolver projects) {
        this.finder = Objects.requireNonNull(finder, "finder");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /**
     * Searches a project's literals.
     *
     * @param context       the request context carrying the anchor
     * @param query         the substring to find
     * @param type          a resource class by prefixed name or label, blank for every resource
     * @param projectAnchor an explicit anchor overriding the context's
     * @return the matches grouped by resource
     */
    @McpTool(name = "text_search", description = "Free-text search over every literal of this "
            + "project - every field, every language tag, substring match, case-insensitive. "
            + "Finds text impact_analysis cannot see: that tool only follows a fixed set of "
            + "edges, so a mention with no usesTerm/addressesRequirement/... edge to back it is "
            + "invisible to it. Each hit names the resource it was found under as the handle "
            + "resource_get reads (its business code), the matched field, and a short snippet. A "
            + "hit on a child a resource owns - a step, an acceptance criterion - is reported under "
            + "its owning resource instead, with the field it was reached through. Hits are "
            + "grouped by resource; at most " + FindText.LIMIT + " matches are shown and the rest "
            + "is counted, never cut silently - narrow the query or pass a type. Empty query is "
            + "rejected.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String textSearch(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The substring to search for. Case-insensitive, matched "
                    + "against every literal regardless of language tag. Must not be blank.")
            final String query,
            @McpToolParam(description = "Optional: only matches in resources of this resource class, as "
                    + "prefixed name or label (describe lists them); omit for every resource",
                    required = false)
            final String type,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        ResolvedProject project = ProjectAnchors.resolve(projects, context, projectAnchor);
        return TextSearchRenderer.render(finder.find(project.id(), query, type));
    }
}
