// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext;
import de.hauschel.arknet.bc.application.port.in.BoundedContextDetail;
import de.hauschel.arknet.bc.application.port.in.DescribeBoundedContextDisplayFallback;
import de.hauschel.arknet.bc.application.port.in.GetBoundedContext;
import de.hauschel.arknet.bc.application.port.in.LinkContext;
import de.hauschel.arknet.bc.application.port.in.LinkTerm;
import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.RelatedContext;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.UnlinkContext;
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
import de.hauschel.arknet.bc.domain.ContextRelationshipNotFoundException;
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
 * therefore carried along by every subsequent replace-by-identity write. {@link #linkContext}
 * resolves both bounded-context codes against {@link #repository}, mints a fresh
 * {@link ContextRelationshipId} and asks {@link #contextRelationshipRepository} to persist the
 * resulting {@link ContextRelationship} as its own resource - never as a field on either bounded
 * context - unless the exact same (upstream, downstream, relationshipType) triple is already
 * recorded, in which case that pre-existing relationship is returned instead (issue #565,
 * mirroring {@link #linkTerm}'s own idempotency). {@link #unlinkContext} is its counterpart: it
 * removes exactly that triple, and - unlike an already-linked term - naming a triple that is not
 * currently recorded is never a silent no-op.</p>
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
        GetBoundedContext, LinkTerm, ResolveBoundedContexts, LinkContext, UnlinkContext,
        DescribeBoundedContextDisplayFallback, UpdateBoundedContext {

    private static final String CODE_PREFIX = "BC";

    /**
     * Bound on {@link #updateWithOptimisticRetry}'s compare-and-set retry loop. Two callers
     * read-modify-writing the same bounded context are resolved by a single retry in the
     * overwhelming majority of cases, since each retry re-reads the now-current state before
     * trying again; this bound only exists so a pathological, sustained storm of concurrent
     * writers against the very same bounded context fails loudly instead of looping forever.
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
    public List<BoundedContextDetail> list(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        List<BoundedContext> all = repository.findAll(projectId, displayLocale);
        if (all.isEmpty()) {
            return List.of();
        }
        // One bulk read of every relationship in the project, not one findByContext call per
        // context - bc_list's whole point is showing every context's edges in a single pass.
        List<ContextRelationship> relationships = contextRelationshipRepository.findAll(projectId);
        Map<BoundedContextId, BoundedContextCode> peerCodes = resolvePeerCodes(projectId, all, relationships);
        return all.stream()
                .map(context -> new BoundedContextDetail(context,
                        relatedContextsOf(context.id(), relationships, peerCodes)))
                .toList();
    }

    @Override
    public Map<BoundedContextCode, BoundedContextDisplayFallback> describe(ProjectId projectId,
            String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAllDisplayFallback(projectId, displayLocale);
    }

    @Override
    public Optional<BoundedContextDetail> get(ProjectId projectId, BoundedContextCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code, displayLocale).map(context -> detailOf(projectId, context));
    }

    /**
     * Wraps one bounded context into the {@link BoundedContextDetail} projection {@link #get}
     * returns: every relationship it carries, in either direction, with the peer's business code
     * resolved. Mirrors {@code AdrService#detailOf} - one extra read (here:
     * {@link ContextRelationshipRepository#findByContext}) plus a peer-code resolution, only on the
     * single-context path; {@link #list} derives every context's relationships from one bulk read
     * instead.
     */
    private BoundedContextDetail detailOf(ProjectId projectId, BoundedContext context) {
        List<ContextRelationship> relationships =
                contextRelationshipRepository.findByContext(projectId, context.id());
        if (relationships.isEmpty()) {
            return new BoundedContextDetail(context, List.of());
        }
        Map<BoundedContextId, BoundedContextCode> peerCodes =
                resolvePeerCodes(projectId, List.of(context), relationships);
        return new BoundedContextDetail(context, relatedContextsOf(context.id(), relationships, peerCodes));
    }

    /**
     * Resolves the business code of every bounded context {@code relationships} references (as
     * upstream or downstream) that is not already known from {@code contexts} - via
     * {@link #resolveExisting}, this service's own batch identity-to-code lookup, exactly the
     * "peer code resolved here, not shape by shape" choice {@code AdrDetail#supersedes} documents.
     * An id that resolves to nothing (e.g. a peer deleted store-first) is simply absent from the
     * result, same as {@link #resolveExisting} promises - {@link #relatedContextsOf} then renders
     * that one relationship under {@link #UNRESOLVED_PEER_CODE} rather than dropping it.
     */
    private Map<BoundedContextId, BoundedContextCode> resolvePeerCodes(ProjectId projectId,
            List<BoundedContext> contexts, List<ContextRelationship> relationships) {
        Map<BoundedContextId, BoundedContextCode> known = new LinkedHashMap<>();
        for (BoundedContext context : contexts) {
            known.put(context.id(), context.code());
        }
        Set<BoundedContextId> unresolved = new LinkedHashSet<>();
        for (ContextRelationship relationship : relationships) {
            if (!known.containsKey(relationship.upstream())) {
                unresolved.add(relationship.upstream());
            }
            if (!known.containsKey(relationship.downstream())) {
                unresolved.add(relationship.downstream());
            }
        }
        if (!unresolved.isEmpty()) {
            ResourceId[] ids = unresolved.stream().map(BoundedContextId::value).toArray(ResourceId[]::new);
            for (ResolveBoundedContexts.ResolvedBoundedContext resolved : resolveExisting(projectId, ids)) {
                known.put(new BoundedContextId(resolved.id()), resolved.code());
            }
        }
        return known;
    }

    /**
     * The placeholder code a dangling relationship (its peer identity no longer resolves - deleted
     * store-first, or an import/merge accident) renders under, rather than being dropped and
     * turning invisible everywhere except {@code impact_analysis}/{@code resource_get} - exactly
     * the class of defect issue #565 set out to make visible in the first place (issue #575
     * review).
     */
    private static final BoundedContextCode UNRESOLVED_PEER_CODE = new BoundedContextCode("<unresolved>");

    /**
     * Renders every relationship in {@code relationships} that names {@code owner} as either
     * upstream or downstream into a {@link RelatedContext}, from {@code owner}'s point of view. A
     * relationship whose peer identity {@code peerCodes} could not resolve is still rendered, under
     * {@link #UNRESOLVED_PEER_CODE} - see that constant's javadoc for why dropping it silently
     * would be the wrong choice.
     */
    private static List<RelatedContext> relatedContextsOf(BoundedContextId owner,
            List<ContextRelationship> relationships, Map<BoundedContextId, BoundedContextCode> peerCodes) {
        List<RelatedContext> related = new ArrayList<>();
        for (ContextRelationship relationship : relationships) {
            boolean ownerIsUpstream = owner.equals(relationship.upstream());
            boolean ownerIsDownstream = owner.equals(relationship.downstream());
            if (!ownerIsUpstream && !ownerIsDownstream) {
                continue;
            }
            BoundedContextId peer = ownerIsUpstream ? relationship.downstream() : relationship.upstream();
            BoundedContextCode peerCode = peerCodes.getOrDefault(peer, UNRESOLVED_PEER_CODE);
            RelatedContext.Direction direction =
                    ownerIsUpstream ? RelatedContext.Direction.UPSTREAM_OF : RelatedContext.Direction.DOWNSTREAM_OF;
            related.add(new RelatedContext(relationship.id(), direction, peer, peerCode,
                    relationship.relationshipType()));
        }
        return related;
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
        // Touches no language-tagged field: name/domainVision and their tags pass straight
        // through from `current`, so compareAndUpdate's write is a scoped no-op on both language
        // variants, and the null write-side default keeps issue #258's sweep off - exactly as
        // before kogn-io/arknet#520.
        return updateWithOptimisticRetry(projectId, code, null, current -> {
            if (current.value().usesTerms().contains(term)) {
                return Optional.empty();
            }
            List<TermRef> linked = new ArrayList<>(current.value().usesTerms());
            linked.add(term);
            BoundedContext updated = new BoundedContext(current.value().id(), current.value().code(),
                    current.value().name(), current.value().domainVision(), current.value().subdomain(),
                    current.value().ownedBy(), linked);
            return Optional.of(new PendingWrite(updated, current.nameLanguage(), current.domainVisionLanguage(),
                    null));
        });
    }

    @Override
    public BoundedContext update(ProjectId projectId, BoundedContextCode code, String name, String domainVision,
            List<String> termCodes, String language, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // Resolution first, outside the retry, mirroring linkTerm()/RequirementService#update: an
        // unknown TERM-9 is a didactic rejection of the whole call, never a race worth retrying.
        // null stays null here - it is the "leave this relation alone" signal
        // (kogn-io/arknet#567, precedent RequirementService#update's usesTermCodes resolution), an
        // empty list a deliberate clear.
        List<TermRef> terms = termCodes == null
                ? null
                : termCodes.stream()
                        .map(termCode -> new TermRef(termLookup.resolveByCode(projectId, termCode)))
                        .distinct()
                        .toList();
        // Mirrors ConstraintService#updateWithOptimisticRetry exactly for the language handling.
        return updateWithOptimisticRetry(projectId, code, defaultLanguage, current -> {
            BoundedContext updated = new BoundedContext(current.value().id(), current.value().code(),
                    name != null ? name : current.value().name(),
                    domainVision != null ? domainVision : current.value().domainVision(),
                    current.value().subdomain(), current.value().ownedBy(),
                    terms != null ? terms : current.value().usesTerms());
            // name/domainVision each get their own language: a field this call did not name
            // round-trips under the exact tag it was read under (a scoped no-op), never under
            // `language`/`defaultLanguage`. Resolved lazily, per field, mirroring
            // ConstraintService#resolveTouchedLanguage exactly. termCodes carries no language of
            // its own and never influences either tag.
            String nameLanguage = resolveTouchedLanguage(name != null, current.value().name(), updated.name(),
                    current.nameLanguage(), language, defaultLanguage);
            String domainVisionLanguage = resolveTouchedLanguage(domainVision != null,
                    current.value().domainVision(), updated.domainVision(), current.domainVisionLanguage(),
                    language, defaultLanguage);
            // A call that changes neither text, nor the term links, nor either field's language
            // tag is a no-op - the same "naming a field with its already-current text but an
            // explicit, different language is still a write" rule ConstraintService states.
            if (updated.equals(current.value())
                    && Objects.equals(nameLanguage, current.nameLanguage())
                    && Objects.equals(domainVisionLanguage, current.domainVisionLanguage())) {
                return Optional.empty();
            }
            return Optional.of(new PendingWrite(updated, nameLanguage, domainVisionLanguage, defaultLanguage));
        });
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
        ContextRelationship candidate = new ContextRelationship(id, upstream, downstream, relationshipType);
        // createIfAbsent mints nothing new if this exact triple is already recorded - see
        // ContextRelationshipRepository's class javadoc for why that check must run inside the
        // out-adapter's own write transaction rather than as a read here beforehand.
        return contextRelationshipRepository.createIfAbsent(projectId, candidate);
    }

    @Override
    public void unlinkContext(ProjectId projectId, BoundedContextCode upstreamCode, BoundedContextCode downstreamCode,
            RelationshipType relationshipType) {
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
        try {
            contextRelationshipRepository.deleteByEdge(projectId, upstream, downstream, relationshipType);
        } catch (ContextRelationshipNotFoundException idAddressedSignal) {
            // The out-port only ever holds opaque identities, so its own not-found signal carries
            // none of the codes the caller actually typed - re-thrown here, one layer up, with the
            // codes this method already resolved (see ContextRelationshipNotFoundException's class
            // javadoc for why this translation lives here rather than at the out-port).
            throw new ContextRelationshipNotFoundException(projectId, upstreamCode, downstreamCode, relationshipType);
        }
    }

    /**
     * What one round of {@link #updateWithOptimisticRetry}'s mutation decided to write: the next
     * state plus the BCP-47 tags {@code name}/{@code domainVision} are written under and the
     * write-side project default the issue #258 sweep keys on ({@code null} switches the sweep
     * off, as {@link #linkTerm} needs).
     */
    private record PendingWrite(BoundedContext updated, String nameLanguage, String domainVisionLanguage,
            String defaultLanguage) {
    }

    /**
     * The one read-modify-write loop behind {@link #linkTerm} and {@link #update}, the same shape
     * {@code RequirementService#updateWithOptimisticRetry} serves both of its callers with: reads
     * the current bounded context and its concurrency token together via
     * {@link BoundedContextRepository#findCurrentByCode} (under {@code readDefaultLanguage} -
     * the project's own default for {@link #update}, {@code null} for {@link #linkTerm}, see the
     * class-level note), asks {@code mutation} for the next state, and writes it back via
     * {@link BoundedContextRepository#compareAndUpdate} - retrying with a fresh read whenever a
     * concurrent writer commits a change in between (two parallel {@code bc_link_term} round trips
     * on the same bounded context used to silently lose whichever one committed last, because the
     * read happened outside any transaction and the write carried no guard at all).
     *
     * <p>{@code mutation} answers {@link Optional#empty()} for a no-op - a term already linked, or
     * an update that changes neither text nor either field's language tag - and the loop then
     * returns the bounded context as read without writing. Everything language-specific lives in
     * the mutation, not here: {@link #linkTerm} hands the tags it observed straight through (a
     * scoped no-op on both language variants), {@link #update} resolves them per touched field via
     * {@link #resolveTouchedLanguage}.</p>
     *
     * @throws BoundedContextNotFoundException             if no bounded context with {@code code}
     *                                                     exists
     * @throws BoundedContextConcurrentlyModifiedException if the write keeps losing the race
     *                                                     across every retry attempt
     */
    private BoundedContext updateWithOptimisticRetry(ProjectId projectId, BoundedContextCode code,
            String readDefaultLanguage,
            Function<BoundedContextRepository.CurrentBoundedContext, Optional<PendingWrite>> mutation) {
        BoundedContextConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            BoundedContextRepository.CurrentBoundedContext current =
                    repository.findCurrentByCode(projectId, code, readDefaultLanguage)
                            .orElseThrow(() -> new BoundedContextNotFoundException(projectId, code));
            Optional<PendingWrite> pending = mutation.apply(current);
            if (pending.isEmpty()) {
                return current.value();
            }
            PendingWrite write = pending.get();
            try {
                repository.compareAndUpdate(projectId, current.head(), write.updated(), write.nameLanguage(),
                        write.domainVisionLanguage(), write.defaultLanguage());
                return write.updated();
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
