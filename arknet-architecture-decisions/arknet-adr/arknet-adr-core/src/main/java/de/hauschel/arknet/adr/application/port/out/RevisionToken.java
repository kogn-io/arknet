// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.out;

import de.hauschel.arknet.adr.domain.Adr;

/**
 * The concurrency token {@link AdrRepository#compareAndUpdate} guards a write with and
 * {@link AdrRepository#findCurrentByCode} hands out: an opaque, in-process holder of the
 * concurrency token the out-adapter read together with the state, minted and interpreted only by
 * the out-adapter. The caller never inspects or constructs the value itself - it only round-trips
 * whatever this port last handed out, within the same call: the token cannot be serialized.
 *
 * <p>Lives at the out-port, not in {@code domain}: it carries no decision business meaning
 * (an {@link Adr} does not know its own token) and exists purely to let a caller
 * round-trip a value it read back to the same port for a compare-and-set check - a port-boundary
 * concept, not an aggregate field. Wrapping the bare token closes the primitive-obsession
 * gap a raw value left open: nothing at the port signature stopped a caller from passing,
 * say, a decision code or another string entirely where a token was expected, and the
 * compiler could not catch it.</p>
 */
public record RevisionToken(Object value) {
}
