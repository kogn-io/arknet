// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.out;

import de.hauschel.arknet.prj.domain.Project;

/**
 * The concurrency token {@link ProjectRegistry#compareAndUpdate} guards a write with and
 * {@link ProjectRegistry#findCurrentById} hands out: the {@code arkprov:head} revision IRI a
 * {@link de.hauschel.arknet.persistence.WriteFunnel} write last recorded for a project
 * (ADR-014), or {@code null} if none has been recorded yet.
 *
 * <p>Lives at the out-port, not in {@code domain}: it carries no project business meaning (a
 * {@link Project} does not know its own head) and exists purely to let a caller round-trip a
 * value it read back to the same port for a compare-and-set check - a port-boundary concept, not
 * an aggregate field. Wrapping the bare revision IRI closes the primitive-obsession gap a raw
 * {@code String} left open: nothing at the port signature stopped a caller from passing, say, a
 * project label or another string entirely where a head was expected, and the compiler could not
 * catch it.</p>
 */
public record RevisionToken(String value) {
}
