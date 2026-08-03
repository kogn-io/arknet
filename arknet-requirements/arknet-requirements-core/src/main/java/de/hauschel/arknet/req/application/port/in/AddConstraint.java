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
 * (constraints carry none) and minus a language argument (constraint text is not multilingual in
 * this scope, unlike requirement {@code title}/{@code description}).</p>
 */
public interface AddConstraint {

    /**
     * Adds a new constraint.
     *
     * @param projectId the project (architecture model) to add the constraint to
     * @param command     the data describing the constraint to create
     * @return the persisted constraint including its assigned identity and code
     */
    Constraint add(ProjectId projectId, NewConstraint command);

    /**
     * Input data for {@link #add(ProjectId, NewConstraint)}.
     *
     * @param title     short human-readable summary
     * @param statement the constraint in one sentence
     * @param type      which of the three subtypes this constraint is
     */
    record NewConstraint(String title, String statement, ConstraintType type) {
    }
}
