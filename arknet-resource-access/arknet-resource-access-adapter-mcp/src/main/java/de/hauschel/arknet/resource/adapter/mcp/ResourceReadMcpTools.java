// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.mcpsupport.ToolParameterDescriptions;
import de.hauschel.arknet.resource.application.port.in.ReadResources;
import de.hauschel.arknet.resource.application.port.in.ResourceHistory;

/**
 * The MCP read tools of generic resource access: {@code resource_get} reads one or more resources
 * by handle, {@code resource_history} the history of one (ADR-60).
 *
 * <p>Both render what the in-ports return and name no resource type: the fields, their order, the
 * owned children, the labels and the merged inverse and symmetric edges come from the shapes and
 * the ontology through {@link ReadResources} and {@link ResourceHistory}.</p>
 *
 * <p><strong>Always offered.</strong> Unlike {@link ResourceAccessMcpTools}, these tools do not
 * depend on {@value ResourceAccessMcpTools#ENABLED_PROPERTY}: they replace the store read tools of
 * the same names, which every daemon offered, and they write nothing.</p>
 */
public final class ResourceReadMcpTools {

    /** The most handles one {@code resource_get} call reads. */
    static final int MAX_HANDLES = 50;

    private static final Pattern HANDLE_SEPARATOR = Pattern.compile("[,\\s]+");

    private final ReadResources reader;
    private final ResourceHistory histories;
    private final ProjectResolver projects;

    /**
     * Creates the tools.
     *
     * @param reader    the reading in-port
     * @param histories the history in-port
     * @param projects  resolves each call's target project from its anchor
     */
    public ResourceReadMcpTools(final ReadResources reader, final ResourceHistory histories,
            final ProjectResolver projects) {
        this.reader = Objects.requireNonNull(reader, "reader");
        this.histories = Objects.requireNonNull(histories, "histories");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /**
     * Reads one or more resources by handle.
     *
     * @param context       the request context carrying the anchor
     * @param id            one or more handles, separated by commas or white space
     * @param displayLocale the display language, blank for the project's default
     * @param allLanguages  whether a multilingual field shows every language instead of one
     * @param projectAnchor an explicit anchor overriding the context's
     * @return the rendered resources
     */
    @McpTool(name = "resource_get",
            description = "Read one or more resources of any type in one call, rendered from their shapes:"
                    + " a heading with business code, type and label, the status, when the resource was"
                    + " created and last changed, its fields in the order describe lists them, the children"
                    + " it owns in their order with their own fields, every edge as the target's business"
                    + " code and label, statements outside the shapes, and the incoming edges grouped by"
                    + " property with the code and label of the resource they come from. Inverse and"
                    + " symmetric edges the ontology declares are merged into one field. A multilingual"
                    + " field shows one value in the display language, marked [fallback: tag] when it is"
                    + " not in that language. A deleted resource - or a code it kept, like TERM-12 - answers"
                    + " with when it was deleted and the fields of its last version. A handle is a business"
                    + " code (FR-1), a full IRI, or a prefixed name over the prefixes describe lists; a"
                    + " handle that names nothing answers NOT FOUND with the reason and does not fail the"
                    + " others. Up to " + MAX_HANDLES + " handles per call.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String resourceGet(
            final McpSyncRequestContext context,
            @McpToolParam(description = "One or more resource handles, separated by commas or spaces: business"
                    + " code (FR-1), full IRI, or prefixed name; at most " + MAX_HANDLES)
            final String id,
            @McpToolParam(description = ToolParameterDescriptions.DISPLAY_LOCALE_DESCRIPTION, required = false)
            final String displayLocale,
            @McpToolParam(description = "Optional: true shows every language of a multilingual field instead"
                    + " of one value in the display language", required = false)
            final Boolean allLanguages,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        List<String> handles = handles(id);
        ResolvedProject project = ProjectAnchors.resolve(projects, context, projectAnchor);
        return ReadingRenderer.readings(reader.read(project.id(), handles, locale(displayLocale, project),
                Boolean.TRUE.equals(allLanguages)));
    }

    /**
     * Reads the history of one resource.
     *
     * @param context       the request context carrying the anchor
     * @param id            the handle
     * @param displayLocale the display language of a deleted resource's last version
     * @param projectAnchor an explicit anchor overriding the context's
     * @return the rendered history
     */
    @McpTool(name = "resource_history",
            description = "History of ONE resource of any type: its publish state, when it was created and"
                    + " last changed, and every entry the versioning recorded for it, oldest first - a"
                    + " publish naming a content-addressed version, or the tombstone of a delete. A deleted"
                    + " resource (or a code it kept, like TERM-12) shows when it was deleted and the fields"
                    + " of its last version; for a deletion from before versioning only that it was deleted,"
                    + " with no entries. Every write publishes a version at once; there is no separate"
                    + " publish step. A resource written past the tools has an empty history - not an"
                    + " error. The handle is a business code (FR-1), a full IRI, or a prefixed name over"
                    + " the prefixes describe lists.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String resourceHistory(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Resource handle: business code (FR-1), full IRI, or prefixed name")
            final String id,
            @McpToolParam(description = ToolParameterDescriptions.DISPLAY_LOCALE_DESCRIPTION, required = false)
            final String displayLocale,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        ResolvedProject project = ProjectAnchors.resolve(projects, context, projectAnchor);
        return ReadingRenderer.history(histories.history(project.id(), id == null ? "" : id,
                locale(displayLocale, project)));
    }

    /**
     * Splits the {@code id} argument into handles.
     *
     * @param id the argument
     * @return the handles, in their order
     * @throws IllegalArgumentException if it names no handle or more than {@value #MAX_HANDLES}
     */
    static List<String> handles(final String id) {
        List<String> handles = id == null ? List.of()
                : Arrays.stream(HANDLE_SEPARATOR.split(id.strip())).filter(handle -> !handle.isEmpty()).toList();
        if (handles.isEmpty()) {
            throw new IllegalArgumentException("Pass at least one handle: a business code such as FR-1, a full IRI"
                    + " or a prefixed name.");
        }
        if (handles.size() > MAX_HANDLES) {
            throw new IllegalArgumentException("At most " + MAX_HANDLES + " handles per call, got " + handles.size()
                    + "; split the handles over several calls.");
        }
        return handles;
    }

    private static String locale(final String displayLocale, final ResolvedProject project) {
        return displayLocale != null && !displayLocale.isBlank() ? displayLocale : project.defaultLanguage();
    }
}
