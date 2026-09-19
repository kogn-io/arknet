// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.pr.shared.RequirementCode;
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

    /**
     * The reverse direction: every registered code whose identity is among {@code ids}. Unknown
     * identities are simply absent, exactly as the port promises - a fake that threw here would
     * make a display-time miss look like a write-time rejection.
     */
    @Override
    public Map<ResourceId, RequirementCode> resolveCodes(ProjectId projectId, ResourceId... ids) {
        Set<ResourceId> wanted = Set.of(ids);
        Map<ResourceId, RequirementCode> resolved = new LinkedHashMap<>();
        knownRequirements.forEach((code, id) -> {
            if (wanted.contains(id)) {
                resolved.put(id, new RequirementCode(code));
            }
        });
        return Map.copyOf(resolved);
    }

}
