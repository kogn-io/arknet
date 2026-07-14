package de.hauschel.arknet.ul.application.port.in;

import de.hauschel.arknet.kernel.WorkspaceId;
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
     * Adds a new term to the workspace glossary.
     *
     * @param workspaceId the workspace (architecture model) to add the term to
     * @param command     the data describing the term to create
     * @return the persisted term including its assigned identity
     */
    Term add(WorkspaceId workspaceId, NewTerm command);

    /**
     * Input data for {@link #add(WorkspaceId, NewTerm)}.
     *
     * @param prefLabel  the preferred label, i.e. the term itself
     * @param definition the meaning of the term
     */
    record NewTerm(String prefLabel, String definition) {
    }
}
