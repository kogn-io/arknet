// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * In-memory test double for {@link ContextRelationshipRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores relationships, keyed by project, in
 * insertion order - unconditional {@link #create}, mirroring the real out-adapter's "no
 * compare-and-set, no dedup" contract (decision 4 of issue #125).</p>
 */
final class InMemoryContextRelationshipRepository implements ContextRelationshipRepository {

    private final Map<ProjectId, List<ContextRelationship>> byProject = new LinkedHashMap<>();

    @Override
    public ContextRelationship create(ProjectId projectId, ContextRelationship relationship) {
        byProject.computeIfAbsent(projectId, key -> new ArrayList<>()).add(relationship);
        return relationship;
    }

    List<ContextRelationship> all(ProjectId projectId) {
        return List.copyOf(byProject.getOrDefault(projectId, List.of()));
    }
}
