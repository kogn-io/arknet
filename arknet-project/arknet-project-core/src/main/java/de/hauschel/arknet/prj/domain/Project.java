// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import java.util.List;
import java.util.Objects;

/**
 * A registered project: the store object ADR-016 introduces to replace the old, derived
 * "workspace" notion. Exactly one dataset holds the data of exactly one project (ADR-016
 * decision 1).
 *
 * <p>Value object of the project component. All invariants are enforced in the compact
 * constructor; instances are immutable and their collection is defensively copied.</p>
 *
 * <p><strong>A project without an anchor is unreachable, so it is not a legal state.</strong>
 * There is no default anchor and no fallback to a server-side working directory (ADR-016
 * decision 3) - a project that could exist without one would be exactly the silent-default
 * failure mode this ADR exists to close. {@code null} or an empty anchor list is therefore
 * rejected here, in the constructor, rather than tolerated and caught later at the point a
 * lookup fails to find anything to return.</p>
 *
 * @param id      opaque, unchanging identity of this project (never derived from an anchor);
 *                minted once, directly by the application service, and stable across relabelling
 *                and across attaching further anchors
 * @param label   the project's human-readable, cross-project-unique name; maps to
 *                {@code dcterms:identifier} when the project describes itself in its own
 *                dataset (ADR-016 decision 7). Distinct from {@code id}: the id is opaque and
 *                never interpreted, the label is what a human reads in a report or a rename
 *                dialog (ADR-016 decision 5)
 * @param anchors the client-supplied handles this project is reachable by, {@code 1..n}; never
 *                empty (see above). Several anchors on the same project cover git worktrees,
 *                multiple IDE directories of the same checkout, or a copy at another location
 *                (ADR-016 decision 4)
 */
public record Project(ProjectId id, String label, List<Anchor> anchors) {

    public Project {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(label, "label");
        if (label.isBlank()) {
            throw new IllegalArgumentException("label must not be blank");
        }
        if (anchors == null || anchors.isEmpty()) {
            throw new IllegalArgumentException("a project must hold at least one anchor - "
                    + "an anchorless project would be unreachable and there is no default (ADR-016)");
        }
        anchors = List.copyOf(anchors);
    }
}
