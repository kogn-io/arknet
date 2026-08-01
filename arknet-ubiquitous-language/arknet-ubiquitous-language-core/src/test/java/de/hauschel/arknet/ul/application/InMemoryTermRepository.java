// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * In-memory test double for {@link TermRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores terms, keyed by project
 * then opaque identity, so the service's policy can be exercised end-to-end. Insertion
 * order is preserved to make {@link #findAll(ProjectId)} assertions deterministic. {@link
 * #create} honours the same contract as the real adapter (rejects an existing identity or a
 * duplicate code). {@link #update} resolves by code and replaces only the fields the caller
 * actually supplied - it cannot reproduce the real adapter's triple-level preservation of a
 * multi-valued {@code skos:prefLabel}/{@code skos:definition} (there is nothing multi-valued to
 * preserve in a plain in-memory {@link Term}), but it does exercise the same "{@code null} leaves
 * a field unchanged" contract, so {@link TermService}'s policy can be tested without a real store.</p>
 */
final class InMemoryTermRepository implements TermRepository {

    private final Map<ProjectId, Map<TermId, Term>> byProject = new LinkedHashMap<>();

    @Override
    public void create(ProjectId projectId, Term term) {
        Map<TermId, Term> terms = byProject.computeIfAbsent(projectId, k -> new LinkedHashMap<>());
        if (terms.containsKey(term.id())) {
            throw new ResourceAlreadyExistsException(projectId, term.id().value());
        }
        if (terms.values().stream().anyMatch(t -> t.code().equals(term.code()))) {
            throw new DuplicateTermCodeException(projectId, term.code());
        }
        terms.put(term.id(), term);
    }

    @Override
    public Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
            ActorFacet actorFacet) {
        Map<TermId, Term> terms = byProject.getOrDefault(projectId, Map.of());
        Term current = terms.values().stream()
                .filter(t -> t.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new TermNotFoundException(projectId, code));
        Term updated = new Term(current.id(), current.code(),
                prefLabel != null ? prefLabel : current.prefLabel(),
                definition != null ? definition : current.definition(),
                actorFacet != null ? actorFacet : current.actorFacet());
        terms.put(updated.id(), updated);
        return updated;
    }

    @Override
    public Optional<Term> findByCode(ProjectId projectId, TermCode code) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(t -> t.code().equals(code))
                .findFirst();
    }

    @Override
    public List<Term> findAll(ProjectId projectId) {
        return List.copyOf(byProject.getOrDefault(projectId, Map.of()).values());
    }

    @Override
    public List<ResolveTerms.ResolvedTerm> findByIds(ProjectId projectId, List<ResourceId> ids) {
        Set<ResourceId> wanted = Set.copyOf(ids);
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(t -> wanted.contains(t.id().value()))
                .map(t -> new ResolveTerms.ResolvedTerm(t.id().value(), t.code()))
                .toList();
    }
}
