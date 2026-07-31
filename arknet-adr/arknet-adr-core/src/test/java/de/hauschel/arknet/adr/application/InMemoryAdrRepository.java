// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;

import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrConcurrentlyModifiedException;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotFoundException;
import de.hauschel.arknet.adr.domain.DuplicateAdrCodeException;
import de.hauschel.arknet.adr.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * In-memory test double for {@link AdrRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores decisions, keyed by project then opaque
 * identity, so the service's policy can be exercised end-to-end. Insertion order is preserved to
 * make {@link #findAll(ProjectId)} assertions deterministic. {@link #create} mirrors the real
 * out-adapter's in-transaction guards: an identity collision rejects with
 * {@link ResourceAlreadyExistsException}, a business-code collision with
 * {@link DuplicateAdrCodeException}.</p>
 *
 * <p><strong>Concurrency token.</strong> Mirrors the real
 * {@code de.hauschel.arknet.persistence.WriteFunnel}'s head, minimally: a fresh opaque marker minted
 * on every {@link #create}/{@link #compareAndUpdate}, tracked per identity - {@link #findCurrentByCode}
 * hands it out alongside the decision, {@link #compareAndUpdate} rejects a stale one, exactly the
 * CAS contract the real adapter enforces via {@code arkprov:head}.</p>
 */
class InMemoryAdrRepository implements AdrRepository {

    private final Map<ProjectId, Map<AdrId, Adr>> byProject = new LinkedHashMap<>();
    private final Map<AdrId, String> headByIdentity = new LinkedHashMap<>();

    @Override
    public void create(ProjectId projectId, Adr adr) {
        Map<AdrId, Adr> adrs = byProject.computeIfAbsent(projectId, k -> new LinkedHashMap<>());
        if (adrs.containsKey(adr.id())) {
            throw new ResourceAlreadyExistsException(projectId, adr.id().value());
        }
        if (adrs.values().stream().anyMatch(existing -> existing.code().equals(adr.code()))) {
            throw new DuplicateAdrCodeException(projectId, adr.code());
        }
        adrs.put(adr.id(), adr);
        headByIdentity.put(adr.id(), UUID.randomUUID().toString());
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, String expectedHead, Adr updated) {
        Map<AdrId, Adr> adrs = byProject.getOrDefault(projectId, Map.of());
        if (!adrs.containsKey(updated.id())) {
            throw new AdrNotFoundException(projectId, updated.code());
        }
        if (!Objects.equals(headByIdentity.get(updated.id()), expectedHead)) {
            throw new AdrConcurrentlyModifiedException(projectId, updated.code());
        }
        adrs.put(updated.id(), updated);
        headByIdentity.put(updated.id(), UUID.randomUUID().toString());
    }

    @Override
    public Optional<Adr> findByCode(ProjectId projectId, AdrCode code) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(adr -> adr.code().equals(code))
                .findFirst();
    }

    @Override
    public Optional<CurrentAdr> findCurrentByCode(ProjectId projectId, AdrCode code) {
        return findByCode(projectId, code)
                .map(adr -> new CurrentAdr(adr, headByIdentity.get(adr.id())));
    }

    @Override
    public List<Adr> findAll(ProjectId projectId) {
        return List.copyOf(byProject.getOrDefault(projectId, Map.of()).values());
    }

    @Override
    public Map<AdrId, AdrCode> findCodesByIds(ProjectId projectId, Collection<AdrId> ids) {
        Map<AdrId, AdrCode> codes = new LinkedHashMap<>();
        byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(adr -> ids.contains(adr.id()))
                .forEach(adr -> codes.put(adr.id(), adr.code()));
        return Map.copyOf(codes);
    }

    @Override
    public List<AdrCode> findSupersedingCodes(ProjectId projectId, AdrId supersededId) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(adr -> adr.supersedes().contains(supersededId))
                .map(adr -> adr.code().value())
                .collect(java.util.stream.Collectors.toCollection(TreeSet::new))
                .stream()
                .map(AdrCode::new)
                .toList();
    }
}
