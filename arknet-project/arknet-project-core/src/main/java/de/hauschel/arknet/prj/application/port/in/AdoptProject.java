// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.DatasetAlreadyAdoptedException;
import de.hauschel.arknet.prj.domain.DuplicateProjectLabelException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.prj.domain.UnattributedRegistrationConflictException;
import de.hauschel.arknet.prj.domain.UnknownDatasetException;

/**
 * Driving port: register an <em>existing</em> dataset as a project, under an anchor the caller
 * presents.
 *
 * <p><strong>Why this is not {@link RegisterProject} with an extra parameter.</strong> Registering
 * mints a fresh identity for a project that has no data yet; adopting takes an identity that
 * already exists - because a dataset is already sitting under it - and gives it the anchor and
 * label it never had. The two differ in exactly the thing that must not be got wrong: whether the
 * caller may choose the identity. Here it must, and that is only safe because the identity has to
 * name a dataset that is already in the store and not yet claimed.</p>
 *
 * <p><strong>What it is for.</strong> Before the registered-anchor model, a project's dataset id
 * was derived from its directory name ({@code slug(basename(git-common-dir))}), so datasets exist
 * under ids like {@code arknet} that no anchor points to. The server cannot repair this on its
 * own: the slug is not invertible, so it cannot know which directory {@code arknet} once meant,
 * and inventing an answer is exactly what registered anchors remove. Only the person at the
 * keyboard knows, and this port is how they say it - the anchor comes from the calling client as
 * usual, the dataset is named explicitly, keeping pre-existing ids as opaque values that gain the
 * anchors they were always reached by, without being renamed or migrated.</p>
 *
 * <p>It stays useful after that migration: a dataset restored from a backup onto a machine whose
 * registry does not know it is the same situation.</p>
 */
public interface AdoptProject {

    /**
     * Adopts the existing dataset {@code datasetId} as a project reachable by {@code anchor}.
     *
     * @param datasetId the identity of a dataset already present in the store, which becomes the
     *                  adopted project's identity unchanged
     * @param label     the project's human-readable, cross-project-unique name
     * @param anchor    the first anchor the adopted project becomes reachable by
     * @return the newly registered project, holding {@code datasetId} as its identity
     * @throws UnknownDatasetException          if no such dataset exists in the store
     * @throws DatasetAlreadyAdoptedException   if a project is already registered for it
     * @throws AnchorAlreadyRegisteredException if {@code anchor} already belongs to a project
     * @throws DuplicateProjectLabelException   if {@code label} is already taken
     * @throws ResourceAlreadyExistsException   if a concurrent {@link #adopt} call won the race
     *                                        for the very same {@code datasetId} - unlike
     *                                        {@link RegisterProject#register}'s freshly minted
     *                                        identity, {@code datasetId} is caller-chosen and can
     *                                        genuinely already be claimed by the time a retried
     *                                        write observes it (issue #174)
     * @throws UnattributedRegistrationConflictException if the write keeps losing a real store
     *                                        commit conflict that none of the guards above can
     *                                        explain, across every retry attempt
     */
    Project adopt(ProjectId datasetId, String label, Anchor anchor);
}
