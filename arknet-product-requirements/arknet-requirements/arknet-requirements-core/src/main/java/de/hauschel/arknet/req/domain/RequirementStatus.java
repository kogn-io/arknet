// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

/**
 * Lifecycle state of a {@link Requirement}.
 *
 * <p>Kept intentionally minimal for the MVP: a requirement is either freshly
 * {@link #PROPOSED} or has been {@link #ACCEPTED}. Richer state machines
 * (rejected, deprecated, ...) are deferred.</p>
 */
public enum RequirementStatus {
    PROPOSED,
    ACCEPTED
}
