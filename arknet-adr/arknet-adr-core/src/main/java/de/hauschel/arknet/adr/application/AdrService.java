// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import de.hauschel.arknet.adr.application.port.in.AcceptAdr;
import de.hauschel.arknet.adr.application.port.in.AddAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.CountSkippedAdrs;
import de.hauschel.arknet.adr.application.port.in.DeleteAdr;
import de.hauschel.arknet.adr.application.port.in.DeprecateAdr;
import de.hauschel.arknet.adr.application.port.in.GetAdr;
import de.hauschel.arknet.adr.application.port.in.ListAdrs;
import de.hauschel.arknet.adr.application.port.in.RejectAdr;
import de.hauschel.arknet.adr.application.port.in.SupersedeAdr;
import de.hauschel.arknet.adr.application.port.in.UpdateAdr;
import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.application.port.out.BoundedContextLookup;
import de.hauschel.arknet.adr.application.port.out.RequirementLookup;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotDeletableException;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrReferencedException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.AdrTextImmutableException;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.DuplicateAdrCodeException;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceIdFactory;

/**
 * Application service implementing the architecture-decision use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link AdrRepository} driven port plus
 * the two cross-context lookups. The component is wired as a plain object (constructor injection) by
 * the composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link AdrId}) is opaque and minted once per decision via
 * {@link ResourceIdFactory}; it never changes. The human-readable business code ({@link AdrCode},
 * {@code ADR-N}) is assigned independently, where {@code N} is one above the highest running number
 * currently used in the target project (numbering is independent per project, starting at 1). A new
 * decision always starts {@link AdrStatus#PROPOSED} - {@code adr_add} takes no status, because a
 * decision recorded as already accepted would skip the transitions this lifecycle has. From
 * {@code PROPOSED} a decision may become {@link AdrStatus#ACCEPTED} or {@link AdrStatus#REJECTED};
 * an accepted one may further become {@link AdrStatus#DEPRECATED} or - via {@code adr_supersede} -
 * {@link AdrStatus#SUPERSEDED}, set together with {@link Adr#supersededBy()} on the <em>superseded</em>
 * decision in one write (kogn-io/arknet#357; see {@link #supersede}). {@link #update} corrects an
 * existing decision field by field, and the status decides how far it
 * may reach: the prose and the decision date are only correctable while {@link AdrStatus#PROPOSED}
 * and are refused with {@link AdrTextImmutableException} in every other status, while
 * all three reference lists stay correctable in every status - completing a reference that
 * could not exist at recording time states the same decision more fully instead of rewriting it, the
 * same licence {@link Adr#supersededBy(AdrId)} already takes against an accepted decision. That rule
 * lives on
 * {@link Adr#reviseText}/{@link Adr#reviseReferences}, not here; this service only fills the
 * caller's untouched fields from the current state and hands the result to the same CAS helper every
 * other write path uses. {@link #delete} is the one path that removes a decision instead of changing
 * it, and it is staged by status as well - only a {@link AdrStatus#PROPOSED} one may go, because what
 * this lifecycle protects is a decision and not a draft (Nygard); every other status is refused with
 * {@link AdrNotDeletableException} and pointed at the path that fits it. While another decision
 * points at it, the delete is refused outright with {@link AdrReferencedException} rather than
 * orphaning that edge, and the code of a deleted decision stays out of circulation - {@link #nextCode}
 * counts retained codes as used, so {@code ADR-7} never names two different decisions over a
 * project's lifetime.</p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} recomputes its next code against a fresh read
 * whenever a concurrent {@code adr_add} claims the same {@code ADR-N} first, via
 * {@link CodeAssignment#createRetryingOnCodeCollision}, and every read-modify-write path
 * ({@link #accept}, {@link #reject}, {@link #deprecate}, {@link #supersede}, {@link #update})
 * retries its whole round trip via {@link AdrRepository#compareAndUpdate} whenever a concurrent
 * writer commits in between - see {@link #updateWithOptimisticRetry}. Neither race is visible to a
 * well-formed caller; only
 * sustained, pathological contention on the very same decision surfaces as
 * {@link AdrConcurrentlyModifiedException}. Parallel sessions of one user against one local store
 * are the normal case, not a remote/multi-writer concern (ADR-001).</p>
 *
 * <p><strong>Where each reference is resolved.</strong> Every reference code is resolved once,
 * here, before anything is written - an unresolvable one must abort the whole {@code adr_add} (or
 * {@code adr_update}) rather than leave a half-linked decision behind, which is also why resolution
 * sits <em>outside</em> the code-assignment and compare-and-set retries: an unknown {@code FR-9} is
 * not a code collision and must not be retried. The two cross-context codes go through their
 * dedicated lookup ports. The two self-referential relations - the {@code adr_supersede} target and
 * every {@code relatedTo} peer - need no lookup port at all: they are this hexagon's own resources,
 * resolved through {@link AdrRepository#findByCode}, and an unknown one is a plain
 * {@link AdrNotFoundException} rather than a didactic cross-context rejection.</p>
 *
 * <p><strong>How {@code relatedTo} is read back.</strong> Only the forward edge is stored, so a
 * decision's peers are the union of what it points at and what points at it (see
 * {@link AdrRepository#findRelatedCodes}) - one merged, deduplicated, running-number-ordered list
 * rather than two directions, because the relation is symmetric and its stored direction carries no
 * meaning. {@link #detailOf} pays one extra reverse read for it; {@link #list} inverts its single
 * full read in memory instead, exactly as it already does for both supersession directions (plus one
 * bulk read for any pre-#357 legacy edge, see {@link AdrRepository#findLegacySupersedesEdges}).
 * Neither follows a peer's own edges onwards, which is what lets {@code relatedTo} carry the cycles
 * it explicitly permits.</p>
 */
public class AdrService
        implements AddAdr, ListAdrs, CountSkippedAdrs, GetAdr, AcceptAdr, RejectAdr, DeprecateAdr, SupersedeAdr,
        UpdateAdr, DeleteAdr {

    private static final String CODE_PREFIX = "ADR";

    /**
     * Bound on {@link #updateWithOptimisticRetry}'s compare-and-set retry loop. Two callers
     * read-modify-writing the same decision are resolved by a single retry in the overwhelming
     * majority of cases, since each retry re-reads the now-current state before trying again; this
     * bound only exists so a pathological, sustained storm of concurrent writers against the very
     * same decision fails loudly instead of looping forever.
     */
    private static final int MAX_RETRY_ATTEMPTS = 20;

    /**
     * Orders {@code ADR-N} code strings by their parsed running number, not by {@link String}'s
     * natural (lexicographic) order - {@code "ADR-10"} sorts before {@code "ADR-2"} under natural
     * order once a project passes ten decisions. Falls back to natural string order when the running
     * number ties, which every well-formed {@code ADR-N} code only ever does with itself - the
     * fallback exists for two distinct, non-conforming store-first (ADR-005) codes that both parse to
     * 0 (see {@link #runningNumber}): without it this comparator returns 0 for two different codes,
     * which is inconsistent with {@link Object#equals} and silently collapses both into one entry in
     * a {@link TreeSet} (see {@link #list}).
     */
    private static final Comparator<String> CODE_BY_RUNNING_NUMBER =
            Comparator.<String>comparingInt(code -> runningNumber(new AdrCode(code)))
                    .thenComparing(Comparator.naturalOrder());

    private final AdrRepository repository;
    private final ResourceIdFactory resourceIdFactory;
    private final RequirementLookup requirementLookup;
    private final BoundedContextLookup boundedContextLookup;

    /**
     * Creates the service.
     *
     * @param repository           the driven persistence port (must not be {@code null})
     * @param resourceIdFactory    mints the opaque identity of a newly recorded decision (must not
     *                             be {@code null})
     * @param requirementLookup    resolves a human-typed requirement code to its opaque identity
     *                             (must not be {@code null})
     * @param boundedContextLookup resolves a human-typed bounded-context code to its opaque identity
     *                             (must not be {@code null})
     */
    public AdrService(AdrRepository repository, ResourceIdFactory resourceIdFactory,
            RequirementLookup requirementLookup, BoundedContextLookup boundedContextLookup) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.requirementLookup = Objects.requireNonNull(requirementLookup, "requirementLookup");
        this.boundedContextLookup = Objects.requireNonNull(boundedContextLookup, "boundedContextLookup");
    }

    @Override
    public AdrDetail add(ProjectId projectId, NewAdr command) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Resolution first, outside the retry: an unknown or ambiguous reference is a didactic
        // rejection of the whole call, not a code collision to be retried.
        List<RequirementRef> requirements = command.addressesRequirementCodes().stream()
                .map(code -> new RequirementRef(requirementLookup.resolveByCode(projectId, code)))
                .distinct()
                .toList();
        List<BoundedContextRef> contexts = command.affectsContextCodes().stream()
                .map(code -> new BoundedContextRef(boundedContextLookup.resolveByCode(projectId, code)))
                .distinct()
                .toList();
        // The peer decisions are this hexagon's own resources, so they resolve through the
        // repository rather than a lookup port - the same reasoning supersededBy() applies, and just
        // as much outside the retry: an unknown ADR-9 is not a code collision.
        List<AdrId> peers = resolvePeers(projectId, command.relatedToCodes());
        // Identity is opaque and stable, so it is minted once, outside the retry: only the business
        // code is recomputed when a concurrent adr_add claims the same candidate first. See
        // CodeAssignment for why that race exists and why it must retry rather than surface the
        // out-adapter's uniqueness guard as a caller-visible failure.
        AdrId id = new AdrId(resourceIdFactory.newId());
        Adr created = CodeAssignment.createRetryingOnCodeCollision(
                DuplicateAdrCodeException.class, () -> {
                    AdrCode code = nextCode(projectId);
                    // supersededBy starts null: a decision is never recorded as already superseded,
                    // it can only become so later via adr_supersede (kogn-io/arknet#357).
                    Adr adr = new Adr(id, code, command.name(), AdrStatus.PROPOSED, command.context(),
                            command.decision(), command.consequences(), command.alternatives(),
                            command.decisionDate(), requirements, contexts, null, peers);
                    repository.create(projectId, adr);
                    return adr;
                });
        return detailOf(projectId, created);
    }

    @Override
    public List<AdrDetail> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        List<Adr> all = repository.findAll(projectId);
        // One extra bulk read beyond findAll, for the pre-#357 legacy supersedes shape (see
        // AdrRepository#findLegacySupersedesEdges) - everything else below stays in-memory, exactly
        // as before this issue.
        List<AdrRepository.LegacySupersession> legacy = repository.findLegacySupersedesEdges(projectId);
        // Both current-model directions come out of the one findAll read: with every decision in
        // hand, its single supersededBy field can simply be inverted in memory - no reverse query,
        // and no second round trip per row. Keyed by business code (not AdrId) so the legacy pairs
        // below, which the store only ever hands back as codes, merge into the very same maps.
        Map<AdrId, AdrCode> codes = new LinkedHashMap<>();
        all.forEach(adr -> codes.putIfAbsent(adr.id(), adr.code()));
        Map<String, TreeSet<String>> supersedesCodes = new LinkedHashMap<>();
        Map<String, TreeSet<String>> supersededByCodes = new LinkedHashMap<>();
        Map<AdrId, TreeSet<String>> relatedFrom = new LinkedHashMap<>();
        for (Adr adr : all) {
            if (adr.supersededBy() != null) {
                AdrCode supersedingCode = codes.get(adr.supersededBy());
                if (supersedingCode == null) {
                    // The successor is not among the decisions findAll materialised - it exists
                    // (this decision's own supersededBy field names it) but findAll's own read-time
                    // tolerance skipped it (an unrecognised status, or a store-first status/
                    // supersededBy disagreement of its own, kogn-io/arknet#357). detailOf pays a
                    // fresh identity-to-code lookup for exactly this case (via findCodesByIds) rather
                    // than dropping the edge - falling back to the very same lookup here is what
                    // keeps adr_list from reporting a different edge than adr_get for the same
                    // decision (kogn-io/arknet#359).
                    supersedingCode =
                            repository.findCodesByIds(projectId, List.of(adr.supersededBy())).get(adr.supersededBy());
                }
                if (supersedingCode != null) {
                    addCode(supersededByCodes, adr.code().value(), supersedingCode.value());
                    addCode(supersedesCodes, supersedingCode.value(), adr.code().value());
                }
            }
            for (AdrId peer : adr.relatedTo()) {
                relatedFrom.computeIfAbsent(peer, key -> new TreeSet<>(CODE_BY_RUNNING_NUMBER))
                        .add(adr.code().value());
            }
        }
        for (AdrRepository.LegacySupersession pair : legacy) {
            addCode(supersededByCodes, pair.supersededCode().value(), pair.supersedingCode().value());
            addCode(supersedesCodes, pair.supersedingCode().value(), pair.supersededCode().value());
        }
        return all.stream()
                .map(adr -> new AdrDetail(adr,
                        sortedCodes(supersedesCodes.get(adr.code().value())),
                        sortedCodes(supersededByCodes.get(adr.code().value())),
                        mergedRelatedCodes(codesOf(adr.relatedTo(), codes),
                                sortedCodes(relatedFrom.get(adr.id())))))
                .toList();
    }

    /** Adds {@code value} to the {@link TreeSet} bucket keyed by {@code key}, creating it if absent. */
    private static void addCode(Map<String, TreeSet<String>> bucket, String key, String value) {
        bucket.computeIfAbsent(key, ignored -> new TreeSet<>(CODE_BY_RUNNING_NUMBER)).add(value);
    }

    @Override
    public Optional<AdrDetail> get(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code).map(adr -> detailOf(projectId, adr));
    }

    @Override
    public AdrDetail accept(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return detailOf(projectId, updateWithOptimisticRetry(projectId, code, Adr::accept));
    }

    @Override
    public AdrDetail reject(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return detailOf(projectId, updateWithOptimisticRetry(projectId, code, Adr::reject));
    }

    @Override
    public AdrDetail deprecate(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return detailOf(projectId, updateWithOptimisticRetry(projectId, code, Adr::deprecate));
    }

    @Override
    public AdrDetail update(ProjectId projectId, AdrCode code, AdrCorrection correction) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(correction, "correction");
        // Resolution first, outside the retry, exactly as in add(): an unknown FR-9 or BC-9 is a
        // didactic rejection of the whole call, never a race worth retrying. Null stays null here -
        // it is the "leave this relation alone" signal, and an empty list is a deliberate clear.
        List<RequirementRef> requirements = correction.addressesRequirementCodes() == null
                ? null
                : correction.addressesRequirementCodes().stream()
                        .map(referenced -> new RequirementRef(requirementLookup.resolveByCode(projectId, referenced)))
                        .distinct()
                        .toList();
        List<BoundedContextRef> contexts = correction.affectsContextCodes() == null
                ? null
                : correction.affectsContextCodes().stream()
                        .map(referenced -> new BoundedContextRef(
                                boundedContextLookup.resolveByCode(projectId, referenced)))
                        .distinct()
                        .toList();
        // Self-reference is refused before the peer codes are even looked up, so a caller naming
        // the decision itself is told exactly that rather than that "ADR-1" resolves - the same
        // refusal, and the same wording, supersede() makes for the same shape of mistake.
        if (correction.relatedToCodes() != null
                && correction.relatedToCodes().stream().map(AdrCode::new).anyMatch(code::equals)) {
            throw new IllegalArgumentException("an ADR must not be related to itself: " + code.value());
        }
        List<AdrId> peers = correction.relatedToCodes() == null
                ? null
                : resolvePeers(projectId, correction.relatedToCodes());
        return detailOf(projectId, updateWithOptimisticRetry(projectId, code, current -> current
                .reviseText(
                        correction.name() != null ? correction.name() : current.name(),
                        correction.context() != null ? correction.context() : current.context(),
                        correction.decision() != null ? correction.decision() : current.decision(),
                        correction.consequences() != null ? correction.consequences() : current.consequences(),
                        correction.alternatives() != null ? correction.alternatives() : current.alternatives(),
                        correction.decisionDate() != null ? correction.decisionDate() : current.decisionDate())
                .reviseReferences(
                        requirements != null ? requirements : current.addressesRequirements(),
                        contexts != null ? contexts : current.affectsContexts(),
                        peers != null ? peers : current.relatedTo())));
    }

    @Override
    public AdrDetail supersede(ProjectId projectId, AdrCode code, AdrCode supersededCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(supersededCode, "supersededCode");
        if (code.equals(supersededCode)) {
            throw new IllegalArgumentException("an ADR must not supersede itself: " + code.value());
        }
        // Only the superseding decision's identity is resolved once, outside the retry loop: an
        // unknown code must abort immediately, and identity is stable for a given code - it cannot
        // itself go stale the way status can. Its ACCEPTED status is deliberately NOT checked here
        // (kogn-io/arknet#359): a check before the loop only ever sees the state at the moment this
        // call started, and the superseding decision can be deprecated or superseded itself in the
        // window between that read and this call's own write - reachable through nothing more exotic
        // than an ordinary concurrent adr_set_status/adr_supersede on it, no store-first edit needed.
        // Landing this call's write regardless would violate the very port contract it is meant to
        // enforce ({@code SupersedeAdr}: "must already be ACCEPTED") without anything in the store
        // ever rejecting it - and if the superseding decision itself later becomes SUPERSEDED, it
        // would couple straight into the P0 kogn-io/arknet#359 fixed elsewhere in this issue. The
        // mutation below therefore re-reads and re-checks the superseding decision's status on every
        // retry attempt instead, against whatever state that attempt actually observes - cheap
        // (single lookup by code) relative to the class of inconsistency it closes.
        AdrId supersedingId = repository.findByCode(projectId, code)
                .orElseThrow(() -> new AdrNotFoundException(projectId, code))
                .id();
        return detailOf(projectId, updateWithOptimisticRetry(projectId, supersededCode, current -> {
            // Idempotency first, before the fresh status check below: Adr#supersededBy would take
            // this very same early return internally, but taking it here too means recording the
            // same pair a second time never depends on the superseding decision's current status -
            // only pairing with a genuinely new successor does. Without this, a superseding decision
            // that became DEPRECATED/SUPERSEDED after its first, already-recorded adr_supersede would
            // turn a promised no-op into a failure.
            if (supersedingId.equals(current.supersededBy())) {
                return current;
            }
            requireSupersedingIsAccepted(projectId, code);
            return current.supersededBy(supersedingId);
        }));
    }

    /**
     * Re-reads the superseding decision by its business code and refuses unless it is currently
     * {@link AdrStatus#ACCEPTED} - the per-attempt half of {@link #supersede}'s status check
     * (kogn-io/arknet#359). {@code code} is known to resolve at this point (the identity was already
     * looked up once in {@link #supersede} before entering the retry), but the status behind it is
     * re-read fresh on every attempt, so a concurrent transition landing before that read is always
     * seen rather than written over.
     *
     * <p>This narrows the window rather than closing it: the CAS token this call holds belongs to
     * the <em>superseded</em> decision, not to the superseding one re-read here, so a successor that
     * is deprecated or superseded between this read and the attempt's own write still lets that
     * write land. The outcome there is a legal supersession chain rather than a refusal, which is
     * why the remainder is left open instead of being pulled into the write transaction (a second
     * CAS token on the superseding decision, an out-port change) - see kogn-io/arknet#359.</p>
     */
    private void requireSupersedingIsAccepted(ProjectId projectId, AdrCode code) {
        AdrStatus status = repository.findByCode(projectId, code)
                .orElseThrow(() -> new AdrNotFoundException(projectId, code))
                .status();
        if (status != AdrStatus.ACCEPTED) {
            throw new IllegalStateException("ADR " + code.value()
                    + " can only supersede another decision while ACCEPTED, was " + status);
        }
    }

    @Override
    public void delete(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Adr adr = repository.findByCode(projectId, code)
                .orElseThrow(() -> new AdrNotFoundException(projectId, code));
        if (adr.status() != AdrStatus.PROPOSED) {
            throw new AdrNotDeletableException(code, adr.status());
        }
        rejectIfReferenced(projectId, code, adr.id());
        repository.delete(projectId, code);
    }

    /**
     * Refuses a delete while another decision still points at {@code id}, naming every referrer and
     * the edge it points with. Both relations are this hexagon's own, so the two reverse reads
     * {@code adr_get} already pays answer the question here too - no new port, and a message in the
     * codes the caller typed rather than in bare identities.
     *
     * <p>This check is didactic, not the guard: it reads before the write transaction opens, so a
     * reference committed in between would slip past it. {@link AdrRepository#delete} repeats it
     * against its own transaction and raises the very same exception - what this one buys is that the
     * common case is rejected with the concrete referrers in hand rather than by a race-free but
     * later check.</p>
     *
     * <p><strong>Labelled {@code supersededBy}, the current write shape.</strong>
     * {@link AdrRepository#findSupersessionReferrers} unions two sources - the current-model
     * {@code arkarch:supersededBy} edge and a store-first (ADR-005) pre-#357
     * {@code arkarch:supersedes} edge - into one flat list of codes, so a single label cannot be
     * exactly right for both (kogn-io/arknet#359). {@code supersededBy} is chosen because it is the
     * only shape any write path still produces; a legacy {@code supersedes} referrer, reachable only
     * through store-first data, is the rare case this didactic pre-check may mislabel. The race-free
     * backstop ({@link AdrRepository#delete}) does not share this limitation - it reads the two
     * predicates separately and labels each correctly.</p>
     */
    private void rejectIfReferenced(ProjectId projectId, AdrCode code, AdrId id) {
        List<AdrReferencedException.Reference> references = Stream.concat(
                repository.findSupersessionReferrers(projectId, id).stream()
                        .map(referrer -> new AdrReferencedException.Reference(
                                referrer, AdrReferencedException.SUPERSEDED_BY)),
                repository.findRelatedCodes(projectId, id).stream()
                        .map(referrer -> new AdrReferencedException.Reference(
                                referrer, AdrReferencedException.RELATED_TO)))
                .toList();
        if (!references.isEmpty()) {
            throw new AdrReferencedException(projectId, code, references);
        }
    }

    /**
     * Read-modify-write helper behind {@link #accept}, {@link #reject}, {@link #deprecate},
     * {@link #supersede} and {@link #update}: reads the current decision and its concurrency token together via
     * {@link AdrRepository#findCurrentByCode},
     * derives the next state via {@code mutation}, and writes it back via
     * {@link AdrRepository#compareAndUpdate} - retrying with a fresh read whenever a concurrent
     * writer commits a change in between. This is the guard the bounded-context and requirements
     * contexts had to retrofit; building it in from the start is what
     * keeps two parallel {@code adr_supersede} calls on the same decision from silently losing one
     * another's edge.
     *
     * <p>{@code mutation} returning its input unchanged (by {@link Object#equals}) is treated as a
     * no-op: accepting an already-accepted decision, recording an already-recorded supersede, or
     * correcting a field to the value it already holds skips the write entirely - no revision, no
     * moved head.</p>
     *
     * @throws AdrNotFoundException             if no decision with {@code code} exists
     * @throws AdrConcurrentlyModifiedException if the write keeps losing the race across every retry
     *                                          attempt
     */
    private Adr updateWithOptimisticRetry(
            ProjectId projectId, AdrCode code, UnaryOperator<Adr> mutation) {
        AdrConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            AdrRepository.CurrentAdr current = repository.findCurrentByCode(projectId, code)
                    .orElseThrow(() -> new AdrNotFoundException(projectId, code));
            Adr updated = mutation.apply(current.value());
            if (updated.equals(current.value())) {
                return current.value();
            }
            try {
                repository.compareAndUpdate(projectId, current.head(), updated);
                return updated;
            } catch (AdrConcurrentlyModifiedException e) {
                // A concurrent writer replaced the decision between our read and our write - retry
                // against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    /**
     * Wraps one decision into the {@link AdrDetail} projection every driving port returns: its
     * {@code relatedTo} identities resolved to codes in one lookup, plus both supersession
     * directions and the backward direction of {@code relatedTo}, each read from the store. Four
     * cheap reads, and only on the single-decision paths - {@link #list} derives every direction
     * from its one full read (plus one bulk legacy read) instead.
     */
    private AdrDetail detailOf(ProjectId projectId, Adr adr) {
        Map<AdrId, AdrCode> codes = adr.relatedTo().isEmpty()
                ? Map.of()
                : repository.findCodesByIds(projectId, adr.relatedTo());
        return new AdrDetail(adr,
                repository.findSupersededCodes(projectId, adr.id()),
                repository.findSupersedingCodes(projectId, adr.id()),
                mergedRelatedCodes(codesOf(adr.relatedTo(), codes),
                        repository.findRelatedCodes(projectId, adr.id())));
    }

    /**
     * Unions a decision's own {@code relatedTo} codes with the codes of the decisions pointing back
     * at it, deduplicated and ordered by running number - the single list {@link AdrDetail#relatedTo}
     * promises. Deduplication is what makes a mutually declared pair ({@code A relatedTo B} and
     * {@code B relatedTo A}, which nothing forbids) report each peer once instead of twice.
     */
    private static List<AdrCode> mergedRelatedCodes(List<AdrCode> forward, List<AdrCode> backward) {
        TreeSet<String> merged = new TreeSet<>(CODE_BY_RUNNING_NUMBER);
        forward.forEach(code -> merged.add(code.value()));
        backward.forEach(code -> merged.add(code.value()));
        return merged.stream().map(AdrCode::new).toList();
    }

    /** Renders one in-memory inversion bucket as sorted codes; an absent bucket is an empty list. */
    private static List<AdrCode> sortedCodes(TreeSet<String> codes) {
        return codes == null ? List.of() : codes.stream().map(AdrCode::new).toList();
    }

    /**
     * Resolves {@code relatedTo} business codes to this hexagon's own identities, rejecting an
     * unknown one outright. Deliberately not a lookup port: unlike a requirement or a bounded
     * context, a peer decision is a resource of this very hexagon, so {@link AdrRepository#findByCode}
     * answers it and a miss is a plain {@link AdrNotFoundException} - the same choice
     * {@link #supersede} made for its target.
     */
    private List<AdrId> resolvePeers(ProjectId projectId, List<String> codes) {
        return codes.stream()
                .map(AdrCode::new)
                .distinct()
                .map(peer -> repository.findByCode(projectId, peer)
                        .map(Adr::id)
                        .orElseThrow(() -> new AdrNotFoundException(projectId, peer)))
                .toList();
    }

    /**
     * Maps identities to codes in the order they were held, dropping any that no longer resolve - a
     * decision deleted store-first (ADR-005) leaves a dangling identity behind, and a display path
     * must not fail over it.
     */
    private static List<AdrCode> codesOf(List<AdrId> ids, Map<AdrId, AdrCode> codes) {
        List<AdrCode> resolved = new ArrayList<>(ids.size());
        for (AdrId id : ids) {
            AdrCode code = codes.get(id);
            if (code != null) {
                resolved.add(code);
            }
        }
        return List.copyOf(resolved);
    }

    /**
     * Derives the next free business code in {@code projectId}: the highest running number the
     * project has ever used, plus one (starting at 1).
     *
     * <p><strong>Ever used, not currently in use.</strong> The maximum runs over every recorded
     * decision's code <em>and</em> the codes {@link AdrRepository#findRetainedCodes} kept from
     * deleted ones. Over the living ones alone, deleting the highest-numbered decision would let the
     * maximum fall back and the next {@code adr_add} hand out that same number again - and a code
     * that already appeared in a commit message or a note would then name something else
     * entirely. Numbers are cheap; a re-used one is a false trail.</p>
     *
     * <p><strong>{@link AdrRepository#findAllCodes}, not {@link AdrRepository#findAll}
     * (kogn-io/arknet#359).</strong> A living decision can still be skipped by {@link #list}/
     * {@link AdrRepository#findAll} - a store-first (ADR-005) status/{@code supersededBy}
     * disagreement, or an unrecognised status - without ceasing to exist or freeing its code. Deriving
     * the maximum from {@code findAll} would let such a decision's number be recomputed and handed
     * out again the moment it holds the project's highest number, and every retry of that collision
     * recomputes the very same number, so {@link CodeAssignment#createRetryingOnCodeCollision} cannot
     * retry its way out - {@code adr_add} would be dead for the project rather than merely racing.
     * {@code findAllCodes} reads only the mandatory identifier/type pair, which no read-time
     * tolerance ever skips, so the number this method derives never depends on whether an existing
     * decision happens to be materialisable right now.</p>
     */
    private AdrCode nextCode(ProjectId projectId) {
        int highestLiving = repository.findAllCodes(projectId).stream()
                .mapToInt(AdrService::runningNumber)
                .max()
                .orElse(0);
        int highestRetained = repository.findRetainedCodes(projectId).stream()
                .mapToInt(AdrService::runningNumber)
                .max()
                .orElse(0);
        return new AdrCode(CODE_PREFIX + "-" + (Math.max(highestLiving, highestRetained) + 1));
    }

    @Override
    public int skippedCount(ProjectId projectId, int materialisedCount) {
        Objects.requireNonNull(projectId, "projectId");
        if (materialisedCount < 0) {
            throw new IllegalArgumentException("materialisedCount must not be negative: " + materialisedCount);
        }
        // findAllCodes never skips a recorded decision (see its own javadoc); the count the caller
        // hands in is exactly the subset its own list() could materialise. The difference is what
        // list() silently dropped. Clamped at zero rather than trusted blindly: the caller's read and
        // this one are unsynchronised, so a decision created in between would briefly overcount
        // findAllCodes against it, and a negative "skipped" count would be a worse signal than a
        // merely stale zero.
        int total = repository.findAllCodes(projectId).size();
        return Math.max(0, total - materialisedCount);
    }

    /** Parses the running number from a code such as {@code ADR-7} (0 if not parseable). */
    private static int runningNumber(AdrCode code) {
        String value = code.value();
        int dash = value.lastIndexOf('-');
        if (dash < 0 || dash == value.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
