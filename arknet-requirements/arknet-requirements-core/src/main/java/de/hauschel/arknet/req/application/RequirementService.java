// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.UnaryOperator;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.AcceptRequirement;
import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirementSchema;
import de.hauschel.arknet.req.application.port.in.LinkTerm;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.UpdateRequirement;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.application.port.out.TermLookup;
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
 * {@link RequirementStatus#PROPOSED}. The only advancing status transition is
 * {@code PROPOSED -> ACCEPTED} - see {@link Requirement#accept()}, which owns that rule;
 * this service only threads it through the read-modify-write round trip. Linking a
 * glossary term is idempotent and independent of the status lifecycle - terms may be linked to a
 * requirement in any status.</p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} retries its next-code computation
 * against a fresh read whenever a concurrent caller claims the same code first, and {@link
 * #accept}/{@link #linkTerm}/{@link #update} retry their whole read-modify-write round trip
 * via {@link RequirementRepository#compareAndUpdate} whenever a concurrent writer commits in
 * between - see {@link #updateWithOptimisticRetry}. Neither race is visible to a well-formed
 * caller; only sustained, pathological contention on the very same requirement surfaces as
 * {@link RequirementConcurrentlyModifiedException}.</p>
 *
 * <p><strong>Correction.</strong> {@link #update} lets a caller correct a
 * requirement's title, description, acceptance criteria and/or MoSCoW priority after the fact -
 * e.g. once an interview sharpens a domain fact the original wording missed, or once a
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
 * <p><strong>Language.</strong> {@code title}/{@code description} may each legally carry several
 * language-tagged variants. {@link #updateWithOptimisticRetry} determines, per field, whether
 * {@code mutation} actually changed it (byte-for-byte against what was just read): a changed
 * field is written under {@code update}'s caller-supplied {@code language}; an unchanged field is
 * written back under the exact tag {@link RequirementRepository.CurrentRequirement#titleLanguage()}/
 * {@link RequirementRepository.CurrentRequirement#descriptionLanguage()} already carried - a
 * scoped no-op at the store, not a retag. This is what keeps {@link #accept}/{@link #linkTerm}
 * (which never touch either field and always call the helper with a {@code null} language) from
 * collapsing a multilingual title/description down to one variant just because they do not know or
 * care about language - the out-adapter preserves every other language variant regardless, but
 * only if it is told the correct tag to leave alone.</p>
 */
public class RequirementService implements AddRequirement, ListRequirements, GetRequirement,
        AcceptRequirement, LinkTerm, UpdateRequirement, ResolveRequirements, GetRequirementSchema {

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
    private final RequirementSchemaSource schemaSource;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added requirement (must not
     *                          be {@code null})
     * @param termLookup        resolves a human-typed glossary term code to its opaque identity
     *                          (must not be {@code null})
     * @param schemaSource      supplies the {@code arkreq:} vocabulary as data, backing
     *                          {@code req_schema} (must not be {@code null})
     */
    public RequirementService(
            RequirementRepository repository, ResourceIdFactory resourceIdFactory, TermLookup termLookup,
            RequirementSchemaSource schemaSource) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.termLookup = Objects.requireNonNull(termLookup, "termLookup");
        this.schemaSource = Objects.requireNonNull(schemaSource, "schemaSource");
    }

    @Override
    public Requirement add(ProjectId projectId, NewRequirement command) {
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
        return CodeAssignment.createRetryingOnCodeCollision(MAX_RETRY_ATTEMPTS,
                DuplicateRequirementCodeException.class, () -> {
                    RequirementCode code = nextCode(projectId, command.type());
                    Requirement requirement = new Requirement(id, code, command.title(),
                            command.description(), command.type(), RequirementStatus.PROPOSED,
                            command.priority(), command.motivatedBy(), command.qualityCategory(),
                            List.of(), command.acceptanceCriteria());
                    repository.create(projectId, requirement, command.language());
                    return requirement;
                });
    }

    @Override
    public List<Requirement> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId);
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
        // accept() never touches title/description, so no call-scoped language applies - the
        // ternaries in updateWithOptimisticRetry always fall back to the language each field was
        // already read under.
        return updateWithOptimisticRetry(projectId, code, null, Requirement::accept);
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
        return updateWithOptimisticRetry(projectId, code, null, current -> {
            if (current.usesTerms().contains(term)) {
                return current;
            }
            List<TermRef> linked = new ArrayList<>(current.usesTerms());
            linked.add(term);
            return new Requirement(current.id(), current.code(), current.title(),
                    current.description(), current.type(), current.status(), current.priority(),
                    current.motivatedBy(), current.qualityCategory(), linked, current.acceptanceCriteria());
        });
    }

    @Override
    public Requirement update(ProjectId projectId, RequirementCode code, String title, String description,
            List<String> acceptanceCriteria, Priority priority, String language) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return updateWithOptimisticRetry(projectId, code, language, current -> new Requirement(current.id(), current.code(),
                title != null ? title : current.title(),
                description != null ? description : current.description(),
                current.type(), current.status(),
                priority != null ? priority : current.priority(), current.motivatedBy(),
                current.qualityCategory(), current.usesTerms(),
                acceptanceCriteria != null ? List.copyOf(acceptanceCriteria) : current.acceptanceCriteria()));
    }

    /**
     * Read-modify-write helper shared by {@link #accept}, {@link #linkTerm} and {@link #update}: reads the
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
     * and {@code mutation} did not itself replace them with a real, explicit list, the write is
     * rejected with {@link MissingAcceptanceCriteriaException} instead of proceeding: a plain
     * replace-by-identity write would otherwise turn that placeholder into a genuine, persisted
     * literal, after which the gap it was meant to surface becomes permanently invisible. Only
     * {@link #update} can supply a real replacement here ({@code acceptanceCriteria != null}); a
     * no-op mutation never reaches this check, since it already returned above.</p>
     *
     * @throws RequirementNotFoundException            if no requirement with {@code code} exists
     * @throws MissingAcceptanceCriteriaException      if the write would carry a legacy
     *                                                  placeholder forward as a real, persisted
     *                                                  acceptance criterion
     * @throws RequirementConcurrentlyModifiedException if the write keeps losing the race across
     *                                                   every retry attempt
     */
    private Requirement updateWithOptimisticRetry(
            ProjectId projectId, RequirementCode code, String language, UnaryOperator<Requirement> mutation) {
        RequirementConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(projectId, code)
                    .orElseThrow(() -> new RequirementNotFoundException(projectId, code));
            Requirement updated = mutation.apply(current.value());
            if (updated.equals(current.value())) {
                return current.value();
            }
            if (current.acceptanceCriteriaIsSynthesized()
                    && updated.acceptanceCriteria().equals(current.value().acceptanceCriteria())) {
                throw new MissingAcceptanceCriteriaException(projectId, code);
            }
            // title/description each get their own language: a field this mutation left byte-for-
            // byte unchanged must round-trip under the exact tag it was read under (a scoped
            // no-op), never under `language` - that tag only ever applies to a field this call is
            // actually changing. Without this distinction, accept()/linkTerm() (which always call
            // this with language == null and never touch either field) would retag or collapse
            // whichever language variant findCurrentByCode happened to select. Canonicalizing here,
            // lazily, rather than eagerly in update(), means a malformed language argument only ever
            // throws when this call is actually changing a language-tagged field under it.
            String titleLanguage = updated.title().equals(current.value().title())
                    ? current.titleLanguage() : LanguageTag.canonicalize(language);
            String descriptionLanguage = updated.description().equals(current.value().description())
                    ? current.descriptionLanguage() : LanguageTag.canonicalize(language);
            try {
                repository.compareAndUpdate(projectId, current.head(), updated, titleLanguage, descriptionLanguage);
                return updated;
            } catch (RequirementConcurrentlyModifiedException e) {
                // A concurrent writer replaced the requirement between our read and our write -
                // retry against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
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
     * running number currently used by that type, plus one (starting at 1).
     */
    private RequirementCode nextCode(ProjectId projectId, RequirementType type) {
        int next = repository.findAll(projectId).stream()
                .filter(r -> r.type() == type)
                .mapToInt(r -> runningNumber(r.code()))
                .max()
                .orElse(0) + 1;
        return new RequirementCode(type.idPrefix() + "-" + next);
    }

    /** Parses the running number from a code such as {@code FR-7} (0 if not parseable). */
    private static int runningNumber(RequirementCode code) {
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
