// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hauschel.arknet.bc.application.port.out.ContextRelationshipRepository;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.ContextRelationshipNotFoundException;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * In-memory test double for {@link ContextRelationshipRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores relationships, keyed by project, in
 * insertion order. {@link #createIfAbsent} mirrors the real out-adapter's triple-based idempotency
 * (issue #565) - a relationship with the exact same (upstream, downstream, relationshipType)
 * triple already recorded is returned unchanged rather than duplicated.</p>
 */
final class InMemoryContextRelationshipRepository implements ContextRelationshipRepository {

    private final Map<ProjectId, List<ContextRelationship>> byProject = new LinkedHashMap<>();

    @Override
    public ContextRelationship createIfAbsent(ProjectId projectId, ContextRelationship relationship) {
        List<ContextRelationship> relationships = byProject.computeIfAbsent(projectId, key -> new ArrayList<>());
        for (ContextRelationship existing : relationships) {
            if (sameTriple(existing, relationship)) {
                return existing;
            }
        }
        relationships.add(relationship);
        return relationship;
    }

    @Override
    public void deleteByEdge(ProjectId projectId, BoundedContextId upstream, BoundedContextId downstream,
            RelationshipType relationshipType) {
        List<ContextRelationship> relationships = byProject.computeIfAbsent(projectId, key -> new ArrayList<>());
        boolean removed = relationships.removeIf(existing -> existing.upstream().equals(upstream)
                && existing.downstream().equals(downstream) && existing.relationshipType() == relationshipType);
        if (!removed) {
            throw new ContextRelationshipNotFoundException(projectId, null, null, relationshipType);
        }
    }

    @Override
    public List<ContextRelationship> findByContext(ProjectId projectId, BoundedContextId context) {
        return all(projectId).stream()
                .filter(r -> r.upstream().equals(context) || r.downstream().equals(context))
                .toList();
    }

    @Override
    public List<ContextRelationship> findAll(ProjectId projectId) {
        return all(projectId);
    }

    private static boolean sameTriple(ContextRelationship a, ContextRelationship b) {
        return a.upstream().equals(b.upstream()) && a.downstream().equals(b.downstream())
                && a.relationshipType() == b.relationshipType();
    }

    List<ContextRelationship> all(ProjectId projectId) {
        return List.copyOf(byProject.getOrDefault(projectId, List.of()));
    }
}
