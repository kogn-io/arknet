// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.Objects;

/**
 * One item of a {@link Block.Bullets} list, together with its 1-based position.
 *
 * <p>The position is what the store's own sub-resources carry ({@code arkreq:position} on an
 * {@code arkreq:Step} reached by {@code arkreq:extensionStep}, on an
 * {@code arkreq:AcceptanceCriterion} reached by {@code arkreq:acceptanceCriterion}), and it is
 * what lets a renderer find an item's other language variants without guessing from the
 * displayed text - two extensions worded identically are realistic, two positions are never
 * the same (issue #319). {@link FlowStep} carries it for exactly the same reason; a bullet list
 * only ever looked position-less because nothing had needed the number yet.</p>
 *
 * <p><strong>{@code badge} and {@code caption} are optional (issue #382).</strong> Most bullet
 * items are bare text - an extension, an acceptance criterion - and carry neither. An
 * {@code arkarch:Consequence} adds a classifying {@code badge} (its {@code POSITIVE}/{@code
 * NEGATIVE}/{@code NEUTRAL} type); an {@code arkarch:ConsideredOption} adds both: a {@code badge}
 * for its {@code CHOSEN}/{@code REJECTED} outcome and a {@code caption} for its short name, kept
 * apart from {@code text} (the option's rationale) rather than glued into one string - the two
 * are separate fields on the resource, and gluing them (the pre-#382 shape) meant a renderer could
 * not style or language-switch one without the other.</p>
 *
 * @param position the 1-based position of this item within its block
 * @param text     the item's text
 * @param badge    a classifying pill shown before {@code text}, or {@code null} for a plain item
 * @param caption  a short label shown before {@code text} (and after {@code badge}, if any), or
 *                 {@code null} for a plain item
 */
public record BulletItem(int position, RichText text, Badge badge, String caption) {

    public BulletItem {
        if (position < 1) {
            throw new IllegalArgumentException("BulletItem position must be >= 1, was " + position);
        }
        Objects.requireNonNull(text, "text");
    }

    /**
     * @param position the 1-based position of this item within its block
     * @param text     the item's text
     */
    public BulletItem(final int position, final RichText text) {
        this(position, text, null, null);
    }
}
