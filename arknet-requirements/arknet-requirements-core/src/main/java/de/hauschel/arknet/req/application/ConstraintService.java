// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.CodeCounter;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.AddConstraint;
import de.hauschel.arknet.req.application.port.in.GetConstraint;
import de.hauschel.arknet.req.application.port.in.ListConstraints;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.in.UpdateConstraint;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintNotFoundException;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.DuplicateConstraintCodeException;

/**
 * Application service implementing the constraint use cases.
 *
 * <p>This is the policy seat of the constraint side of the requirements hexagon - mirrors
 * {@link RequirementService} in shape but stays markedly simpler: a {@link Constraint} carries no
 * lifecycle status and no cross-resource edges of its own, so there is no {@code accept}/{@code
 * linkTerm} equivalent here. {@code req_link_constraint} - the one operation that mutates state as
 * a consequence of a constraint existing - mutates the <em>requirement</em>, not the constraint,
 * and therefore lives on {@link RequirementService} (see {@link
 * de.hauschel.arknet.req.application.port.in.LinkConstraint}), constructor-injected with the
 * same {@link ConstraintRepository} this service also depends on.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link ConstraintId}) is opaque and minted once per
 * constraint via {@link ResourceIdFactory}; it never changes. The human-readable business code
 * ({@link ConstraintCode}, {@code TCON-N}/{@code BCON-N}/{@code RCON-N}) is assigned
 * independently, where {@code N} is one above the highest running number currently used by that
 * subtype in the target project - numbering is independent per subtype and per project, exactly
 * mirroring {@link RequirementService}'s {@code FR-N}/{@code NFR-N} numbering. Neither code nor
 * type is ever reassigned afterwards: {@link #update} changes text only (see
 * {@link UpdateConstraint}).</p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} retries its next-code computation against a
 * fresh read whenever a concurrent caller claims the same code first - the same
 * {@link CodeAssignment} TOCTOU-retry helper {@code RequirementService#add} already uses,
 * generalised across bc/ul/uc/req and now constraint. {@link #update} instead runs the
 * read-modify-write retry loop {@link #updateWithOptimisticRetry} against the
 * compare-and-set guard on {@link ConstraintRepository#compareAndUpdate}, mirroring
 * {@code RequirementService#updateWithOptimisticRetry} minus that method's
 * acceptance-criteria-placeholder guard (a constraint has no derived sub-resources to synthesize).</p>
 */
public class ConstraintService
        implements AddConstraint, GetConstraint, ListConstraints, ResolveConstraints, UpdateConstraint {

    /**
     * Bound on {@link #add}'s and {@link #updateWithOptimisticRetry}'s retry loops - see
     * {@link RequirementService#MAX_RETRY_ATTEMPTS} for the rationale, identical here.
     */
    static final int MAX_RETRY_ATTEMPTS = CodeAssignment.DEFAULT_MAX_ATTEMPTS;

    private final ConstraintRepository repository;
    private final ResourceIdFactory resourceIdFactory;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added constraint (must not
     *                          be {@code null})
     */
    public ConstraintService(ConstraintRepository repository, ResourceIdFactory resourceIdFactory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
    }

    @Override
    public Constraint add(ProjectId projectId, NewConstraint command, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry - see
        // RequirementService#add's javadoc for the full rationale, identical here.
        ConstraintId id = new ConstraintId(resourceIdFactory.newId());
        // Resolved once, outside the retry: the language a fresh constraint is written under does
        // not depend on which code candidate ultimately wins, and a missing default must reject
        // the call before any code is even computed (issue #258).
        String language = LanguageTag.resolveWriteLanguage(command.language(), defaultLanguage);
        return CodeAssignment.createRetryingOnCodeCollision(MAX_RETRY_ATTEMPTS,
                DuplicateConstraintCodeException.class, () -> {
                    ConstraintCode code = nextCode(projectId, command.type());
                    Constraint constraint = new Constraint(id, code, command.title(), command.statement(),
                            command.type());
                    repository.create(projectId, constraint, language);
                    return constraint;
                });
    }

    @Override
    public List<Constraint> list(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId, displayLocale);
    }

    @Override
    public Optional<Constraint> get(ProjectId projectId, ConstraintCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code, displayLocale);
    }

    @Override
    public Constraint update(ProjectId projectId, ConstraintCode code, String title, String statement,
            String language, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return updateWithOptimisticRetry(projectId, code, title, statement, language, defaultLanguage);
    }

    /**
     * Read-modify-write helper behind {@link #update}: reads the current constraint and its
     * concurrency token together via {@link ConstraintRepository#findCurrentByCode}, derives the
     * next state, and writes it back via {@link ConstraintRepository#compareAndUpdate} - retrying
     * with a fresh read whenever a concurrent writer commits a change in between, so two parallel
     * round trips on the same constraint cannot silently lose whichever committed last. Mirrors
     * {@code RequirementService#updateWithOptimisticRetry}, minus that method's
     * acceptance-criteria-placeholder guard.
     *
     * <p>A call that changes neither text nor either field's language tag is a no-op: it returns
     * the constraint as read without writing. "Neither language tag" matters on its own (issue
     * #271) - naming {@code title} with its already-current text but an explicit, different
     * {@code language} is a genuine write (it adds that language variant), not a no-op, even
     * though the {@link Constraint} value is byte-for-byte unchanged.</p>
     *
     * @throws ConstraintNotFoundException              if no constraint with {@code code} exists
     * @throws ConstraintConcurrentlyModifiedException if the write keeps losing the race across
     *                                                  every retry attempt
     */
    private Constraint updateWithOptimisticRetry(ProjectId projectId, ConstraintCode code, String title,
            String statement, String language, String defaultLanguage) {
        ConstraintConcurrentlyModifiedException lastConflict = null;
        for (int attempt = 1; attempt <= MAX_RETRY_ATTEMPTS; attempt++) {
            // The project's own default language, not the reading process's, decides which
            // language variant this read-modify-write round trip sees (issue #456): its values are
            // what an untouched field is echoed back as and compared against, its tags what such a
            // field is written back under.
            ConstraintRepository.CurrentConstraint current =
                    repository.findCurrentByCode(projectId, code, defaultLanguage)
                            .orElseThrow(() -> new ConstraintNotFoundException(projectId, code));
            Constraint updated = new Constraint(current.value().id(), current.value().code(),
                    title != null ? title : current.value().title(),
                    statement != null ? statement : current.value().statement(),
                    current.value().type());
            // title/statement each get their own language: a field this call did not name
            // round-trips under the exact tag it was read under (a scoped no-op), never under
            // `language`/`defaultLanguage`. Resolving lazily, per field, rather than eagerly in
            // update(), means a malformed/missing language argument only ever throws when this
            // call actually touches a language-tagged field (issue #258).
            String titleLanguage = resolveTouchedLanguage(title != null, current.value().title(),
                    updated.title(), current.titleLanguage(), language, defaultLanguage);
            String statementLanguage = resolveTouchedLanguage(statement != null, current.value().statement(),
                    updated.statement(), current.statementLanguage(), language, defaultLanguage);
            if (updated.equals(current.value())
                    && Objects.equals(titleLanguage, current.titleLanguage())
                    && Objects.equals(statementLanguage, current.statementLanguage())) {
                return current.value();
            }
            try {
                repository.compareAndUpdate(projectId, current.head(), updated, titleLanguage, statementLanguage,
                        defaultLanguage);
                return updated;
            } catch (ConstraintConcurrentlyModifiedException e) {
                // A concurrent writer replaced the constraint between our read and our write -
                // retry against the now-current state instead of silently discarding that change.
                lastConflict = e;
            }
        }
        throw lastConflict;
    }

    /**
     * The BCP-47 language tag a single field ({@code title}/{@code statement}) is written under:
     * freshly resolved via {@link LanguageTag#resolveWriteLanguage} when {@code touched} and either
     * the caller named {@code language} explicitly or {@code updatedText} actually differs from
     * {@code currentText}; otherwise {@code currentLanguage} unchanged (a scoped no-op, not a
     * retag). Identical rule to {@code RequirementService#resolveTouchedLanguage} - a field named
     * by the caller but resent with its own already-current text and no {@code language} argument
     * is still a no-op, so a full-state round trip never demands a {@code defaultLanguage} the
     * project may not have (issue #271).
     */
    private static String resolveTouchedLanguage(boolean touched, String currentText, String updatedText,
            String currentLanguage, String language, String defaultLanguage) {
        boolean languageTouched = touched && (language != null || !Objects.equals(updatedText, currentText));
        return languageTouched
                ? LanguageTag.resolveWriteLanguage(language, defaultLanguage)
                : currentLanguage;
    }

    @Override
    public List<ResolvedConstraint> resolveExisting(ProjectId projectId, ResourceId... ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.length == 0) {
            return List.of();
        }
        return repository.findByIds(projectId, List.of(ids));
    }

    /**
     * Derives the next free business code for {@code type} in {@code projectId}: the highest
     * running number that subtype has ever used, plus one (starting at 1). Same shape as
     * {@code RequirementService#nextCode}, and since kogn-io/arknet#360 the same two
     * corrections - see that method for the reasoning in full; what follows is what the two facts
     * mean for a constraint.
     *
     * <p>{@link ConstraintRepository#findAllCodes} is the source, because
     * {@link ConstraintRepository#findAll} omits a constraint whose {@code title} or
     * {@code constraintStatement} is unreadable, and a code omitted from the count is a code this
     * method hands out for the second time - into a
     * {@link DuplicateConstraintCodeException} that recomputing only reproduces.</p>
     *
     * <p>The three counters are kept apart by the {@code TCON-}/{@code BCON-}/{@code RCON-} prefix
     * that {@link CodeCounter} anchors on, no longer by filtering on
     * {@link de.hauschel.arknet.req.domain.Constraint#type()}: a constraint's type is fixed at
     * creation, but the triple recording it is no more guaranteed readable than any other, and the
     * counter must not depend on it.</p>
     */
    private ConstraintCode nextCode(ProjectId projectId, ConstraintType type) {
        String prefix = type.idPrefix() + "-";
        int highest = CodeCounter.highestRunningNumber(prefix, repository.findAllCodes(projectId),
                ConstraintCode::value);
        return new ConstraintCode(prefix + (highest + 1));
    }
}
