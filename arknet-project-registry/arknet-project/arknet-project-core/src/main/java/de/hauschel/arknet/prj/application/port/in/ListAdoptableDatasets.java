// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: which datasets hold data but belong to no registered project yet.
 *
 * <p>{@link AdoptProject} needs the caller to name a dataset exactly, and a caller who has to guess
 * one will guess wrong - the ids in question are old directory slugs nobody wrote down. This is
 * what makes the answer available without guessing; it is the natural companion of
 * {@link ListProjects} and rendered alongside it rather than as a tool of its own, because "what is
 * here" is one question, not two.</p>
 *
 * <p>The list shrinks to empty as adoption proceeds and stays empty afterwards, which makes it
 * self-limiting: it is not a permanent inventory of the store but a to-do list that disappears when
 * it is done.</p>
 */
public interface ListAdoptableDatasets {

    /**
     * Returns every dataset present in the store that no registered project claims.
     *
     * @return the adoptable dataset identities, ordered by value for a stable rendering, never
     *         {@code null}
     */
    List<ProjectId> adoptable();
}
