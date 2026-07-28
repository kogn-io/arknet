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
     * @param label the block heading
     * @param text  the text; non-blank (an absent optional field is left out entirely rather
     *              than rendered as an empty block)
     */
    record Prose(String label, String text) implements Block {
        public Prose {
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * An unordered list of text items: acceptance criteria, extension flows.
     *
     * @param label the block heading
     * @param items the items; never {@code null}
     */
    record Bullets(String label, List<String> items) implements Block {
        public Bullets {
            Objects.requireNonNull(label, "label");
            items = items == null ? List.of() : List.copyOf(items);
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
