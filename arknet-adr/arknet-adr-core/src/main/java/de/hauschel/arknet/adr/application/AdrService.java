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
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.hauschel.arknet.adr.application.port.in.AcceptAdr;
import de.hauschel.arknet.adr.application.port.in.AddAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
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
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsequenceCorrection;
import de.hauschel.arknet.adr.domain.ConsideredOption;
import de.hauschel.arknet.adr.domain.ConsideredOptionCorrection;
import de.hauschel.arknet.adr.domain.DuplicateAdrCodeException;
import de.hauschel.arknet.adr.domain.NewConsequence;
import de.hauschel.arknet.adr.domain.NewConsideredOption;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.LanguageTag;
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
 * existing decision field by field - see {@link Adr#reviseText}/{@link Adr#withConsequenceCorrections}/
 * {@link Adr#withConsideredOptionCorrections}/{@link Adr#reviseReferences} for the exact per-field
 * rules; this service only fills the caller's untouched fields from the current state, resolves the
 * language each touched multilingual field is written under, and hands the result to the same CAS
 * helper every other write path uses. {@link #delete} is the one path that removes a decision instead
 * of changing it, and it is staged by status as well - only a {@link AdrStatus#PROPOSED} one may go,
 * because what this lifecycle protects is a decision and not a draft (Nygard); every other status is
 * refused with {@link AdrNotDeletableException} and pointed at the path that fits it. While another
 * decision points at it, the delete is refused outright with {@link AdrReferencedException} rather
 * than orphaning that edge, and the code of a deleted decision stays out of circulation -
 * {@link #nextCode} counts retained codes as used, so {@code ADR-7} never names two different
 * decisions over a project's lifetime.</p>
 *
 * <p><strong>Consequences and considered options (kogn-io/arknet#357).</strong> {@code adr_add}
 * takes both as lists of structured drafts ({@link NewConsequence}/{@link NewConsideredOption}),
 * numbered {@code 1..n} in call order - see {@link #toPositionedConsequences}/
 * {@link #toPositionedConsideredOptions}, mirroring
 * {@code RequirementService#toPositionedAcceptanceCriteria} (issue #266). {@code adr_update} can
 * append further ones in any status, and correct an existing position's content only while
 * {@link AdrStatus#PROPOSED} - the exact rules live on {@link Adr#withAppendedConsequences}/
 * {@link Adr#withConsequenceCorrections} (and their {@code ConsideredOption} counterparts), not
 * here.</p>
 *
 * <p><strong>Language.</strong> {@code name}/{@code context}/{@code decision}, every consequence's
 * {@code statement} and every considered option's {@code name}/{@code rationale} may each legally
 * carry several language-tagged variants. Unlike the requirements bounded context's per-field
 * {@code language} arguments, {@code adr_add}/{@code adr_update} take a single {@code language} for
 * the whole call (mirroring {@code req_add}'s, not {@code req_update}'s, shape) - a deliberate
 * simplification, since one decision is authored in one language at a time far more often than one
 * requirement's title and description are corrected in two different languages within the same call.
 * A field/position this call does not touch always round-trips under the exact tag it already
 * carried ({@link AdrRepository.CurrentAdr#nameLanguage()}/{@code contextLanguage}/
 * {@code decisionLanguage}/{@code consequenceLanguageByPosition}/{@code optionLanguageByPosition}) -
 * a scoped no-op at the store, never a retag, the same principle
 * {@code RequirementService#resolveTouchedLanguage} follows. A field/position this call does touch
 * resolves a language via {@link LanguageTag#resolveWriteLanguage} only when the write actually needs
 * one (issue #258: {@code accept}/{@code reject}/{@code deprecate}/{@code supersede} never touch any
 * multilingual field and therefore never demand a {@code defaultLanguage} the project may not have).</p>
 *
 * <p><strong>The fine-grained text-immutability exemption is call-scoped, not field-scoped
 * (kogn-io/arknet#357).</strong> Because one {@code language} argument governs the whole call, this
 * service computes a single {@code newLanguageVariant} boolean per {@code update} call: whether the
 * resolved write language is entirely absent from
 * {@link AdrRepository.CurrentAdr#nameContextDecisionLanguages()} (the union of every language tag
 * currently present across {@code name}/{@code context}/{@code decision}). If so, the whole call is
 * exempt from {@link Adr#reviseText}'s status gate for every field it touches - a call that is really
 * adding a translation. If the resolved language is already used by even one of the three fields, the
 * whole call is treated as editing existing content and is not exempt for any of them. See
 * {@link Adr#reviseText}'s own javadoc for the full rule and the trade-off this simplification makes.
 * Appending a consequence/considered option is never gated at all (any status); correcting an
 * existing one in place is gated exactly like {@code name}/{@code context}/{@code decision}, but
 * without this exemption - see {@link Adr#withConsequenceCorrections}'s javadoc for why.</p>
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
        implements AddAdr, ListAdrs, GetAdr, AcceptAdr, RejectAdr, DeprecateAdr, SupersedeAdr, UpdateAdr,
        DeleteAdr {

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
    public AdrDetail add(ProjectId projectId, NewAdr command, String defaultLanguage) {
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
        // A brand-new decision is written entirely in one language: resolved once, outside the
        // retry, and applied uniformly to name/context/decision and every consequence/option text.
        String language = LanguageTag.resolveWriteLanguage(command.language(), defaultLanguage);
        List<Consequence> consequences = toPositionedConsequences(command.consequences());
        List<ConsideredOption> consideredOptions = toPositionedConsideredOptions(command.consideredOptions());
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
                            command.decision(), consequences, consideredOptions, command.decisionDate(),
                            requirements, contexts, null, peers);
                    repository.create(projectId, adr, language);
                    return adr;
                });
        return detailOf(projectId, created);
    }

    /** Numbers freshly authored consequences {@code 1..n} in call order (mirrors requirements #266). */
    private static List<Consequence> toPositionedConsequences(List<NewConsequence> drafts) {
        List<Consequence> consequences = new ArrayList<>();
        int position = 1;
        for (NewConsequence draft : drafts) {
            consequences.add(new Consequence(position++, draft.statement(), draft.type()));
        }
        return consequences;
    }

    /** {@link #toPositionedConsequences} for considered options. */
    private static List<ConsideredOption> toPositionedConsideredOptions(List<NewConsideredOption> drafts) {
        List<ConsideredOption> options = new ArrayList<>();
        int position = 1;
        for (NewConsideredOption draft : drafts) {
            options.add(new ConsideredOption(position++, draft.name(), draft.rationale(), draft.outcome()));
        }
        return options;
    }

    @Override
    public List<AdrDetail> list(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        List<Adr> all = repository.findAll(projectId, displayLocale);
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
    public Optional<AdrDetail> get(ProjectId projectId, AdrCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code, displayLocale).map(adr -> detailOf(projectId, adr));
    }

    @Override
    public AdrDetail accept(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // accept() never touches any multilingual field - null language/defaultLanguage is safe
        // even on a project that has one configured, since resolution is never reached (issue #258).
        return detailOf(projectId, updateWithOptimisticRetry(projectId, code, false, false, false,
                Set.of(), Set.of(), null, null, current -> current.value().accept()));
    }

    @Override
    public AdrDetail reject(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return detailOf(projectId, updateWithOptimisticRetry(projectId, code, false, false, false,
                Set.of(), Set.of(), null, null, current -> current.value().reject()));
    }

    @Override
    public AdrDetail deprecate(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return detailOf(projectId, updateWithOptimisticRetry(projectId, code, false, false, false,
                Set.of(), Set.of(), null, null, current -> current.value().deprecate()));
    }

    @Override
    public AdrDetail update(ProjectId projectId, AdrCode code, AdrCorrection correction, String defaultLanguage) {
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
        boolean nameTouched = correction.name() != null;
        boolean contextTouched = correction.context() != null;
        boolean decisionTouched = correction.decision() != null;
        Set<Integer> touchedConsequencePositions = correction.consequenceCorrections().stream()
                .map(ConsequenceCorrection::position)
                .collect(Collectors.toUnmodifiableSet());
        Set<Integer> touchedOptionPositions = correction.consideredOptionCorrections().stream()
                .map(ConsideredOptionCorrection::position)
                .collect(Collectors.toUnmodifiableSet());
        Adr updated = updateWithOptimisticRetry(projectId, code, nameTouched, contextTouched, decisionTouched,
                touchedConsequencePositions, touchedOptionPositions, correction.language(), defaultLanguage,
                current -> current.value()
                        .reviseText(
                                correction.name() != null ? correction.name() : current.value().name(),
                                correction.context() != null ? correction.context() : current.value().context(),
                                correction.decision() != null ? correction.decision() : current.value().decision(),
                                correction.decisionDate() != null
                                        ? correction.decisionDate() : current.value().decisionDate(),
                                newLanguageVariant(current, correction.language(), defaultLanguage,
                                        nameTouched || contextTouched || decisionTouched))
                        .withAppendedConsequences(correction.newConsequences())
                        .withConsequenceCorrections(projectId, correction.consequenceCorrections())
                        .withAppendedConsideredOptions(correction.newConsideredOptions())
                        .withConsideredOptionCorrections(projectId, correction.consideredOptionCorrections())
                        .reviseReferences(
                                requirements != null ? requirements : current.value().addressesRequirements(),
                                contexts != null ? contexts : current.value().affectsContexts(),
                                peers != null ? peers : current.value().relatedTo()));
        return detailOf(projectId, updated);
    }

    /**
     * Whether {@code language} (or, if {@code null}, {@code defaultLanguage}) names a language tag
     * entirely absent from {@code current}'s {@code name}/{@code context}/{@code decision} - the
     * single, call-scoped flag {@link Adr#reviseText} uses to decide whether this call is exempt from
     * its status gate. Resolved lazily: only when {@code textTouched} says this call actually names
     * one of the three fields, so a call that leaves all three alone (e.g. one only appending a
     * consequence) never demands a {@code defaultLanguage} the project may not have.
     */
    private static boolean newLanguageVariant(AdrRepository.CurrentAdr current, String language,
            String defaultLanguage, boolean textTouched) {
        if (!textTouched) {
            return false;
        }
        String resolved = LanguageTag.resolveWriteLanguage(language, defaultLanguage);
        return !current.nameContextDecisionLanguages().contains(resolved);
    }

    @Override
    public AdrDetail supersede(ProjectId projectId, AdrCode code, AdrCode supersededCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(supersededCode, "supersededCode");
        if (code.equals(supersededCode)) {
            throw new IllegalArgumentException("an ADR must not supersede itself: " + code.value());
        }
        // The superseding decision is resolved and status-checked once, outside the retry loop:
        // its identity and status do not depend on the superseded decision's current state, and an
        // unknown code or a not-yet-ACCEPTED status must abort immediately, before the superseded
        // decision - the one this call actually writes (kogn-io/arknet#357) - is touched at all.
        Adr superseding = repository.findByCode(projectId, code, null)
                .orElseThrow(() -> new AdrNotFoundException(projectId, code));
        if (superseding.status() != AdrStatus.ACCEPTED) {
            throw new IllegalStateException("ADR " + code.value()
                    + " can only supersede another decision while ACCEPTED, was " + superseding.status());
        }
        AdrId supersedingId = superseding.id();
        // The CAS retry now runs on the superseded code, not the superseding one: that is the
        // record this write actually replaces. AdrStatus#ACCEPTED is enforced a second time inside
        // the retried mutation itself (Adr#supersededBy), against whatever state each retry attempt
        // re-reads. Never touches any multilingual field.
        return detailOf(projectId, updateWithOptimisticRetry(projectId, supersededCode, false, false, false,
                Set.of(), Set.of(), null, null, current -> current.value().supersededBy(supersedingId)));
    }

    @Override
    public void delete(ProjectId projectId, AdrCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Adr adr = repository.findByCode(projectId, code, null)
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
     */
    private void rejectIfReferenced(ProjectId projectId, AdrCode code, AdrId id) {
        List<AdrReferencedException.Reference> references = Stream.concat(
                repository.findSupersessionReferrers(projectId, id).stream()
                        .map(referrer -> new AdrReferencedException.Reference(
                                referrer, AdrReferencedException.SUPERSEDES)),
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
     * {@link #supersede} and {@link #update}: reads the current decision and its concurrency token
     * together via {@link AdrRepository#findCurrentByCode}, derives the next state via
     * {@code mutation}, resolves the language each touched field/position is written under, and
     * writes the result back via {@link AdrRepository#compareAndUpdate} - retrying with a fresh read
     * whenever a concurrent writer commits a change in between. This is the guard the
     * bounded-context and requirements contexts had to retrofit; building it in from the start is
     * what keeps two parallel {@code adr_supersede} calls on the same decision from silently losing
     * one another's edge.
     *
     * <p>{@code mutation} returning its input unchanged (by {@link Object#equals}) is treated as a
     * no-op: accepting an already-accepted decision, recording an already-recorded supersede, or
     * correcting a field to the value it already holds skips the write entirely - no revision, no
     * moved head.</p>
     *
     * @param nameTouched                   whether this call itself names {@code name}; an untouched
     *                                      field's language always passes through {@code current}'s
     *                                      own tag rather than resolving a fresh one
     * @param contextTouched                {@code nameTouched}'s counterpart for {@code context}
     * @param decisionTouched               {@code nameTouched}'s counterpart for {@code decision}
     * @param touchedConsequencePositions   the positions this call's own {@code
     *                                      consequenceCorrections} name - a newly appended position
     *                                      (absent from {@code current} entirely) always resolves
     *                                      fresh regardless of this set
     * @param touchedOptionPositions        {@code touchedConsequencePositions}'s counterpart for
     *                                      {@code consideredOptionCorrections}
     * @param language                      the call's own language argument, or {@code null}
     * @param defaultLanguage               the project's configured fallback, or {@code null} -
     *                                      resolved together with {@code language} only when a
     *                                      touched field/position actually needs it (issue #258)
     * @throws AdrNotFoundException             if no decision with {@code code} exists
     * @throws AdrConcurrentlyModifiedException if the write keeps losing the race across every retry
     *                                          attempt
     */
    private Adr updateWithOptimisticRetry(ProjectId projectId, AdrCode code, boolean nameTouched,
            boolean contextTouched, boolean decisionTouched, Set<Integer> touchedConsequencePositions,
            Set<Integer> touchedOptionPositions, String language, String defaultLanguage,
            Function<AdrRepository.CurrentAdr, Adr> mutation) {
        AdrConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            AdrRepository.CurrentAdr current = repository.findCurrentByCode(projectId, code)
                    .orElseThrow(() -> new AdrNotFoundException(projectId, code));
            Adr updated = mutation.apply(current);
            if (updated.equals(current.value())) {
                return current.value();
            }
            String nameLanguage = touchedLanguage(nameTouched, current.nameLanguage(), language, defaultLanguage);
            String contextLanguage =
                    touchedLanguage(contextTouched, current.contextLanguage(), language, defaultLanguage);
            String decisionLanguage =
                    touchedLanguage(decisionTouched, current.decisionLanguage(), language, defaultLanguage);
            Map<Integer, String> consequenceLanguageByPosition = positionLanguages(
                    updated.consequences().stream().map(Consequence::position).toList(),
                    current.consequenceLanguageByPosition(), touchedConsequencePositions, language, defaultLanguage);
            Map<Integer, String> optionLanguageByPosition = positionLanguages(
                    updated.consideredOptions().stream().map(ConsideredOption::position).toList(),
                    current.optionLanguageByPosition(), touchedOptionPositions, language, defaultLanguage);
            try {
                repository.compareAndUpdate(projectId, current.head(), updated, nameLanguage, contextLanguage,
                        decisionLanguage, consequenceLanguageByPosition, optionLanguageByPosition, defaultLanguage);
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
     * The BCP-47 tag a scalar field ({@code name}/{@code context}/{@code decision}) is written
     * under: freshly resolved via {@link LanguageTag#resolveWriteLanguage} when {@code touched},
     * otherwise {@code currentLanguage} unchanged (a scoped no-op, not a retag).
     */
    private static String touchedLanguage(
            boolean touched, String currentLanguage, String language, String defaultLanguage) {
        return touched ? LanguageTag.resolveWriteLanguage(language, defaultLanguage) : currentLanguage;
    }

    /**
     * The BCP-47 tag each position in {@code positions} is written under: a position absent from
     * {@code currentLanguageByPosition} (a newly appended consequence/option) always resolves fresh;
     * an existing position named in {@code touchedPositions} (this call's own corrections) also
     * resolves fresh; every other existing position round-trips under the tag
     * {@code currentLanguageByPosition} already carried for it.
     */
    private static Map<Integer, String> positionLanguages(List<Integer> positions,
            Map<Integer, String> currentLanguageByPosition, Set<Integer> touchedPositions, String language,
            String defaultLanguage) {
        Map<Integer, String> result = new LinkedHashMap<>();
        for (Integer position : positions) {
            boolean isNewPosition = !currentLanguageByPosition.containsKey(position);
            result.put(position, isNewPosition || touchedPositions.contains(position)
                    ? LanguageTag.resolveWriteLanguage(language, defaultLanguage)
                    : currentLanguageByPosition.get(position));
        }
        return result;
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
                .map(peer -> repository.findByCode(projectId, peer, null)
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
     * <p><strong>Ever used, not currently in use.</strong> The maximum runs over the living decisions
     * <em>and</em> the codes {@link AdrRepository#findRetainedCodes} kept from deleted ones. Over the
     * living ones alone, deleting the highest-numbered decision would let the maximum fall back and
     * the next {@code adr_add} hand out that same number again - and a code that already appeared in
     * a commit message or a note would then name something else entirely. Numbers are cheap; a
     * re-used one is a false trail.</p>
     */
    private AdrCode nextCode(ProjectId projectId) {
        int highestLiving = repository.findAll(projectId, null).stream()
                .mapToInt(adr -> runningNumber(adr.code()))
                .max()
                .orElse(0);
        int highestRetained = repository.findRetainedCodes(projectId).stream()
                .mapToInt(AdrService::runningNumber)
                .max()
                .orElse(0);
        return new AdrCode(CODE_PREFIX + "-" + (Math.max(highestLiving, highestRetained) + 1));
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
