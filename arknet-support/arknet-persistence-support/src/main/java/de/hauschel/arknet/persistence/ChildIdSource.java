// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.UUID;

/**
 * Mints the opaque part of a child resource's local name, the {@code <id>} in
 * {@code <parent>#<kind>-<id>} (ADR-64, see {@link ChildFragments}).
 *
 * <p>The production source is {@link #random()}: a random UUID, the same source of opacity the
 * top-level resource IRIs use. A test hands its {@link VersionedWriteFunnel} a deterministic
 * source instead, to know or to provoke the names a write mints. An id carries letters, digits and
 * hyphens only, so it is a valid IRI fragment as it stands.</p>
 */
@FunctionalInterface
public interface ChildIdSource {

    /**
     * Mints the next id.
     *
     * @return an id of letters, digits and hyphens, never empty
     */
    String next();

    /**
     * The production source: a fresh random UUID per id.
     *
     * @return the random source
     */
    static ChildIdSource random() {
        return () -> UUID.randomUUID().toString();
    }
}
