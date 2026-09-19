// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;
import de.hauschel.arknet.uc.domain.UseCaseReferencedException;

/**
 * Driving port: removes a use case, and the flow steps it owns, from the project entirely
 * (kogn-io/arknet#566), mirroring {@code DeleteConstraint} exactly.
 *
 * <p>Unlike {@link UpdateUseCase}, there is no field-level correction here - the whole resource
 * goes away. The typical case is a use case that should never have been written: a duplicate, or a
 * flow that turned out to belong to another use case. A use case has no lifecycle status, so there
 * is no state that forbids the delete either: the only thing that holds one back is another use
 * case still pointing at it via {@code arkreq:includesUseCase}/{@code arkreq:extendsUseCase} - see
 * {@link UseCaseReferencedException}.</p>
 *
 * <p><strong>The steps go with it.</strong> A use case's {@code arkreq:mainStep}/
 * {@code arkreq:extensionStep} resources are aggregate-internal value objects reachable through
 * nothing but their use case, so deleting the use case deletes them too - the same child treatment
 * {@code adr_delete} gives a decision's consequences. A requirement a step realised via
 * {@code arkreq:stepRealises} is untouched: that edge is outgoing, and it disappears with the step
 * that carried it.</p>
 *
 * <p>Backs the MCP tool {@code uc_delete}, the closing counterpart of {@link AddUseCase} this
 * resource type lacked until now.</p>
 */
public interface DeleteUseCase {

    /**
     * Deletes the use case identified by {@code code}, and its flow steps, from {@code projectId}.
     *
     * @param projectId the project (architecture model) the use case lives in
     * @param code      the use-case code, e.g. {@code UC1}
     * @throws UseCaseNotFoundException   if no use case with this identity exists
     * @throws UseCaseReferencedException if another use case still includes or extends it
     */
    void delete(ProjectId projectId, UseCaseCode code);
}
