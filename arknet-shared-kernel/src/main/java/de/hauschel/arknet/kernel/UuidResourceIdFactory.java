// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.UUID;

/**
 * Default {@link ResourceIdFactory}: mints a flat, opaque {@link ResourceId} under a single
 * kernel-owned base IRI.
 *
 * <p>The base IRI and the UUID minting scheme are the "kernel secret" {@link ResourceId}'s
 * Javadoc refers to - no bounded-context or resource-type segment appears in the IRI; the type
 * of a resource lives in {@code rdf:type}, not in its identity string.</p>
 */
public final class UuidResourceIdFactory implements ResourceIdFactory {

    private static final String BASE_IRI = "https://w3id.org/arknet/id/";

    @Override
    public ResourceId newId() {
        return ResourceId.of(BASE_IRI + UUID.randomUUID());
    }
}
