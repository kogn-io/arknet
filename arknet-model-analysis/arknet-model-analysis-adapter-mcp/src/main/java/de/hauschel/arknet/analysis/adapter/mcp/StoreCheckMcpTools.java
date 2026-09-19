// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.adapter.mcp;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.analysis.application.port.in.ReadModelSnapshot;
import de.hauschel.arknet.analysis.application.port.in.ReadTraceabilityGraph;
import de.hauschel.arknet.analysis.domain.LanguageGapCheck;
import de.hauschel.arknet.analysis.domain.OrphanCheck;
import de.hauschel.arknet.analysis.domain.RoleTermDuplicateCheck;
import de.hauschel.arknet.analysis.domain.StepAcceptanceCheck;
import de.hauschel.arknet.analysis.domain.StoreCheckKind;
import de.hauschel.arknet.analysis.domain.TraceabilityGraph;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.mcpsupport.ToolParameterDescriptions;
import de.hauschel.arknet.persistence.Prefixes;
import de.hauschel.arknet.persistence.StoreSnapshot;

/**
 * The read-only checking tool of the composition root: {@code store_check}
 * (kogn-io/arknet#412).
 *
 * <p><strong>One tool with a selector, not one tool per rule.</strong> Same shape and same reason
 * as {@code store_overview}/{@code resource_get}: a check reads whatever the seven
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

    private final ReadModelSnapshot snapshots;
    private final ReadTraceabilityGraph graphs;
    private final StoreCheckRenderer renderer;
    private final ProjectResolver projects;
    private final DisplayLocale displayLocale;

    /**
     * @param snapshots     the in-port that reads one project's model as a raw snapshot of the
     *                      published language, rather than a query of this tool's own
     * @param graphs        the in-port that reads the traced model of one project - needed only by
     *                      ORPHAN, which is why LANGUAGE/ROLE_TERM_DUPLICATE/STEP_ACCEPTANCE stay on
     *                      the raw {@code snapshots} read instead: a language check that resolved
     *                      each field to one display language would only ever see the language it
     *                      resolved to, which is precisely the language it must not assume
     * @param prefixes      the CURIE resolver
     * @param projects      resolves each call's target project - and, with it, the maintained
     *                      language set the language check compares the store against
     * @param displayLocale the process-wide display-language fallback ORPHAN needs to resolve a
     *                      term's label and match it in prose, shared with {@code trace_matrix}/
     *                      {@code impact_analysis} (issue #274) - see {@link #readGraph}
     */
    public StoreCheckMcpTools(final ReadModelSnapshot snapshots, final ReadTraceabilityGraph graphs,
            final Prefixes prefixes, final ProjectResolver projects, final DisplayLocale displayLocale) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
        this.graphs = Objects.requireNonNull(graphs, "graphs");
        this.renderer = new StoreCheckRenderer(Objects.requireNonNull(prefixes, "prefixes"));
        this.projects = Objects.requireNonNull(projects, "projects");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
    }

    @McpTool(name = "store_check", description = "Check this project's stored model against what the "
            + "project declares about itself, and report what is decidable; it reads only, changes "
            + "nothing and refuses nothing. Select rules with 'checks'; omit it to run all of them. "
            + "Four checks exist today. LANGUAGE reports every field that carries at least one "
            + "language-tagged value but not one for each language the project maintains "
            + "(project_update languages) - one row per resource and field, with the missing tags. If "
            + "the project declares no maintained language set, LANGUAGE says so instead of reporting "
            + "a clean result: with no declared set there is no target state, and a field written in "
            + "one language is not incomplete against anything. What LANGUAGE does NOT see: a field "
            + "carrying no language-tagged value at all - a single untagged value, or a field never "
            + "written - is indistinguishable from a field that is simply not multilingual and is "
            + "never reported; and it judges presence per language only, never whether one language's "
            + "text is a current translation of another's. ROLE_TERM_DUPLICATE reports every role "
            + "(arkproc:Role, role_add) and glossary term (skos:Concept, term_add) that carry the same "
            + "name, compared case-insensitively and trimmed across every language variant of the "
            + "role's name against the term's prefLabel - a report only, never a rejection: the two "
            + "resource types stay independent of each other (kogn-io/arknet#512). STEP_ACCEPTANCE "
            + "reports every main-flow use-case step (arkreq:mainStep) that no acceptance criterion "
            + "stands behind, walking the two-hop path step -> arkreq:stepRealises -> requirement -> "
            + "arkreq:acceptanceCriterion, and keeps its two cases apart: a step realising no "
            + "requirement at all is a missing edge, a step whose realised requirements carry no "
            + "criterion is an incomplete requirement. One criterion on one realised requirement is "
            + "enough for the step to count as covered. What STEP_ACCEPTANCE does NOT see: extension "
            + "steps, which carry no realises edge at tool level (kogn-io/arknet#317) and are out of "
            + "scope rather than reported; whether a criterion actually covers the step, which is a "
            + "reading and not a check; and a use case with no business code of its own, which is "
            + "skipped rather than named by a guessed handle. ORPHAN reports every orphaned artifact "
            + "(kogn-io/arknet#473): a requirement no use case realises, a glossary term never "
            + "referenced (neither used by a requirement, a use case or an architecture decision "
            + "(arkarch:usesTerm), a bounded context's ubiquitous language, nor another term's "
            + "skos:broader or skos:related), a requirement's, use case's, bounded context's or "
            + "architecture decision's text naming a term without the matching "
            + "usesTerm/primaryRole/supportingRole/ubiquitousLanguageTerm edge, or a term's own "
            + "skos:definition naming another term without a skos:broader or skos:related edge - and "
            + "a constraint no requirement or use case is bound by via constrainedBy. What ORPHAN does "
            + "NOT see: the text-mention match is literal and whole-word, not stem-based, so it also "
            + "flags everyday words used in their ordinary sense - a hit there is a reading hint for a "
            + "human, not a finding that demands an edge. orphan_check is a deprecated alias for this "
            + "check; further checks fold in here too rather than arriving as new tools.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String storeCheck(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Which checks to run, as a list of names. Allowed: LANGUAGE, "
                    + "ROLE_TERM_DUPLICATE, STEP_ACCEPTANCE, ORPHAN. Omit the parameter (or pass an empty list) to "
                    + "run every check - which is what most callers want, since the set is small and each is "
                    + "cheap.", required = false)
            final List<String> checks,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final List<StoreCheckKind> selected = select(checks);
        final Supplier<StoreSnapshot> snapshot = memoize(() -> snapshots.read(project.id()));
        final List<String> sections = new ArrayList<>(selected.size());
        for (final StoreCheckKind kind : selected) {
            sections.add(switch (kind) {
                case LANGUAGE -> renderer.languageSection(project.maintainedLanguages(),
                        LanguageGapCheck.run(snapshot.get(), project.maintainedLanguages()));
                case ROLE_TERM_DUPLICATE ->
                        renderer.roleTermDuplicateSection(RoleTermDuplicateCheck.run(snapshot.get()));
                case STEP_ACCEPTANCE ->
                        renderer.stepAcceptanceSection(StepAcceptanceCheck.run(snapshot.get()));
                case ORPHAN -> renderer.orphanSection(OrphanCheck.run(readGraph(project)));
            });
        }
        return renderer.report(selected, sections);
    }

    /**
     * Reads the traceability graph for {@code project}, resolving labels under the requesting
     * project's own configured default language rather than this class's process-wide, per-daemon
     * {@link #displayLocale} - the same merge {@code trace_matrix}/{@code orphan_check} already
     * apply via {@code TraceabilityMcpTools#readGraph} (issue #274). Only ORPHAN calls this; the
     * other three checks stay on the raw snapshot.
     */
    private TraceabilityGraph readGraph(final ResolvedProject project) {
        final DisplayLocale effective = displayLocale.withRequestedOverride(project.defaultLanguage());
        return graphs.read(project.id(), effective);
    }

    /**
     * Wraps {@code delegate} so it runs at most once: the first {@link Supplier#get()} call
     * computes and caches the value, every later call returns the cached one. Used to defer the
     * raw {@link StoreSnapshot} read until a check actually needs it (see the {@link #graphs}
     * parameter Javadoc) without duplicating, in a second place, which {@link StoreCheckKind}
     * branch of {@link #storeCheck} that is - a selection of ORPHAN alone must not pay for a
     * snapshot read it never uses, and a future branch that starts reading the snapshot cannot
     * forget to say so, because there is nowhere else left to say it. Not thread-safe by design:
     * each call to {@link #storeCheck} builds and consumes its own instance sequentially, on one
     * thread.
     */
    private static <T> Supplier<T> memoize(final Supplier<T> delegate) {
        return new Supplier<>() {

            private T value;
            private boolean computed;

            @Override
            public T get() {
                if (!computed) {
                    value = delegate.get();
                    computed = true;
                }
                return value;
            }
        };
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

    /**
     * Extracts the calling client's project anchor from the per-call transport context.
     * Null-tolerant on every hop; a {@code null} result is a caller error at {@link
     * ProjectResolver}, never a route to a default - the same shape every driving adapter of a
     * bounded context carries.
     */
    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }

    /**
     * Resolves the project a call targets: the explicit {@code projectAnchor} argument if the
     * caller supplied one, otherwise the anchor its transport carried.
     */
    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }
}
