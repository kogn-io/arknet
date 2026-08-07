// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintType;

/**
 * Driving port: register a new constraint.
 *
 * <p>Backs the MVP tool {@code constraint_add}. Identity assignment
 * ({@code TCON-N}/{@code BCON-N}/{@code RCON-N}, one running number per subtype) is policy of the
 * implementing application service - mirrors {@link AddRequirement} exactly, minus a status
 * (constraints carry none).</p>
 *
 * <p><strong>Language.</strong> {@code title} and {@code statement} are written as
 * language-tagged literals, both under the single {@code language} this one call names (issue
 * #313) - a brand-new constraint is written whole, so there is nothing yet to tag differently per
 * field. A second language is added afterwards by {@link UpdateConstraint}, one tag per call,
 * exactly the two-call shape {@code req_add}/{@code req_update} and
 * {@code term_add}/{@code term_update} already have.</p>
 */
public interface AddConstraint {

    /**
     * Adds a new constraint.
     *
     * @param projectId       the project (architecture model) to add the constraint to
     * @param command         the data describing the constraint to create
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - used only when
     *                        {@link NewConstraint#language()} is omitted
     * @return the persisted constraint including its assigned identity and code
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if {@code command} names no
     *                        {@code language} and {@code defaultLanguage} is {@code null} too
     */
    Constraint add(ProjectId projectId, NewConstraint command, String defaultLanguage);

    /**
     * Input data for {@link #add(ProjectId, NewConstraint, String)}.
     *
     * @param title     short human-readable summary
     * @param statement the constraint in one sentence
     * @param type      which of the three subtypes this constraint is
     * @param language  the BCP-47 language tag {@code title} and {@code statement} are written in
     *                  (e.g. {@code "de"}), or {@code null} to fall back to the project's
     *                  configured default language
     */
    record NewConstraint(String title, String statement, ConstraintType type, String language) {
    }
}
