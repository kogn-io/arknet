// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;

/**
 * Driving port: correct the title and/or statement of an already-created constraint, or state
 * either of them in a further language.
 *
 * <p>Backs the MVP tool {@code constraint_update}. Both fields are optional: {@code null} leaves
 * that field unchanged, so a caller can correct only the statement without restating the title. A
 * non-{@code null} value must still satisfy {@link Constraint}'s own invariants (non-blank).</p>
 *
 * <p><strong>What a constraint update deliberately cannot change.</strong> Not its
 * {@link de.hauschel.arknet.req.domain.ConstraintType}: the type decides the business code's
 * prefix ({@code TCON-}/{@code BCON-}/{@code RCON-}), so retyping a constraint would either
 * invalidate its code or silently renumber it under another subtype's running number - and
 * everything already pointing at it via {@code oslc_rm:constrainedBy} refers to that code in
 * prose. And not a status: the ontology gives a constraint none (there is no
 * {@code constraint_set_status}). This port exists for text, and for the second language
 * {@code constraint_add} could not write in one call - not to turn {@link Constraint} into a
 * generally mutable resource (issue #313's explicit non-goal).</p>
 *
 * <p><strong>Language.</strong> {@code title}/{@code statement} may each legally carry several
 * language-tagged variants (SHACL {@code sh:uniqueLang}). {@code language} names the BCP-47 tag
 * every field <em>this call actually touches</em> is written in - whichever of {@code title}/
 * {@code statement} is non-{@code null} - mirroring {@code UpdateRequirement}'s single shared
 * {@code language}. A field this call does not touch keeps every language variant it already had,
 * untouched. A field that <em>is</em> being changed but ships no {@code language} falls back to
 * {@code defaultLanguage} rather than staying untagged (issue #258) - and if that changed field's
 * existing value already carries an untagged literal, writing it under a tag equal to
 * {@code defaultLanguage} sweeps the untagged one away instead of preserving it as a spurious
 * "other" variant. That sweep is what makes the pre-#313 corpus of untagged constraint literals
 * repairable at all: it normalises lazily, on the next write that happens to touch the field, not
 * as a batch migration.</p>
 */
public interface UpdateConstraint {

    /**
     * Updates the constraint identified by {@code code} within a project, leaving any
     * {@code null}/omitted argument unchanged.
     *
     * @param projectId       the project (architecture model) the constraint lives in
     * @param code            the constraint code, e.g. {@code TCON-1}
     * @param title           the new short summary, or {@code null} to leave it unchanged
     * @param statement       the new one-sentence statement, or {@code null} to leave it unchanged
     * @param language        the BCP-47 language tag a non-{@code null} {@code title}/
     *                        {@code statement} is written in, or {@code null} to fall back to
     *                        {@code defaultLanguage}. Only the existing literal carrying the tag
     *                        actually written is replaced - every other language-tagged variant of
     *                        a field being corrected survives untouched, except an existing
     *                        untagged one that a fallback to {@code defaultLanguage} sweeps away
     *                        (see class-level Language note)
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - only consulted for a field this call
     *                        is actually changing and that ships no {@code language}
     * @return the updated constraint
     * @throws de.hauschel.arknet.req.domain.ConstraintNotFoundException if no constraint with
     *                        {@code code} exists in this project
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if a changed field ships no
     *                        {@code language} and {@code defaultLanguage} is {@code null} too
     */
    Constraint update(ProjectId projectId, ConstraintCode code, String title, String statement,
            String language, String defaultLanguage);
}
