package de.hauschel.arknet.ul.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve glossary terms"), not after
 * any technology. Implementations live in adapter modules (e.g. an RDF/SKOS-backed
 * adapter) and must not leak their mechanism into this contract.</p>
 *
 * <p>The {@link WorkspaceId} routing key identifies which architecture model (and
 * thus which glossary) a term belongs to. A local single-user adapter may treat it
 * as an implicit default; a remote/team adapter uses it to address one of several
 * workspaces.</p>
 */
public interface TermRepository {

    /**
     * Persists the given term, inserting or replacing by identity.
     *
     * @param workspaceId the workspace (architecture model) to store the term in
     * @param term        the term to store
     */
    void save(WorkspaceId workspaceId, Term term);

    /**
     * Finds a term by its identity within a workspace.
     *
     * @param workspaceId the workspace (architecture model) to look up the term in
     * @param id          the term identity
     * @return the term if present, otherwise {@link Optional#empty()}
     */
    Optional<Term> findById(WorkspaceId workspaceId, TermId id);

    /**
     * Returns all terms stored in a workspace glossary.
     *
     * @param workspaceId the workspace (architecture model) to list terms from
     * @return all terms, never {@code null}
     */
    List<Term> findAll(WorkspaceId workspaceId);
}
