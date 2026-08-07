// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application.port.out;

import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.Requirement;

/**
 * The concurrency token {@link RequirementRepository#compareAndUpdate} guards a write with and
 * {@link RequirementRepository#findCurrentByCode} hands out: an opaque string representing a
 * resource's last observed write, minted and interpreted only by the out-adapter, or
 * {@code null} if none has been recorded yet. The caller never inspects or constructs the value
 * itself - it only round-trips whatever this port last handed out.
 *
 * <p>Shared by both compare-and-set out-ports of this bounded context - {@link
 * RequirementRepository} and {@link ConstraintRepository} (issue #313) - rather than duplicated
 * per resource type: the token carries no per-type structure at all, only the guarantee that the
 * value came from, and goes back to, the same port. Which resource a given token belongs to is
 * fixed by the port method it is passed to, not by its Java type.</p>
 *
 * <p>Lives at the out-port, not in {@code domain}: it carries no business meaning of its own
 * (neither a {@link Requirement} nor a {@link Constraint} knows its own token) and exists purely
 * to let a caller round-trip a value it read back to the same port for a compare-and-set check - a
 * port-boundary concept, not an aggregate field. Wrapping the bare token closes the
 * primitive-obsession gap a raw {@code String} left open: nothing at the port signature stopped a
 * caller from passing, say, a requirement code or another string entirely where a token was
 * expected, and the compiler could not catch it.</p>
 */
public record RevisionToken(String value) {
}
