package de.hauschel.arknet.bc.application;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * In-memory test double for {@link BoundedContextRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores bounded contexts, keyed by workspace
 * then opaque identity, so the service's policy can be exercised end-to-end. Insertion order is
 * preserved to make {@link #findAll(WorkspaceId)} assertions deterministic. {@link #create}
 * mirrors the real out-adapter's in-transaction guards: an identity collision rejects with
 * {@link ResourceAlreadyExistsException}, a business-code collision with
 * {@link DuplicateBoundedContextCodeException}.</p>
 */
final class InMemoryBoundedContextRepository implements BoundedContextRepository {

    private final Map<WorkspaceId, Map<BoundedContextId, BoundedContext>> byWorkspace = new LinkedHashMap<>();

    @Override
    public void create(WorkspaceId workspaceId, BoundedContext boundedContext) {
        Map<BoundedContextId, BoundedContext> contexts = byWorkspace.computeIfAbsent(workspaceId,
                k -> new LinkedHashMap<>());
        if (contexts.containsKey(boundedContext.id())) {
            throw new ResourceAlreadyExistsException(workspaceId, boundedContext.id().value());
        }
        boolean codeTaken = contexts.values().stream().anyMatch(bc -> bc.code().equals(boundedContext.code()));
        if (codeTaken) {
            throw new DuplicateBoundedContextCodeException(workspaceId, boundedContext.code());
        }
        contexts.put(boundedContext.id(), boundedContext);
    }

    @Override
    public void update(WorkspaceId workspaceId, BoundedContext boundedContext) {
        Map<BoundedContextId, BoundedContext> contexts = byWorkspace.getOrDefault(workspaceId, Map.of());
        if (!contexts.containsKey(boundedContext.id())) {
            throw new BoundedContextNotFoundException(workspaceId, boundedContext.code());
        }
        contexts.put(boundedContext.id(), boundedContext);
    }

    @Override
    public Optional<BoundedContext> findByCode(WorkspaceId workspaceId, BoundedContextCode code) {
        return byWorkspace.getOrDefault(workspaceId, Map.of()).values().stream()
                .filter(bc -> bc.code().equals(code))
                .findFirst();
    }

    @Override
    public List<BoundedContext> findAll(WorkspaceId workspaceId) {
        return List.copyOf(byWorkspace.getOrDefault(workspaceId, Map.of()).values());
    }
}
