// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.Objects;

/**
 * Human-readable business label of an {@link Adr}, e.g. {@code ADR-1}.
 *
 * <p>Value object wrapping the business identifier. Generation of the running number is a policy
 * concern of the application layer, not of this type. This is deliberately separate from
 * {@link AdrId}: the code is what a human types (into {@code adr_get}, {@code adr_supersede}, ...)
 * and what {@code dcterms:identifier} carries in the store; it may in principle be relabelled
 * without touching the decision's underlying identity.</p>
 *
 * <p><strong>A different numbering space than the markdown ADRs.</strong> The store's codes run
 * unpadded from {@code ADR-1} upwards, per project, assigned by this bounded context. arknet's own
 * repository additionally keeps hand-written decision records as files (zero-padded
 * {@code adr-NNN-*.md}); the two numbering spaces are unrelated and neither derives from the
 * other.</p>
 *
 * @param value the non-blank code string
 */
public record AdrCode(String value) {

    public AdrCode {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("AdrCode must not be blank");
        }
    }
}
