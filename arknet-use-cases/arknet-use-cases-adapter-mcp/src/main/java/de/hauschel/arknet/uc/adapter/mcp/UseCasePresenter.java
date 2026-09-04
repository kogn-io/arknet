// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.mcp;

import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.hauschel.arknet.actor.application.port.in.ResolveActors;
import de.hauschel.arknet.actor.application.port.in.ResolveActors.ResolvedActor;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints.ResolvedConstraint;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.ConstraintRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.TermRef;
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
 * hexagons ({@link ResolveActors}, owned by the actor register since issue #336, and
 * {@link ResolveRequirements}, owned by requirements) to answer that purely for display - the
 * use-cases core itself still never depends on {@code arknet-actor-core}/
 * {@code arknet-requirements-core}, and {@code uc_add}'s own write path still resolves via the
 * decoupled {@code ActorLookup}/{@code RequirementLookup} out-ports. {@link
 * #formatFull} calls {@link ResolveActors#resolveExisting}/{@link
 * ResolveRequirements#resolveExisting} exactly once each per rendering, batched across every
 * {@link ActorRef}/{@link RequirementRef} involved; an id either port could not resolve simply
 * falls back to the bare IRI - rendering never throws and never drops a reference.</p>
 *
 * <p><strong>Term/constraint display resolution (issue #329).</strong> {@link TermRef}/
 * {@link ConstraintRef} carry a glossary term's/constraint's opaque subject identity, mirroring
 * the requirements bounded context's own {@code RequirementPresenter} exactly. {@code usesTerms}
 * batches through {@link ResolveTerms}, borrowed independently of {@link ResolveActors} - since
 * issue #336 actor identities and glossary-term identities are two disjoint identity spaces
 * altogether, not merely disjoint by well-formed-project convention as before; {@code
 * constrainedBy} batches through {@link ResolveConstraints}, a second, genuinely
 * cross-bounded-context port from the requirements hexagon (not this same module's own port the
 * way the sibling {@code RequirementPresenter}'s {@code ResolveConstraints} is - {@code
 * Constraint} lives in requirements, not use-cases).</p>
 */
final class UseCasePresenter {

    private final ResolveActors resolveActors;
    private final ResolveTerms resolveTerms;
    private final ResolveRequirements resolveRequirements;
    private final ResolveConstraints resolveConstraints;

    /**
     * @param resolveActors        the actor register's driving port used to render a referenced
     *                             actor's business code instead of its bare IRI (issue #336)
     * @param resolveTerms         ubiquitous-language driving port used to render a linked
     *                             glossary term's business code instead of its bare IRI
     * @param resolveRequirements requirements driving port used only to render a referenced
     *                             requirement's business code instead of its bare IRI
     * @param resolveConstraints  requirements driving port used only to render a linked
     *                             constraint's business code instead of its bare IRI
     */
    UseCasePresenter(final ResolveActors resolveActors, final ResolveTerms resolveTerms,
            final ResolveRequirements resolveRequirements, final ResolveConstraints resolveConstraints) {
        this.resolveActors = Objects.requireNonNull(resolveActors, "resolveActors");
        this.resolveTerms = Objects.requireNonNull(resolveTerms, "resolveTerms");
        this.resolveRequirements = Objects.requireNonNull(resolveRequirements, "resolveRequirements");
        this.resolveConstraints = Objects.requireNonNull(resolveConstraints, "resolveConstraints");
    }

    static String formatShort(final UseCase uc) {
        return "%s | %s | %s".formatted(uc.code().value(), uc.title(), uc.goal());
    }

    String formatFull(final ProjectId projectId, final UseCase uc) {
        final Map<ResourceId, ResolvedActor> actorsById = resolveActorsFor(projectId, uc);
        final Map<ResourceId, ResolvedRequirement> requirementsById = resolveRequirementsFor(projectId, uc);
        final Map<ResourceId, ResolvedTerm> usesTermsById = resolveUsesTermsFor(projectId, uc);
        final Map<ResourceId, ResolvedConstraint> constraintsById = resolveConstraintsFor(projectId, uc);

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
        if (!uc.usesTerms().isEmpty()) {
            sb.append("  usesTerms: ")
                    .append(uc.usesTerms().stream().map(ref -> renderTerm(ref, usesTermsById))
                            .reduce((a, b) -> a + ", " + b).orElse(""))
                    .append('\n');
        }
        if (!uc.constrainedBy().isEmpty()) {
            sb.append("  constrainedBy: ")
                    .append(uc.constrainedBy().stream().map(ref -> renderConstraint(ref, constraintsById))
                            .reduce((a, b) -> a + ", " + b).orElse(""))
                    .append('\n');
        }
        return sb.toString().stripTrailing();
    }

    /** Renders one actor reference: its resolved business code, or its bare IRI as a fallback. */
    private static String renderActor(final ActorRef ref, final Map<ResourceId, ResolvedActor> actorsById) {
        final ResolvedActor actor = actorsById.get(ref.value());
        return actor != null ? actor.code().value() : ref.value().value();
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
     * actors) in exactly one call to {@link ResolveActors#resolveExisting} (issue #336 - the
     * register replaced the old ubiquitous-language actor facet as the resolution source here).
     *
     * <p><strong>Structurally cannot throw on a duplicate key (mirrors
     * {@code RequirementPresenter#resolveTermsFor}).</strong> {@link ResolveActors} promises at
     * most one {@link ResolvedActor} per identity, but this method must not rely on every
     * implementation upholding that: a plain {@code Collectors.toMap(a -> a.id(), a -> a)} throws
     * {@code IllegalStateException} the moment two returned {@link ResolvedActor}s share an
     * identity, turning a display concern into a thrown exception - the very thing this rendering
     * path exists to avoid. The merge function below keeps the first entry for a duplicate key
     * instead; which one is kept is immaterial here, since rendering only ever reads
     * {@link ResolvedActor#code()}.</p>
     */
    private Map<ResourceId, ResolvedActor> resolveActorsFor(final ProjectId projectId, final UseCase uc) {
        final ResourceId[] ids = Stream.concat(
                        Stream.of(uc.primaryActor()), uc.supportingActors().stream())
                .map(ActorRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveActors.resolveExisting(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedActor::id, a -> a, (first, second) -> first));
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

    /** Renders one term reference: its resolved business code, or its bare IRI as a fallback. */
    private static String renderTerm(final TermRef ref, final Map<ResourceId, ResolvedTerm> termsById) {
        final ResolvedTerm term = termsById.get(ref.value());
        return term != null ? term.code().value() : ref.value().value();
    }

    /** {@link #renderTerm}, for an already-resolved {@link ConstraintRef}. */
    private static String renderConstraint(
            final ConstraintRef ref, final Map<ResourceId, ResolvedConstraint> constraintsById) {
        final ResolvedConstraint constraint = constraintsById.get(ref.value());
        return constraint != null ? constraint.code().value() : ref.value().value();
    }

    /**
     * Batch-resolves every glossary term {@code uc} uses ({@code usesTerms}, issue #329) in
     * exactly one call to {@link ResolveTerms#resolve} - independent of
     * {@link #resolveActorsFor}'s own batch call against a different port and, since issue #336,
     * a structurally different identity space ({@code arknet-actor}'s register vs. the glossary).
     * Same duplicate-key-tolerant merge reasoning as {@link #resolveActorsFor}.
     */
    private Map<ResourceId, ResolvedTerm> resolveUsesTermsFor(final ProjectId projectId, final UseCase uc) {
        final ResourceId[] ids = uc.usesTerms().stream()
                .map(TermRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveTerms.resolve(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedTerm::id, t -> t, (first, second) -> first));
    }

    /**
     * Batch-resolves every constraint {@code uc} is bound by ({@code constrainedBy}, issue #329)
     * in exactly one call to {@link ResolveConstraints#resolveExisting}. Same duplicate-key-
     * tolerant merge reasoning as {@link #resolveActorsFor}, mirroring
     * {@code RequirementPresenter#resolveConstraintsFor}.
     */
    private Map<ResourceId, ResolvedConstraint> resolveConstraintsFor(final ProjectId projectId, final UseCase uc) {
        final ResourceId[] ids = uc.constrainedBy().stream()
                .map(ConstraintRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveConstraints.resolveExisting(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedConstraint::id, c -> c, (first, second) -> first));
    }

    private static void appendOptional(final StringBuilder sb, final String field, final String value) {
        if (value != null) {
            sb.append("  ").append(field).append(": ").append(value).append('\n');
        }
    }
}
