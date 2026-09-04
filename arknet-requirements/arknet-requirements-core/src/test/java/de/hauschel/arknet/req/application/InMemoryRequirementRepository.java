// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.DuplicateRequirementCodeException;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;

/**
 * In-memory test double for {@link RequirementRepository}.
 *
 * <p>A hand-rolled fake (not a mock): it actually stores requirements, keyed by
 * project then opaque identity, so the service's policy can be exercised end-to-end.
 * Insertion order is preserved to make {@link #findAll(ProjectId)} assertions
 * deterministic.</p>
 *
 * <p><strong>Concurrency token.</strong> Mirrors the real {@link
 * de.hauschel.arknet.persistence.WriteFunnel}'s head, minimally: a fresh opaque marker minted on
 * every {@link #create}/{@link #compareAndUpdate}, tracked per identity - {@link
 * #findCurrentByCode} hands it out alongside the requirement, {@link #compareAndUpdate} rejects a
 * stale one, exactly the CAS contract the real adapter enforces via {@code arkprov:head}.</p>
 *
 * <p><strong>Legacy acceptance criteria.</strong> {@link Requirement}'s constructor rejects an
 * empty {@code acceptanceCriteria} list unconditionally, so this fake - unlike the real
 * {@code KognioRdfRequirementRepository} - can never hold a requirement without one; a legacy
 * requirement predating the mandatory-criterion invariant is instead created via {@link
 * #createLegacy}, which stores whatever criteria it is given (typically placeholder-like text)
 * but marks the identity so {@link #findCurrentByCode} reports {@code
 * acceptanceCriteriaIsSynthesized() == true} for it - mirroring the real adapter's structural
 * signal (no {@code arkreq:acceptanceCriterion} triple at all) without depending on the
 * placeholder's exact text.</p>
 */
final class InMemoryRequirementRepository implements RequirementRepository {

    private final Map<ProjectId, Map<RequirementId, Requirement>> byProject = new LinkedHashMap<>();
    /**
     * Codes seeded by {@link #seedUnmaterialisableCode}, deliberately kept out of {@link #byProject}
     * so that {@link #findAll} cannot see them while {@link #findAllCodes} can
     * (kogn-io/arknet#360).
     */
    private final Map<ProjectId, List<RequirementCode>> unmaterialisableByProject = new LinkedHashMap<>();
    private final Map<RequirementId, RevisionToken> headByIdentity = new LinkedHashMap<>();
    private final Set<RequirementId> legacyAcceptanceCriteria = new HashSet<>();
    private final Map<RequirementId, String> titleLanguageByIdentity = new LinkedHashMap<>();
    private final Map<RequirementId, String> descriptionLanguageByIdentity = new LinkedHashMap<>();
    private final Map<RequirementId, String> rationaleLanguageByIdentity = new LinkedHashMap<>();
    private final Map<RequirementId, Map<Integer, String>> acceptanceCriteriaLanguageByIdentity =
            new LinkedHashMap<>();

    @Override
    public void create(ProjectId projectId, Requirement requirement, String language) {
        Map<RequirementId, Requirement> requirements = byProject.computeIfAbsent(projectId,
                k -> new LinkedHashMap<>());
        if (requirements.containsKey(requirement.id())) {
            throw new ResourceAlreadyExistsException(projectId, requirement.id().value());
        }
        // Mirrors the real out-adapter's in-transaction askCodeExists guard: a
        // business-code collision is rejected here too, so a fake exercising RequirementService's
        // next-code retry loop actually needs that retry to succeed, the same way the real store
        // would.
        boolean codeTaken = requirements.values().stream().anyMatch(r -> r.code().equals(requirement.code()));
        if (codeTaken) {
            throw new DuplicateRequirementCodeException(projectId, requirement.code());
        }
        requirements.put(requirement.id(), requirement);
        headByIdentity.put(requirement.id(), new RevisionToken(UUID.randomUUID().toString()));
        titleLanguageByIdentity.put(requirement.id(), language);
        descriptionLanguageByIdentity.put(requirement.id(), language);
        // Null tag for a requirement created without a rationale: there is no literal to tag, and
        // findCurrentByCode must report the same null the real adapter reports for it (issue #321).
        rationaleLanguageByIdentity.put(requirement.id(), requirement.rationale() == null ? null : language);
        Map<Integer, String> criteriaLanguages = new LinkedHashMap<>();
        requirement.acceptanceCriteria().forEach(criterion -> criteriaLanguages.put(criterion.position(), language));
        acceptanceCriteriaLanguageByIdentity.put(requirement.id(), criteriaLanguages);
    }

    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Requirement updated,
            String titleLanguage, String descriptionLanguage, String rationaleLanguage,
            Map<Integer, String> acceptanceCriteriaLanguageByPosition, String defaultLanguage) {
        // This fake stores a single title/description value per identity (no multi-valued
        // literals), so there is nothing for it to sweep - defaultLanguage only matters to the
        // real out-adapter's language-variant preservation, exercised by
        // KognioRdfRequirementRepositoryMultilingualTest instead.
        Map<RequirementId, Requirement> requirements = byProject.getOrDefault(projectId, Map.of());
        Requirement current = requirements.get(updated.id());
        if (current == null) {
            throw new RequirementNotFoundException(projectId, updated.code());
        }
        if (!Objects.equals(headByIdentity.get(updated.id()), expectedHead)) {
            throw new RequirementConcurrentlyModifiedException(projectId, updated.code());
        }
        requirements.put(updated.id(), updated);
        headByIdentity.put(updated.id(), new RevisionToken(UUID.randomUUID().toString()));
        titleLanguageByIdentity.put(updated.id(), titleLanguage);
        descriptionLanguageByIdentity.put(updated.id(), descriptionLanguage);
        rationaleLanguageByIdentity.put(updated.id(), updated.rationale() == null ? null : rationaleLanguage);
        acceptanceCriteriaLanguageByIdentity.put(updated.id(), new LinkedHashMap<>(acceptanceCriteriaLanguageByPosition));
        // Mirrors the real adapter's replace-by-identity write: whatever acceptanceCriteria
        // `updated` carries is written as real triples, so the identity is never legacy again
        // afterwards - the only way to reach this line for a previously-legacy identity is a
        // write RequirementService's guard actually let through (i.e. one that replaced the
        // placeholder with a real, explicit list).
        legacyAcceptanceCriteria.remove(updated.id());
    }

    @Override
    public Optional<Requirement> findByCode(ProjectId projectId, RequirementCode code, String displayLocale) {
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(r -> r.code().equals(code))
                .findFirst();
    }

    @Override
    public Optional<CurrentRequirement> findCurrentByCode(ProjectId projectId, RequirementCode code) {
        return findByCode(projectId, code, null)
                .map(requirement -> new CurrentRequirement(requirement, headByIdentity.get(requirement.id()),
                        legacyAcceptanceCriteria.contains(requirement.id()),
                        titleLanguageByIdentity.get(requirement.id()),
                        descriptionLanguageByIdentity.get(requirement.id()),
                        rationaleLanguageByIdentity.get(requirement.id()),
                        acceptanceCriteriaLanguageByIdentity.getOrDefault(requirement.id(), Map.of())));
    }

    /**
     * Test-only backdoor for a requirement that predates the mandatory acceptance-criterion
     * invariant: stores {@code requirement} exactly like {@link #create} does, but additionally
     * marks its identity so {@link #findCurrentByCode} reports {@code
     * acceptanceCriteriaIsSynthesized() == true} - the fake's stand-in for the real adapter's
     * "no {@code arkreq:acceptanceCriterion} triple at all" signal, since {@link Requirement}'s
     * own constructor cannot represent an empty criteria list to trigger that signal
     * structurally.
     */
    void createLegacy(ProjectId projectId, Requirement requirement) {
        create(projectId, requirement, null);
        legacyAcceptanceCriteria.add(requirement.id());
    }

    @Override
    public List<Requirement> findAll(ProjectId projectId, String displayLocale) {
        // Nothing multi-valued to select a language variant from in this plain in-memory fake -
        // displayLocale is accepted and ignored.
        return List.copyOf(byProject.getOrDefault(projectId, Map.of()).values());
    }

    /**
     * Every stored requirement's code, plus every code {@link #seedUnmaterialisableCode} planted -
     * the latter standing in for what the real out-adapter's title/description skip hides from
     * {@link #findAll} (kogn-io/arknet#360). Unordered and untyped, as the port promises.
     */
    @Override
    public List<RequirementCode> findAllCodes(ProjectId projectId) {
        List<RequirementCode> codes = new ArrayList<>(byProject.getOrDefault(projectId, Map.of()).values().stream()
                .map(Requirement::code)
                .toList());
        codes.addAll(unmaterialisableByProject.getOrDefault(projectId, List.of()));
        return List.copyOf(codes);
    }

    /**
     * Plants a code that {@link #findAllCodes} reports and {@link #findAll} never will - the fake's
     * stand-in for a requirement the real out-adapter reads but cannot materialise, its mandatory
     * {@code title} or {@code description} having been left unreadable by a store-first
     * write. Nothing but the code is stored, because nothing but the code is what such a
     * requirement still contributes: the counter has to see it, every other read must not.
     */
    void seedUnmaterialisableCode(ProjectId projectId, RequirementCode code) {
        unmaterialisableByProject.computeIfAbsent(projectId, key -> new ArrayList<>()).add(code);
    }

    @Override
    public List<ResolveRequirements.ResolvedRequirement> findByIds(ProjectId projectId, List<ResourceId> ids) {
        Set<ResourceId> wanted = Set.copyOf(ids);
        return byProject.getOrDefault(projectId, Map.of()).values().stream()
                .filter(r -> wanted.contains(r.id().value()))
                .map(r -> new ResolveRequirements.ResolvedRequirement(r.id().value(), r.code()))
                .toList();
    }
}
