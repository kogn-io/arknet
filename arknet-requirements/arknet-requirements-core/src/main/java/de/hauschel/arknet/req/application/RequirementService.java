// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.CodeCounter;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.AcceptRequirement;
import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirementSchema;
import de.hauschel.arknet.req.application.port.in.LinkConstraint;
import de.hauschel.arknet.req.application.port.in.LinkTerm;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.ProposeRequirement;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.UpdateRequirement;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.application.port.out.TermLookup;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.AcceptanceCriterionTextPatch;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintNotFoundException;
import de.hauschel.arknet.req.domain.ConstraintRef;
import de.hauschel.arknet.req.domain.DuplicateRequirementCodeException;
import de.hauschel.arknet.req.domain.MissingAcceptanceCriteriaException;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementSchemaTerm;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Application service implementing the requirement use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link RequirementRepository}
 * driven port. The component is wired as a plain object (constructor injection) by the
 * composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link RequirementId}) is opaque and minted once per
 * requirement via {@link ResourceIdFactory}; it never changes. The human-readable business code
 * ({@link RequirementCode}, {@code FR-N}/{@code NFR-N}) is assigned independently, where
 * {@code N} is one above the highest running number currently used by that type in the target
 * project (numbering is independent per type and per project). New requirements start
 * {@link RequirementStatus#PROPOSED}. The status is settable in both directions -
 * {@code PROPOSED -> ACCEPTED} via {@link #accept} and {@code ACCEPTED -> PROPOSED} via
 * {@link #propose} - see {@link Requirement#accept()}/{@link Requirement#propose()}, which own
 * those rules; this service only threads each through the read-modify-write round trip (issue
 * #291, ADR-019 point 4). Linking a
 * glossary term is idempotent and independent of the status lifecycle - terms may be linked to a
 * requirement in any status. {@link #linkConstraint} mirrors {@link #linkTerm} exactly for
 * {@code oslc_rm:constrainedBy}, resolving the human-typed {@link ConstraintCode} via the
 * constructor-injected {@link ConstraintRepository} instead of a cross-BC lookup port, since
 * {@link Constraint} lives inside this same bounded context.</p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} retries its next-code computation
 * against a fresh read whenever a concurrent caller claims the same code first, and {@link
 * #accept}/{@link #propose}/{@link #linkTerm}/{@link #update} retry their whole read-modify-write
 * round trip via {@link RequirementRepository#compareAndUpdate} whenever a concurrent writer
 * commits in between - see {@link #updateWithOptimisticRetry}. Neither race is visible to a
 * well-formed caller; only sustained, pathological contention on the very same requirement
 * surfaces as {@link RequirementConcurrentlyModifiedException}.</p>
 *
 * <p><strong>Correction.</strong> {@link #update} lets a caller correct a
 * requirement's title, description, rationale, acceptance criteria and/or MoSCoW priority after
 * the fact - e.g. once an interview sharpens a domain fact the original wording missed, or once a
 * prioritisation review finds a whole register sitting on {@code MUST_HAVE}. Every argument is
 * optional ({@code null} leaves that field unchanged, priority included - it is never a request
 * to remove one); a non-{@code null} value still has to satisfy {@link Requirement}'s own
 * invariants (non-blank title/description, a non-empty, duplicate-free acceptance-criteria
 * list), so a caller cannot use {@code update} to put the requirement into a state {@code
 * req_add} itself could never have created. Status and linked terms are untouched - {@link
 * #accept} and {@link #linkTerm} remain the only way to change those. The priority parameter
 * is an interim step that a generic {@code resource_update} facade is meant to
 * replace; see {@link UpdateRequirement}. If a requirement predates the mandatory
 * acceptance-criterion invariant (its criteria are currently a read-time placeholder, never a
 * store fact), {@code accept}/{@code linkTerm}/an {@code update} that leaves {@code
 * acceptanceCriteria} {@code null} all throw {@link MissingAcceptanceCriteriaException} instead
 * of silently turning that placeholder into a persisted literal - see
 * {@link #updateWithOptimisticRetry}. Passing real criteria to {@code update} is exactly how a
 * caller closes that gap.</p>
 *
 * <p><strong>Language.</strong> {@code title}/{@code description}/{@code rationale} may each
 * legally carry several language-tagged variants. {@link #updateWithOptimisticRetry} determines,
 * per field, whether {@code update}'s caller named it ({@code title != null}/
 * {@code description != null}/{@code rationale != null}) - but naming a field alone does not force
 * a fresh {@link
 * de.hauschel.arknet.kernel.LanguageTag#resolveWriteLanguage} resolution of that field's language:
 * a caller resending a field's already-current text as part of a full-state round trip, without
 * itself naming a {@code language}, must stay a no-op even on a project with no {@code
 * defaultLanguage} configured - exactly the case {@link #accept}/{@link #linkTerm} already rely on
 * (see below). A field only resolves a fresh write language (issue #258 - rejected, never silently
 * written untagged, if neither {@code language} nor {@code defaultLanguage} resolves it) when it
 * is named <em>and</em> either the caller supplied {@code language} explicitly or the supplied
 * text actually differs from what is stored (issue #271 - text equality alone used to also count
 * as untouched, which let a caller correcting an untagged/mistagged literal back to its own
 * already-current wording round-trip under the old tag, silently discarding the caller's {@code
 * language} argument). Every other field is written back under the exact tag {@link
 * RequirementRepository.CurrentRequirement#titleLanguage()}/
 * {@link RequirementRepository.CurrentRequirement#descriptionLanguage()}/
 * {@link RequirementRepository.CurrentRequirement#rationaleLanguage()} already carried - a
 * scoped no-op at the store, not a retag. This is what keeps {@link #accept}/{@link #linkTerm}
 * (which never touch either field and always call the helper with a {@code null} language and a
 * {@code null} defaultLanguage) from collapsing a multilingual title/description down to one
 * variant just because they do not know or care about language - the out-adapter preserves every
 * other language variant regardless, but only if it is told the correct tag to leave alone.</p>
 *
 * <p><strong>Rationale is optional, which the language machinery handles without a special case
 * (issue #321).</strong> A requirement carrying no {@code arkreq:rationale} at all reads back as
 * {@code null} with a {@code null}
 * {@link RequirementRepository.CurrentRequirement#rationaleLanguage()}, so an untouched
 * {@code rationale} round-trips {@code null}/{@code null} exactly the way an untouched
 * {@code title} round-trips its own tag - nothing is written, nothing is swept. The first
 * {@code update} that names one resolves a fresh write language like any other named field,
 * because {@code null} differs from the incoming text.</p>
 */
public class RequirementService implements AddRequirement, ListRequirements, GetRequirement,
        AcceptRequirement, ProposeRequirement, LinkTerm, LinkConstraint, UpdateRequirement, ResolveRequirements,
        GetRequirementSchema {

    /**
     * Bound on {@link #add}'s and {@link #updateWithOptimisticRetry}'s retry loops.
     * Both races this guards against - two callers computing the same next-free {@link
     * RequirementCode}, or two callers read-modify-writing the same requirement - are resolved by
     * a single retry in the overwhelming majority of cases, since each retry re-reads the
     * now-current state before trying again; this bound only exists so a pathological, sustained
     * storm of concurrent writers against the very same requirement fails loudly instead of
     * looping forever.
     *
     * <p>Package-private rather than {@code private} so {@code RequirementServiceConcurrencyTest}
     * (same package) can assert the exact number of {@code compareAndUpdate} attempts a permanently
     * contended retry loop makes before giving up, instead of only asserting that it eventually
     * gives up.</p>
     */
    static final int MAX_RETRY_ATTEMPTS = 20;

    private final RequirementRepository repository;
    private final ResourceIdFactory resourceIdFactory;
    private final TermLookup termLookup;
    private final ConstraintRepository constraintRepository;
    private final RequirementSchemaSource schemaSource;

    /**
     * Creates the service.
     *
     * @param repository            the driven persistence port (must not be {@code null})
     * @param resourceIdFactory     mints the opaque identity of a newly added requirement (must
     *                              not be {@code null})
     * @param termLookup            resolves a human-typed glossary term code to its opaque
     *                              identity (must not be {@code null})
     * @param constraintRepository  resolves a human-typed constraint code to the constraint it
     *                              names, for {@link #linkConstraint} - a direct, same-module
     *                              dependency rather than a {@code TermLookup}-style cross-BC
     *                              lookup port, since {@link Constraint} lives in this same
     *                              bounded context (must not be {@code null})
     * @param schemaSource          supplies the {@code arkreq:} vocabulary as data, backing
     *                              {@code req_schema} (must not be {@code null})
     */
    public RequirementService(
            RequirementRepository repository, ResourceIdFactory resourceIdFactory, TermLookup termLookup,
            ConstraintRepository constraintRepository, RequirementSchemaSource schemaSource) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.termLookup = Objects.requireNonNull(termLookup, "termLookup");
        this.constraintRepository = Objects.requireNonNull(constraintRepository, "constraintRepository");
        this.schemaSource = Objects.requireNonNull(schemaSource, "schemaSource");
    }

    @Override
    public Requirement add(ProjectId projectId, NewRequirement command, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry: only the
        // business code is recomputed when a concurrent add() claims the same candidate first,
        // generalised to all four bounded contexts as a shared helper. nextCode() reads
        // the highest running number client-side, before create()'s own in-transaction uniqueness
        // check, so two concurrent req_add calls for the same type can legitimately compute the
        // same candidate code; CodeAssignment turns that race into an invisible, automatic retry
        // instead of surfacing the out-adapter's guard as a caller-visible failure.
        RequirementId id = new RequirementId(resourceIdFactory.newId());
        // Resolved once, outside the retry: the language a fresh requirement is written under does
        // not depend on which code candidate ultimately wins, and a missing default must reject
        // the call before any code is even computed (issue #258).
        String language = LanguageTag.resolveWriteLanguage(command.language(), defaultLanguage);
        List<AcceptanceCriterion> acceptanceCriteria = toPositionedAcceptanceCriteria(command.acceptanceCriteria());
        return CodeAssignment.createRetryingOnCodeCollision(MAX_RETRY_ATTEMPTS,
                DuplicateRequirementCodeException.class, () -> {
                    RequirementCode code = nextCode(projectId, command.type());
                    Requirement requirement = new Requirement(id, code, command.title(),
                            command.description(), command.rationale(), command.type(),
                            RequirementStatus.PROPOSED, command.priority(), command.motivatedBy(),
                            command.qualityCategory(), List.of(), acceptanceCriteria, List.of());
                    repository.create(projectId, requirement, language);
                    return requirement;
                });
    }

    /**
     * Numbers plain acceptance-criterion texts {@code 1..n} in list order - a fresh requirement
     * has no caller-addressable position yet, unlike {@code req_update}'s position-addressed
     * corrections (issue #266).
     */
    private static List<AcceptanceCriterion> toPositionedAcceptanceCriteria(List<String> texts) {
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        int position = 1;
        for (String text : texts) {
            criteria.add(new AcceptanceCriterion(position++, text));
        }
        return criteria;
    }

    @Override
    public List<Requirement> list(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId, displayLocale);
    }

    @Override
    public Optional<Requirement> get(ProjectId projectId, RequirementCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code, displayLocale);
    }

    @Override
    public Requirement accept(ProjectId projectId, RequirementCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // accept() never touches title/description/rationale, so no call-scoped language applies -
        // the ternaries in updateWithOptimisticRetry always fall back to the language each field
        // was already read under, and never reach LanguageTag#resolveWriteLanguage - passing a null
        // defaultLanguage here is therefore safe even though this project may well have one
        // configured.
        return updateWithOptimisticRetry(projectId, code, null, null, false, false, false, Set.of(),
                Requirement::accept);
    }

    @Override
    public Requirement propose(ProjectId projectId, RequirementCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // propose() never touches any text field either - same null/null reasoning as accept().
        return updateWithOptimisticRetry(projectId, code, null, null, false, false, false, Set.of(),
                Requirement::propose);
    }

    @Override
    public Requirement linkTerm(ProjectId projectId, RequirementCode code, String termCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(termCode, "termCode");
        // Resolution does not depend on the requirement's current state, so it happens once,
        // outside the retry loop below - a lookup failure must propagate immediately and leave
        // the requirement untouched, exactly as before.
        TermRef term = new TermRef(termLookup.resolveByCode(projectId, termCode));
        // linkTerm() never touches any text field either - same null/null reasoning as accept().
        return updateWithOptimisticRetry(projectId, code, null, null, false, false, false, Set.of(), current -> {
            if (current.usesTerms().contains(term)) {
                return current;
            }
            List<TermRef> linked = new ArrayList<>(current.usesTerms());
            linked.add(term);
            return new Requirement(current.id(), current.code(), current.title(),
                    current.description(), current.rationale(), current.type(), current.status(),
                    current.priority(), current.motivatedBy(), current.qualityCategory(), linked,
                    current.acceptanceCriteria(), current.constrainedBy());
        });
    }

    @Override
    public Requirement linkConstraint(ProjectId projectId, RequirementCode code, String constraintCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(constraintCode, "constraintCode");
        // Resolution does not depend on the requirement's current state, so it happens once,
        // outside the retry loop below - mirrors linkTerm() exactly, except the lookup is a
        // direct, same-module read against ConstraintRepository rather than a cross-BC
        // TermLookup: Constraint lives inside this same bounded context.
        ConstraintCode parsedCode = new ConstraintCode(constraintCode);
        // No display language: this lookup only needs the constraint's opaque identity for the
        // constrainedBy edge, never its title/statement text, so which language variant the
        // out-adapter would surface is irrelevant here.
        Constraint constraint = constraintRepository.findByCode(projectId, parsedCode, null)
                .orElseThrow(() -> new ConstraintNotFoundException(projectId, parsedCode));
        ConstraintRef ref = new ConstraintRef(constraint.id().value());
        // linkConstraint() never touches any text field either - same null/null reasoning as
        // accept().
        return updateWithOptimisticRetry(projectId, code, null, null, false, false, false, Set.of(), current -> {
            if (current.constrainedBy().contains(ref)) {
                return current;
            }
            List<ConstraintRef> linked = new ArrayList<>(current.constrainedBy());
            linked.add(ref);
            return new Requirement(current.id(), current.code(), current.title(),
                    current.description(), current.rationale(), current.type(), current.status(),
                    current.priority(), current.motivatedBy(), current.qualityCategory(),
                    current.usesTerms(), current.acceptanceCriteria(), linked);
        });
    }

    @Override
    public Requirement update(ProjectId projectId, RequirementCode code, String title, String description,
            String rationale, List<String> newAcceptanceCriteria,
            List<AcceptanceCriterionTextPatch> acceptanceCriteriaTextPatches,
            Priority priority, String language, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // Which positions this call itself patches (issue #271): the signal
        // updateWithOptimisticRetry resolves a fresh language against, instead of comparing the
        // patched text to what is already stored there - a caller correcting a typo back to the
        // project's already-current wording is still an explicit write to that position, not a
        // no-op.
        Set<Integer> touchedAcceptanceCriteriaPositions = acceptanceCriteriaTextPatches == null
                ? Set.of()
                : acceptanceCriteriaTextPatches.stream()
                        .map(AcceptanceCriterionTextPatch::position)
                        .collect(Collectors.toUnmodifiableSet());
        return updateWithOptimisticRetry(projectId, code, language, defaultLanguage, title != null,
                description != null, rationale != null, touchedAcceptanceCriteriaPositions, current -> {
            Requirement base = new Requirement(current.id(), current.code(),
                    title != null ? title : current.title(),
                    description != null ? description : current.description(),
                    rationale != null ? rationale : current.rationale(),
                    current.type(), current.status(),
                    priority != null ? priority : current.priority(), current.motivatedBy(),
                    current.qualityCategory(), current.usesTerms(), current.acceptanceCriteria(),
                    current.constrainedBy());
            base = base.withAppendedAcceptanceCriteria(newAcceptanceCriteria);
            return acceptanceCriteriaTextPatches != null
                    ? base.withAcceptanceCriteriaTextPatches(projectId, acceptanceCriteriaTextPatches)
                    : base;
        });
    }

    /**
     * Read-modify-write helper shared by {@link #accept}, {@link #propose}, {@link #linkTerm} and
     * {@link #update}: reads the
     * current requirement and its concurrency token together via
     * {@link RequirementRepository#findCurrentByCode}, derives the next state via {@code mutation},
     * and writes it back via {@link RequirementRepository#compareAndUpdate} - retrying with a
     * fresh read whenever a concurrent writer commits a change in between: two parallel
     * read-modify-write round trips on the same requirement used to silently lose
     * whichever one committed last; the compare-and-set guard itself degenerated from a
     * full-snapshot comparison to a head comparison (ADR-014 decision 4).
     *
     * <p>{@code mutation} returning its input unchanged (by {@link Object#equals}) is treated as
     * a no-op: the existing idempotency rules ({@link Requirement#accept()} on an already-accepted
     * requirement, linking an already-linked term) skip the write entirely, exactly as before this
     * fix.</p>
     *
     * <p><strong>Legacy acceptance-criteria guard.</strong> If {@code current}'s acceptance
     * criteria are a read-time placeholder (no {@code arkreq:acceptanceCriterion} triple actually
     * exists yet, see {@link RequirementRepository.CurrentRequirement#acceptanceCriteriaIsSynthesized()})
     * and {@code mutation} left the placeholder's position ({@code 1}, the only entry a synthesized
     * list ever carries) with its exact placeholder text, the write is rejected with
     * {@link MissingAcceptanceCriteriaException} instead of proceeding: a plain replace-by-identity
     * write would otherwise turn that placeholder into a genuine, persisted literal, after which
     * the gap it was meant to surface becomes permanently invisible. Merely appending further
     * criteria after it (via {@link Requirement#withAppendedAcceptanceCriteria}) does not clear the
     * guard - the placeholder itself would still be persisted at position {@code 1} - only
     * {@link #update} patching that exact position with real text (via
     * {@link Requirement#withAcceptanceCriteriaTextPatches}) does. A no-op mutation never reaches
     * this check, since it already returned above. This guard runs <em>before</em> resolving a
     * touched field's write language whenever {@code mutation} changed any text (title,
     * description, rationale or an acceptance criterion) - a legacy requirement missing its
     * acceptance
     * criteria must surface {@link MissingAcceptanceCriteriaException} even when the call also
     * lacks a {@code language}/{@code defaultLanguage} for that unrelated text change, rather than
     * failing on the language gap first and hiding the more fundamental one.</p>
     *
     * @throws RequirementNotFoundException            if no requirement with {@code code} exists
     * @throws MissingAcceptanceCriteriaException      if the write would carry a legacy
     *                                                  placeholder forward as a real, persisted
     *                                                  acceptance criterion
     * @throws RequirementConcurrentlyModifiedException if the write keeps losing the race across
     *                                                   every retry attempt
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if {@code titleTouched},
     *                                                   {@code descriptionTouched} or
     *                                                   {@code touchedAcceptanceCriteriaPositions}
     *                                                   marks a field/position as this call's own
     *                                                   and neither {@code language} nor
     *                                                   {@code defaultLanguage} is given
     */
    private Requirement updateWithOptimisticRetry(ProjectId projectId, RequirementCode code, String language,
            String defaultLanguage, boolean titleTouched, boolean descriptionTouched, boolean rationaleTouched,
            Set<Integer> touchedAcceptanceCriteriaPositions, UnaryOperator<Requirement> mutation) {
        RequirementConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(projectId, code)
                    .orElseThrow(() -> new RequirementNotFoundException(projectId, code));
            Requirement updated = mutation.apply(current.value());
            // A byte-for-byte-unchanged requirement might still not be a no-op (a touched field
            // retagged to an explicit language without changing its text, issue #271) - the check
            // below covers that. A changed one never is, and the guard must see it before any
            // language resolution gets a chance to throw on an unrelated field (see the guard's
            // own javadoc above).
            boolean textUnchanged = updated.equals(current.value());
            if (!textUnchanged) {
                rejectAcceptanceCriteriaPlaceholderCarriedForward(current, updated, projectId, code);
            }
            // title/description/each acceptance criterion's text each get their own language: a
            // field/position this call itself did not name (titleTouched/descriptionTouched/
            // touchedAcceptanceCriteriaPositions - see update()) round-trips under the exact tag it
            // was read under (a scoped no-op), never under `language`/`defaultLanguage`. A named
            // field/position only resolves a fresh write language when the caller also supplied
            // `language` explicitly or the text it supplies actually differs from what is stored
            // (issue #271) - a named field whose text happens to equal what is stored and whose
            // caller did not name a language is still a no-op, exactly like an unnamed one, so that
            // resending a field's already-current text as part of a full-state round trip never
            // demands a `defaultLanguage` the project may not have (see
            // resolveTouchedLanguage/resolveTouchedPositionLanguage below). Without the touched
            // distinction at all, accept()/linkTerm() (which always call this with titleTouched ==
            // false, descriptionTouched == false, an empty position set, and a null
            // language/defaultLanguage) would retag or collapse whichever language variant
            // findCurrentByCode happened to select. Resolving here, lazily, rather than eagerly in
            // update(), means a malformed/missing language argument only ever throws when this call
            // actually touches a language-tagged field/position (issue #258).
            String titleLanguage = resolveTouchedLanguage(titleTouched, current.value().title(), updated.title(),
                    current.titleLanguage(), language, defaultLanguage);
            String descriptionLanguage = resolveTouchedLanguage(descriptionTouched, current.value().description(),
                    updated.description(), current.descriptionLanguage(), language, defaultLanguage);
            // Same rule for the optional rationale: a requirement that carries none reads back
            // null/null here, so an untouched one resolves to the null tag it was read under and
            // the out-adapter writes no arkreq:rationale triple at all - the very first update()
            // that names one is a text change against that null and therefore resolves freshly.
            String rationaleLanguage = resolveTouchedLanguage(rationaleTouched, current.value().rationale(),
                    updated.rationale(), current.rationaleLanguage(), language, defaultLanguage);
            Map<Integer, String> acceptanceCriteriaLanguageByPosition = acceptanceCriteriaLanguageByPosition(
                    current, updated, language, defaultLanguage, touchedAcceptanceCriteriaPositions);
            // A true no-op needs both text and language to already match what is stored: text-only
            // equality (the pre-#271 check) missed a named field/position whose caller supplied a
            // different language for text that happens to already match - see the block comment
            // above.
            if (textUnchanged
                    && Objects.equals(titleLanguage, current.titleLanguage())
                    && Objects.equals(descriptionLanguage, current.descriptionLanguage())
                    && Objects.equals(rationaleLanguage, current.rationaleLanguage())
                    && acceptanceCriteriaLanguageByPosition.equals(current.acceptanceCriteriaLanguageByPosition())) {
                return current.value();
            }
            if (textUnchanged) {
                rejectAcceptanceCriteriaPlaceholderCarriedForward(current, updated, projectId, code);
            }
            try {
                repository.compareAndUpdate(projectId, current.head(), updated, titleLanguage, descriptionLanguage,
                        rationaleLanguage, acceptanceCriteriaLanguageByPosition, defaultLanguage);
                return updated;
            } catch (RequirementConcurrentlyModifiedException e) {
                // A concurrent writer replaced the requirement between our read and our write -
                // retry against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    /**
     * The guard body of {@link #updateWithOptimisticRetry}'s legacy-acceptance-criteria check
     * (see that method's javadoc) - extracted so it can run both ahead of language resolution
     * (when {@code mutation} changed some text) and just before the write (when it did not, but a
     * touched field was still retagged to a new language, issue #271).
     */
    private static void rejectAcceptanceCriteriaPlaceholderCarriedForward(
            RequirementRepository.CurrentRequirement current, Requirement updated, ProjectId projectId,
            RequirementCode code) {
        if (!current.acceptanceCriteriaIsSynthesized()) {
            return;
        }
        AcceptanceCriterion placeholder = current.value().acceptanceCriteria().get(0);
        AcceptanceCriterion stillAtPlaceholderPosition = updated.acceptanceCriteria().stream()
                .filter(criterion -> criterion.position() == placeholder.position())
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "acceptance criteria never lose an existing position (append/patch-only, issue #266)"));
        if (stillAtPlaceholderPosition.text().equals(placeholder.text())) {
            throw new MissingAcceptanceCriteriaException(projectId, code);
        }
    }

    /**
     * The BCP-47 language tag each of {@code updated}'s acceptance-criterion positions is written
     * under: a position absent from {@code current} altogether (a newly appended position - it
     * has no prior tag or text to compare against) is always freshly resolved via {@link
     * LanguageTag#resolveWriteLanguage}; an existing position named in {@code touchedPositions}
     * (this call's own {@code acceptanceCriteriaTextPatches}, see {@link #update}) resolves fresh
     * only if the caller supplied {@code language} explicitly or the patched text actually differs
     * from what is stored there (issue #271, and its regression - a named position whose patched
     * text happens to equal what is stored and whose caller did not name a language is a no-op,
     * not a forced retag). Every other position round-trips under the exact tag {@code current}
     * carried for it (a scoped no-op). Mirrors the {@code titleTouched}/{@code descriptionTouched}
     * distinction directly above via {@link #resolveTouchedPositionLanguage}, once per position
     * instead of once for the whole field - the same shape {@code
     * UseCaseService#updateWithOptimisticRetry} already uses for {@code Step#text()} via
     * {@code stepTextLanguageByPosition}.
     */
    private static Map<Integer, String> acceptanceCriteriaLanguageByPosition(
            RequirementRepository.CurrentRequirement current, Requirement updated, String language,
            String defaultLanguage, Set<Integer> touchedPositions) {
        Map<Integer, String> currentTextByPosition = new HashMap<>();
        for (AcceptanceCriterion criterion : current.value().acceptanceCriteria()) {
            currentTextByPosition.put(criterion.position(), criterion.text());
        }
        Map<Integer, String> languageByPosition = new LinkedHashMap<>();
        for (AcceptanceCriterion criterion : updated.acceptanceCriteria()) {
            boolean isNewPosition = !currentTextByPosition.containsKey(criterion.position());
            String resolved = resolveTouchedPositionLanguage(isNewPosition,
                    touchedPositions.contains(criterion.position()),
                    currentTextByPosition.get(criterion.position()), criterion.text(),
                    current.acceptanceCriteriaLanguageByPosition().get(criterion.position()),
                    language, defaultLanguage);
            languageByPosition.put(criterion.position(), resolved);
        }
        return languageByPosition;
    }

    /**
     * The BCP-47 language tag a single scalar field ({@code title}/{@code description}/
     * {@code rationale}) is
     * written under: freshly resolved via {@link LanguageTag#resolveWriteLanguage} when {@code
     * touched} and either the caller named {@code language} explicitly or {@code updatedText}
     * actually differs from {@code currentText}; otherwise {@code currentLanguage} unchanged (a
     * scoped no-op, not a retag). A field named by the caller but resent with its own
     * already-current text and no {@code language} argument is therefore still a no-op (issue
     * #271's regression - naming a field alone used to be enough to force a resolution that a
     * project with no {@code defaultLanguage} could not satisfy).
     */
    private static String resolveTouchedLanguage(boolean touched, String currentText, String updatedText,
            String currentLanguage, String language, String defaultLanguage) {
        boolean languageTouched = touched && (language != null || !Objects.equals(updatedText, currentText));
        return languageTouched
                ? LanguageTag.resolveWriteLanguage(language, defaultLanguage)
                : currentLanguage;
    }

    /**
     * {@link #resolveTouchedLanguage} extended with {@code isNewPosition}: a position with no
     * prior text/tag at all (a newly appended acceptance criterion) always resolves fresh,
     * regardless of {@code touched} - there is nothing to compare its text against or fall back
     * to.
     */
    private static String resolveTouchedPositionLanguage(boolean isNewPosition, boolean touched,
            String currentText, String updatedText, String currentLanguage, String language,
            String defaultLanguage) {
        if (isNewPosition) {
            return LanguageTag.resolveWriteLanguage(language, defaultLanguage);
        }
        return resolveTouchedLanguage(touched, currentText, updatedText, currentLanguage, language, defaultLanguage);
    }

    @Override
    public List<ResolvedRequirement> resolveExisting(ProjectId projectId, ResourceId... ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.length == 0) {
            return List.of();
        }
        return repository.findByIds(projectId, List.of(ids));
    }

    @Override
    public List<RequirementSchemaTerm> schema() {
        return schemaSource.schema();
    }

    /**
     * Derives the next free business code for {@code type} in {@code projectId}: the highest
     * running number that type has ever used, plus one (starting at 1).
     *
     * <p><strong>Counted over {@link RequirementRepository#findAllCodes}, not
     * {@link RequirementRepository#findAll} (kogn-io/arknet#360).</strong> The listing drops a
     * requirement it cannot materialise - a store-first (ADR-005) write can leave the mandatory
     * {@code title} or {@code description} unreadable - while the code that requirement holds
     * stays as taken as any other. Counting over the listing would therefore mint that code again
     * as soon as it is the project's highest, and {@code create} would answer with a
     * {@link DuplicateRequirementCodeException} that
     * {@link CodeAssignment#createRetryingOnCodeCollision} cannot retry away, because every retry
     * recomputes the identical number. {@code findAllCodes} reads code and type only, so nothing
     * a listing skips can hide a taken number from this method.</p>
     *
     * <p><strong>The type comes from the prefix, not from {@code r.type()}.</strong> Both counters
     * used to be separated by filtering the listing on the domain type, which the raw read no
     * longer offers - and deliberately so: a requirement whose type triple cannot be read is
     * exactly the sort this method must still count. The code carries the same partition anyway,
     * and {@link CodeCounter} anchors its match at the start of the code, so the {@code FR-}
     * counter passes over an {@code NFR-3} rather than swallowing it.</p>
     */
    private RequirementCode nextCode(ProjectId projectId, RequirementType type) {
        String prefix = type.idPrefix() + "-";
        int highest = CodeCounter.highestRunningNumber(prefix, repository.findAllCodes(projectId),
                RequirementCode::value);
        return new RequirementCode(prefix + (highest + 1));
    }
}
