// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.out;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.domain.DuplicateUseCaseCodeException;
import de.hauschel.arknet.uc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseConcurrentlyModifiedException;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Driven port: persistence capability the component needs from the outside.
 *
 * <p>Named after the capability ("store and retrieve use cases"), not after any
 * technology. Implementations live in adapter modules (e.g. an RDF-backed
 * adapter) and must not leak their mechanism into this contract.</p>
 *
 * <p>The {@link ProjectId} routing key identifies which architecture model a
 * use case belongs to. A local single-user adapter may treat it as an implicit
 * default; a remote/team adapter uses it to address one of several projects.</p>
 *
 * <p><strong>Create vs. update.</strong> Identity is opaque and minted once (see
 * {@link de.hauschel.arknet.kernel.ResourceIdFactory}), so "insert or replace by identity" is no
 * longer a coherent single operation: an identity either already exists (an update) or it does
 * not (a create), and conflating the two would hide a caller bug (writing to an id nobody
 * minted, or an id that was already used). {@link #create} and {@link #compareAndUpdate}
 * therefore make that distinction explicit at the port - there is no unconditional update: every
 * correction to an already-created use case goes through the compare-and-set guard, mirroring
 * the requirements/bounded-context bounded contexts, so a guarded write path can never
 * be bypassed by accident.</p>
 *
 * <p><strong>Language, and why a full replace needs per-field/per-step tags.</strong>
 * {@code title}/{@code goal}/each step's {@code text} may each legally carry several
 * language-tagged variants (SKOS-S14-style {@code sh:uniqueLang}). {@link #compareAndUpdate}
 * replaces a use case's triples wholesale by identity - so preserving every language variant a
 * caller did not touch cannot rely on simply never writing an untouched field; the out-adapter
 * must capture every existing variant before the replace and re-attach every one the write is not
 * itself targeting. Since {@link UpdateUseCase} lets a caller change {@code title}/{@code goal}
 * independently of each other and of any patched step, {@link #compareAndUpdate} takes one
 * language argument per field, plus a per-position map for steps - mirroring
 * {@code RequirementRepository#compareAndUpdate}'s {@code titleLanguage}/
 * {@code descriptionLanguage} split, generalised to a third, multi-instance field ({@code Step}).
 * {@link #create} takes one shared tag instead, since a freshly created use case is written whole
 * in one call (mirroring {@code TermRepository#create}).</p>
 */
public interface UseCaseRepository {

    /**
     * Persists a brand-new use case whose identity does not yet exist in the project.
     *
     * @param projectId the project (architecture model) to store the use case in
     * @param useCase     the use case to create
     * @param language    the BCP-47 language tag {@code useCase.title()}/{@code useCase.goal()}
     *                    and every step's {@code text} are written in (e.g. {@code "de"}), or
     *                    {@code null} for a plain, untagged literal - one shared tag, since a
     *                    freshly created use case is written whole in one call
     * @throws ResourceAlreadyExistsException  if a use case with this identity already exists
     * @throws DuplicateUseCaseCodeException   if another use case already carries this use
     *                                          case's {@link UseCaseCode} - identity collision
     *                                          and business-label collision are distinct
     *                                          failure modes
     * @throws RuntimeException if {@code useCase} violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-use-cases-core} must not depend on.
     */
    void create(ProjectId projectId, UseCase useCase, String language);

    /**
     * Replaces an existing use case by identity (including all its derived step resources), but
     * only if its current concurrency token still equals {@code expectedHead} - the
     * compare-and-set guard against the lost-update race (mirroring
     * {@code RequirementRepository#compareAndUpdate}). A read-modify-write round trip (e.g.
     * {@code uc_update}) reads the current state and token together via
     * {@link #findCurrentByCode}, derives {@code updated}, and calls this method with the token it
     * observed - a mismatch means the read was already stale, and the caller must re-read and
     * retry rather than silently discard the concurrent change.
     *
     * @param projectId    the project (architecture model) the use case lives in
     * @param expectedHead the {@link RevisionToken} the caller last observed for this use case
     *                     (from {@link #findCurrentByCode}), or {@code null} if the caller expects
     *                     no revision to exist yet
     * @param updated      the use case to store in place of the current one, if its head still
     *                     matches {@code expectedHead}
     * @param titleLanguage the BCP-47 language tag {@code updated.title()} is written in for this
     *                      call, or {@code null} for untagged. A call that leaves {@code title}'s
     *                      content unchanged must pass through the tag the value it read was
     *                      itself resolved under (see {@link CurrentUseCase#titleLanguage()}), so
     *                      the write is a scoped no-op on that variant rather than an unrelated
     *                      retag/collapse
     * @param goalLanguage  the same as {@code titleLanguage}, for {@code updated.goal()} (see
     *                      {@link CurrentUseCase#goalLanguage()})
     * @param stepTextLanguageByPosition the same as {@code titleLanguage}, per main-flow step
     *                      position: the tag {@code updated.steps()}' step at that position is
     *                      written in for this call, or {@code null} for untagged at that
     *                      position. A position this call does not patch must carry through
     *                      {@link CurrentUseCase#stepTextLanguageByPosition()}'s own entry for it
     * @throws UseCaseNotFoundException              if no use case with this identity exists at
     *                                                all
     * @throws UseCaseConcurrentlyModifiedException if {@code expectedHead} no longer matches the
     *                                                stored use case's current head - a
     *                                                concurrent write raced ahead
     * @throws RuntimeException if {@code updated} violates a SHACL write constraint. The
     *                          concrete signal type is deliberately not fixed by this port: a
     *                          real implementation's {@code WriteConstraintViolationException}
     *                          lives in {@code arknet-persistence-support}, a module
     *                          {@code arknet-use-cases-core} must not depend on.
     */
    void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, UseCase updated,
            String titleLanguage, String goalLanguage, Map<Integer, String> stepTextLanguageByPosition);

    /**
     * Finds a use case by its human-readable business code within a project.
     *
     * @param projectId     the project (architecture model) to look up the use case in
     * @param code          the use-case code (e.g. {@code UC1})
     * @param displayLocale the BCP-47 language tag the caller wants {@code title}/{@code goal}/
     *                      each step's {@code text} shown in, overriding this repository's own
     *                      configured display-language preference for this one call, or
     *                      {@code null} to use that preference unchanged
     * @return the use case if present, otherwise {@link Optional#empty()}
     */
    Optional<UseCase> findByCode(ProjectId projectId, UseCaseCode code, String displayLocale);

    /**
     * Reads a use case's current state together with its concurrency token (recorded by the last
     * write through this port, ADR-014). Backs the read side of the read-modify-write round trip
     * {@link #compareAndUpdate} guards the write side of - mirrors
     * {@code RequirementRepository#findCurrentByCode}.
     *
     * @param projectId the project (architecture model) to look up the use case in
     * @param code        the use-case code (e.g. {@code UC1})
     * @return the use case and its current head, or {@link Optional#empty()} if no use case with
     *         this code exists
     */
    Optional<CurrentUseCase> findCurrentByCode(ProjectId projectId, UseCaseCode code);

    /**
     * A use case's state paired with its current concurrency token (the {@link RevisionToken}, or
     * {@code null} if no write has ever been recorded for this use case), as read together by
     * {@link #findCurrentByCode}.
     *
     * @param value                      the use case as currently read
     * @param head                       the concurrency token, or {@code null}
     * @param titleLanguage              the BCP-47 language tag of the specific {@code title}
     *                                   literal {@code value.title()} was selected from (or
     *                                   {@code null} if untagged) - a read-modify-write round trip
     *                                   that does not itself intend to change {@code title} must
     *                                   pass this straight through to {@link #compareAndUpdate}'s
     *                                   {@code titleLanguage} argument
     * @param goalLanguage               the same as {@code titleLanguage}, for
     *                                   {@code value.goal()}
     * @param stepTextLanguageByPosition the same as {@code titleLanguage}, per main-flow step
     *                                   position: the tag the step at that position's currently
     *                                   selected {@code text} literal carries
     */
    record CurrentUseCase(UseCase value, RevisionToken head, String titleLanguage, String goalLanguage,
            Map<Integer, String> stepTextLanguageByPosition) {
    }

    /**
     * Returns all use cases stored in a project.
     *
     * @param projectId the project (architecture model) to list use cases from
     * @return all use cases, never {@code null}
     */
    List<UseCase> findAll(ProjectId projectId);
}
