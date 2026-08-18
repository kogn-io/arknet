// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.AcceptanceCriterionPositionNotFoundException;
import de.hauschel.arknet.req.domain.AcceptanceCriterionTextPatch;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Driving port: correct the title, description, rationale, acceptance criteria and/or MoSCoW
 * priority of an already-created requirement, leaving {@code status} and {@code usesTerms} to their
 * own ports ({@code req_set_status}, {@code req_link_term}).
 *
 * <p>Every field is optional: {@code null} leaves that field unchanged, so a caller can correct
 * only the description without restating the title. A non-{@code null} value must still satisfy
 * {@link Requirement}'s own invariants (non-blank, non-empty/duplicate-free criteria). A
 * {@code null} {@code priority} leaves an already-set one untouched - it is never a "remove the
 * priority" signal, since {@code null} is already the sentinel for every other field here;
 * un-setting a priority once set is out of scope, and would need a distinct signal rather than
 * overloading {@code null} (the same rule the sibling {@code UpdateTerm} port applies to its
 * Actor facette).</p>
 *
 * <p><strong>Acceptance criteria are two independent, narrowly-scoped mechanisms (issue
 * #266).</strong> {@code newAcceptanceCriteria} appends new, non-blank criterion texts after the
 * existing ones (positions continuing from the current highest); {@code
 * acceptanceCriteriaTextPatches} instead corrects the wording of one or more existing criteria by
 * {@link de.hauschel.arknet.req.domain.AcceptanceCriterion#position() position}, leaving every
 * other criterion untouched. Mirrors {@code UpdateUseCase}'s {@code stepTextPatches}: mid-list
 * insert, delete-with-shift and reorder are all explicitly out of scope - position is a purely
 * technical write-ordering detail here, never a business identity a caller renumbers. Both may be
 * given in the same call (e.g. append two criteria while also fixing the wording of an existing
 * one); a patch naming a position with no matching criterion is rejected rather than silently
 * ignored.</p>
 *
 * <p><strong>Language.</strong> {@code title}/{@code description}/{@code rationale}/each
 * acceptance criterion's {@code text} may each legally carry several language-tagged variants
 * (SKOS-S14-style {@code sh:uniqueLang}, mirroring {@code UpdateTerm}'s
 * {@code prefLabel}/{@code definition}).
 * {@code language} names the BCP-47 tag every language-tagged field <em>this call actually
 * touches</em> is written in - whichever of {@code title}/{@code description}/{@code rationale}
 * is non-{@code null}, and every criterion named in
 * {@code newAcceptanceCriteria}/{@code acceptanceCriteriaTextPatches}
 * - mirroring {@code UpdateUseCase}'s single shared {@code language} covering whichever of
 * {@code title}/{@code goal}/a patched step's text it touches. A field (or criterion) this call
 * does not touch keeps every language variant it already had, untouched, exactly as before this
 * parameter existed. A field/criterion that <em>is</em> being changed but ships no
 * {@code language} falls back to {@code defaultLanguage} (see
 * {@link #update(ProjectId, RequirementCode, String, String, String, java.util.List,
 * java.util.List, Priority, String, String)}'s {@code defaultLanguage} parameter) rather than
 * staying untagged (issue #258) - and if a changed field/criterion's existing value already
 * carries an untagged
 * literal under the same predicate, writing it under a tag equal to {@code defaultLanguage} sweeps
 * the untagged one away instead of preserving it as a spurious "other" variant (see
 * {@code RequirementRepository#compareAndUpdate}'s {@code defaultLanguage} parameter for the
 * out-adapter side of this).</p>
 *
 * <p><strong>Rationale (issue #321).</strong> {@code rationale} joins this port for the same
 * reason {@code title}/{@code description} are here at all: the reason a requirement exists is
 * refined during elicitation like any other prose, and {@code req_add}'s one-shot capture is
 * rarely where a "so that ..." reaches its final wording. Since it is optional at creation, this
 * is also the port that records a reason for a requirement registered without one.</p>
 *
 * <p><strong>Background.</strong> Backs the MVP tool {@code req_update}: requirements
 * elicited during an interview are sometimes sharpened afterwards, and until this port existed the
 * only correction path was duplicating the requirement under a new code. {@code priority} joined
 * the optional fields later because nothing else could change it once set -
 * {@code req_set_status} covers only {@code RequirementStatus} - and re-creating a requirement to
 * fix its priority loses its code and every {@code usesTerm}/{@code realises} reference. That
 * parameter is deliberately interim: a generic {@code resource_update} facade (ADR-014
 * phase 3) is meant to absorb it and the growing pile of per-bounded-context update tools it
 * belongs to; once that facade exists, this parameter goes, not the other way round.</p>
 */
public interface UpdateRequirement {

    /**
     * Updates the requirement identified by {@code code} within a project, leaving any
     * {@code null}/omitted argument unchanged.
     *
     * @param projectId         the project (architecture model) the requirement lives in
     * @param code                the requirement code, e.g. {@code FR-1}
     * @param title               the new title, or {@code null} to leave it unchanged
     * @param description         the new normative statement, or {@code null} to leave it unchanged
     * @param rationale           the new reason this requirement exists (issue #321), or
     *                            {@code null} to leave it unchanged. {@code null} is never a
     *                            request to remove an already-recorded rationale - the same rule
     *                            {@code priority} follows, since {@code null} is already this
     *                            port's "leave alone" sentinel for every field; un-setting one
     *                            would need its own distinct signal
     * @param newAcceptanceCriteria new, non-blank criterion texts to append after the existing
     *                            ones, or {@code null}/empty to append none
     * @param acceptanceCriteriaTextPatches text corrections for individual existing acceptance
     *                            criteria, addressed by their {@code position}, or {@code null} to
     *                            leave every existing criterion's text unchanged
     * @param priority            the new MoSCoW priority, or {@code null} to leave an already-set
     *                            one unchanged (never a request to remove it)
     * @param language            the BCP-47 language tag a non-{@code null} {@code title}/
     *                            {@code description}/{@code rationale}/every touched acceptance
     *                            criterion is written in, or {@code null} to fall back to
     *                            {@code defaultLanguage}. Only
     *                            the existing literal carrying the tag actually written is
     *                            replaced - every other language-tagged variant of a field/
     *                            criterion being corrected survives untouched, except an existing
     *                            untagged one that a fallback to {@code defaultLanguage} sweeps
     *                            away (see class-level Language note)
     * @param defaultLanguage     the target project's configured default language (see
     *                            {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                            or {@code null} if it has none - only consulted for a field/
     *                            criterion this call is actually changing and that ships no
     *                            {@code language}
     * @return the updated requirement
     * @throws AcceptanceCriterionPositionNotFoundException if {@code acceptanceCriteriaTextPatches}
     *                            names a position with no matching existing criterion
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if a changed field/
     *                            criterion ships no {@code language} and {@code defaultLanguage} is
     *                            {@code null} too
     */
    Requirement update(ProjectId projectId, RequirementCode code, String title, String description,
            String rationale, List<String> newAcceptanceCriteria,
            List<AcceptanceCriterionTextPatch> acceptanceCriteriaTextPatches,
            Priority priority, String language, String defaultLanguage);
}
