// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermNotFoundException;
import de.hauschel.arknet.ul.domain.TermReferencedException;

/**
 * Driving port: removes a term and its triples from the project glossary entirely (issue #335).
 *
 * <p>Unlike {@link UpdateTerm}, there is no field-level correction here - the whole resource, and
 * everything it carries (its {@code skos:prefLabel}/{@code skos:definition} in every language),
 * goes away. Rejected outright, rather than silently orphaning an edge, if anything
 * else in the project still references the term - see {@link TermReferencedException}. Backs the
 * MVP tool {@code term_delete}, the closing counterpart of {@link AddTerm} this glossary lacked
 * until now.</p>
 */
public interface DeleteTerm {

    /**
     * Deletes the term identified by {@code code} from {@code projectId}.
     *
     * @param projectId the project (architecture model) the term lives in
     * @param code      the term code, e.g. {@code TERM-1}
     * @throws TermNotFoundException   if no term with this code exists
     * @throws TermReferencedException if anything else in the project still references the term
     */
    void delete(ProjectId projectId, TermCode code);
}
