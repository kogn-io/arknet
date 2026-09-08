// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: register a new bounded context.
 *
 * <p>Backs the MVP tool {@code bc_add}. Identity assignment (the opaque
 * {@link de.hauschel.arknet.bc.domain.BoundedContextId}) and business-code assignment
 * ({@code BC-N}) are policy of the implementing application service.</p>
 *
 * <p><strong>Language (kogn-io/arknet#520).</strong> {@code name} and {@code domainVision} are
 * written as language-tagged literals, both under the single {@link NewBoundedContext#language()}
 * this one call names - mirrors {@code AddRole}/{@code AddConstraint} exactly. A second language is
 * added afterwards by {@link UpdateBoundedContext}, one tag per call.</p>
 */
public interface AddBoundedContext {

    /**
     * Adds a new bounded context.
     *
     * @param projectId       the project (architecture model) to add the bounded context to
     * @param command         the data describing the bounded context to create
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - used only when
     *                        {@link NewBoundedContext#language()} is omitted
     * @return the persisted bounded context including its assigned identity and code
     * @throws DuplicateBoundedContextCodeException if a concurrent {@code bc_add} keeps claiming
     *                                               the same candidate business code across every
     *                                               retry attempt
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if {@code command} names
     *         no {@code language} and {@code defaultLanguage} is {@code null} too
     */
    BoundedContext add(ProjectId projectId, NewBoundedContext command, String defaultLanguage);

    /**
     * Input data for {@link #add(ProjectId, NewBoundedContext, String)}.
     *
     * @param name         the context's human-readable name (e.g. {@code OrderManagement})
     * @param domainVision one sentence stating what this context does and why it exists
     * @param subdomain    strategic subdomain classification; optional (may be {@code null})
     * @param ownedBy      the owning team name; optional (may be {@code null})
     * @param language     the BCP-47 language tag {@code name} and {@code domainVision} are
     *                     written in (e.g. {@code "de"}), or {@code null} to fall back to the
     *                     project's configured default language
     */
    record NewBoundedContext(
            String name,
            String domainVision,
            Subdomain subdomain,
            String ownedBy,
            String language) {
    }
}
