package de.hauschel.arknet.ul.application.port.in;

import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Driving port: fetch a single glossary term by identity.
 *
 * <p>Backs the MVP tool {@code term_get}.</p>
 */
public interface GetTerm {

    /**
     * Looks up a term by its identity within a workspace glossary.
     *
     * @param workspaceId the workspace (architecture model) to look up the term in
     * @param id          the term identity
     * @return the term if present, otherwise {@link Optional#empty()}
     */
    Optional<Term> get(WorkspaceId workspaceId, TermId id);
}
