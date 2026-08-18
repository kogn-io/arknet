// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

import java.util.HashMap;
import java.util.Map;
import java.util.NoSuchElementException;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.out.ConstraintLookup;

/**
 * In-memory test double for {@link ConstraintLookup}.
 *
 * <p>A hand-rolled fake (not a mock): codes must be {@link #register(String, ResourceId)
 * registered} before they resolve, mirroring the real adapter's contract that an unknown code is
 * rejected rather than silently accepted. The exact rejection type is deliberately not
 * {@code UnresolvedReferenceException} - that type lives in {@code arknet-persistence-support},
 * a module {@code arknet-use-cases-core} does not (and must not) depend on;
 * {@link ConstraintLookup} itself only promises "some runtime exception", so this fake's own
 * signal is enough to prove {@link UseCaseService#linkConstraint} lets a lookup failure
 * propagate.
 */
final class InMemoryConstraintLookup implements ConstraintLookup {

    private final Map<String, ResourceId> knownConstraints = new HashMap<>();

    void register(String constraintCode, ResourceId resourceId) {
        knownConstraints.put(constraintCode, resourceId);
    }

    @Override
    public ResourceId resolveByCode(ProjectId projectId, String constraintCode) {
        ResourceId resolved = knownConstraints.get(constraintCode);
        if (resolved == null) {
            throw new NoSuchElementException("fake lookup: unknown constraint code '" + constraintCode + "'");
        }
        return resolved;
    }
}
