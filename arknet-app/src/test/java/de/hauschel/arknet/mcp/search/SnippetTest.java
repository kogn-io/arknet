// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.search;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link Snippet#around(String, String)}. */
class SnippetTest {

    @Test
    void keepsTheWholeTextWhenItIsShorterThanTheRadiusOnEachSide() {
        assertThat(Snippet.around("Login succeeds", "succeeds")).isEqualTo("Login succeeds");
    }

    @Test
    void addsAnEllipsisOnBothSidesWhenBothAreTruncated() {
        String text = "a".repeat(60) + "NEEDLE" + "b".repeat(60);

        String snippet = Snippet.around(text, "NEEDLE");

        assertThat(snippet).startsWith("...").endsWith("...").contains("NEEDLE");
    }

    @Test
    void addsNoLeadingEllipsisWhenTheMatchIsNearTheStart() {
        String text = "NEEDLE" + "b".repeat(60);

        String snippet = Snippet.around(text, "NEEDLE");

        assertThat(snippet).doesNotStartWith("...").endsWith("...");
    }

    @Test
    void collapsesNewlinesToSpaces() {
        String snippet = Snippet.around("first line\nsecond line", "second");

        assertThat(snippet).doesNotContain("\n").contains("first line second line");
    }

    @Test
    void matchesCaseInsensitively() {
        assertThat(Snippet.around("Login succeeds", "LOGIN")).contains("Login");
    }
}
