// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext;
import de.hauschel.arknet.bc.application.port.in.DescribeBoundedContextDisplayFallback;
import de.hauschel.arknet.bc.application.port.in.GetBoundedContext;
import de.hauschel.arknet.bc.application.port.in.LinkContext;
import de.hauschel.arknet.bc.application.port.in.LinkTerm;
import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.UpdateBoundedContext;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextConcurrentlyModifiedException;
import de.hauschel.arknet.bc.domain.BoundedContextDisplayFallback;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.ContextRelationshipId;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.CodeCounter;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Application service implementing the bounded-context use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link BoundedContextRepository}
 * driven port. The component is wired as a plain object (constructor injection) by the
 * composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link BoundedContextId}) is opaque and minted once per
 * bounded context via {@link ResourceIdFactory}; it never changes. The human-readable business
 * code ({@link BoundedContextCode}, {@code BC-N}) is assigned independently, where {@code N} is
 * one above the highest running number currently used in the target project (numbering is
 * independent per project, starting at 1). Linking a glossary term is idempotent - a term may
 * be linked to a bounded context at any time; the edge lives inside the aggregate and is
 * therefore carried along by every subsequent replace-by-identity write. {@link #linkContext}, in
 * contrast, is pure create with no idempotency check: it resolves both bounded-context codes
 * against {@link #repository}, mints a fresh {@link ContextRelationshipId} and persists the
 * resulting {@link ContextRelationship} as its own resource - never as a field on either bounded
 * context.</p>
 *
 * <p><strong>Multilingual, mirroring {@code ConstraintService}'s policy (kogn-io/arknet#520).
 * </strong> {@code name}/{@code domainVision} are language-tagged; {@link #add} and {@link #update}
 * resolve and pass through the BCP-47 tags exactly the way {@code ConstraintService} does for
 * {@code title}/{@code statement}, including the "changing a field's language alone is a real
 * write" rule - see {@link #resolveTouchedLanguage}. {@link #linkTerm}, which touches neither text
 * field, reads under the repository's own configured display language (passes {@code null} as
 * {@code defaultLanguage} to {@link BoundedContextRepository#findCurrentByCode}) and passes the
 * observed {@code nameLanguage}/{@code domainVisionLanguage} straight through unchanged to
 * {@link BoundedContextRepository#compareAndUpdate} - a deliberately narrower choice than
 * {@code RequirementService#linkTerm}'s project-default-aware read (issue #456): extending
 * {@link LinkTerm}'s own in-port signature with a {@code defaultLanguage} parameter was left out of
 * this issue's scope, since {@code linkTerm} never writes a language-tagged field itself and the
 * gap only affects which language a concurrent write's untouched fields are echoed back under, not
 * which language ends up stored.</p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} recomputes its next code
 * against a fresh read whenever a concurrent {@code bc_add} claims the same {@code BC-N} first,
 * via {@link CodeAssignment#createRetryingOnCodeCollision}, and {@link #linkTerm}/{@link #update}
 * retry their whole read-modify-write round trip via
 * {@link BoundedContextRepository#compareAndUpdate} whenever a concurrent writer commits in
 * between. Neither race is visible to a well-formed caller; only sustained, pathological contention
 * on the very same bounded context surfaces as {@link BoundedContextConcurrentlyModifiedException}.
 * Parallel sessions of one user against one local store are the normal case, not a
 * remote/multi-writer concern.</p>
 */
public class BoundedContextService implements AddBoundedContext, ListBoundedContexts,
        GetBoundedContext, LinkTerm, ResolveBoundedContexts, LinkContext, DescribeBoundedContextDisplayFallback,
        UpdateBoundedContext {

    private static final String CODE_PREFIX = "BC";

    /**
     * Bound on {@link #updateWithOptimisticRetry}'s/{@link #linkTermWithOptimisticRetry}'s
     * compare-and-set retry loops. Two callers read-modify-writing the same bounded context are
     * resolved by a single retry in the overwhelming majority of cases, since each retry re-reads
     * the now-current state before trying again; this bound only exists so a pathological,
     * sustained storm of concurrent writers against the very same bounded context fails loudly
     * instead of looping forever.
     */
    private static final int MAX_RETRY_ATTEMPTS = 20;

    private final BoundedContextRepository repository;
    private final ResourceIdFactory resourceIdFactory;
    private final TermLookup termLookup;
    private final ContextRelationshipRepository contextRelationshipRepository;

    /**
     * Creates the service.
     *
     * @param repository                    the driven persistence port (must not be {@code null})
     * @param resourceIdFactory              mints the opaque identity of a newly added bounded
     *                                       context or context relationship (must not be
     *                                       {@code null})
     * @param termLookup                     resolves a human-typed glossary term code to its
     *                                       opaque identity (must not be {@code null})
     * @param contextRelationshipRepository  the driven persistence port for
     *                                       {@link ContextRelationship} (must not be
     *                                       {@code null})
     */
    public BoundedContextService(BoundedContextRepository repository, ResourceIdFactory resourceIdFactory,
            TermLookup termLookup, ContextRelationshipRepository contextRelationshipRepository) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.termLookup = Objects.requireNonNull(termLookup, "termLookup");
        this.contextRelationshipRepository =
                Objects.requireNonNull(contextRelationshipRepository, "contextRelationshipRepository");
    }

    @Override
    public BoundedContext add(ProjectId projectId, NewBoundedContext command, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry: only the
        // business code is recomputed when a concurrent bc_add claims the same candidate first.
        // See CodeAssignment for why that race exists and why it must retry rather
        // than surface the out-adapter's uniqueness guard as a caller-visible failure.
        BoundedContextId id = new BoundedContextId(resourceIdFactory.newId());
        String language = LanguageTag.resolveWriteLanguage(command.language(), defaultLanguage);
        return CodeAssignment.createRetryingOnCodeCollision(
                DuplicateBoundedContextCodeException.class, () -> {
                    BoundedContextCode code = nextCode(projectId);
                    BoundedContext boundedContext = new BoundedContext(id, code, command.name(),
                            command.domainVision(), command.subdomain(), command.ownedBy(), List.of());
                    repository.create(projectId, boundedContext, language);
                    return boundedContext;
                });
    }

    @Override
    public List<BoundedContext> list(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId, displayLocale);
    }

    @Override
    public Map<BoundedContextCode, BoundedContextDisplayFallback> describe(ProjectId projectId,
            String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAllDisplayFallback(projectId, displayLocale);
    }

    @Override
    public Optional<BoundedContext> get(ProjectId projectId, BoundedContextCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code, displayLocale);
    }

    @Override
    public List<ResolvedBoundedContext> resolveExisting(ProjectId projectId, ResourceId... ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.length == 0) {
            return List.of();
        }
        return repository.findByIds(projectId, List.of(ids));
    }

    @Override
    public BoundedContext linkTerm(ProjectId projectId, BoundedContextCode code, String termCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(termCode, "termCode");
        // Resolution does not depend on the bounded context's current state, so it happens once,
        // outside the retry loop below - an unknown/ambiguous term code must propagate as a
        // didactic rejection immediately and leave the bounded context untouched.
        TermRef term = new TermRef(termLookup.resolveByCode(projectId, termCode));
        return linkTermWithOptimisticRetry(projectId, code, term);
    }

    @Override
    public BoundedContext update(ProjectId projectId, BoundedContextCode code, String name, String domainVision,
            String language, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return updateWithOptimisticRetry(projectId, code, name, domainVision, language, defaultLanguage);
    }

    @Override
    public ContextRelationship linkContext(ProjectId projectId, BoundedContextCode upstreamCode,
            BoundedContextCode downstreamCode, RelationshipType relationshipType) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(upstreamCode, "upstreamCode");
        Objects.requireNonNull(downstreamCode, "downstreamCode");
        Objects.requireNonNull(relationshipType, "relationshipType");
        BoundedContextId upstream = repository.findByCode(projectId, upstreamCode, null)
                .orElseThrow(() -> new BoundedContextNotFoundException(projectId, upstreamCode))
                .id();
        BoundedContextId downstream = repository.findByCode(projectId, downstreamCode, null)
                .orElseThrow(() -> new BoundedContextNotFoundException(projectId, downstreamCode))
                .id();
        ContextRelationshipId id = new ContextRelationshipId(resourceIdFactory.newId());
        ContextRelationship relationship = new ContextRelationship(id, upstream, downstream, relationshipType);
        return contextRelationshipRepository.create(projectId, relationship);
    }

    /**
     * Read-modify-write helper behind {@link #linkTerm}: reads the current bounded context and its
     * concurrency token together via {@link BoundedContextRepository#findCurrentByCode} (under the
     * repository's own configured display language - {@code linkTerm} touches no language-tagged
     * field, see the class-level note), derives the next state (appending {@code term} unless
     * already linked), and writes it back via {@link BoundedContextRepository#compareAndUpdate} -
     * retrying with a fresh read whenever a concurrent writer commits a change in between (two
     * parallel {@code bc_link_term} round trips on the same bounded context used to silently lose
     * whichever one committed last, because the read happened outside any transaction and the write
     * carried no guard at all).
     *
     * <p>{@code name}/{@code domainVision} and their language tags are passed straight through
     * unchanged from {@code current} - this method never touches either field, so
     * {@code compareAndUpdate}'s write is a scoped no-op on both language variants, exactly as
     * {@code current.value()} carries them.</p>
     *
     * <p>A no-op ({@code term} already linked) is treated as such and skips the write entirely,
     * exactly as before kogn-io/arknet#520.</p>
     *
     * @throws BoundedContextNotFoundException             if no bounded context with {@code code}
     *                                                     exists
     * @throws BoundedContextConcurrentlyModifiedException if the write keeps losing the race
     *                                                     across every retry attempt
     */
    private BoundedContext linkTermWithOptimisticRetry(ProjectId projectId, BoundedContextCode code, TermRef term) {
        BoundedContextConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            BoundedContextRepository.CurrentBoundedContext current =
                    repository.findCurrentByCode(projectId, code, null)
                            .orElseThrow(() -> new BoundedContextNotFoundException(projectId, code));
            if (current.value().usesTerms().contains(term)) {
                return current.value();
            }
            List<TermRef> linked = new ArrayList<>(current.value().usesTerms());
            linked.add(term);
            BoundedContext updated = new BoundedContext(current.value().id(), current.value().code(),
                    current.value().name(), current.value().domainVision(), current.value().subdomain(),
                    current.value().ownedBy(), linked);
            try {
                repository.compareAndUpdate(projectId, current.head(), updated,
                        current.nameLanguage(), current.domainVisionLanguage(), null);
                return updated;
            } catch (BoundedContextConcurrentlyModifiedException e) {
                // A concurrent writer replaced the bounded context between our read and our write -
                // retry against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    /**
     * Read-modify-write helper behind {@link #update}, mirroring
     * {@code ConstraintService#updateWithOptimisticRetry} exactly for the language handling:
     * reads the current bounded context and its concurrency token together via
     * {@link BoundedContextRepository#findCurrentByCode}, derives the next state, and writes it
     * back via {@link BoundedContextRepository#compareAndUpdate} - retrying with a fresh read
     * whenever a concurrent writer commits a change in between.
     *
     * <p>A call that changes neither text nor either field's language tag is a no-op: it returns
     * the bounded context as read without writing - the same "naming a field with its
     * already-current text but an explicit, different language is still a write" rule
     * {@code ConstraintService} states.</p>
     *
     * @throws BoundedContextNotFoundException             if no bounded context with {@code code}
     *                                                     exists
     * @throws BoundedContextConcurrentlyModifiedException if the write keeps losing the race
     *                                                     across every retry attempt
     */
    private BoundedContext updateWithOptimisticRetry(ProjectId projectId, BoundedContextCode code, String name,
            String domainVision, String language, String defaultLanguage) {
        BoundedContextConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            BoundedContextRepository.CurrentBoundedContext current =
                    repository.findCurrentByCode(projectId, code, defaultLanguage)
                            .orElseThrow(() -> new BoundedContextNotFoundException(projectId, code));
            BoundedContext updated = new BoundedContext(current.value().id(), current.value().code(),
                    name != null ? name : current.value().name(),
                    domainVision != null ? domainVision : current.value().domainVision(),
                    current.value().subdomain(), current.value().ownedBy(), current.value().usesTerms());
            // name/domainVision each get their own language: a field this call did not name
            // round-trips under the exact tag it was read under (a scoped no-op), never under
            // `language`/`defaultLanguage`. Resolved lazily, per field, mirroring
            // ConstraintService#resolveTouchedLanguage exactly.
            String nameLanguage = resolveTouchedLanguage(name != null, current.value().name(), updated.name(),
                    current.nameLanguage(), language, defaultLanguage);
            String domainVisionLanguage = resolveTouchedLanguage(domainVision != null, current.value().domainVision(),
                    updated.domainVision(), current.domainVisionLanguage(), language, defaultLanguage);
            if (updated.equals(current.value())
                    && Objects.equals(nameLanguage, current.nameLanguage())
                    && Objects.equals(domainVisionLanguage, current.domainVisionLanguage())) {
                return current.value();
            }
            try {
                repository.compareAndUpdate(projectId, current.head(), updated, nameLanguage, domainVisionLanguage,
                        defaultLanguage);
                return updated;
            } catch (BoundedContextConcurrentlyModifiedException e) {
                // A concurrent writer replaced the bounded context between our read and our write -
                // retry against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    /**
     * The BCP-47 language tag a single field ({@code name}/{@code domainVision}) is written under -
     * mirrors {@code ConstraintService#resolveTouchedLanguage} exactly.
     */
    private static String resolveTouchedLanguage(boolean touched, String currentText, String updatedText,
            String currentLanguage, String language, String defaultLanguage) {
        boolean languageTouched = touched && (language != null || !Objects.equals(updatedText, currentText));
        return languageTouched
                ? LanguageTag.resolveWriteLanguage(language, defaultLanguage)
                : currentLanguage;
    }

    /**
     * Derives the next free business code in {@code projectId}: the highest running number the
     * project already uses, plus one (starting at 1).
     *
     * <p><strong>{@link BoundedContextRepository#findAllCodes}, not
     * {@link BoundedContextRepository#findAll} (kogn-io/arknet#360).</strong> A bounded context
     * written store-first without {@code arknet:name} or {@code arkddd:domainVision} is
     * invisible to {@code findAll}, which joins both as mandatory - yet its {@code BC-N} is taken
     * just the same. Counting over {@code findAll} would therefore hand that very number out again
     * as soon as such a context holds the project's highest one; {@link #create}'s uniqueness guard
     * then rejects the write, and because every retry recomputes the identical number,
     * {@link CodeAssignment#createRetryingOnCodeCollision} cannot work its way past it either -
     * {@code bc_add} would be permanently dead for that project rather than merely racing.
     * {@code findAllCodes} reads the identifier without those joins, so this computation no longer
     * depends on how complete a context's other fields are.</p>
     */
    private BoundedContextCode nextCode(ProjectId projectId) {
        String prefix = CODE_PREFIX + "-";
        int highest = CodeCounter.highestRunningNumber(prefix,
                repository.findAllCodes(projectId), BoundedContextCode::value);
        return new BoundedContextCode(prefix + (highest + 1));
    }
}
