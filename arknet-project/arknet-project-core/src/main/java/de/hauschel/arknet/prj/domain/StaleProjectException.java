// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import de.hauschel.arknet.kernel.ProjectId;
import java.util.Objects;

/**
 * Thrown when a read-modify-write round trip (e.g. {@code project_attach_anchor}, {@code
 * project_rename}) keeps losing the optimistic-concurrency race against other writers of the
 * same project (see
 * {@link de.hauschel.arknet.prj.application.port.out.ProjectRegistry#compareAndUpdate}) across
 * every retry attempt the application service allows.
 *
 * <p>An expected-but-rare domain outcome, not a programming error: sustained, high-frequency
 * concurrent writes to the very same project registration - the same lost-update race the other
 * three bounded contexts guard against on their own aggregates (issue #108/#144), applied here to
 * the registry the fourth, project, bounded context owns. Distinct from
 * {@link ProjectNotFoundException} - the project exists throughout, it is just never observed to
 * still match the caller's stale read for long enough to commit.</p>
 */
public class StaleProjectException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId id;

    /**
     * Creates the exception.
     *
     * @param id the project identity whose update kept losing the race
     */
    public StaleProjectException(ProjectId id) {
        super("project " + Objects.requireNonNull(id, "id").value()
                + " could not be updated - it kept changing concurrently across every retry attempt");
        this.id = id;
    }

    /** @return the project identity whose update kept losing the race */
    public ProjectId id() {
        return id;
    }
}
