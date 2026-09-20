// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.out;

import de.hauschel.arknet.actor.domain.Actor;

/**
 * The concurrency token {@link ActorRepository#compareAndUpdate} guards a write with and
 * {@link ActorRepository#findCurrentByCode} hands out: an opaque string representing an actor's
 * last observed write, minted and interpreted only by the out-adapter, or {@code null} if none has
 * been recorded yet. The caller never inspects or constructs the value itself - it only round-trips
 * whatever this port last handed out.
 *
 * <p>Lives at the out-port, not in {@code domain}: it carries no actor business meaning (an
 * {@link Actor} does not know its own token) and exists purely to let a caller round-trip a value
 * it read back to the same port for a compare-and-set check - a port-boundary concept, not an
 * aggregate field. Wrapping the bare token closes the primitive-obsession gap a raw {@code String}
 * left open: nothing at the port signature stopped a caller from passing, say, an actor code or
 * another string entirely where a token was expected, and the compiler could not catch it.</p>
 */
public record RevisionToken(String value) {
}
