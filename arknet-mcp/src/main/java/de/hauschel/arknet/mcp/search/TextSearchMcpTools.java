// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.search;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.mcp.store.AnchorContext;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreReader;
import de.hauschel.arknet.mcp.store.Triple;

/**
 * The free-text search tool of the composition root: {@code text_search} (kogn-io/arknet#594
 * Part 1).
 *
 * <p><strong>Composition-root In-Adapter, no domain of its own</strong> - the same shape and
 * reasoning as {@code store_check}/{@code store_overview}: it reads whatever the seven bounded
 * contexts wrote, over the very same {@link StoreReader}, and belongs here rather than in any one
 * hexagon.</p>
 *
 * <p><strong>What this sees that {@code impact_analysis} does not.</strong>
 * {@code impact_analysis} answers "what references this resource" over a fixed set of edges;
 * it is blind to prose. {@code text_search} answers "where does this text occur" over every
 * literal the project holds - the two are complementary, not overlapping.</p>
 */
public final class TextSearchMcpTools {

    /**
     * The most hits a single call renders. Search is meant to orient an agent toward a handful of
     * resources, not to page through the whole store - a query wide enough to exceed this should
     * be narrowed, not paginated.
     */
    static final int MAX_HITS = 200;

    private static final Comparator<Triple> DETERMINISTIC_ORDER = Comparator
            .comparing(Triple::subject)
            .thenComparing(Triple::predicate)
            .thenComparing(triple -> ((RdfNode.Literal) triple.object()).lexicalForm());

    private final StoreReader storeReader;
    private final Prefixes prefixes;
    private final ProjectResolver projects;

    /**
     * @param storeReader the generic store read path, shared with every other read-only tool
     *                    rather than a second query of its own
     * @param prefixes    the CURIE resolver, shared for the same reason
     * @param projects    resolves each call's target project
     */
    public TextSearchMcpTools(final StoreReader storeReader, final Prefixes prefixes, final ProjectResolver projects) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @McpTool(name = "text_search", description = "Free-text search over every literal of this "
            + "project - every field, every language tag, substring match, case-insensitive. "
            + "Finds text impact_analysis cannot see: that tool only follows a fixed set of "
            + "edges, so a mention with no usesTerm/addressesRequirement/... edge to back it is "
            + "invisible to it. Each hit names the resource it was found under as a business id "
            + "or CURIE, the matched field, and a short snippet. A hit on a Step or "
            + "AcceptanceCriterion - neither carries a business id of its own - is reported under "
            + "its owning use case or requirement instead, with the edge it was reached through. "
            + "Hits are grouped by resource; the result is capped (narrow the query if it says "
            + "so). Empty query is rejected.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String textSearch(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The substring to search for. Case-insensitive, matched "
                    + "against every literal regardless of language tag. Must not be blank.")
            final String query,
            @McpToolParam(description = "Optional anchor identifying the project to search, used "
                    + "INSTEAD of the anchor your transport sends in the X-Arknet-Project-Anchor header. "
                    + "Only needed for a client that cannot set that header - most callers should omit "
                    + "this. Must be an anchor already registered for the project; project_list shows "
                    + "what is registered.", required = false)
            final String projectAnchor) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException(
                    "query must not be blank - pass a non-empty substring to search for, e.g. \"login\"");
        }
        final ResolvedProject project = AnchorContext.resolveResolvedProject(context, projectAnchor, projects);
        final List<Triple> matches = storeReader.literalContaining(project.id(), query).stream()
                .sorted(DETERMINISTIC_ORDER)
                .toList();
        final List<Triple> capped = matches.size() > MAX_HITS ? matches.subList(0, MAX_HITS) : matches;
        final SearchHitResolver resolver = new SearchHitResolver(storeReader, prefixes, project.id());
        final List<SearchHit> hits = capped.stream().map(match -> resolver.resolve(match, query)).toList();
        return TextSearchRenderer.render(query, matches.size(), hits, MAX_HITS);
    }
}
