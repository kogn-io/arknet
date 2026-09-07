// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.out.RoleLookup;

/**
 * In-memory test double for {@link RoleLookup}.
 *
 * <p>A hand-rolled fake (not a mock): codes must be {@link #register(String, ResourceId)
 * registered} before they resolve, mirroring the real adapter's contract that an unknown code is
 * rejected rather than silently accepted. The exact rejection type is deliberately not
 * {@code UnresolvedReferenceException} - that type lives in {@code arknet-persistence-support},
 * a module {@code arknet-use-cases-core} does not (and must not) depend on; {@link RoleLookup}
 * itself only promises "some runtime exception", so this fake's own signal is enough to prove
 * {@link UseCaseService#add} lets a lookup failure propagate.</p>
 */
final class InMemoryRoleLookup implements RoleLookup {

    private final Map<String, ResourceId> knownRoles = new HashMap<>();

    void register(String roleCode, ResourceId resourceId) {
        knownRoles.put(roleCode, resourceId);
    }

    @Override
    public ResourceId resolveByCode(ProjectId projectId, String roleCode) {
        ResourceId resolved = knownRoles.get(roleCode);
        if (resolved == null) {
            throw new NoSuchElementException("fake lookup: unknown role code '" + roleCode + "'");
        }
        return resolved;
    }
}
