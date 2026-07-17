package de.hauschel.arknet.ul.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermNotFoundException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;

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
 *
 * <p><strong>Create vs. update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does
 * not (a create), and conflating the two would hide a caller bug (writing to an id nobody
 * minted, or an id that was already used). {@link #create} and {@link #update} therefore make
 * that distinction explicit at the port.</p>
 */
public interface TermRepository {

    /**
     * Persists a brand-new term whose identity does not yet exist in the workspace.
     *
     * @param workspaceId the workspace (architecture model) to store the term in
     * @param term        the term to create
     * @throws ResourceAlreadyExistsException if a term with this identity already exists
     * @throws DuplicateTermCodeException     if another term already carries this term's
     *                                        {@link TermCode} - identity collision and
     *                                        business-label collision are distinct failure modes
     */
    void create(WorkspaceId workspaceId, Term term);

    /**
     * Replaces an existing term by identity.
     *
     * @param workspaceId the workspace (architecture model) the term lives in
     * @param term        the term to store in place of the current one
     * @throws TermNotFoundException if no term with this identity exists
     */
    void update(WorkspaceId workspaceId, Term term);

    /**
     * Finds a term by its human-readable business code within a workspace.
     *
     * @param workspaceId the workspace (architecture model) to look up the term in
     * @param code        the term code (e.g. {@code TERM-1})
     * @return the term if present, otherwise {@link Optional#empty()}
     */
    Optional<Term> findByCode(WorkspaceId workspaceId, TermCode code);

    /**
     * Returns all terms stored in a workspace glossary.
     *
     * @param workspaceId the workspace (architecture model) to list terms from
     * @return all terms, never {@code null}
     */
    List<Term> findAll(WorkspaceId workspaceId);

    /**
     * Finds every term in a workspace whose identity is among {@code ids}, in one store
     * round-trip - backs {@link ResolveTerms} (issue #77). This is a batch lookup, not a per-id
     * existence check: an id absent from the workspace is simply absent from the result, never an
     * error.
     *
     * <p>Returns the slim {@link ResolveTerms.ResolvedTerm} projection, not the full {@link Term}
     * aggregate (issue #84): the only consumer of this method is {@link ResolveTerms}, which
     * exists purely to answer "what code names this identity" for display - joining fields such
     * as {@code prefLabel}/{@code definition} the caller never reads would needlessly exclude a
     * store-first term that carries an identity and a code but happens to miss one of them.</p>
     *
     * @param workspaceId the workspace (architecture model) to look up terms in
     * @param ids         the opaque identities to resolve; an empty list yields an empty result
     * @return the resolved terms found, in no particular order, never {@code null}
     */
    List<ResolveTerms.ResolvedTerm> findByIds(WorkspaceId workspaceId, List<ResourceId> ids);
}
