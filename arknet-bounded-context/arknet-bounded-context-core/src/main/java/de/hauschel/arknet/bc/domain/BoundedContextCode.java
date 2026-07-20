// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Objects;

/**
 * Human-readable business label of a {@link BoundedContext}, e.g. {@code BC-1}.
 *
 * <p>Value object wrapping the business identifier. Generation of the running number is a
 * policy concern of the application layer, not of this type. This is deliberately separate
 * from {@link BoundedContextId}: the code is what a human types (into {@code bc_get},
 * {@code bc_link_term}, ...) and what {@code dcterms:identifier} carries in the store; it may
 * in principle be relabelled without touching the bounded context's underlying identity.</p>
 *
 * @param value the non-blank code string
 */
public record BoundedContextCode(String value) {

    public BoundedContextCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("BoundedContextCode must not be blank");
        }
    }
}
