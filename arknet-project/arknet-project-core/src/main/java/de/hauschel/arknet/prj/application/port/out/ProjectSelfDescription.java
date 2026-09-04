// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.out;

import de.hauschel.arknet.prj.domain.Project;

/**
 * Driven port: writes a project's own anchors and label into that project's <em>own</em> dataset,
 * so the registry stays an index rebuildable from the datasets it indexes
 * rather than a single point of failure - a dataset restored from a backup carries its identity
 * with it, without the registry having to be restored in lockstep.
 *
 * <p><strong>Deliberately a second port, not a second method on {@link ProjectRegistry}.</strong>
 * A call through this port targets a different dataset than a call through
 * {@link ProjectRegistry} - the project's own, not the reserved system dataset. There is
 * therefore no shared transaction and no two-phase commit spanning both writes; folding them
 * into one method would suggest an atomicity this component cannot actually offer.</p>
 *
 * <p><strong>Ordering is binding, not incidental.</strong> The application service always calls
 * {@link ProjectRegistry} first, then this port: the registry is where a duplicate anchor is
 * detected, so it must be the one to reject a bad write before anything is written anywhere.
 * If {@link #describe} then fails, the registry entry is left standing without a matching
 * self-description - at that moment the registry is the authority on what is true, and the
 * inconsistency is transient: because {@link #describe} is called idempotently on every
 * successful registry write (registration, attaching an anchor, renaming), the very next
 * successful write to the same project re-describes it and the drift heals itself without any
 * dedicated repair step.</p>
 */
public interface ProjectSelfDescription {

    /**
     * Writes {@code project}'s current anchors and label into {@code project}'s own dataset,
     * replacing whatever self-description was written there before.
     *
     * @param project the project to describe, in its own dataset
     */
    void describe(Project project);
}
