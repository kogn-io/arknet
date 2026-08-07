// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.out;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.ConstraintNotFoundException;
import de.hauschel.arknet.req.domain.DuplicateConstraintCodeException;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;

/**
 * Driven port: persistence capability the component needs for {@link Constraint}s.
 *
 * <p>Named after the capability, not after any technology - mirrors {@link RequirementRepository}
 * in spirit, and, since issue #313, in its write shape too: {@code title}/{@code statement} carry
 * one language-tagged literal per language, and a second language can only be added by a second
 * write, so this port needs the same {@link #create}/{@link #compareAndUpdate} split and the same
 * {@link RevisionToken} guard. It stays narrower in what a write may change: a constraint's
 * {@link de.hauschel.arknet.req.domain.ConstraintType} (and with it its
 * {@link ConstraintCode}) is fixed at creation, and the ontology gives a constraint no status
 * field, so {@link #compareAndUpdate} exists for text corrections and further language variants
 * only - there is no {@code constraint_set_status} equivalent for it to back.</p>
 *
 * <p>Both writes run through the shared {@link de.hauschel.arknet.persistence.WriteFunnel}, so
 * each records a PROV-O revision and an {@code arkprov:head} - the very token
 * {@link #findCurrentByCode} hands out and {@link #compareAndUpdate} compares against.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model a constraint belongs
 * to, exactly as it does for {@link RequirementRepository}.</p>
 */
public interface ConstraintRepository {

    /**
     * Persists a brand-new constraint whose identity does not yet exist in the project.
     *
     * @param projectId  the project (architecture model) to store the constraint in
     * @param constraint the constraint to create
     * @param language   the BCP-47 language tag {@code constraint.title()} and
     *                   {@code constraint.statement()} are written in (e.g. {@code "de"}), or
     *                   {@code null} for plain, untagged literals - the same tag applies to both,
     *                   since a freshly created constraint is written whole in one call (mirrors
     *                   {@link RequirementRepository#create}'s own {@code language})
     * @throws ResourceAlreadyExistsException   if a constraint with this identity already exists
     * @throws DuplicateConstraintCodeException if another constraint already carries this
     *                                            constraint's {@link ConstraintCode} - identity
     *                                            collision and business-label collision are
     *                                            distinct failure modes, exactly as for
     *                                            {@link RequirementRepository#create}
     * @throws RuntimeException if {@code constraint} violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-requirements-core} must not depend on.
     */
    void create(ProjectId projectId, Constraint constraint, String language);

    /**
     * Replaces an existing constraint by identity, but only if its current concurrency token still
     * equals {@code expectedHead}; otherwise the write is rejected and nothing is persisted -
     * the same compare-and-set contract {@link RequirementRepository#compareAndUpdate} carries,
     * for the same reason: a read-modify-write round trip reads state and head together via
     * {@link #findCurrentByCode}, derives {@code updated}, and writes it back with the head it
     * observed, so a concurrent write cannot be silently discarded.
     *
     * <p><strong>Guards writes made through this port, not edits that bypass it.</strong> Same
     * limitation as {@link RequirementRepository#compareAndUpdate} - see that method's javadoc: a
     * direct store-first (ADR-005) edit leaves the token untouched and is therefore invisible to
     * this check.</p>
     *
     * <p><strong>Why two language arguments, not one.</strong> {@code title} and
     * {@code statement} may each legally carry several language-tagged variants (SHACL
     * {@code sh:uniqueLang}, issue #313), and {@code UpdateConstraint} lets a caller correct one
     * without the other - so the two fields can legitimately end up carrying different
     * single-call-scoped tags at different times. {@link #create}, writing a brand-new constraint
     * whole in one call, needs only one.</p>
     *
     * @param projectId  the project (architecture model) the constraint lives in
     * @param expectedHead the {@link RevisionToken} the caller last observed for this constraint
     *                     (from {@link #findCurrentByCode}), or {@code null} if the caller expects
     *                     no revision to exist yet
     * @param updated      the constraint to store in place of the current one, if its head still
     *                     matches {@code expectedHead}. Its {@link Constraint#code()} and
     *                     {@link Constraint#type()} must be the ones it already carries - this
     *                     port replaces text, never business identity
     * @param titleLanguage the BCP-47 language tag {@code updated.title()} is written in for this
     *                     call, or {@code null} for a plain, untagged literal. A call that leaves
     *                     the title's content unchanged must pass through the tag the value it
     *                     read was itself resolved under (see
     *                     {@link CurrentConstraint#titleLanguage()}), so the write is a scoped
     *                     no-op on that one language variant rather than an unrelated
     *                     retag/collapse; every other language-tagged variant of {@code title}
     *                     survives untouched
     * @param statementLanguage the same as {@code titleLanguage}, for {@code updated.statement()}
     *                     (see {@link CurrentConstraint#statementLanguage()} for the pass-through
     *                     case) - independent of {@code titleLanguage}
     * @param defaultLanguage the target project's configured default language (see
     *                     {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                     or {@code null} if it has none. Used only to decide whether an existing
     *                     <em>untagged</em> literal on {@code title}/{@code statement} should be
     *                     swept away rather than preserved as an "other" language variant: when
     *                     {@code titleLanguage}/{@code statementLanguage} (canonicalized) equals
     *                     it, the literal being written is - by construction - the very literal an
     *                     omitted {@code language} argument would have resolved to, so a
     *                     still-untagged sibling of the same predicate is a stale duplicate of it,
     *                     not a genuine other-language variant, and is dropped instead of
     *                     preserved (issue #258's lazy sweep, applied to the pre-#313 corpus of
     *                     untagged constraint literals). Has no bearing on which tag is actually
     *                     written
     * @throws ConstraintNotFoundException              if no constraint with this identity exists
     *                                                   at all
     * @throws ConstraintConcurrentlyModifiedException if {@code expectedHead} no longer matches
     *                                                   the stored constraint's current head - a
     *                                                   concurrent write raced ahead
     * @throws RuntimeException if {@code updated} violates a SHACL write constraint - same
     *                          deliberately unfixed signal type as {@link #create}
     */
    void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Constraint updated,
            String titleLanguage, String statementLanguage, String defaultLanguage);

    /**
     * Finds a constraint by its human-readable business code within a project.
     *
     * @param projectId     the project (architecture model) to look up the constraint in
     * @param code          the constraint code (e.g. {@code TCON-1})
     * @param displayLocale the BCP-47 language tag the caller wants {@code title}/
     *                      {@code statement} shown in, overriding this repository's own configured
     *                      display-language preference for this one call, or {@code null} to use
     *                      that preference unchanged
     * @return the constraint if present, otherwise {@link Optional#empty()}
     */
    Optional<Constraint> findByCode(ProjectId projectId, ConstraintCode code, String displayLocale);

    /**
     * Reads a constraint's current state together with its concurrency token, backing the read
     * side of the read-modify-write round trip whose write side {@link #compareAndUpdate} guards -
     * mirrors {@link RequirementRepository#findCurrentByCode}, minus that method's
     * multi-read caveat: a constraint has no follow-up reads (no linked terms, no positioned
     * sub-resources), so its whole state and its token do come from one query.
     *
     * @param projectId the project (architecture model) to look up the constraint in
     * @param code      the constraint code (e.g. {@code TCON-1})
     * @return the constraint and its current head, or {@link Optional#empty()} if no constraint
     *         with this code exists
     */
    Optional<CurrentConstraint> findCurrentByCode(ProjectId projectId, ConstraintCode code);

    /**
     * A constraint's state paired with its current concurrency token (the {@link RevisionToken},
     * or {@code null} if no write has ever been recorded for this constraint), as read together by
     * {@link #findCurrentByCode}.
     *
     * @param value             the constraint as currently read
     * @param head              the concurrency token, or {@code null}
     * @param titleLanguage     the BCP-47 language tag of the specific {@code title} literal
     *                          {@code value.title()} was selected from (or {@code null} if that
     *                          literal is untagged). A read-modify-write round trip that does not
     *                          itself intend to change {@code title} must pass this straight
     *                          through to {@link #compareAndUpdate}'s {@code titleLanguage}
     *                          argument, so the resulting write is a scoped no-op on the same
     *                          variant rather than an unrelated retag or a collapse of every other
     *                          language variant this constraint may carry
     * @param statementLanguage the same as {@code titleLanguage}, for {@code value.statement()}
     */
    record CurrentConstraint(Constraint value, RevisionToken head, String titleLanguage,
            String statementLanguage) {
    }

    /**
     * Returns all constraints stored in a project.
     *
     * @param projectId     the project (architecture model) to list constraints from
     * @param displayLocale the BCP-47 language tag the caller wants each constraint's
     *                      {@code title}/{@code statement} shown in, overriding this repository's
     *                      own configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged - the same per-call
     *                      override {@link #findByCode} accepts
     * @return all constraints, never {@code null}
     */
    List<Constraint> findAll(ProjectId projectId, String displayLocale);

    /**
     * Finds every constraint in a project whose identity is among {@code ids}, in one store
     * round-trip - backs {@link ResolveConstraints}. This is a batch lookup, not a per-id
     * existence check: an id absent from the project is simply absent from the result, never an
     * error.
     *
     * @param projectId the project (architecture model) to look up constraints in
     * @param ids         the opaque identities to resolve; an empty list yields an empty result
     * @return the resolved constraints found, in no particular order, never {@code null}
     */
    List<ResolveConstraints.ResolvedConstraint> findByIds(ProjectId projectId, List<ResourceId> ids);
}
