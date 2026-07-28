// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.out.ActorLookup;

/**
 * In-memory test double for {@link ActorLookup}.
 *
 * <p>A hand-rolled fake (not a mock): names must be {@link #register(String, ResourceId)
 * registered} before they resolve, mirroring the real adapter's contract that an unknown name is
 * rejected rather than silently accepted. The exact rejection type is deliberately not
 * {@code UnresolvedReferenceException} - that type lives in {@code arknet-persistence-support},
 * a module {@code arknet-use-cases-core} does not (and must not) depend on; {@link ActorLookup}
 * itself only promises "some runtime exception", so this fake's own signal is enough to prove
 * {@link UseCaseService#add} lets a lookup failure propagate.</p>
 */
final class InMemoryActorLookup implements ActorLookup {

    private final Map<String, ResourceId> knownActors = new HashMap<>();

    void register(String actorName, ResourceId resourceId) {
        knownActors.put(actorName, resourceId);
    }

    @Override
    public ResourceId resolveByName(ProjectId projectId, String actorName) {
        ResourceId resolved = knownActors.get(actorName);
        if (resolved == null) {
            throw new NoSuchElementException("fake lookup: unknown actor name '" + actorName + "'");
        }
        return resolved;
    }
}
