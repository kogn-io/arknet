// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.mcp;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;

/**
 * Renders {@link UseCase}s into the plain-text strings {@link UseCaseMcpTools} returns from its
 * tool calls. Split out of that class (issue #96) because the two carry independent reasons to
 * change: {@link UseCaseMcpTools} changes when a tool's parameter contract changes, this class
 * changes when the rendered text does - the two were previously mixed into a single class body.
 *
 * <p><strong>Actor/requirement display resolution.</strong> {@link ActorRef} and
 * {@link RequirementRef} carry an opaque subject identity, not a business label - but a human
 * who typed {@code Customer}/{@code FR-1} into {@code uc_add} expects to see those again, not a
 * raw IRI they cannot re-type. {@link UseCaseMcpTools} is the gate into the use-cases hexagon,
 * not part of its core, so this presenter may borrow driving ports of <em>different</em>
 * hexagons ({@link ResolveTerms}, owned by ubiquitous-language, and {@link ResolveRequirements},
 * owned by requirements) to answer that purely for display - the use-cases core itself still
 * never depends on {@code arknet-ubiquitous-language-core}/{@code arknet-requirements-core}, and
 * {@code uc_add}'s own write path still resolves via the decoupled {@code ActorLookup}/
 * {@code RequirementLookup} out-ports (ADR-008). {@link #formatFull} calls
 * {@link ResolveTerms#resolve}/{@link ResolveRequirements#resolveExisting} exactly once each per
 * rendering, batched across every {@link ActorRef}/{@link RequirementRef} involved; an id either
 * port could not resolve simply falls back to the bare IRI - rendering never throws and never
 * drops a reference.</p>
 */
final class UseCasePresenter {

    private final ResolveTerms resolveTerms;
    private final ResolveRequirements resolveRequirements;

    /**
     * @param resolveTerms        ubiquitous-language driving port used only to render a
     *                             referenced actor's business name instead of its bare IRI
     * @param resolveRequirements requirements driving port used only to render a referenced
     *                             requirement's business code instead of its bare IRI
     */
    UseCasePresenter(final ResolveTerms resolveTerms, final ResolveRequirements resolveRequirements) {
        this.resolveTerms = Objects.requireNonNull(resolveTerms, "resolveTerms");
        this.resolveRequirements = Objects.requireNonNull(resolveRequirements, "resolveRequirements");
    }

    static String formatShort(final UseCase uc) {
        return "%s | %s | %s".formatted(uc.code().value(), uc.title(), uc.goal());
    }

    String formatFull(final ProjectId projectId, final UseCase uc) {
        final Map<ResourceId, ResolvedTerm> actorsById = resolveActorsFor(projectId, uc);
        final Map<ResourceId, ResolvedRequirement> requirementsById = resolveRequirementsFor(projectId, uc);

        final StringBuilder sb = new StringBuilder();
        sb.append(uc.code().value()).append(' ').append(uc.title()).append('\n');
        sb.append("  goal: ").append(uc.goal()).append('\n');
        appendOptional(sb, "scope", uc.scope());
        appendOptional(sb, "trigger", uc.trigger());
        sb.append("  primaryActor: ").append(renderActor(uc.primaryActor(), actorsById)).append('\n');
        if (!uc.supportingActors().isEmpty()) {
            sb.append("  supportingActors: ")
                    .append(uc.supportingActors().stream().map(ref -> renderActor(ref, actorsById))
                            .reduce((a, b) -> a + ", " + b).orElse(""))
                    .append('\n');
        }
        appendOptional(sb, "precondition", uc.precondition());
        appendOptional(sb, "postcondition", uc.postcondition());
        sb.append("  steps:").append('\n');
        for (final Step step : uc.steps()) {
            sb.append("    ").append(step.position()).append(". ").append(step.text());
            if (!step.realises().isEmpty()) {
                sb.append(" -> realises ")
                        .append(step.realises().stream().map(ref -> renderRequirement(ref, requirementsById))
                                .reduce((a, b) -> a + ", " + b).orElse(""));
            }
            sb.append('\n');
        }
        if (!uc.extensions().isEmpty()) {
            sb.append("  extensions:").append('\n');
            for (final String extension : uc.extensions()) {
                sb.append("    - ").append(extension).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    /** Renders one actor reference: its resolved business name, or its bare IRI as a fallback. */
    private static String renderActor(final ActorRef ref, final Map<ResourceId, ResolvedTerm> actorsById) {
        final ResolvedTerm term = actorsById.get(ref.value());
        return term != null ? term.code().value() : ref.value().value();
    }

    /**
     * Renders one requirement reference: its resolved business code, or its bare IRI as a
     * fallback.
     */
    private static String renderRequirement(
            final RequirementRef ref, final Map<ResourceId, ResolvedRequirement> requirementsById) {
        final ResolvedRequirement requirement = requirementsById.get(ref.value());
        return requirement != null ? requirement.code().value() : ref.value().value();
    }

    /**
     * Batch-resolves every actor referenced by {@code uc} (its primary actor plus its supporting
     * actors) in exactly one call to {@link ResolveTerms#resolve}.
     *
     * <p><strong>Structurally cannot throw on a duplicate key (mirrors
     * {@code RequirementPresenter#resolveTermsFor}).</strong> {@link ResolveTerms} promises at
     * most one {@link ResolvedTerm} per identity, but this method must not rely on every
     * implementation upholding that: a plain {@code Collectors.toMap(t -> t.id(), t -> t)} throws
     * {@code IllegalStateException} the moment two returned {@link ResolvedTerm}s share an
     * identity, turning a display concern into a thrown exception - the very thing this rendering
     * path exists to avoid. The merge function below keeps the first entry for a duplicate key
     * instead; which one is kept is immaterial here, since rendering only ever reads
     * {@link ResolvedTerm#code()}.</p>
     */
    private Map<ResourceId, ResolvedTerm> resolveActorsFor(final ProjectId projectId, final UseCase uc) {
        final ResourceId[] ids = Stream.concat(
                        Stream.of(uc.primaryActor()), uc.supportingActors().stream())
                .map(ActorRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveTerms.resolve(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedTerm::id, t -> t, (first, second) -> first));
    }

    /**
     * Batch-resolves every requirement referenced by {@code uc}'s steps (the union of all
     * {@code realises} references) in exactly one call to
     * {@link ResolveRequirements#resolveExisting} - same merge-function reasoning as
     * {@link #resolveActorsFor}.
     */
    private Map<ResourceId, ResolvedRequirement> resolveRequirementsFor(
            final ProjectId projectId, final UseCase uc) {
        final ResourceId[] ids = uc.steps().stream()
                .flatMap(step -> step.realises().stream())
                .map(RequirementRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveRequirements.resolveExisting(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedRequirement::id, r -> r, (first, second) -> first));
    }

    private static void appendOptional(final StringBuilder sb, final String field, final String value) {
        if (value != null) {
            sb.append("  ").append(field).append(": ").append(value).append('\n');
        }
    }
}
