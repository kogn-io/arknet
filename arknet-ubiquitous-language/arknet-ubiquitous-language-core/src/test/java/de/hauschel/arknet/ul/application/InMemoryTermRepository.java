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
    public void create(ProjectId projectId, Term term, String language) {
        Map<TermId, Term> terms = byProject.computeIfAbsent(projectId, k -> new LinkedHashMap<>());
        if (terms.containsKey(term.id())) {
            throw new ResourceAlreadyExistsException(projectId, term.id().value());
        }
        if (terms.values().stream().anyMatch(t -> t.code().equals(term.code()))) {
            throw new DuplicateTermCodeException(projectId, term.code());
        }
        // language is a real store-level concept (which literal a write scopes its delete to) that
        // this in-memory fake has nothing multi-valued to model - accepted and ignored, same as the
        // real adapter's language-scoped delete has nothing to do against a fresh identity.
        terms.put(term.id(), term);
    }

    @Override
    public Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
            ActorFacet actorFacet, String language, String defaultLanguage, Optional<TermCode> broader) {
        // Nothing multi-valued to sweep in this plain in-memory fake either (see class-level
        // note) - defaultLanguage is accepted and ignored here too. broader (issue #252) is not
        // resolved/cycle-checked here either - that is the real out-adapter's concern (see
        // TermCycleException's javadoc), this fake just applies the same
        // null-unchanged/empty-clear/present-replace tri-state TermService already hands it.
        Map<TermId, Term> terms = byProject.getOrDefault(projectId, Map.of());
        Term current = terms.values().stream()
                .filter(t -> t.code().equals(code))
                .findFirst()
                .orElseThrow(() -> new TermNotFoundException(projectId, code));
        Term updated = new Term(current.id(), current.code(),
                prefLabel != null ? prefLabel : current.prefLabel(),
                definition != null ? definition : current.definition(),
                actorFacet != null ? actorFacet : current.actorFacet(),
                broader != null ? broader.orElse(null) : current.broader());
        terms.put(updated.id(), updated);
        return updated;
    }

    @Override
    public Optional<Term> findByCode(ProjectId projectId, TermCode code, String displayLocale) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(t -> t.code().equals(code))
                .findFirst();
    }

    @Override
    public List<Term> findAll(ProjectId projectId, String displayLocale) {
        // Nothing multi-valued to select a language variant from in this plain in-memory fake
        // either (see class-level note) - displayLocale is accepted and ignored.
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

    @Override
    public void delete(ProjectId projectId, TermCode code) {
        // The cross-BC reference check (issue #335) is the real out-adapter's concern - it is the
        // only side that can traverse the store's other named graphs (see
        // TermReferencedException's javadoc). This fake only exercises TermService's own
        // pass-through and the not-found case.
        Map<TermId, Term> terms = byProject.getOrDefault(projectId, Map.of());
        TermId id = terms.values().stream()
                .filter(t -> t.code().equals(code))
                .findFirst()
                .map(Term::id)
                .orElseThrow(() -> new TermNotFoundException(projectId, code));
        terms.remove(id);
    }
}
