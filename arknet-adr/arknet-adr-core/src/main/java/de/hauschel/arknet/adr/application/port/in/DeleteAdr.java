// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrNotDeletableException;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrReferencedException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: removes a recorded decision and its triples from the project entirely.
 *
 * <p>Unlike {@link UpdateAdr}, there is no field-level correction here - the whole resource goes
 * away. Backs the tool {@code adr_delete}, the closing counterpart of {@link AddAdr} this hexagon
 * lacked.</p>
 *
 * <p><strong>Only a proposal is deletable.</strong> A decision may be deleted while it is
 * {@link AdrStatus#PROPOSED} and in no other status. What is protected is a decision, not a draft
 * (Nygard): once somebody accepted, turned down or retired a record, the record is the history an
 * ADR exists to keep, and removing it erases the reasoning a later reader needs. Each other status
 * therefore has its own path and its own refusal text - see {@link AdrNotDeletableException} -
 * rather than a shared "no". {@link AdrStatus#REJECTED} in particular is <em>not</em> a way to get
 * rid of a record: "considered and turned down" is a decision with value, so a proposal recorded by
 * accident is undone here, not by rejecting it.</p>
 *
 * <p><strong>Referential integrity is refused, not repaired.</strong> While another decision points
 * at this one - naming it as its own successor via {@code arkarch:supersededBy}, or via
 * {@code arkarch:relatedTo} - the delete is rejected with {@link AdrReferencedException} rather than
 * silently orphaning that edge, the same line {@code term_delete}/{@code actor_delete} draw for their
 * own referrers. Both relations point back into this very hexagon, so the rejection can name the
 * decisions concerned by their codes.</p>
 *
 * <p><strong>The code is not handed out again.</strong> Deleting {@code ADR-7} does not free the
 * number 7: a code that already appeared in a commit message or a note must not come back naming
 * something else, so the next {@code adr_add} continues above every code the project has ever
 * used.</p>
 */
public interface DeleteAdr {

    /**
     * Deletes the decision identified by {@code code} from {@code projectId}.
     *
     * @param projectId the project (architecture model) the decision lives in
     * @param code      the ADR code, e.g. {@code ADR-1}
     * @throws AdrNotFoundException      if no decision with this code exists
     * @throws AdrNotDeletableException  if the decision is no longer {@link AdrStatus#PROPOSED}
     * @throws AdrReferencedException    if another decision still points at it
     */
    void delete(ProjectId projectId, AdrCode code);
}
