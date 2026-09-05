// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintNotFoundException;
import de.hauschel.arknet.req.domain.DuplicateConstraintCodeException;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;

/**
 * In-memory test double for {@link ConstraintRepository}.
 *
 * <p>A hand-rolled fake (not a mock), mirroring {@code InMemoryRequirementRepository}: it actually
 * stores constraints, keyed by project then opaque identity, so the service's policy can be
 * exercised end-to-end.</p>
 *
 * <p><strong>Concurrency token.</strong> Mirrors the real {@link
 * de.hauschel.arknet.persistence.WriteFunnel}'s head, minimally: a fresh opaque marker minted on
 * every {@link #create}/{@link #compareAndUpdate}, tracked per identity - {@link
 * #findCurrentByCode} hands it out alongside the constraint, {@link #compareAndUpdate} rejects a
 * stale one, exactly the CAS contract the real adapter enforces via {@code arkprov:head}.</p>
 *
 * <p><strong>One value per field, not a per-language set.</strong> This fake stores a single
 * title/statement value per identity, plus the tag each was last written under - enough to exercise
 * {@code ConstraintService}'s per-field language resolution, but not the real adapter's
 * capture-preserve-reattach of <em>other</em> language variants. That mechanism is exercised
 * against a real store by {@code KognioRdfConstraintRepositoryMultilingualTest} instead, the same
 * split {@code InMemoryRequirementRepository} already makes.</p>
 */
public final class InMemoryConstraintRepository implements ConstraintRepository {

    private final Map<ProjectId, Map<ConstraintId, Constraint>> byProject = new LinkedHashMap<>();
    /**
     * Codes seeded by {@link #seedUnmaterialisableCode}, held apart from {@link #byProject} so that
     * only {@link #findAllCodes} reports them (kogn-io/arknet#360).
     */
    private final Map<ProjectId, List<ConstraintCode>> unmaterialisableByProject = new LinkedHashMap<>();
    private final Map<ConstraintId, RevisionToken> headByIdentity = new LinkedHashMap<>();
    private final Map<ConstraintId, String> titleLanguageByIdentity = new LinkedHashMap<>();
    private final Map<ConstraintId, String> statementLanguageByIdentity = new LinkedHashMap<>();

    @Override
    public void create(ProjectId projectId, Constraint constraint, String language) {
        Map<ConstraintId, Constraint> constraints = byProject.computeIfAbsent(projectId, k -> new LinkedHashMap<>());
        if (constraints.containsKey(constraint.id())) {
            throw new ResourceAlreadyExistsException(projectId, constraint.id().value());
        }
        boolean codeTaken = constraints.values().stream().anyMatch(c -> c.code().equals(constraint.code()));
        if (codeTaken) {
            throw new DuplicateConstraintCodeException(projectId, constraint.code());
        }
        constraints.put(constraint.id(), constraint);
        headByIdentity.put(constraint.id(), new RevisionToken(UUID.randomUUID().toString()));
        titleLanguageByIdentity.put(constraint.id(), language);
        statementLanguageByIdentity.put(constraint.id(), language);
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Constraint updated,
            String titleLanguage, String statementLanguage, String defaultLanguage) {
        // Nothing multi-valued to sweep in this fake - defaultLanguage only matters to the real
        // out-adapter's language-variant preservation (see the class javadoc).
        Map<ConstraintId, Constraint> constraints = byProject.getOrDefault(projectId, Map.of());
        Constraint current = constraints.get(updated.id());
        if (current == null) {
            throw new ConstraintNotFoundException(projectId, updated.code());
        }
        if (!Objects.equals(headByIdentity.get(updated.id()), expectedHead)) {
            throw new ConstraintConcurrentlyModifiedException(projectId, updated.code());
        }
        constraints.put(updated.id(), updated);
        headByIdentity.put(updated.id(), new RevisionToken(UUID.randomUUID().toString()));
        titleLanguageByIdentity.put(updated.id(), titleLanguage);
        statementLanguageByIdentity.put(updated.id(), statementLanguage);
    }

    @Override
    public Optional<Constraint> findByCode(ProjectId projectId, ConstraintCode code, String displayLocale) {
        // Nothing multi-valued to select a language variant from in this plain in-memory fake -
        // displayLocale is accepted and ignored.
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(c -> c.code().equals(code))
                .findFirst();
    }

    @Override
    public Optional<CurrentConstraint> findCurrentByCode(ProjectId projectId, ConstraintCode code,
            String defaultLanguage) {
        // This fake holds one value per field, not one per language tag, so it has no language
        // variant to select between - defaultLanguage is accepted to honour the port contract and
        // deliberately ignored (the real adapter's selection is pinned in
        // KognioRdfConstraintRepositoryMultilingualTest instead).
        return findByCode(projectId, code, null)
                .map(constraint -> new CurrentConstraint(constraint, headByIdentity.get(constraint.id()),
                        titleLanguageByIdentity.get(constraint.id()),
                        statementLanguageByIdentity.get(constraint.id())));
    }

    @Override
    public List<Constraint> findAll(ProjectId projectId, String displayLocale) {
        return List.copyOf(byProject.getOrDefault(projectId, Map.of()).values());
    }

    /**
     * Every stored constraint's code, plus whatever {@link #seedUnmaterialisableCode} planted - the
     * codes the real out-adapter keeps but {@link #findAll} drops (kogn-io/arknet#360). All three
     * types in one unordered list, exactly as the port describes.
     */
    @Override
    public List<ConstraintCode> findAllCodes(ProjectId projectId) {
        List<ConstraintCode> codes = new ArrayList<>(byProject.getOrDefault(projectId, Map.of()).values().stream()
                .map(Constraint::code)
                .toList());
        codes.addAll(unmaterialisableByProject.getOrDefault(projectId, List.of()));
        return List.copyOf(codes);
    }

    /**
     * Plants a code visible to {@link #findAllCodes} and to nothing else - the fake's stand-in for
     * a constraint whose {@code title} or {@code constraintStatement} a store-first write
     * left unreadable, so that the real adapter's listing skips it while its code stays taken.
     */
    void seedUnmaterialisableCode(ProjectId projectId, ConstraintCode code) {
        unmaterialisableByProject.computeIfAbsent(projectId, key -> new ArrayList<>()).add(code);
    }

    @Override
    public List<ResolveConstraints.ResolvedConstraint> findByIds(ProjectId projectId, List<ResourceId> ids) {
        Set<ResourceId> wanted = Set.copyOf(ids);
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(c -> wanted.contains(c.id().value()))
                .map(c -> new ResolveConstraints.ResolvedConstraint(c.id().value(), c.code()))
                .toList();
    }
}
