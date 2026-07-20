// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Driving port: fetch a single glossary term by its human-readable code.
 *
 * <p>Backs the MVP tool {@code term_get}. Lookup is by {@link TermCode} (e.g. {@code TERM-1}) -
 * what a human types - never by the opaque {@link de.hauschel.arknet.ul.domain.TermId}.</p>
 */
public interface GetTerm {

    /**
     * Looks up a term by its business code within a workspace glossary.
     *
     * @param workspaceId the workspace (architecture model) to look up the term in
     * @param code        the term code (e.g. {@code TERM-1})
     * @return the term if present, otherwise {@link Optional#empty()}
     */
    Optional<Term> get(WorkspaceId workspaceId, TermCode code);
}
