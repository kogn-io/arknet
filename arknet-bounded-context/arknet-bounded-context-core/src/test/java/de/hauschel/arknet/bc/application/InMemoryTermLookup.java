// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * In-memory test double for {@link TermLookup}.
 *
 * <p>A hand-rolled fake (not a mock): codes must be {@link #register(String, ResourceId)
 * registered} before they resolve, mirroring the real adapter's contract that an unknown code is
 * rejected rather than silently accepted. The exact rejection type is deliberately not
 * {@code UnresolvedReferenceException} - that type lives in {@code arknet-persistence-support},
 * a module {@code arknet-bounded-context-core} does not (and must not) depend on;
 * {@link TermLookup} itself only promises "some runtime exception", so this fake's own signal is
 * enough to prove {@link BoundedContextService#linkTerm} lets a lookup failure propagate.</p>
 */
final class InMemoryTermLookup implements TermLookup {

    private final Map<String, ResourceId> knownTerms = new HashMap<>();

    void register(String termCode, ResourceId resourceId) {
        knownTerms.put(termCode, resourceId);
    }

    @Override
    public ResourceId resolveByCode(WorkspaceId workspaceId, String termCode) {
        ResourceId resolved = knownTerms.get(termCode);
        if (resolved == null) {
            throw new NoSuchElementException("fake lookup: unknown term code '" + termCode + "'");
        }
        return resolved;
    }
}
