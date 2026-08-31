// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

/**
 * Reads a {@link Block.Prose}'s structure in a test.
 *
 * <p>Most prose fields are one paragraph, and a test about glossary markup or code references
 * should say so once rather than unwrap {@link ProsePart} at every assertion.</p>
 */
final class ProseParts {

    private ProseParts() {
    }

    /** @return the text of the block's single paragraph, failing if it has any other structure. */
    static RichText soleParagraph(final Block block) {
        assertThat(block).isInstanceOf(Block.Prose.class);
        final List<ProsePart> parts = ((Block.Prose) block).parts();
        assertThat(parts).singleElement().isInstanceOf(ProsePart.Paragraph.class);
        return ((ProsePart.Paragraph) parts.getFirst()).text();
    }

    /** @return the block's parts, for a test that is about the structure itself. */
    static List<ProsePart> partsOf(final Block block) {
        assertThat(block).isInstanceOf(Block.Prose.class);
        return ((Block.Prose) block).parts();
    }
}
