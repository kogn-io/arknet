// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.Term;

/**
 * Driving port: register a new glossary term.
 *
 * <p>Backs the MVP tool {@code term_add}. Identity assignment ({@code TERM-N}) is
 * policy of the implementing application service; the term is minted as a
 * {@code skos:Concept} by the out-adapter.</p>
 */
public interface AddTerm {

    /**
     * Adds a new term to the project glossary.
     *
     * @param projectId the project (architecture model) to add the term to
     * @param command     the data describing the term to create
     * @return the persisted term including its assigned identity
     */
    Term add(ProjectId projectId, NewTerm command);

    /**
     * Input data for {@link #add(ProjectId, NewTerm)}.
     *
     * @param prefLabel  the preferred label, i.e. the term itself
     * @param definition the meaning of the term
     * @param actorFacet optional Actor facette: if set, the same
     *                   skos:Concept is additionally an {@code arkproc:Actor}.
     *                   Optional (may be {@code null})
     * @param language   the BCP-47 language tag {@code prefLabel} and {@code definition} are
     *                   written in (e.g. {@code "de"}), or {@code null} for a plain, untagged
     *                   literal - the same tag applies to both fields, since a term is normally
     *                   registered in one language at a time
     */
    record NewTerm(String prefLabel, String definition, ActorFacet actorFacet, String language) {
    }
}
