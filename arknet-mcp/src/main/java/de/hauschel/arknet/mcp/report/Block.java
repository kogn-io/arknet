// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
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
     * <p>Carries {@link ProsePart}s rather than a string because a text may mention the
     * ubiquitous language, and the builder - not the renderer - is who can tell a mention the
     * model backs with an edge from one it does not; and because a prose field may itself be
     * structured, the author having written paragraphs and a bullet list into one literal (issue
     * #388). A field nobody analyses simply arrives as {@link #plain}.</p>
     *
     * @param label  the block heading
     * @param source the store literal this block was built from, character for character - what
     *               the renderer matches against the subject's other language-tagged literals
     * @param parts  the field's structure in reading order; never empty for a block that is
     *               rendered at all (an absent optional field is left out entirely rather than
     *               rendered as an empty block)
     */
    record Prose(String label, String source, List<ProsePart> parts) implements Block {
        public Prose {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(source, "source");
            parts = parts == null ? List.of() : List.copyOf(parts);
        }

        /**
         * A single, unstructured paragraph - the shape for a field that is not free prose at all
         * (a date, a category name, an owner) and would only be damaged by looking for markup in
         * it.
         *
         * @param label the block heading
         * @param text  a text with nothing marked up
         * @return the block
         */
        public static Prose plain(final String label, final String text) {
            Objects.requireNonNull(text, "text");
            return new Prose(label, text, List.of(new ProsePart.Paragraph(RichText.plain(text))));
        }

        /**
         * A single paragraph with its spans already marked up - the shape a caller produces that
         * analyses the text itself rather than letting {@link ProseMarkdown} structure it.
         *
         * @param label the block heading
         * @param text  the marked-up text
         * @return the block
         */
        public static Prose paragraph(final String label, final RichText text) {
            Objects.requireNonNull(text, "text");
            return new Prose(label, text.text(), List.of(new ProsePart.Paragraph(text)));
        }
    }

    /**
     * An unordered list of text items: acceptance criteria, extension flows.
     *
     * <p>The items carry their 1-based position rather than arriving as bare texts, so a
     * renderer can pair an item with the store sub-resource it came from - see {@link
     * BulletItem}. {@link Flow} has carried the same number all along.</p>
     *
     * @param label the block heading
     * @param items the items, in ascending position order; never {@code null}
     */
    record Bullets(String label, List<BulletItem> items) implements Block {
        public Bullets {
            Objects.requireNonNull(label, "label");
            items = items == null ? List.of() : List.copyOf(items);
        }

        /**
         * Numbers {@code texts} by their order, which is what a caller whose own items have no
         * position of their own can say truthfully: the n-th text is the n-th item.
         *
         * @param label the block heading
         * @param texts texts with nothing marked up, in ascending position order
         * @return the block
         */
        public static Bullets plain(final String label, final List<String> texts) {
            return numberedByOrder(label, texts == null ? List.of() : texts.stream().map(RichText::plain).toList());
        }

        /**
         * Numbers {@code texts} 1..n by their list order. Named after where the number comes
         * from, because that is the distinction this whole block turns on: a caller whose items
         * carry a position of their own in the model passes it through the canonical constructor
         * instead, so that a renderer can pair the item with the store resource behind it.
         *
         * @param label the block heading
         * @param texts the texts, in ascending position order
         * @return the block
         */
        private static Bullets numberedByOrder(final String label, final List<RichText> texts) {
            if (texts == null) {
                return new Bullets(label, List.of());
            }
            final List<BulletItem> items = new ArrayList<>(texts.size());
            for (int index = 0; index < texts.size(); index++) {
                items.add(new BulletItem(index + 1, texts.get(index)));
            }
            return new Bullets(label, items);
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
