// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermConcurrentlyModifiedException;
import de.hauschel.arknet.ul.domain.TermCycleException;
import de.hauschel.arknet.ul.domain.TermNotFoundException;
import de.hauschel.arknet.ul.domain.TermReferencedException;
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
 * projects.</p>
 *
 * <p><strong>Create vs. update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does
 * not (a create), and conflating the two would hide a caller bug (writing to an id nobody
 * minted, or an id that was already used). {@link #create} makes that distinction explicit for a
 * brand-new term.</p>
 *
 * <p><strong>Update is a targeted correction, not a replace.</strong>
 * {@link #update} used to take a full {@link Term} and overwrite everything stored under this
 * identity - which silently destroyed every stored value the caller never meant to touch, most
 * severely a multi-valued preferred label or definition (a store-first term can legally carry
 * several language-tagged labels or several definition values, which {@link Term}'s single-
 * {@code String} fields can only ever hold one of at a time). {@link #update} instead takes the
 * term's unchanged business {@link TermCode} plus one nullable argument per correctable field,
 * exactly mirroring {@code UpdateTerm}'s own "{@code null} leaves that field unchanged" contract:
 * only the field(s) whose new value is actually supplied are ever touched, so an untouched
 * field's other language variants, duplicate values, or the whole field itself if never touched
 * at all survive unconditionally. The code itself is never among the correctable fields and is
 * therefore never rewritten - a code collision is now structurally unreachable via
 * this method, not merely checked.</p>
 */
public interface TermRepository {

    /**
     * Persists a brand-new term whose identity does not yet exist in the project.
     *
     * @param projectId the project (architecture model) to store the term in
     * @param term      the term to create
     * @param language  the BCP-47 language tag {@code term.prefLabel()}/{@code term.definition()}
     *                  is written in (e.g. {@code "de"}), or {@code null} for a plain, untagged
     *                  literal
     * @throws ResourceAlreadyExistsException if a term with this identity already exists
     * @throws DuplicateTermCodeException     if another term already carries this term's
     *                                        {@link TermCode} - identity collision and
     *                                        business-label collision are distinct failure modes
     * @throws TermNotFoundException          if {@code term.broader()} is set but does not
     *                                        resolve to an existing term in the project
     * @throws RuntimeException if {@code term} violates a SHACL write constraint. The concrete
     *                          signal type is deliberately not fixed by this port: a real
     *                          implementation's {@code WriteConstraintViolationException} lives
     *                          in {@code arknet-persistence-support}, a module
     *                          {@code arknet-ubiquitous-language-core} must not depend on.
     */
    void create(ProjectId projectId, Term term, String language);

    /**
     * Corrects specific fields of the term identified by {@code code}, leaving every field the
     * caller did not ask to change - including any other language-tagged preferred-label variant
     * or duplicate definition a store-first term may legally carry - completely untouched.
     *
     * <p>Reads the term's current state together with its revision head before the write
     * transaction, then applies the patch as a compare-and-set: the write only takes effect if
     * that head still matches what was read. A concurrent write to this term - even one touching a
     * different field - moves the shared head and is therefore a conflict too, not only a write to
     * the same field; {@code update} retries transparently on a head conflict, re-reading the
     * term's current state on every attempt, so a losing caller's own change is never silently
     * discarded. Only once every retry attempt keeps losing the race does
     * {@link TermConcurrentlyModifiedException} reach the caller.</p>
     *
     * <p>A call with {@code prefLabel} and {@code definition} both {@code null}
     * is a no-op: nothing is written, no revision is recorded, and the revision head does not
     * move.</p>
     *
     * @param projectId the project (architecture model) the term lives in
     * @param code        the term's own, unchanged business code - {@code update} never rewrites
     *                    the term's identifier, so this can never itself introduce a code
     *                    collision
     * @param prefLabel   the new preferred label, or {@code null} to leave every existing
     *                    preferred label untouched
     * @param definition  the new definition, or {@code null} to leave every existing
     *                    definition untouched
     * @param language    the BCP-47 language tag the new {@code prefLabel}/{@code definition} is
     *                    written in (e.g. {@code "de"}), or {@code null} for a plain, untagged
     *                    literal (the caller, {@code TermService#update}, has already resolved a
     *                    {@code null} against the project's default before this port sees it, or
     *                    rejected the call - see {@code UpdateTerm}'s own {@code language}
     *                    parameter). Deletion is scoped to this same tag: only the existing literal
     *                    carrying it is removed, so every other language-tagged variant of a field
     *                    being corrected survives untouched
     * @param defaultLanguage the target project's configured default language, canonicalized by
     *                    the caller, or {@code null} if it has none. Used only to decide whether an
     *                    existing <em>untagged</em> literal on {@code prefLabel}/{@code definition}
     *                    should be swept away rather than preserved: when the tag actually written
     *                    for that field equals {@code defaultLanguage}, the literal being written
     *                    is - by construction - the very literal an omitted {@code language}
     *                    argument would have resolved to, so a still-untagged sibling of the same
     *                    predicate is a stale duplicate of it, not a genuine other-language
     *                    variant, and the delete filter widens to remove it too (issue #258). Has
     *                    no bearing on which tag is actually written - that decision was already
     *                    made by the caller
     * @param broader     {@code null} to leave an already-set {@code skos:broader} term untouched,
     *                    {@link Optional#empty()} to clear it, or {@link Optional#of} the code of
     *                    an already-existing term to set/replace it - resolved and cycle-checked
     *                    against this project's own glossary (issue #252)
     * @return the term's up-to-date state after the correction
     * @throws TermNotFoundException             if no term with this code exists, or if
     *                                            {@code broader} carries a code that does not
     *                                            resolve to an existing term in the project
     * @throws TermCycleException                if setting {@code broader} would make
     *                                            {@code code} its own (direct or transitive)
     *                                            broader term
     * @throws TermConcurrentlyModifiedException if a concurrent writer kept advancing the term's
     *                                            revision head across every retry attempt
     * @throws RuntimeException if the patched term violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-ubiquitous-language-core} must not depend on.
     */
    Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
            String language, String defaultLanguage, Optional<TermCode> broader);

    /**
     * Finds a term by its human-readable business code within a project.
     *
     * @param projectId     the project (architecture model) to look up the term in
     * @param code          the term code (e.g. {@code TERM-1})
     * @param displayLocale the BCP-47 language tag the caller wants {@code prefLabel}/
     *                      {@code definition} shown in, overriding this repository's own
     *                      configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged
     * @return the term if present, otherwise {@link Optional#empty()}
     */
    Optional<Term> findByCode(ProjectId projectId, TermCode code, String displayLocale);

    /**
     * Returns all terms stored in a project glossary.
     *
     * @param projectId     the project (architecture model) to list terms from
     * @param displayLocale the BCP-47 language tag the caller wants each term's {@code
     *                      prefLabel}/{@code definition} shown in, overriding this repository's
     *                      own configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged - the same per-call
     *                      override {@link #findByCode} already accepts (issue #274)
     * @return all terms, never {@code null}
     */
    List<Term> findAll(ProjectId projectId, String displayLocale);

    /**
     * Finds every term in a project whose identity is among {@code ids}, in one store
     * round-trip - backs {@link ResolveTerms}. This is a batch lookup, not a per-id
     * existence check: an id absent from the project is simply absent from the result, never an
     * error.
     *
     * <p>Returns the slim {@link ResolveTerms.ResolvedTerm} projection, not the full {@link Term}
     * aggregate: the only consumer of this method is {@link ResolveTerms}, which
     * exists purely to answer "what code names this identity" for display - joining fields such
     * as {@code prefLabel}/{@code definition} the caller never reads would needlessly exclude a
     * store-first term that carries an identity and a code but happens to miss one of them.</p>
     *
     * @param projectId the project (architecture model) to look up terms in
     * @param ids         the opaque identities to resolve; an empty list yields an empty result
     * @return the resolved terms found, in no particular order, never {@code null}
     */
    List<ResolveTerms.ResolvedTerm> findByIds(ProjectId projectId, List<ResourceId> ids);

    /**
     * Deletes the term identified by {@code code}, and every triple it carries in the glossary's
     * named graph, from the project (issue #335). Rejects outright, without deleting anything, if
     * anything else in the project still references the term - see {@link TermReferencedException}
     * for the predicates checked.
     *
     * @param projectId the project (architecture model) the term lives in
     * @param code      the term code, e.g. {@code TERM-1}
     * @throws TermNotFoundException   if no term with this code exists
     * @throws TermReferencedException if anything else in the project still references the term
     */
    void delete(ProjectId projectId, TermCode code);
}
