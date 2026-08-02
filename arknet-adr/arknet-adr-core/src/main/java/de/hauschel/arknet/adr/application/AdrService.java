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

import de.hauschel.arknet.adr.application.port.in.AcceptAdr;
import de.hauschel.arknet.adr.application.port.in.AddAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.DeprecateAdr;
import de.hauschel.arknet.adr.application.port.in.GetAdr;
import de.hauschel.arknet.adr.application.port.in.ListAdrs;
import de.hauschel.arknet.adr.application.port.in.RejectAdr;
import de.hauschel.arknet.adr.application.port.in.SupersedeAdr;
import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.application.port.out.BoundedContextLookup;
import de.hauschel.arknet.adr.application.port.out.RequirementLookup;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.AdrStatus;
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
 * an accepted one may further become {@link AdrStatus#DEPRECATED}. {@link AdrStatus#SUPERSEDED} is
 * not a value this service ever writes - it stays derived-only from {@code adr_supersede}'s
 * {@code supersedes}/{@code supersededBy} reverse-read (see {@link Adr#supersede}).</p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} recomputes its next code against a fresh read
 * whenever a concurrent {@code adr_add} claims the same {@code ADR-N} first, via
 * {@link CodeAssignment#createRetryingOnCodeCollision}, and every read-modify-write path
 * ({@link #accept}, {@link #reject}, {@link #deprecate}, {@link #supersede}) retries its whole round
 * trip via {@link AdrRepository#compareAndUpdate} whenever a concurrent writer commits in between - see
 * {@link #updateWithOptimisticRetry}. Neither race is visible to a well-formed caller; only
 * sustained, pathological contention on the very same decision surfaces as
 * {@link AdrConcurrentlyModifiedException}. Parallel sessions of one user against one local store
 * are the normal case, not a remote/multi-writer concern (ADR-001).</p>
 *
 * <p><strong>Where each reference is resolved.</strong> The two cross-context codes are resolved
 * once, here, before anything is written - an unresolvable one must abort the whole
 * {@code adr_add} rather than leave a half-linked decision behind, which is also why resolution sits
 * <em>outside</em> the code-assignment retry: an unknown {@code FR-9} is not a code collision and
 * must not be retried. The self-referential {@code supersedes} target needs no lookup port at all:
 * it is this hexagon's own resource, resolved through {@link AdrRepository#findByCode}.</p>
 */
public class AdrService implements AddAdr, ListAdrs, GetAdr, AcceptAdr, RejectAdr, DeprecateAdr, SupersedeAdr {

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
        // Identity is opaque and stable, so it is minted once, outside the retry: only the business
        // code is recomputed when a concurrent adr_add claims the same candidate first. See
        // CodeAssignment for why that race exists and why it must retry rather than surface the
        // out-adapter's uniqueness guard as a caller-visible failure.
        AdrId id = new AdrId(resourceIdFactory.newId());
        Adr created = CodeAssignment.createRetryingOnCodeCollision(
                DuplicateAdrCodeException.class, () -> {
                    AdrCode code = nextCode(projectId);
                    Adr adr = new Adr(id, code, command.name(), AdrStatus.PROPOSED, command.context(),
                            command.decision(), command.consequences(), command.alternatives(),
                            command.decisionDate(), requirements, contexts, List.of());
                    repository.create(projectId, adr);
                    return adr;
                });
        return detailOf(projectId, created);
    }

    @Override
    public List<AdrDetail> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        List<Adr> all = repository.findAll(projectId);
        // Both directions come out of the one read: with every decision in hand, the forward edges
        // can simply be inverted in memory - no reverse query, and no second round trip per row.
        Map<AdrId, AdrCode> codes = new LinkedHashMap<>();
        all.forEach(adr -> codes.putIfAbsent(adr.id(), adr.code()));
        Map<AdrId, TreeSet<String>> supersededBy = new LinkedHashMap<>();
        for (Adr adr : all) {
            for (AdrId superseded : adr.supersedes()) {
                supersededBy.computeIfAbsent(superseded, key -> new TreeSet<>(CODE_BY_RUNNING_NUMBER))
                        .add(adr.code().value());
            }
        }
        return all.stream()
                .map(adr -> new AdrDetail(adr, codesOf(adr.supersedes(), codes),
                        supersededBy.getOrDefault(adr.id(), new TreeSet<>(CODE_BY_RUNNING_NUMBER)).stream()
                                .map(AdrCode::new).toList()))
                .toList();
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
    public AdrDetail supersede(ProjectId projectId, AdrCode code, AdrCode supersededCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(supersededCode, "supersededCode");
        if (code.equals(supersededCode)) {
            throw new IllegalArgumentException("an ADR must not supersede itself: " + code.value());
        }
        // Resolved once, outside the retry loop: the superseded decision's identity does not depend
        // on the superseding one's current state, and an unknown code must abort immediately and
        // leave the superseding decision untouched.
        AdrId supersededId = repository.findByCode(projectId, supersededCode)
                .map(Adr::id)
                .orElseThrow(() -> new AdrNotFoundException(projectId, supersededCode));
        return detailOf(projectId,
                updateWithOptimisticRetry(projectId, code, current -> current.supersede(supersededId)));
    }

    /**
     * Read-modify-write helper behind {@link #accept}, {@link #reject}, {@link #deprecate} and
     * {@link #supersede}: reads the current decision and its concurrency token together via
     * {@link AdrRepository#findCurrentByCode},
     * derives the next state via {@code mutation}, and writes it back via
     * {@link AdrRepository#compareAndUpdate} - retrying with a fresh read whenever a concurrent
     * writer commits a change in between. This is the guard the bounded-context and requirements
     * contexts had to retrofit; building it in from the start is what
     * keeps two parallel {@code adr_supersede} calls on the same decision from silently losing one
     * another's edge.
     *
     * <p>{@code mutation} returning its input unchanged (by {@link Object#equals}) is treated as a
     * no-op: accepting an already-accepted decision, or recording an already-recorded supersede,
     * skips the write entirely - no revision, no moved head.</p>
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
     * {@code supersedes} identities resolved to codes, plus the backward direction read from the
     * store. Two cheap reads, and only on the single-decision paths - {@link #list} derives both
     * directions from its one full read instead.
     */
    private AdrDetail detailOf(ProjectId projectId, Adr adr) {
        Map<AdrId, AdrCode> codes = adr.supersedes().isEmpty()
                ? Map.of()
                : repository.findCodesByIds(projectId, adr.supersedes());
        return new AdrDetail(adr, codesOf(adr.supersedes(), codes),
                repository.findSupersedingCodes(projectId, adr.id()));
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
     * Derives the next free business code in {@code projectId}: the highest running number currently
     * in use, plus one (starting at 1).
     */
    private AdrCode nextCode(ProjectId projectId) {
        int next = repository.findAll(projectId).stream()
                .mapToInt(adr -> runningNumber(adr.code()))
                .max()
                .orElse(0) + 1;
        return new AdrCode(CODE_PREFIX + "-" + next);
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
