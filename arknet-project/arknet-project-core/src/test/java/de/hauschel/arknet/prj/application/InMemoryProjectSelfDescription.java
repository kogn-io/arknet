// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import de.hauschel.arknet.prj.application.port.out.ProjectSelfDescription;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * In-memory test double for {@link ProjectSelfDescription}.
 *
 * <p>A hand-rolled fake (not a mock): it records the latest description written per project, so
 * a test can assert that every successful registry write was followed by a matching
 * self-description write, and {@link #writeCount()} lets a test assert that an idempotent no-op
 * skipped this port entirely, exactly as {@link ProjectService} promises.</p>
 */
final class InMemoryProjectSelfDescription implements ProjectSelfDescription {

    private final Map<ProjectId, Project> described = new LinkedHashMap<>();
    private final AtomicInteger writeCount = new AtomicInteger();

    @Override
    public void describe(Project project) {
        described.put(project.id(), project);
        writeCount.incrementAndGet();
    }

    /** @return the last description written for {@code id}, or {@code null} if none was */
    Project lastDescribed(ProjectId id) {
        return described.get(id);
    }

    /** @return how many times {@link #describe} was called */
    int writeCount() {
        return writeCount.get();
    }
}
