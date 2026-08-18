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
 * @param position the 1-based position of this item within its block
 * @param text     the item's text
 */
public record BulletItem(int position, RichText text) {

    public BulletItem {
        if (position < 1) {
            throw new IllegalArgumentException("BulletItem position must be >= 1, was " + position);
        }
        Objects.requireNonNull(text, "text");
    }
}
