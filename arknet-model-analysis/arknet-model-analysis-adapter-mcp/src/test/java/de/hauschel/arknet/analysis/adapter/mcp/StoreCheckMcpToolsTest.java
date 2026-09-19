// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.adapter.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.analysis.application.port.in.ReadModelSnapshot;
import de.hauschel.arknet.analysis.application.port.in.ReadTraceabilityGraph;
import de.hauschel.arknet.analysis.domain.StoreCheckKind;
import de.hauschel.arknet.analysis.domain.TraceabilityGraph;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.persistence.Prefixes;
import de.hauschel.arknet.persistence.StoreSnapshot;

/**
 * Unit tests for {@code store_check}'s tool surface: which checks a {@code checks} argument
 * selects, and what the declared schema promises an agent.
 *
 * <p>The tool description is asserted here on purpose. Only the schema reliably reaches an agent -
 * a limit documented in a {@code CLAUDE.md} does not - so "the description names what the check
 * cannot see" is a property of the product, not of the documentation, and belongs in a test.</p>
 */
class StoreCheckMcpToolsTest {

    @Test
    void runsEveryCheckWhenTheSelectorIsOmittedOrEmpty() {
        assertThat(StoreCheckMcpTools.select(null)).containsExactly(StoreCheckKind.values());
        assertThat(StoreCheckMcpTools.select(List.of())).containsExactly(StoreCheckKind.values());
        assertThat(StoreCheckMcpTools.select(Arrays.asList(" ", null)))
                .containsExactly(StoreCheckKind.values());
    }

    @Test
    void acceptsASelectorRegardlessOfCasingAndCollapsesARepeatedOne() {
        assertThat(StoreCheckMcpTools.select(List.of("language", "LANGUAGE")))
                .containsExactly(StoreCheckKind.LANGUAGE);
    }

    @Test
    void acceptsSeveralDistinctSelectorsInTheOrderGiven() {
        assertThat(StoreCheckMcpTools.select(List.of("role_term_duplicate", "language")))
                .containsExactly(StoreCheckKind.ROLE_TERM_DUPLICATE, StoreCheckKind.LANGUAGE);
    }

    /** Point (c) of kogn-io/arknet#512: an omitted selector runs every check, this one included. */
    @Test
    void runsEveryDeclaredCheckWhenTheSelectorIsOmitted() {
        assertThat(StoreCheckMcpTools.select(null))
                .containsExactly(StoreCheckKind.LANGUAGE, StoreCheckKind.ROLE_TERM_DUPLICATE,
                        StoreCheckKind.STEP_ACCEPTANCE, StoreCheckKind.ORPHAN);
    }

    /** kogn-io/arknet#473: ORPHAN is selectable like every other check. */
    @Test
    void acceptsOrphanAsASelector() {
        assertThat(StoreCheckMcpTools.select(List.of("orphan"))).containsExactly(StoreCheckKind.ORPHAN);
    }

    /** A silently skipped rule is worse than a rejected call: nobody notices a check that did not run. */
    @Test
    void rejectsAnUnknownSelectorAndNamesTheAllowedValues() {
        assertThatThrownBy(() -> StoreCheckMcpTools.select(List.of("orphans")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orphans")
                .hasMessageContaining("LANGUAGE")
                .hasMessageContaining("ROLE_TERM_DUPLICATE")
                .hasMessageContaining("STEP_ACCEPTANCE")
                .hasMessageContaining("ORPHAN");
    }

    /**
     * PR #643 review, P2-2: a selection of ORPHAN alone must not read the raw snapshot -
     * ORPHAN reads only the traceability graph (see {@link StoreCheckMcpTools} constructor
     * Javadoc), and LANGUAGE/ROLE_TERM_DUPLICATE/STEP_ACCEPTANCE are the only checks that need it.
     */
    @Test
    void doesNotReadTheRawSnapshotWhenOnlyOrphanIsSelected() {
        final AtomicBoolean snapshotRead = new AtomicBoolean(false);
        final ReadModelSnapshot snapshots = projectId -> {
            snapshotRead.set(true);
            return StoreSnapshot.of(List.of());
        };
        final ReadTraceabilityGraph graphs =
                (projectId, locale) -> TraceabilityGraph.of(StoreSnapshot.of(List.of()), locale);
        final StoreCheckMcpTools tools = new StoreCheckMcpTools(snapshots, graphs, Prefixes.defaults(),
                anchor -> new ResolvedProject(new ProjectId("sample-project"), null, List.of()),
                DisplayLocale.DEFAULT);

        tools.storeCheck(null, List.of("ORPHAN"), "sample-project");

        assertThat(snapshotRead).isFalse();
    }

    @Test
    void declaresExactlyOneReadOnlyTool() {
        List<McpTool> tools = Arrays.stream(StoreCheckMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .toList();

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("store_check");
            assertThat(tool.annotations().readOnlyHint()).isTrue();
        });
    }

    @Test
    void statesInItsOwnDescriptionWhatTheLanguageCheckCannotSee() {
        McpTool tool = Arrays.stream(StoreCheckMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .findFirst()
                .orElseThrow();

        assertThat(tool.description())
                .contains("does NOT see")
                .contains("no language-tagged value at all")
                .contains("no maintained language set");
    }

    @Test
    void namesRoleTermDuplicateAndItsIntentInItsOwnDescription() {
        McpTool tool = Arrays.stream(StoreCheckMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .findFirst()
                .orElseThrow();

        assertThat(tool.description())
                .contains("ROLE_TERM_DUPLICATE")
                .contains("never a rejection");
    }

    /**
     * The scope limit of kogn-io/arknet#317 only reaches an agent through the schema: a caller
     * that does not know extension steps are out of scope reads a clean STEP_ACCEPTANCE section as
     * "every step is covered".
     */
    @Test
    void namesStepAcceptanceAndWhatItCannotSeeInItsOwnDescription() {
        McpTool tool = Arrays.stream(StoreCheckMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .findFirst()
                .orElseThrow();

        assertThat(tool.description())
                .contains("STEP_ACCEPTANCE")
                .contains("arkreq:stepRealises")
                .contains("does NOT see")
                .contains("extension steps");
    }

    /**
     * kogn-io/arknet#473: ORPHAN's own description names what it folds in from the former
     * {@code orphan_check} tool, and that {@code orphan_check} is now a deprecated alias.
     */
    @Test
    void namesOrphanAndPointsFromTheDeprecatedAliasInItsOwnDescription() {
        McpTool tool = Arrays.stream(StoreCheckMcpTools.class.getDeclaredMethods())
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .findFirst()
                .orElseThrow();

        assertThat(tool.description())
                .contains("ORPHAN")
                .contains("requirement no use case realises")
                .contains("orphan_check is a deprecated alias");
    }
}
