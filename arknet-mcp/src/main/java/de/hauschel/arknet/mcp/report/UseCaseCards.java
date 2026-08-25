// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.trace.TraceabilityGraph;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.TermRef;
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
 * <p><strong>The glossary in the sentence, not beside it (issue #333).</strong> Since issue #329
 * a use case's goal/scope/trigger/precondition/postcondition and its step/extension texts can
 * mention the ubiquitous language while the model records it as {@code arkreq:usesTerm}
 * (glossary) or {@code arkreq:primaryActor}/{@code supportingActor} (the use case's own actors)
 * edges - the same gap {@link RequirementCards} closed for requirement prose. Every one of those
 * fields is therefore marked up through {@link Glossary}, mirroring {@link
 * TraceabilityGraph#useCaseProseTexts(String)} (the mention scan behind {@code orphan_check}'s
 * "mentioned in text but not linked" list, which now covers the same fields): a mention backed
 * by a {@code usesTerm} edge, or naming the use case's own primary/supporting actor, is a link,
 * any other mention a gap. The {@code Uses terms} chip list survives only for edges whose term
 * no field's text names, exactly as {@link RequirementCards} already does for its own {@code
 * usesTerm} edges. {@code Primary actor}/{@code Supporting actors} stay full chip lists
 * regardless of mention - they are the use case's cast, always shown, not a "not named in the
 * text" residue.</p>
 *
 * <p><strong>{@code constrainedBy} (issue #329) is deliberately not rendered here.</strong> No
 * resource type's {@code oslc_rm:constrainedBy} edge is rendered in this report today - not even
 * the sibling requirements bounded context's own {@code constrainedBy} (see
 * {@link RequirementCards}, which has no constraint block at all) - so there is no existing
 * pattern to mirror; adding one would mean inventing a new cross-cutting mechanism (a constraint
 * business-code lookup, a new report section or block kind) rather than reusing an established
 * one, left for a follow-up that covers both resource types together.</p>
 */
public final class UseCaseCards {

    /** The section title, shared with {@link ModelViews}' failure message for this section. */
    public static final String SECTION_TITLE = "Use Cases";

    /**
     * The extensions block's label, shared with {@link HtmlReportRenderer#langSources} so it can
     * pair this specific {@link Block.Bullets} list with its own sub-resources rather than another
     * {@link Block.Bullets} list the same card might carry (issue #358).
     */
    public static final String EXTENSIONS_LABEL = "Extensions";

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
     * @param projectId     the project to read
     * @param displayLocale the resolved project's own configured default display language
     *                      (BCP-47 tag), or {@code null} if it has none - passed straight through
     *                      to {@code uc_list}'s own port so the report honours the same project
     *                      default {@code uc_list}/{@code term_list} already do (issue #281)
     * @param glossary    the project's glossary, for actor labels
     * @return the use-case section, ordered by business code
     */
    public ModelSection section(final ProjectId projectId, final String displayLocale, final Glossary glossary) {
        Objects.requireNonNull(glossary, "glossary");
        final List<UseCase> all = useCases.list(projectId, displayLocale);
        final Map<ResourceId, ResolvedRequirement> reqs = resolveRequirements(projectId, all);
        final List<ModelCard> cards = all.stream()
                .sorted(Comparator.comparing(uc -> uc.code().value(), BusinessCodes.ORDER))
                .map(uc -> card(uc, glossary, reqs))
                .toList();
        return new ModelSection(SECTION_TITLE, "use-cases",
                "goal, actors and the ordered main flow - as authored, not as triples", cards);
    }

    private ModelCard card(
            final UseCase uc,
            final Glossary glossary,
            final Map<ResourceId, ResolvedRequirement> reqs) {
        final Set<ResourceId> usesTerms = uc.usesTerms().stream()
                .map(TermRef::value)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        // For markup, a mention of the use case's own primary/supporting actor is not an
        // unlinked mention either - that relationship is already recorded, just under
        // primaryActor/supportingActor rather than usesTerm (issue #333, mirrors
        // TraceabilityGraph#unlinkedMentions()'s use-case sweep). The narrower usesTerms alone
        // stays the "linked" set for the Uses-terms chip reduction below, since an actor is
        // already shown in its own Primary/Supporting actor block.
        final Set<ResourceId> linked = new LinkedHashSet<>(usesTerms);
        linked.add(uc.primaryActor().value());
        uc.supportingActors().stream().map(ActorRef::value).forEach(linked::add);

        final List<String> texts = new ArrayList<>();
        texts.add(uc.goal());
        addIfNotBlank(texts, uc.scope());
        addIfNotBlank(texts, uc.trigger());
        addIfNotBlank(texts, uc.precondition());
        addIfNotBlank(texts, uc.postcondition());
        uc.steps().forEach(step -> texts.add(step.text()));
        texts.addAll(uc.extensions());

        final List<Block> blocks = new ArrayList<>();
        blocks.add(new Block.Prose("Goal", glossary.markUp(uc.goal(), linked)));
        addIfPresent(blocks, "Scope", uc.scope(), glossary, linked);
        addIfPresent(blocks, "Trigger", uc.trigger(), glossary, linked);
        blocks.add(new Block.Refs("Primary actor", List.of(glossary.ref(uc.primaryActor().value()))));
        if (!uc.supportingActors().isEmpty()) {
            blocks.add(new Block.Refs("Supporting actors", uc.supportingActors().stream()
                    .map(ActorRef::value)
                    .map(glossary::ref)
                    .toList()));
        }
        addIfPresent(blocks, "Precondition", uc.precondition(), glossary, linked);
        addIfPresent(blocks, "Postcondition", uc.postcondition(), glossary, linked);
        blocks.add(new Block.Flow("Main flow",
                uc.steps().stream().map(step -> flowStep(step, reqs, glossary, linked)).toList()));
        if (!uc.extensions().isEmpty()) {
            final List<BulletItem> items = new ArrayList<>(uc.extensions().size());
            for (int index = 0; index < uc.extensions().size(); index++) {
                items.add(new BulletItem(index + 1, glossary.markUp(uc.extensions().get(index), linked)));
            }
            blocks.add(new Block.Bullets(EXTENSIONS_LABEL, items));
        }
        UnmentionedTerms.addTo(blocks, usesTerms, glossary, texts, "Uses terms", "not named in the text");
        return new ModelCard(uc.code().value(), uc.title(), uc.id().value().value(), List.of(), blocks);
    }

    private static FlowStep flowStep(
            final Step step, final Map<ResourceId, ResolvedRequirement> reqs,
            final Glossary glossary, final Set<ResourceId> linked) {
        return new FlowStep(step.position(), glossary.markUp(step.text(), linked),
                step.realises().stream().map(ref -> requirementRef(ref, reqs)).toList());
    }

    private static Ref requirementRef(final RequirementRef ref, final Map<ResourceId, ResolvedRequirement> reqs) {
        final ResolvedRequirement resolved = reqs.get(ref.value());
        return Ref.of(resolved != null ? resolved.code().value() : ref.value().value(), ref.value().value());
    }

    private static void addIfPresent(
            final List<Block> blocks, final String label, final String value,
            final Glossary glossary, final Set<ResourceId> linked) {
        if (value != null && !value.isBlank()) {
            blocks.add(new Block.Prose(label, glossary.markUp(value, linked)));
        }
    }

    private static void addIfNotBlank(final List<String> texts, final String value) {
        if (value != null && !value.isBlank()) {
            texts.add(value);
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
