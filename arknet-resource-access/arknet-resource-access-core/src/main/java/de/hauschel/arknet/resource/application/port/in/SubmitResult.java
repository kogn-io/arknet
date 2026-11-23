// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;

/**
 * What an accepted document changed, one entry per resource.
 *
 * @param changes the changed resources, created ones first, then updated, then deleted
 */
public record SubmitResult(List<SubmittedChange> changes) {

    /**
     * Copies the list.
     *
     * @param changes the changed resources
     */
    public SubmitResult {
        changes = List.copyOf(Objects.requireNonNull(changes, "changes"));
    }
}
