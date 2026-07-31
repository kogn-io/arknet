// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.out;

import de.hauschel.arknet.bc.domain.BoundedContext;

/**
 * The concurrency token {@link BoundedContextRepository#compareAndUpdate} guards a write with and
 * {@link BoundedContextRepository#findCurrentByCode} hands out: the {@code arkprov:head} revision
 * IRI a {@link de.hauschel.arknet.persistence.WriteFunnel} write last recorded for a bounded
 * context (ADR-014), or {@code null} if none has been recorded yet.
 *
 * <p>Lives at the out-port, not in {@code domain}: it carries no bounded-context business meaning
 * (a {@link BoundedContext} does not know its own head) and exists purely to let a caller
 * round-trip a value it read back to the same port for a compare-and-set check - a port-boundary
 * concept, not an aggregate field. Wrapping the bare revision IRI closes the primitive-obsession
 * gap a raw {@code String} left open: nothing at the port signature stopped a caller from passing,
 * say, a bounded-context code or another string entirely where a head was expected, and the
 * compiler could not catch it.</p>
 */
public record RevisionToken(String value) {
}
