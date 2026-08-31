// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.List;
import java.util.Objects;

/**
 * One structural part of a single prose field: a paragraph, or a bullet list (issue #388).
 *
 * <p><strong>Why the structure lives inside {@link Block.Prose} rather than becoming several
 * blocks.</strong> A prose field is exactly one store literal, and the report offers a
 * client-side switch between that literal's languages by wrapping the rendered field in one
 * element. Letting a list inside a decision text become a sibling {@link Block.Bullets} would
 * split one literal across several blocks - all carrying the same heading, none of them
 * switchable on its own, and each of them looking to the renderer like a list of positioned
 * store sub-resources ({@code arkarch:Consequence} and friends), which it is not. Nesting keeps
 * the field's boundary intact: one literal, one block, one language switch.</p>
 *
 * <p>Sealed, so a new prose shape is a compile error in every renderer instead of silently
 * unrendered text.</p>
 */
public sealed interface ProsePart {

    /**
     * A run of prose, already marked up.
     *
     * <p>Paragraphs are separated by a blank line in the source literal; a single line break
     * inside one collapses to a space, as it does in Markdown - a hard-wrapped sentence is one
     * sentence.</p>
     *
     * @param text the paragraph's text; never {@code null}
     */
    record Paragraph(RichText text) implements ProsePart {
        public Paragraph {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A bullet list the author wrote with {@code - } line markers.
     *
     * <p>Unlike {@link Block.Bullets}, these items carry no position: they are runs of one
     * literal's text, not store sub-resources, and there is nothing to pair them back to.</p>
     *
     * @param items the items in reading order; never {@code null}
     */
    record Bullets(List<RichText> items) implements ProsePart {
        public Bullets {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }
}
