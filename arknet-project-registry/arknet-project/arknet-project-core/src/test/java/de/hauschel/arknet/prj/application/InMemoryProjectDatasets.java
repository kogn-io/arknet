// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.application.port.out.ProjectDatasets;
import de.hauschel.arknet.prj.domain.DatasetInUseException;

/**
 * In-memory test double for {@link ProjectDatasets}: per project, the model resources its dataset
 * holds, plus a switch that makes the removal fail the way a dataset held open by a concurrent call
 * does.
 *
 * <p>Independent of {@link InMemoryProjectRegistry} for the same reason {@link
 * InMemoryDatasetInventory} is: the registry entry and the dataset are two stores, and the tests
 * of deletion have to be able to tell which of the two went away.</p>
 */
final class InMemoryProjectDatasets implements ProjectDatasets {

    private final Map<ProjectId, Map<String, Long>> resources = new LinkedHashMap<>();
    private final Map<ProjectId, Long> revisions = new LinkedHashMap<>();
    private final Set<ProjectId> deleted = new LinkedHashSet<>();
    private final Set<ProjectId> inUse = new LinkedHashSet<>();
    private final Map<ProjectId, RuntimeException> failures = new LinkedHashMap<>();
    private Runnable beforeCounting = () -> { };

    /**
     * Declares that a project's dataset holds {@code count} model resources of {@code type}.
     *
     * @return this fake, for chaining in a test's arrange step
     */
    InMemoryProjectDatasets holding(ProjectId projectId, String type, long count) {
        resources.computeIfAbsent(projectId, id -> new TreeMap<>()).put(type, count);
        return this;
    }

    /** Declares that a project's dataset holds {@code count} entries in its version history. */
    InMemoryProjectDatasets withRevisions(ProjectId projectId, long count) {
        revisions.put(projectId, count);
        return this;
    }

    /** Makes every later {@link #delete} of this project's dataset fail as held open. */
    InMemoryProjectDatasets inUse(ProjectId projectId) {
        inUse.add(projectId);
        return this;
    }

    /** Makes every later {@link #delete} of this project's dataset fail with {@code failure}. */
    InMemoryProjectDatasets failing(ProjectId projectId, RuntimeException failure) {
        failures.put(projectId, failure);
        return this;
    }

    /** Runs {@code action} before every count - a stand-in for a concurrent caller. */
    InMemoryProjectDatasets beforeCounting(Runnable action) {
        beforeCounting = action;
        return this;
    }

    /** @return whether {@link #delete} removed this project's dataset */
    boolean wasDeleted(ProjectId projectId) {
        return deleted.contains(projectId);
    }

    @Override
    public synchronized Map<String, Long> modelResourceCounts(ProjectId projectId) {
        beforeCounting.run();
        return Map.copyOf(resources.getOrDefault(projectId, Map.of()));
    }

    @Override
    public synchronized long revisionCount(ProjectId projectId) {
        return revisions.getOrDefault(projectId, 0L);
    }

    @Override
    public synchronized void delete(ProjectId projectId) {
        if (inUse.contains(projectId)) {
            throw new DatasetInUseException(projectId, new IllegalStateException("open lease"));
        }
        if (failures.containsKey(projectId)) {
            throw failures.get(projectId);
        }
        resources.remove(projectId);
        revisions.remove(projectId);
        deleted.add(projectId);
    }
}
