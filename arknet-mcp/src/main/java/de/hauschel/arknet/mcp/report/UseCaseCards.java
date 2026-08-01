// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;

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
 * <p><strong>Actors are glossary terms.</strong> An actor reference is resolved against the
 * report's {@link Glossary}, so the chip reads {@code Customer} rather than {@code TERM-1}.
 * Requirement references keep the borrowed {@link ResolveRequirements} port (ADR-008, the same
 * borrowing {@code uc_get} does), because a requirement's business code {@code FR-1} <em>is</em>
 * how a human names it. Both are called once per report, batched across every reference of
 * every use case, and an identity neither resolves falls back to its IRI rather than being
 * dropped.</p>
 *
 * <p><strong>No marked-up prose here.</strong> A requirement's text is marked up against the
 * glossary because {@code arkreq:usesTerm} makes "this text is about that term" a fact the
 * model can hold. A use case has no such edge - only actor roles - so a glossary word in its
 * goal or a step would have no edge that could ever be pleaded missing. Showing it as a gap
 * would demand a link the model has no place for; see {@link Span.TermGap}.</p>
 */
public final class UseCaseCards {

    /** The section title, shared with {@link ModelViews}' failure message for this section. */
    public static final String SECTION_TITLE = "Use Cases";

    private final ListUseCases useCases;
    private final ResolveRequirements requirements;

    /**
     * @param useCases     the use-cases context's list in-port
     * @param requirements borrowed for realised-requirement display codes
     */
    public UseCaseCards(final ListUseCases useCases, final ResolveRequirements requirements) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
    }

    /**
     * @param projectId the project to read
     * @param glossary    the project's glossary, for actor labels
     * @return the use-case section, ordered by business code
     */
    public ModelSection section(final ProjectId projectId, final Glossary glossary) {
        Objects.requireNonNull(glossary, "glossary");
        final List<UseCase> all = useCases.list(projectId);
        final Map<ResourceId, ResolvedRequirement> reqs = resolveRequirements(projectId, all);
        final List<ModelCard> cards = all.stream()
                .sorted(Comparator.comparing(uc -> uc.code().value()))
                .map(uc -> card(uc, glossary, reqs))
                .toList();
        return new ModelSection(SECTION_TITLE, "use-cases",
                "goal, actors and the ordered main flow - as authored, not as triples", cards);
    }

    private ModelCard card(
            final UseCase uc,
            final Glossary glossary,
            final Map<ResourceId, ResolvedRequirement> reqs) {
        final List<Block> blocks = new ArrayList<>();
        blocks.add(Block.Prose.plain("Goal", uc.goal()));
        addIfPresent(blocks, "Scope", uc.scope());
        addIfPresent(blocks, "Trigger", uc.trigger());
        blocks.add(new Block.Refs("Primary actor", List.of(glossary.ref(uc.primaryActor().value()))));
        if (!uc.supportingActors().isEmpty()) {
            blocks.add(new Block.Refs("Supporting actors", uc.supportingActors().stream()
                    .map(ActorRef::value)
                    .map(glossary::ref)
                    .toList()));
        }
        addIfPresent(blocks, "Precondition", uc.precondition());
        addIfPresent(blocks, "Postcondition", uc.postcondition());
        blocks.add(new Block.Flow("Main flow", uc.steps().stream().map(step -> flowStep(step, reqs)).toList()));
        if (!uc.extensions().isEmpty()) {
            blocks.add(Block.Bullets.plain("Extensions", uc.extensions()));
        }
        return new ModelCard(uc.code().value(), uc.title(), uc.id().value().value(), List.of(), blocks);
    }

    private static FlowStep flowStep(final Step step, final Map<ResourceId, ResolvedRequirement> reqs) {
        return new FlowStep(step.position(), step.text(),
                step.realises().stream().map(ref -> requirementRef(ref, reqs)).toList());
    }

    private static Ref requirementRef(final RequirementRef ref, final Map<ResourceId, ResolvedRequirement> reqs) {
        final ResolvedRequirement resolved = reqs.get(ref.value());
        return Ref.of(resolved != null ? resolved.code().value() : ref.value().value(), ref.value().value());
    }

    private static void addIfPresent(final List<Block> blocks, final String label, final String value) {
        if (value != null && !value.isBlank()) {
            blocks.add(Block.Prose.plain(label, value));
        }
    }

    /**
     * Resolves every requirement realised by any step of any use case in one call. The merge
     * function keeps the first entry per identity rather than letting a duplicate turn a display
     * concern into an {@code IllegalStateException} - the same guard the MCP in-adapter applies,
     * for the same reason: this path exists to render, never to throw.
     */
    private Map<ResourceId, ResolvedRequirement> resolveRequirements(
            final ProjectId projectId, final List<UseCase> all) {
        final ResourceId[] ids = all.stream()
                .flatMap(uc -> uc.steps().stream())
                .flatMap(step -> step.realises().stream())
                .map(RequirementRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return requirements.resolveExisting(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedRequirement::id, r -> r, (first, second) -> first));
    }
}
