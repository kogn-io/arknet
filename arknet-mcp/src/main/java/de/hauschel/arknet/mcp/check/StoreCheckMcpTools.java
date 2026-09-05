// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.check;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.mcp.store.AnchorContext;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.StoreReader;

/**
 * The read-only checking tool of the composition root: {@code store_check}
 * (kogn-io/arknet#412).
 *
 * <p><strong>One tool with a selector, not one tool per rule.</strong> Same shape and same reason
 * as {@code store_overview}/{@code resource_get} (ADR-006): a check reads whatever the seven
 * bounded contexts wrote, over the very same generic {@link StoreReader} snapshot, and is not a
 * bounded context of its own - so it belongs here rather than in any hexagon, and its rules belong
 * behind one {@code checks} parameter rather than behind one tool name each. Every additional tool
 * name costs every agent context on every call, whether it runs that check or not.</p>
 *
 * <p><strong>Nothing here writes.</strong> No field is filled in, no language is generated, nothing
 * is refused - a finding is something to read, and what follows from it stays with the caller. That
 * is also why the tool is declared {@code readOnlyHint}.</p>
 */
public final class StoreCheckMcpTools {

    private final StoreReader storeReader;
    private final StoreCheckRenderer renderer;
    private final ProjectResolver projects;

    /**
     * @param storeReader the generic store read path, shared with {@code store_overview} and the
     *                    traceability tools rather than a second query of its own
     * @param prefixes    the CURIE resolver, shared for the same reason
     * @param projects    resolves each call's target project - and, with it, the maintained
     *                    language set the language check compares the store against
     */
    public StoreCheckMcpTools(final StoreReader storeReader, final Prefixes prefixes,
            final ProjectResolver projects) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        this.renderer = new StoreCheckRenderer(Objects.requireNonNull(prefixes, "prefixes"));
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @McpTool(name = "store_check", description = "Check this project's stored model against what the "
            + "project declares about itself, and report what is decidable; it reads only, changes "
            + "nothing and refuses nothing. Select rules with 'checks'; omit it to run all of them. "
            + "Today there is exactly one: LANGUAGE reports every field that carries at least one "
            + "language-tagged value but not one for each language the project maintains "
            + "(project_update languages) - one row per resource and field, with the missing tags. If "
            + "the project declares no maintained language set, LANGUAGE says so instead of reporting "
            + "a clean result: with no declared set there is no target state, and a field written in "
            + "one language is not incomplete against anything. What LANGUAGE does NOT see: a field "
            + "carrying no language-tagged value at all - a single untagged value, or a field never "
            + "written - is indistinguishable from a field that is simply not multilingual and is "
            + "never reported; and it judges presence per language only, never whether one language's "
            + "text is a current translation of another's. Further checks fold in here rather than "
            + "arriving as new tools (kogn-io/arknet#473); orphan_check is still its own tool for now.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String storeCheck(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Which checks to run, as a list of names. Allowed: LANGUAGE. "
                    + "Omit the parameter (or pass an empty list) to run every check - which is what "
                    + "most callers want, since the set is small and each is cheap.", required = false)
            final List<String> checks,
            @McpToolParam(description = "Optional anchor identifying the project to check, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project =
                AnchorContext.resolveResolvedProject(context, projectAnchor, projects);
        final List<StoreCheckKind> selected = select(checks);
        final List<String> sections = new ArrayList<>(selected.size());
        for (final StoreCheckKind kind : selected) {
            sections.add(switch (kind) {
                case LANGUAGE -> renderer.languageSection(project.maintainedLanguages(),
                        LanguageGapCheck.run(storeReader.readSnapshot(project.id()),
                                project.maintainedLanguages()));
            });
        }
        return renderer.report(selected, sections);
    }

    /**
     * Resolves the {@code checks} argument: an omitted or empty list means every check, an entry
     * that names no check is a caller error naming the allowed values rather than a silently
     * skipped rule - a check nobody notices did not run is worse than a rejected call.
     *
     * <p>Package-private rather than private so it can be pinned without a Spring context: the
     * selection is pure argument handling and has nothing to do with a store, a project or a
     * transport.</p>
     */
    static List<StoreCheckKind> select(final List<String> checks) {
        if (checks == null || checks.stream().allMatch(check -> check == null || check.isBlank())) {
            return List.of(StoreCheckKind.values());
        }
        final LinkedHashSet<StoreCheckKind> selected = new LinkedHashSet<>();
        for (final String check : checks) {
            if (check != null && !check.isBlank()) {
                selected.add(StoreCheckKind.parse(check));
            }
        }
        return List.copyOf(selected);
    }
}
