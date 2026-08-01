// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.DuplicateProjectLabelException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.UnattributedRegistrationConflictException;

/**
 * Driving port: register a brand-new project with exactly one anchor.
 *
 * <p>Backs the tool {@code project_add}. A project cannot be registered without at least one
 * anchor - there is no "register now, attach an anchor later" path, because an anchorless
 * project would be unreachable the moment it exists (ADR-016 decision 3, enforced by
 * {@link Project}'s own constructor). Adding further anchors to an already-registered project is
 * the job of {@link AttachAnchor}, not this port.</p>
 */
public interface RegisterProject {

    /**
     * Registers a new project.
     *
     * @param label  the project's human-readable, cross-project-unique name
     * @param anchor the project's first anchor
     * @return the newly registered project, including its minted identity
     * @throws AnchorAlreadyRegisteredException if {@code anchor} already belongs to a project
     * @throws DuplicateProjectLabelException   if {@code label} already labels a different
     *                                          project
     * @throws UnattributedRegistrationConflictException if the write keeps losing a real store
     *                                          commit conflict that neither guard above explains,
     *                                          across every retry attempt (see that exception's
     *                                          javadoc - an expected-but-rare outcome, not a
     *                                          programming error)
     */
    Project register(String label, Anchor anchor);
}
