// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.DuplicateProjectLabelException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;
import de.hauschel.arknet.prj.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.prj.domain.StaleProjectException;

/**
 * In-memory test double for {@link ProjectRegistry}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores projects, keyed by opaque identity, so
 * the service's policy can be exercised end-to-end. Insertion order is preserved to make
 * {@link #findAll()} assertions deterministic.</p>
 *
 * <p><strong>Concurrency token.</strong> Mirrors the real write funnel's head, minimally: a fresh
 * opaque marker minted on every {@link #register}/{@link #compareAndUpdate}, tracked per
 * identity - {@link #findCurrentById} hands it out alongside the project, {@link
 * #compareAndUpdate} rejects a stale one, exactly the CAS contract the real adapter would
 * enforce.</p>
 *
 * <p>{@link #writeCount()} counts every mutating call ({@link #register} and a
 * {@link #compareAndUpdate} that actually applies), so a test can assert that an idempotent
 * no-op ({@code attach} of an already-present anchor, {@code rename} to the current label)
 * really performed no write.</p>
 */
final class InMemoryProjectRegistry implements ProjectRegistry {

    private final Map<ProjectId, Project> byId = new LinkedHashMap<>();
    private final Map<ProjectId, String> headById = new LinkedHashMap<>();
    private final AtomicInteger writeCount = new AtomicInteger();

    @Override
    public void register(Project project) {
        if (byId.containsKey(project.id())) {
            throw new ResourceAlreadyExistsException(project.id());
        }
        boolean labelTaken = byId.values().stream().anyMatch(p -> p.label().equals(project.label()));
        if (labelTaken) {
            throw new DuplicateProjectLabelException(project.label());
        }
        for (Anchor anchor : project.anchors()) {
            findByAnchor(anchor).ifPresent(owner -> {
                throw new AnchorAlreadyRegisteredException(anchor, owner.id());
            });
        }
        byId.put(project.id(), project);
        headById.put(project.id(), UUID.randomUUID().toString());
        writeCount.incrementAndGet();
    }

    @Override
    public Optional<Project> findByAnchor(Anchor anchor) {
        return byId.values().stream()
                .filter(p -> p.anchors().contains(anchor))
                .findFirst();
    }

    @Override
    public Optional<Project> findById(ProjectId id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public List<Project> findAll() {
        return List.copyOf(byId.values());
    }

    @Override
    public Optional<CurrentProject> findCurrentById(ProjectId id) {
        return findById(id).map(project -> new CurrentProject(project, headById.get(id)));
    }

    @Override
    public void compareAndUpdate(String expectedHead, Project project) {
        Project current = byId.get(project.id());
        if (current == null) {
            throw new ProjectNotFoundException(project.id());
        }
        if (!Objects.equals(headById.get(project.id()), expectedHead)) {
            throw new StaleProjectException(project.id());
        }
        boolean labelTaken = byId.values().stream()
                .anyMatch(p -> !p.id().equals(project.id()) && p.label().equals(project.label()));
        if (labelTaken) {
            throw new DuplicateProjectLabelException(project.label());
        }
        for (Anchor anchor : project.anchors()) {
            findByAnchor(anchor).ifPresent(owner -> {
                if (!owner.id().equals(project.id())) {
                    throw new AnchorAlreadyRegisteredException(anchor, owner.id());
                }
            });
        }
        byId.put(project.id(), project);
        headById.put(project.id(), UUID.randomUUID().toString());
        writeCount.incrementAndGet();
    }

    /** @return how many mutating calls ({@link #register}/{@link #compareAndUpdate}) applied */
    int writeCount() {
        return writeCount.get();
    }
}
