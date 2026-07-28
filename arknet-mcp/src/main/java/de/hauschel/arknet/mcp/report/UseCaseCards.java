// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;

/**
 * Builds the report's Cockburn-style use-case cards from the use-cases context's read
 * in-ports.
 *
 * <p>This is the reason the report stopped rendering everything generically. Read as triples,
 * a use case falls apart: its flow lives in {@code n} separate {@code arkreq:Step} resources
 * under opaque IRIs whose order is an {@code arkreq:position} literal, and its actors and
 * realised requirements are further opaque IRIs. Read through {@link ListUseCases} it arrives
 * as what it is - a goal, actors, and an ordered flow - because the owning context already did
 * the ordering and the joining.</p>
 *
 * <p><strong>Borrowed display ports.</strong> Actor and requirement references carry opaque
 * identities, so this builder borrows {@link ResolveTerms} (owned by ubiquitous-language) and
 * {@link ResolveRequirements} (owned by requirements) purely to render a business code instead
 * of a bare IRI - the same borrowing the use-cases MCP in-adapter does for {@code uc_get}
 * (ADR-008). Both are called exactly once per report, batched across every reference of every
 * use case, and an identity neither port resolves falls back to its IRI rather than being
 * dropped.</p>
 */
public final class UseCaseCards {

    private final ListUseCases useCases;
    private final ResolveTerms terms;
    private final ResolveRequirements requirements;

    /**
     * @param useCases     the use-cases context's list in-port
     * @param terms        borrowed for actor display codes
     * @param requirements borrowed for realised-requirement display codes
     */
    public UseCaseCards(
            final ListUseCases useCases,
            final ResolveTerms terms,
            final ResolveRequirements requirements) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
    }

    /**
     * @param workspaceId the workspace to read
     * @return the use-case section, ordered by business code
     */
    public ModelSection section(final WorkspaceId workspaceId) {
        final List<UseCase> all = useCases.list(workspaceId);
        final Map<ResourceId, ResolvedTerm> actors = resolveActors(workspaceId, all);
        final Map<ResourceId, ResolvedRequirement> reqs = resolveRequirements(workspaceId, all);
        final List<ModelCard> cards = all.stream()
                .sorted(java.util.Comparator.comparing(uc -> uc.code().value()))
                .map(uc -> card(uc, actors, reqs))
                .toList();
        return new ModelSection("Use Cases", "use-cases",
                "goal, actors and the ordered main flow - as authored, not as triples", cards);
    }

    private ModelCard card(
            final UseCase uc,
            final Map<ResourceId, ResolvedTerm> actors,
            final Map<ResourceId, ResolvedRequirement> reqs) {
        final List<Block> blocks = new ArrayList<>();
        blocks.add(new Block.Prose("Goal", uc.goal()));
        addIfPresent(blocks, "Scope", uc.scope());
        addIfPresent(blocks, "Trigger", uc.trigger());
        blocks.add(new Block.Refs("Primary actor", List.of(actorRef(uc.primaryActor(), actors))));
        if (!uc.supportingActors().isEmpty()) {
            blocks.add(new Block.Refs("Supporting actors",
                    uc.supportingActors().stream().map(ref -> actorRef(ref, actors)).toList()));
        }
        addIfPresent(blocks, "Precondition", uc.precondition());
        addIfPresent(blocks, "Postcondition", uc.postcondition());
        blocks.add(new Block.Flow("Main flow", uc.steps().stream().map(step -> flowStep(step, reqs)).toList()));
        if (!uc.extensions().isEmpty()) {
            blocks.add(new Block.Bullets("Extensions", uc.extensions()));
        }
        return new ModelCard(uc.code().value(), uc.title(), uc.id().value().value(), List.of(), blocks);
    }

    private static FlowStep flowStep(final Step step, final Map<ResourceId, ResolvedRequirement> reqs) {
        return new FlowStep(step.position(), step.text(),
                step.realises().stream().map(ref -> requirementRef(ref, reqs)).toList());
    }

    private static Ref actorRef(final ActorRef ref, final Map<ResourceId, ResolvedTerm> actors) {
        final ResolvedTerm resolved = actors.get(ref.value());
        return new Ref(resolved != null ? resolved.code().value() : ref.value().value(), ref.value().value());
    }

    private static Ref requirementRef(final RequirementRef ref, final Map<ResourceId, ResolvedRequirement> reqs) {
        final ResolvedRequirement resolved = reqs.get(ref.value());
        return new Ref(resolved != null ? resolved.code().value() : ref.value().value(), ref.value().value());
    }

    private static void addIfPresent(final List<Block> blocks, final String label, final String value) {
        if (value != null && !value.isBlank()) {
            blocks.add(new Block.Prose(label, value));
        }
    }

    /**
     * Resolves every actor of every use case in one call. The merge function keeps the first
     * entry per identity rather than letting a duplicate turn a display concern into an
     * {@code IllegalStateException} - the same guard the MCP in-adapter applies, for the same
     * reason: this path exists to render, never to throw.
     */
    private Map<ResourceId, ResolvedTerm> resolveActors(final WorkspaceId workspaceId, final List<UseCase> all) {
        final ResourceId[] ids = all.stream()
                .flatMap(uc -> Stream.concat(Stream.of(uc.primaryActor()), uc.supportingActors().stream()))
                .map(ActorRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return terms.getById(workspaceId, ids).stream()
                .collect(Collectors.toMap(ResolvedTerm::id, t -> t, (first, second) -> first));
    }

    /** Resolves every requirement realised by any step of any use case in one call. */
    private Map<ResourceId, ResolvedRequirement> resolveRequirements(
            final WorkspaceId workspaceId, final List<UseCase> all) {
        final ResourceId[] ids = all.stream()
                .flatMap(uc -> uc.steps().stream())
                .flatMap(step -> step.realises().stream())
                .map(RequirementRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return requirements.getById(workspaceId, ids).stream()
                .collect(Collectors.toMap(ResolvedRequirement::id, r -> r, (first, second) -> first));
    }
}
