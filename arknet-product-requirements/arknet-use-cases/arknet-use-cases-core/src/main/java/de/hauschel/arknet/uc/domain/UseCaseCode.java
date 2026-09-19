// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

/**
 * Human-readable business label of a {@link UseCase}, e.g. {@code UC1}.
 *
 * <p>Value object wrapping the business identifier. Generation of the running number is a
 * policy concern of the application layer, not of this type. This is deliberately separate
 * from {@link UseCaseId}: the code is what a human types (into {@code uc_get}) and what
 * {@code dcterms:identifier} carries in the store; it may in principle be relabelled without
 * touching the use case's underlying identity.</p>
 *
 * @param value the non-blank code string
 */
public record UseCaseCode(String value) {

    public UseCaseCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("UseCaseCode must not be blank");
        }
    }
}
