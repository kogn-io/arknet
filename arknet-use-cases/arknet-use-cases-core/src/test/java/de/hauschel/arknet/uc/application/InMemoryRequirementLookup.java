// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.out.RequirementLookup;

/**
 * In-memory test double for {@link RequirementLookup}.
 *
 * <p>A hand-rolled fake (not a mock): codes must be {@link #register(String, ResourceId)
 * registered} before they resolve, mirroring the real adapter's contract that an unknown code is
 * rejected rather than silently accepted. The exact rejection type is deliberately not
 * {@code UnresolvedReferenceException} - that type lives in {@code arknet-persistence-support},
 * a module {@code arknet-use-cases-core} does not (and must not) depend on;
 * {@link RequirementLookup} itself only promises "some runtime exception", so this fake's own
 * signal is enough to prove {@link UseCaseService#add} lets a lookup failure propagate.</p>
 */
final class InMemoryRequirementLookup implements RequirementLookup {

    private final Map<String, ResourceId> knownRequirements = new HashMap<>();

    void register(String requirementCode, ResourceId resourceId) {
        knownRequirements.put(requirementCode, resourceId);
    }

    @Override
    public ResourceId resolveByCode(ProjectId projectId, String requirementCode) {
        ResourceId resolved = knownRequirements.get(requirementCode);
        if (resolved == null) {
            throw new NoSuchElementException("fake lookup: unknown requirement code '" + requirementCode + "'");
        }
        return resolved;
    }
}
