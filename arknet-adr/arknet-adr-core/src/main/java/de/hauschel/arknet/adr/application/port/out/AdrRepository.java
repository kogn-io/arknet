// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotDeletableException;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrReferencedException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.DuplicateAdrCodeException;
import de.hauschel.arknet.adr.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve architecture decisions"), not after any
 * technology. Implementations live in adapter modules (e.g. an RDF-backed adapter) and must not leak
 * their mechanism into this contract.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model a decision belongs to. A
 * local single-user adapter may treat it as an implicit default; a remote/team adapter uses it to
 * address one of several projects.</p>
 *
 * <p><strong>Create vs. compare-and-set update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is not a
 * coherent single operation: an identity either already exists (an update) or it does not (a
 * create), and conflating the two would hide a caller bug. {@link #create} and
 * {@link #compareAndUpdate} therefore make that distinction explicit at the port - and there is no
 * unconditional update: every correction to an already-recorded decision goes through the
 * compare-and-set guard, which is what the bounded-context context had to retrofit later
 * and what this one is built with from the start.</p>
 */
public interface AdrRepository {

    /**
     * Persists a brand-new decision whose identity does not yet exist in the project.
     *
     * @param projectId the project (architecture model) to store the decision in
     * @param adr       the decision to create
     * @throws ResourceAlreadyExistsException if a decision with this identity already exists
     * @throws DuplicateAdrCodeException      if another decision already carries this decision's
     *                                        {@link AdrCode} - identity collision and business-label
     *                                        collision are distinct failure modes
     * @throws RuntimeException if {@code adr} violates a SHACL write constraint. The concrete
     *                          signal type is deliberately not fixed by this port: a real
     *                          implementation's {@code WriteConstraintViolationException} lives
     *                          in {@code arknet-persistence-support}, a module
     *                          {@code arknet-adr-core} must not depend on.
     */
    void create(ProjectId projectId, Adr adr);

    /**
     * Replaces an existing decision by identity, but only if its current concurrency token still
     * equals {@code expectedHead} - the compare-and-set guard against the lost-update race. A
     * read-modify-write round trip ({@code adr_set_status}, {@code adr_supersede}) reads the current
     * state and token together via {@link #findCurrentByCode}, derives {@code updated}, and calls
     * this method with the token it observed; a mismatch means the read was already stale, and the
     * caller must re-read and retry rather than silently discard the concurrent change (a
     * {@code supersedes} edge a concurrent {@code adr_supersede} had just added).
     *
     * <p><strong>The token guards writes made through this port, not edits that bypass it.</strong>
     * {@code expectedHead} only ever changes when a write goes through this port's own
     * {@code create}/{@code compareAndUpdate} (ADR-014); a direct store-first (ADR-005) edit to
     * this decision leaves the token untouched. Such an edit therefore passes this method's
     * compare-and-set check undetected, and the subsequent replace-by-identity write silently
     * overwrites it. The guard closes the lost-update window between two callers of this port, not
     * between a caller of this port and a store-first edit that bypassed it entirely.</p>
     *
     * @param projectId    the project (architecture model) the decision lives in
     * @param expectedHead the token the caller last observed for this
     *                     decision (from {@link #findCurrentByCode}), or {@code null} if the caller
     *                     expects no revision to exist yet
     * @param updated      the decision to store in place of the current one, if its head still
     *                     matches {@code expectedHead}
     * @throws AdrNotFoundException              if no decision with this identity exists at all
     * @throws AdrConcurrentlyModifiedException if {@code expectedHead} no longer matches the stored
     *                                          decision's current head - a concurrent write raced
     *                                          ahead
     * @throws RuntimeException if {@code updated} violates a SHACL write constraint. The concrete
     *                          signal type is deliberately not fixed by this port: a real
     *                          implementation's {@code WriteConstraintViolationException} lives
     *                          in {@code arknet-persistence-support}, a module
     *                          {@code arknet-adr-core} must not depend on.
     */
    void compareAndUpdate(ProjectId projectId, String expectedHead, Adr updated);

    /**
     * Finds a decision by its human-readable business code within a project.
     *
     * @param projectId the project (architecture model) to look up the decision in
     * @param code      the ADR code (e.g. {@code ADR-1})
     * @return the decision if present, otherwise {@link Optional#empty()}
     */
    Optional<Adr> findByCode(ProjectId projectId, AdrCode code);

    /**
     * Reads a decision's current state together with its concurrency token (recorded by the last
     * write through this port, ADR-014). The scalar fields and the token itself come from one
     * query call - one snapshot - which is the load-bearing guarantee here, not an ordering of
     * clauses within that query. The multi-valued edges are deliberately filled in by later,
     * independent follow-up reads; that is safe precisely because a later read can only be
     * fresher, never staler, than the token: a write through this port that commits in between
     * moves the token, so the subsequent {@link #compareAndUpdate} then fails its comparison
     * and the caller re-reads instead of overwriting a state it never actually saw. The pairing is
     * therefore conservative - state is never older than the token it is paired with - never
     * optimistic. Backs the read side of the read-modify-write round trip
     * {@link #compareAndUpdate} guards the write side of.
     *
     * @param projectId the project (architecture model) to look up the decision in
     * @param code      the ADR code (e.g. {@code ADR-1})
     * @return the decision and its current head, or {@link Optional#empty()} if no decision with
     *         this code exists
     */
    Optional<CurrentAdr> findCurrentByCode(ProjectId projectId, AdrCode code);

    /**
     * A decision's state paired with its current concurrency token (an opaque string, or
     * {@code null} if no write has ever been recorded for this decision), as read together by
     * {@link #findCurrentByCode}.
     */
    record CurrentAdr(Adr value, String head) {
    }

    /**
     * Returns all decisions stored in a project.
     *
     * @param projectId the project (architecture model) to list decisions from
     * @return all decisions, never {@code null}
     */
    List<Adr> findAll(ProjectId projectId);

    /**
     * Returns the business code of every decision recorded in a project, read independently of
     * whether that decision can currently be materialised into an {@link Adr} - unlike
     * {@link #findAll}, a decision this hexagon's own read-time tolerance skips (an unrecognised
     * {@code adrStatus}, or a store-first (ADR-005) {@code adrStatus}/{@code supersededBy}
     * disagreement, kogn-io/arknet#357) still counts here.
     *
     * <p><strong>Why this exists (kogn-io/arknet#359).</strong>
     * {@link de.hauschel.arknet.adr.application.AdrService#nextCode} derives the next free
     * {@code ADR-N} from the highest running number ever used. Deriving it from {@link #findAll}
     * alone would make that number depend on whether the highest-numbered decision happens to be
     * materialisable right now - a decision skipped by {@link #findAll} is still alive and its code
     * still assigned, so {@code findAll} silently omitting it would let {@code nextCode} recompute
     * and hand the very same number out again, which the next {@link #create} then rejects as a
     * {@link DuplicateAdrCodeException} on every retry: not a transient collision {@code adr_add}
     * can recover from, a permanently dead number. This method reads only the mandatory
     * {@code dcterms:identifier}/type pair, nothing a status decode or a bi-implication check could
     * ever skip, so the number it feeds into {@code nextCode} does not depend on materialisability at
     * all.</p>
     *
     * @param projectId the project (architecture model) to read codes from
     * @return every recorded decision's business code, never {@code null}
     */
    List<AdrCode> findAllCodes(ProjectId projectId);

    /**
     * Deletes the decision identified by {@code code}, and every triple it carries in this hexagon's
     * own named graph, from the project. Whether the decision may be deleted at all - its status,
     * and whether anything still points at it - is decided above this port first; what this port
     * adds is the guarantee that both checks and the removal share one atomic view of the store, so
     * a status change or a reference written between the two cannot slip through: an implementation
     * therefore repeats both the status check and the reference check against its own write
     * transaction, rejecting with {@link AdrNotDeletableException} or {@link AdrReferencedException}
     * there too.
     *
     * @param projectId the project (architecture model) the decision lives in
     * @param code      the ADR code, e.g. {@code ADR-1}
     * @throws AdrNotFoundException     if no decision with this code exists
     * @throws AdrNotDeletableException if the decision is no longer {@link AdrStatus#PROPOSED}
     * @throws AdrReferencedException   if another decision still points at it
     */
    void delete(ProjectId projectId, AdrCode code);

    /**
     * Returns the business codes of decisions that were deleted from the project and are kept out of
     * circulation - what {@link #delete} retains so a code can never name two different decisions
     * over a project's lifetime. Read together with {@link #findAll} whenever the next free code is
     * derived; the two sets are disjoint, since a retained code belongs to a decision that no longer
     * exists.
     *
     * <p>Never rejects and never reports a code twice. A decision deleted <em>without</em> the
     * implementation being able to retain its code is simply absent - the contract is "every code
     * this port could keep", not "every code ever used", and the one implementation-side gap this
     * leaves is documented where it arises.</p>
     *
     * @param projectId the project (architecture model) to read the retained codes of
     * @return the retained codes, sorted by running number, never {@code null}
     */
    List<AdrCode> findRetainedCodes(ProjectId projectId);

    /**
     * Resolves opaque decision identities to their business codes, in one store round-trip (not one
     * per id) - what turns bare identities into something a human can re-type. Two callers today:
     * {@link Adr#relatedTo()}'s peer list, and the successor of a decision whose
     * {@link Adr#supersededBy()} target {@link #findAll} skipped, which {@code AdrService#list}
     * falls back to this lookup for rather than dropping the edge (kogn-io/arknet#359).
     *
     * <p>Never rejects: an id that resolves to nothing in the project is simply absent from the
     * result, the same contract a sibling hexagon's {@code ResolveTerms}/{@code ResolveRequirements}
     * driving port promises. The caller decides whether "missing" matters.</p>
     *
     * @param projectId the project (architecture model) to resolve identities in
     * @param ids       the opaque identities to resolve; an empty collection yields an empty map
     * @return the resolved identity-to-code mapping, never {@code null}
     */
    Map<AdrId, AdrCode> findCodesByIds(ProjectId projectId, Collection<AdrId> ids);

    /**
     * Reads the business codes of every decision that supersedes {@code supersededId} - what
     * {@code supersededId}'s own {@link de.hauschel.arknet.adr.application.port.in.AdrDetail#supersededBy() supersededBy display} needs
     * (kogn-io/arknet#357).
     *
     * <p>Two sources, unioned: {@code supersededId}'s own {@code arkarch:supersededBy} field (the
     * current write shape, an ordinary forward read on the decision itself), and a reverse read of
     * the pre-#357 {@code arkarch:supersedes} triple a store-first record may still carry
     * (written on the <em>superseding</em> decision, back when that direction was the one asserted).
     * Nothing writes the legacy shape any more, but nothing migrates it away either - a project with
     * decisions superseded before this issue keeps reading correctly. In the common case (every
     * decision written under the current model) this returns at most one code, since
     * {@code arkarch:supersededBy} carries {@code sh:maxCount 1}; the legacy source is what can make
     * it more than one.</p>
     *
     * @param projectId    the project (architecture model) to read in
     * @param supersededId the identity of the decision to find the successors of
     * @return the superseding decisions' codes, sorted, never {@code null}
     */
    List<AdrCode> findSupersedingCodes(ProjectId projectId, AdrId supersededId);

    /**
     * Reads the business codes of every decision {@code supersedingId} supersedes - what
     * {@code supersedingId}'s own {@link de.hauschel.arknet.adr.application.port.in.AdrDetail#supersedes() supersedes display} needs
     * (kogn-io/arknet#357), the mirror of {@link #findSupersedingCodes}.
     *
     * <p>Two sources, unioned: a reverse read of every decision naming {@code supersedingId} in its
     * own {@code arkarch:supersededBy} field (the current write shape - {@code supersedingId} is
     * each such decision's successor), and {@code supersedingId}'s own pre-#357
     * {@code arkarch:supersedes} triple, should a store-first record still carry one (written back
     * when the superseding decision itself asserted the forward edge).</p>
     *
     * @param projectId      the project (architecture model) to read in
     * @param supersedingId  the identity of the decision to find the predecessors of
     * @return the superseded decisions' codes, sorted, never {@code null}
     */
    List<AdrCode> findSupersededCodes(ProjectId projectId, AdrId supersedingId);

    /**
     * Reads the business codes of every decision whose supersession edge - either direction, either
     * write shape - names {@code target}: the current-model {@code arkarch:supersededBy} pointed at
     * {@code target} (i.e. a decision {@code target} itself supersedes) or the pre-#357
     * {@code arkarch:supersedes} pointed at {@code target} (i.e. a decision that names {@code target}
     * as what it replaces). What {@code adr_delete}'s reference guard needs: either shape would leave
     * a dangling edge behind if {@code target} disappeared, and the guard does not care which shape
     * wrote it, only that removing {@code target} would orphan it.
     *
     * <p>Deliberately not the same read as {@link #findSupersedingCodes}/{@link #findSupersededCodes}:
     * those each also read {@code target}'s <em>own</em> outgoing field to answer a display question
     * ("who supersedes/does this decision supersede"), which is not a dangling-reference risk at all
     * - that triple disappears together with {@code target} itself, harming nobody. This method reads
     * only the two <em>external</em> reverse edges.</p>
     *
     * @param projectId the project (architecture model) to read in
     * @param target    the identity of the decision a delete would remove
     * @return the referencing decisions' codes, sorted, never {@code null}
     */
    List<AdrCode> findSupersessionReferrers(ProjectId projectId, AdrId target);

    /**
     * Reads every store-first (pre-#357) {@code arkarch:supersedes} edge still present in the
     * project, as (superseding code, superseded code) pairs - the forward-only triple this hexagon
     * used to write on the superseding decision before the edge moved onto the superseded decision's
     * own {@code arkarch:supersededBy} field. Nothing writes this predicate any more, so every pair
     * returned here is legacy data {@link de.hauschel.arknet.adr.application.AdrService#list} must
     * keep folding into both supersession directions rather than silently dropping.
     *
     * <p>One bulk read for the whole project, not one query per decision - the same "single full
     * read, everything else in memory" shape {@link #findAll} already gives {@code adr_list}.</p>
     *
     * @param projectId the project (architecture model) to read in
     * @return every legacy pair, never {@code null}
     */
    List<LegacySupersession> findLegacySupersedesEdges(ProjectId projectId);

    /** One pre-#357 {@code arkarch:supersedes} edge, as read back by {@link #findLegacySupersedesEdges}. */
    record LegacySupersession(AdrCode supersedingCode, AdrCode supersededCode) {
    }

    /**
     * Reads the backward direction of {@code arkarch:relatedTo}: the business codes of every
     * decision whose {@code relatedTo} edge points at {@code relatedId}.
     *
     * <p>This exists for the same reason {@link #findSupersedingCodes} does - only the forward edge
     * is ever asserted, so the other direction has to be read backwards. What differs is what the
     * caller does with the answer: {@code relatedTo} is an {@code owl:SymmetricProperty}, so the
     * application service unions this result with the decision's own forward edges into the single
     * list {@code AdrDetail#relatedTo} carries, rather than reporting two directions of one and the
     * same relation.</p>
     *
     * <p>One step backwards, never a traversal: the peers this returns are not themselves followed.
     * That is what keeps a legitimate {@code A relatedTo B}, {@code B relatedTo A} cycle - which
     * this relation explicitly permits, unlike {@code supersedes} - from looping.</p>
     *
     * @param projectId the project (architecture model) to read in
     * @param relatedId the identity of the decision to find the referencing peers of
     * @return the referencing decisions' codes, sorted, never {@code null}
     */
    List<AdrCode> findRelatedCodes(ProjectId projectId, AdrId relatedId);
}
