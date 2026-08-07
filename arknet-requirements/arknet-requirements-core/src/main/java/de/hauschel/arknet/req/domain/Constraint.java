// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * A single non-negotiable, externally-imposed constraint on the solution space (ISO 29148):
 * {@code arkreq:TechnicalConstraint}, {@code arkreq:BusinessConstraint} or
 * {@code arkreq:RegulatoryConstraint}, attached to zero or more {@link Requirement}s via
 * {@code oslc_rm:constrainedBy}.
 *
 * <p>Value object of the requirements component, mirroring {@link Requirement}'s own shape: all
 * invariants are enforced in the compact constructor; instances are immutable. Unlike
 * {@link Requirement}, a {@link Constraint} carries no lifecycle status at all - the ontology
 * gives it no status field, and no {@code constraint_set_status} tool exists. Its text, however,
 * <em>is</em> correctable: {@code constraint_update} (issue #313) replaces {@link #title()}/
 * {@link #statement()}, which is also the only way to state either in a second language, since a
 * write carries exactly one language tag per call. What no write path can change is
 * {@link #code()} and {@link #type()} - the type decides the code's prefix, and everything
 * referring to a constraint refers to that code. Every subtype is equally supported; the type
 * distinction lives only in {@link #type()} and the RDF type the out-adapter writes for it, not in
 * a class hierarchy.</p>
 *
 * @param id        opaque, unchanging identity of this constraint (never a business label);
 *                  minted once by a {@link de.hauschel.arknet.kernel.ResourceIdFactory} and
 *                  stable across relabelling
 * @param code      human-readable business label (e.g. {@code TCON-1}, {@code BCON-1},
 *                  {@code RCON-1}); maps to {@code dcterms:identifier}
 * @param title     short human-readable summary; maps to {@code dcterms:title}
 * @param statement the constraint in one sentence ("Must run on the JVM", "Budget cap of ...");
 *                  maps to {@code arkreq:constraintStatement} and is required by the requirements
 *                  SHACL shape
 * @param type      which of the three subtypes this constraint is
 */
public record Constraint(
        ConstraintId id,
        ConstraintCode code,
        String title,
        String statement,
        ConstraintType type) {

    public Constraint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(title, "title");
        Objects.requireNonNull(statement, "statement");
        Objects.requireNonNull(type, "type");
        if (title.isBlank()) {
            throw new IllegalArgumentException("title must not be blank");
        }
        if (statement.isBlank()) {
            throw new IllegalArgumentException("statement must not be blank");
        }
    }
}
