// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.out;

import java.util.Objects;

import de.hauschel.arknet.uc.domain.UseCase;

/**
 * The concurrency token {@link UseCaseRepository#compareAndUpdate} guards a write with and
 * {@link UseCaseRepository#findCurrentByCode} hands out: the {@code arkprov:head} revision IRI a
 * {@code WriteFunnel} write last recorded for a use case (ADR-014), or {@code null} if none has
 * been recorded yet.
 *
 * <p>Lives at the out-port, not in {@code domain}: it carries no use-case business meaning (a
 * {@link UseCase} does not know its own head) and exists purely to let a caller round-trip a
 * value it read back to the same port for a compare-and-set check - a port-boundary concept, not
 * an aggregate field. Wrapping the bare revision IRI closes the primitive-obsession gap a raw
 * {@code String} left open: nothing at the port signature stopped a caller from passing, say, a
 * use-case code or another string entirely where a head was expected, and the compiler could not
 * catch it.</p>
 *
 * @param value the wrapped revision IRI, never {@code null}
 */
public record RevisionToken(String value) {

    public RevisionToken {
        Objects.requireNonNull(value, "value");
    }
}
