// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

/**
 * Minting port: creates a fresh, store-neutral {@link ResourceId}.
 *
 * <p>Application services depend on this interface, not on any concrete minting scheme, so the
 * identity policy (flat opaque IRI, hierarchical, ...) stays a kernel/composition-root concern
 * rather than leaking into a bounded context's domain or application layer.</p>
 */
public interface ResourceIdFactory {

    /**
     * Mints a new, previously unused {@link ResourceId}.
     *
     * @return a fresh resource identity
     */
    ResourceId newId();
}
