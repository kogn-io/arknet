// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.check;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

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

    /** A silently skipped rule is worse than a rejected call: nobody notices a check that did not run. */
    @Test
    void rejectsAnUnknownSelectorAndNamesTheAllowedValues() {
        assertThatThrownBy(() -> StoreCheckMcpTools.select(List.of("orphans")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("orphans")
                .hasMessageContaining("LANGUAGE");
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
}
