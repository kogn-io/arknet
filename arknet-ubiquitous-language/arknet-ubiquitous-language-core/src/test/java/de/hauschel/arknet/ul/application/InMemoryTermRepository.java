package de.hauschel.arknet.ul.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * In-memory test double for {@link TermRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores terms, keyed by workspace
 * then opaque identity, so the service's policy can be exercised end-to-end. Insertion
 * order is preserved to make {@link #findAll(WorkspaceId)} assertions deterministic. It honours
 * the same create/update contract as the real adapter (create rejects an existing identity or a
 * duplicate code; update rejects a missing identity), so tests exercise the true port semantics.</p>
 */
final class InMemoryTermRepository implements TermRepository {

    private final Map<WorkspaceId, Map<TermId, Term>> byWorkspace = new LinkedHashMap<>();

    @Override
    public void create(WorkspaceId workspaceId, Term term) {
        Map<TermId, Term> terms = byWorkspace.computeIfAbsent(workspaceId, k -> new LinkedHashMap<>());
        if (terms.containsKey(term.id())) {
            throw new ResourceAlreadyExistsException(workspaceId, term.id().value());
        }
        if (terms.values().stream().anyMatch(t -> t.code().equals(term.code()))) {
            throw new DuplicateTermCodeException(workspaceId, term.code());
        }
        terms.put(term.id(), term);
    }

    @Override
    public void update(WorkspaceId workspaceId, Term term) {
        Map<TermId, Term> terms = byWorkspace.getOrDefault(workspaceId, Map.of());
        if (!terms.containsKey(term.id())) {
            throw new TermNotFoundException(workspaceId, term.code());
        }
        terms.put(term.id(), term);
    }

    @Override
    public Optional<Term> findByCode(WorkspaceId workspaceId, TermCode code) {
        return byWorkspace.getOrDefault(workspaceId, Map.of()).values().stream()
                .filter(t -> t.code().equals(code))
                .findFirst();
    }

    @Override
    public List<Term> findAll(WorkspaceId workspaceId) {
        return List.copyOf(byWorkspace.getOrDefault(workspaceId, Map.of()).values());
    }

    @Override
    public List<ResolveTerms.ResolvedTerm> findByIds(WorkspaceId workspaceId, List<ResourceId> ids) {
        Set<ResourceId> wanted = Set.copyOf(ids);
        return byWorkspace.getOrDefault(workspaceId, Map.of()).values().stream()
                .filter(t -> wanted.contains(t.id().value()))
                .map(t -> new ResolveTerms.ResolvedTerm(t.id().value(), t.code()))
                .toList();
    }
}
