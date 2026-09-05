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
import de.hauschel.arknet.ul.domain.TermId;
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
 *
 * <p><strong>Forward direction only, on both sides of the symmetric relation
 * (kogn-io/arknet#420).</strong> Every {@link Term} this port hands back carries the {@code
 * skos:related} peers the term itself points at, never the ones pointing back at it, and every
 * write asserts that same one direction. The reverse direction is a read of its own,
 * {@link #findRelatedCodes}, so the application service can merge the two into the single symmetric
 * list its driving ports promise without this port ever having to guess which half of a merged list
 * it is expected to persist.</p>
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
     * @throws TermNotFoundException          if {@code term.broader()} is set, or {@code
     *                                        term.related()} holds a code, that does not resolve to
     *                                        an existing term in the project
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
     * <p>A call with every correctable field ({@code prefLabel}, {@code definition},
     * {@code broader}, {@code related}) {@code null} is a no-op: nothing is written, no revision is
     * recorded, and the revision head does not move.</p>
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
     * @param related     {@code null} to leave the term's own {@code skos:related} peers untouched,
     *                    an empty list to clear them, or the codes of already-existing terms to
     *                    replace them wholesale - resolved against this project's own glossary
     *                    (kogn-io/arknet#420). Only this term's own forward edges are ever touched;
     *                    an edge another term asserts towards this one is that term's to clear
     * @return the term's up-to-date state after the correction, its {@code related} list holding
     *         the forward direction alone (see the type-level note)
     * @throws IllegalArgumentException          if {@code related} names {@code code} itself or
     *                                            names the same term twice - rejected before
     *                                            anything is written
     * @throws TermNotFoundException             if no term with this code exists, or if
     *                                            {@code broader} or an entry of {@code related}
     *                                            carries a code that does not resolve to an
     *                                            existing term in the project
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
            String language, String defaultLanguage, Optional<TermCode> broader, List<TermCode> related);

    /**
     * Reads the backward direction of {@code skos:related}: the business codes of every term in the
     * project whose own {@code skos:related} edge points at {@code id} (kogn-io/arknet#420).
     *
     * <p>Exists because only the forward direction is ever asserted, while the property is an
     * {@code owl:SymmetricProperty} - so the application service unions this with the term's own
     * forward peers into the single list its driving ports hand out, rather than reporting two
     * directions of one and the same relation. One step backwards, never a traversal: the peers
     * returned here are not themselves followed, which is what keeps a legitimate {@code A related
     * B}, {@code B related A} pair - which this relation, unlike {@code skos:broader}, explicitly
     * permits - from looping. Mirrors {@code AdrRepository#findRelatedCodes} exactly.</p>
     *
     * <p>Never rejects: an identity absent from the project simply has no referrers, which is an
     * empty list, not an error. Never reports a code twice.</p>
     *
     * @param projectId the project (architecture model) to read in
     * @param id        the identity of the term to find the referencing peers of
     * @return the referencing terms' codes, ordered by running number, never {@code null}
     */
    List<TermCode> findRelatedCodes(ProjectId projectId, TermId id);

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
     * Returns the business code of every term recorded in a project glossary, read independently of
     * whether that term can currently be materialised into a {@link Term}. {@link #findAll} joins
     * {@code skos:prefLabel} and {@code skos:definition} as mandatory - a {@link Term} refuses to
     * exist without either - and so silently drops a store-first concept that carries
     * neither, or only one of the two; a concept dropped that way is still there and its code still
     * taken, and this method still reports it.
     *
     * <p><strong>Why this exists (kogn-io/arknet#360).</strong>
     * {@link de.hauschel.arknet.ul.application.TermService#add} derives the next free
     * {@code TERM-N} from the highest running number the project ever handed out. Derived from
     * {@link #findAll} alone, that number would depend on whether the highest-numbered concept
     * happens to carry both literals right now: a label-less concept holding the project's highest
     * number is invisible to the listing read, so the derivation would mint its code a second time,
     * and the implementation's in-transaction uniqueness guard would reject the write with
     * {@link DuplicateTermCodeException}. Retrying does not help, because the recomputation reads
     * the same store and arrives at the same taken number again - the project's {@code term_add}
     * is not racing, it is dead. This method joins only the concept type and the mandatory
     * {@code dcterms:identifier}, neither of which a missing label or definition can hide, so the
     * derived number does not depend on materialisability at all.</p>
     *
     * <p>Never rejects, and never reports one code twice; a glossary with no terms yields an empty
     * list. Disjoint from {@link #findRetainedCodes}, which covers the codes of terms that no
     * longer exist - the next free code is derived from both.</p>
     *
     * @param projectId the project (architecture model) to read the term codes of
     * @return every recorded term's business code, never {@code null}
     */
    List<TermCode> findAllCodes(ProjectId projectId);

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

    /**
     * Returns the business codes of terms that were deleted from the project and are kept out of
     * circulation - what {@link #delete} retains so a code can never name two different terms over
     * a project's lifetime (issue #350). Read together with {@link #findAll} whenever the next free
     * code is derived; the two sets are disjoint, since a retained code belongs to a term that no
     * longer exists.
     *
     * <p>Never rejects and never reports a code twice. A term deleted <em>without</em> the
     * implementation being able to retain its code is simply absent - the contract is "every code
     * this port could keep", not "every code ever used", and the one implementation-side gap this
     * leaves is documented where it arises.</p>
     *
     * @param projectId the project (architecture model) to read the retained codes of
     * @return the retained codes, never {@code null}
     */
    List<TermCode> findRetainedCodes(ProjectId projectId);
}
