package de.hauschel.arknet.ul.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * In-memory test double for {@link TermRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores terms, keyed by workspace
 * then identity, so the service's policy can be exercised end-to-end. Insertion
 * order is preserved to make {@link #findAll(WorkspaceId)} assertions
 * deterministic.</p>
 */
final class InMemoryTermRepository implements TermRepository {

    private final Map<WorkspaceId, Map<TermId, Term>> byWorkspace = new LinkedHashMap<>();

    @Override
    public void save(WorkspaceId workspaceId, Term term) {
        byWorkspace.computeIfAbsent(workspaceId, k -> new LinkedHashMap<>())
                .put(term.id(), term);
    }

    @Override
    public Optional<Term> findById(WorkspaceId workspaceId, TermId id) {
        return Optional.ofNullable(byWorkspace.getOrDefault(workspaceId, Map.of()).get(id));
    }

    @Override
    public List<Term> findAll(WorkspaceId workspaceId) {
        return List.copyOf(byWorkspace.getOrDefault(workspaceId, Map.of()).values());
    }
}
