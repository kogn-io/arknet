// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.List;
import java.util.Objects;

/**
 * One numbered step of a use case's main flow, as shown on a model card.
 *
 * @param position the 1-based position in the flow, shown as the step number
 * @param text     what happens in this step
 * @param realises the requirements this step realises, already resolved to their business
 *                 codes; may be empty, never {@code null}
 */
public record FlowStep(int position, String text, List<Ref> realises) {

    public FlowStep {
        Objects.requireNonNull(text, "text");
        realises = realises == null ? List.of() : List.copyOf(realises);
    }
}
