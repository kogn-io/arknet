// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermConcurrentlyModifiedException;
import de.hauschel.arknet.ul.domain.TermNotFoundException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve glossary terms"), not after
 * any technology. Implementations live in adapter modules (e.g. an RDF/SKOS-backed
 * adapter) and must not leak their mechanism into this contract.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model (and
 * thus which glossary) a term belongs to. A local single-user adapter may treat it
 * as an implicit default; a remote/team adapter uses it to address one of several
 * workspaces.</p>
 *
 * <p><strong>Create vs. update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does
 * not (a create), and conflating the two would hide a caller bug (writing to an id nobody
 * minted, or an id that was already used). {@link #create} makes that distinction explicit for a
 * brand-new term.</p>
 *
 * <p><strong>Update is a targeted correction, not a replace (issue #163 follow-up).</strong>
 * {@link #update} used to take a full {@link Term} and replace the subject's triples wholesale -
 * which silently destroyed every triple the caller never meant to touch, most severely a
 * multi-valued {@code skos:prefLabel}/{@code skos:definition} (issues #80/#81: a store-first term
 * can legally carry several language-tagged {@code prefLabel}s or several {@code definition}
 * literals, which {@link Term}'s single-{@code String} fields can only ever hold one of at a
 * time). {@link #update} instead takes the term's unchanged business {@link TermCode} plus one
 * nullable argument per correctable field, exactly mirroring {@code UpdateTerm}'s own "{@code
 * null} leaves that field unchanged" contract: only the predicate(s) whose new value is actually
 * supplied are ever deleted-and-reinserted at the triple level, so an untouched field's other
 * language variants, duplicate values, or the whole field itself if never touched at all survive
 * unconditionally. The code itself is never among the correctable fields and is therefore never
 * rewritten - a code collision (issue #114's original concern) is now structurally unreachable via
 * this method, not merely checked.</p>
 */
public interface TermRepository {

    /**
     * Persists a brand-new term whose identity does not yet exist in the workspace.
     *
     * @param projectId the workspace (architecture model) to store the term in
     * @param term        the term to create
     * @throws ResourceAlreadyExistsException if a term with this identity already exists
     * @throws DuplicateTermCodeException     if another term already carries this term's
     *                                        {@link TermCode} - identity collision and
     *                                        business-label collision are distinct failure modes
     */
    void create(ProjectId projectId, Term term);

    /**
     * Corrects specific fields of the term identified by {@code code}, leaving every field the
     * caller did not ask to change - including any other language-tagged {@code skos:prefLabel}
     * variant or duplicate {@code skos:definition} a store-first (issues #80/#81) term may legally
     * carry - completely untouched at the triple level.
     *
     * <p>Reads the term's current state together with its revision head before the write
     * transaction, then applies the patch as a compare-and-set: the write only takes effect if
     * that head still matches what was read. A concurrent write to this term - even one touching a
     * different field - moves the shared head and is therefore a conflict too, not only a write to
     * the same predicate; {@code update} retries transparently on a head conflict, re-reading the
     * term's current state on every attempt, so a losing caller's own change is never silently
     * discarded. Only once every retry attempt keeps losing the race does
     * {@link TermConcurrentlyModifiedException} reach the caller.</p>
     *
     * <p>A call with {@code prefLabel}, {@code definition} and {@code actorFacet} all {@code null}
     * is a no-op: nothing is written, no revision is recorded, and the revision head does not
     * move.</p>
     *
     * @param projectId the workspace (architecture model) the term lives in
     * @param code        the term's own, unchanged business code - {@code update} never rewrites
     *                    {@code dcterms:identifier}, so this can never itself introduce a code
     *                    collision
     * @param prefLabel   the new preferred label, or {@code null} to leave every existing
     *                    {@code skos:prefLabel} triple untouched
     * @param definition  the new definition, or {@code null} to leave every existing
     *                    {@code skos:definition} triple untouched
     * @param actorFacet  the new Actor facette, or {@code null} to leave an already-set facette
     *                    (its type and role triples) untouched. Within a non-{@code null} facette,
     *                    a {@code null} {@link ActorFacet#role()} likewise leaves an already-set
     *                    role triple untouched - only the type is always replaced
     * @return the term's up-to-date state after the correction
     * @throws TermNotFoundException             if no term with this code exists
     * @throws TermConcurrentlyModifiedException if a concurrent writer kept advancing the term's
     *                                            revision head across every retry attempt
     */
    Term update(ProjectId projectId, TermCode code, String prefLabel, String definition, ActorFacet actorFacet);

    /**
     * Finds a term by its human-readable business code within a workspace.
     *
     * @param projectId the workspace (architecture model) to look up the term in
     * @param code        the term code (e.g. {@code TERM-1})
     * @return the term if present, otherwise {@link Optional#empty()}
     */
    Optional<Term> findByCode(ProjectId projectId, TermCode code);

    /**
     * Returns all terms stored in a workspace glossary.
     *
     * @param projectId the workspace (architecture model) to list terms from
     * @return all terms, never {@code null}
     */
    List<Term> findAll(ProjectId projectId);

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
     * @param projectId the workspace (architecture model) to look up terms in
     * @param ids         the opaque identities to resolve; an empty list yields an empty result
     * @return the resolved terms found, in no particular order, never {@code null}
     */
    List<ResolveTerms.ResolvedTerm> findByIds(ProjectId projectId, List<ResourceId> ids);
}
