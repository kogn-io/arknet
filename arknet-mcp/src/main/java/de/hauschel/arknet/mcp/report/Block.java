// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.List;
import java.util.Objects;

/**
 * One labelled part of a {@link ModelCard}'s body.
 *
 * <p>Deliberately a small, closed vocabulary of <em>shapes</em> ("a paragraph", "a list", "a
 * set of links", "a numbered flow") rather than a per-field type: the card builders decide
 * which domain field is which shape, and the renderer decides how a shape looks. Adding a
 * field to a bounded context therefore never touches the renderer, and adding a second
 * renderer (the Vaadin review UI of ADR-010) never touches the card builders.</p>
 *
 * <p>Sealed, so a new shape is a compile error in every renderer instead of a silently
 * unrendered block.</p>
 */
public sealed interface Block {

    /** @return the human-readable heading of this block (e.g. {@code Goal}). */
    String label();

    /**
     * A single run of text: a goal, a description, a precondition.
     *
     * <p>Carries {@link RichText} rather than a string because a text may mention the
     * ubiquitous language, and the builder - not the renderer - is who can tell a mention the
     * model backs with an edge from one it does not. A field nobody analyses simply arrives as
     * {@link RichText#plain}.</p>
     *
     * @param label the block heading
     * @param text  the text; non-blank (an absent optional field is left out entirely rather
     *              than rendered as an empty block)
     */
    record Prose(String label, RichText text) implements Block {
        public Prose {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(text, "text");
        }

        /**
         * @param label the block heading
         * @param text  a text with nothing marked up
         * @return the block
         */
        public static Prose plain(final String label, final String text) {
            return new Prose(label, RichText.plain(text));
        }
    }

    /**
     * An unordered list of text items: acceptance criteria, extension flows.
     *
     * @param label the block heading
     * @param items the items; never {@code null}
     */
    record Bullets(String label, List<RichText> items) implements Block {
        public Bullets {
            Objects.requireNonNull(label, "label");
            items = items == null ? List.of() : List.copyOf(items);
        }

        /**
         * @param label the block heading
         * @param items texts with nothing marked up
         * @return the block
         */
        public static Bullets plain(final String label, final List<String> items) {
            return new Bullets(label, items == null ? List.of()
                    : items.stream().map(RichText::plain).toList());
        }
    }

    /**
     * A set of references to other resources, rendered as linked chips.
     *
     * @param label the block heading
     * @param refs  the references, already resolved to business codes; never {@code null}
     */
    record Refs(String label, List<Ref> refs) implements Block {
        public Refs {
            Objects.requireNonNull(label, "label");
            refs = refs == null ? List.of() : List.copyOf(refs);
        }
    }

    /**
     * An ordered, numbered flow - the shape that makes a use case readable as a use case
     * rather than as a heap of {@code arkreq:Step} resources.
     *
     * @param label the block heading
     * @param steps the ordered steps; never {@code null}
     */
    record Flow(String label, List<FlowStep> steps) implements Block {
        public Flow {
            Objects.requireNonNull(label, "label");
            steps = steps == null ? List.of() : List.copyOf(steps);
        }
    }
}
