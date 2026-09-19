// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

/**
 * MoSCoW prioritisation of a {@link Requirement}.
 *
 * <p>Applies to both functional and non-functional requirements; a requirement
 * may leave this unset (see {@link Requirement#priority()}).</p>
 */
public enum Priority {
    MUST_HAVE,
    SHOULD_HAVE,
    COULD_HAVE,
    WONT_HAVE
}
