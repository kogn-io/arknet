// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import de.hauschel.arknet.actor.application.port.in.ResolveRoles;
import de.hauschel.arknet.actor.application.port.in.ResolveRoles.ResolvedRole;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.in.ResolveConstraints;
import de.hauschel.arknet.uc.application.port.in.ResolveConstraints.ResolvedConstraint;
import de.hauschel.arknet.uc.application.port.in.ResolveRequirements;
import de.hauschel.arknet.uc.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.uc.domain.ConstraintRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.RoleRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.pr.shared.TermRef;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;

/**
 * Renders {@link UseCase}s into the plain-text strings {@link UseCaseMcpTools} returns from its
 * tool calls. Split out of that class (issue #96) because the two carry independent reasons to
 * change: {@link UseCaseMcpTools} changes when a tool's parameter contract changes, this class
 * changes when the rendered text does - the two were previously mixed into a single class body.
 *
 * <p><strong>Role/term display resolution.</strong> {@link RoleRef} and {@link TermRef} carry an
 * opaque subject identity, not a business label - but a human who typed {@code ROLE-4}/{@code
 * TERM-1} into {@code uc_add} expects to see those again, not a raw IRI they cannot re-type.
 * {@link UseCaseMcpTools} is the gate into the use-cases hexagon, not part of its core, so this
 * presenter may borrow driving ports of <em>different</em> hexagons ({@link ResolveRoles}, owned by
 * the actor register's role resource type since ADR-37/kogn-io/arknet#405 Part C - replacing this
 * same class's former {@code ResolveActors} use, from before {@code arkreq:primaryRole}/{@code
 * supportingRole} repointed those edges at {@code arkproc:Role} instead of {@code arkproc:Actor} -
 * and {@link ResolveTerms}, owned by the domain-modelling context) to answer that purely for
 * display; the use-cases core itself still never depends on {@code arknet-actor-core}/{@code
 * arknet-ubiquitous-language-core}, and {@code uc_add}'s own write path still resolves via the
 * decoupled {@code RoleLookup}/{@code TermLookup} out-ports.</p>
 *
 * <p><strong>Requirement/constraint display resolution.</strong> {@link RequirementRef} and
 * {@link ConstraintRef} point at resources of the sibling component of this very bounded context,
 * and they are resolved through this component's <em>own</em> driving ports
 * {@link ResolveRequirements}/{@link ResolveConstraints} - not through the requirements
 * component's equivalents, which this presenter used to borrow. Since ADR-49 a neighbour is read
 * through its published language in one direction as well as the other: the in-port delegates to
 * the {@code RequirementLookup}/{@code ConstraintLookup} out-ports, whose store adapter reads the
 * neighbouring graph, so no module of the use-cases component depends on a module of the
 * requirements component.</p>
 *
 * <p>{@link #formatFull} calls each of the four resolving ports exactly once per rendering,
 * batched across every reference involved; an id a port could not resolve simply falls back to the
 * bare IRI - rendering never throws and never drops a reference.</p>
 */
final class UseCasePresenter {

    private final ResolveRoles resolveRoles;
    private final ResolveTerms resolveTerms;
    private final ResolveRequirements resolveRequirements;
    private final ResolveConstraints resolveConstraints;

    /**
     * @param resolveRoles         the actor register's driving port used to render a referenced
     *                             role's business code instead of its bare IRI (ADR-37/
     *                             kogn-io/arknet#405 Part C)
     * @param resolveTerms         ubiquitous-language driving port used to render a linked
     *                             glossary term's business code instead of its bare IRI
     * @param resolveRequirements this component's own driving port used only to render a
     *                             referenced requirement's business code instead of its bare IRI
     * @param resolveConstraints  this component's own driving port used only to render a linked
     *                             constraint's business code instead of its bare IRI
     */
    UseCasePresenter(final ResolveRoles resolveRoles, final ResolveTerms resolveTerms,
            final ResolveRequirements resolveRequirements, final ResolveConstraints resolveConstraints) {
        this.resolveRoles = Objects.requireNonNull(resolveRoles, "resolveRoles");
        this.resolveTerms = Objects.requireNonNull(resolveTerms, "resolveTerms");
        this.resolveRequirements = Objects.requireNonNull(resolveRequirements, "resolveRequirements");
        this.resolveConstraints = Objects.requireNonNull(resolveConstraints, "resolveConstraints");
    }

    static String formatShort(final UseCase uc) {
        return "%s | %s | %s".formatted(uc.code().value(), uc.title(), uc.goal());
    }

    /**
     * {@link #formatShort(UseCase)}, optionally followed by the numbered main-flow steps and the
     * extensions - both in the same compact form {@link #formatFull} uses, but without its
     * {@code realises}/role/term edge resolution (issue #600 part 3: an auditor who only wants the
     * flow text no longer needs a separate {@code uc_get} per use case).
     */
    static String formatShort(final UseCase uc, final boolean withSteps) {
        final StringBuilder sb = new StringBuilder(formatShort(uc));
        if (!withSteps) {
            return sb.toString();
        }
        for (final Step step : uc.steps()) {
            sb.append('\n').append("  ").append(step.position()).append(". ").append(step.text());
        }
        if (!uc.extensions().isEmpty()) {
            sb.append('\n').append("  extensions:");
            for (final String extension : uc.extensions()) {
                sb.append('\n').append("    - ").append(extension);
            }
        }
        return sb.toString();
    }

    /**
     * @param displayLocale the BCP-47 language tag the caller's own read call is showing this use
     *                      case's other fields under, passed straight through to
     *                      {@link ResolveRoles#resolveExisting} so a rendered role's overridden
     *                      display language agrees with the rest of the call ({@code uc_get}'s own
     *                      {@code displayLocale} argument), or {@code null} when the caller has no
     *                      such context of its own ({@code uc_add}/{@code uc_update}/
     *                      {@code uc_link_term}/{@code uc_link_constraint}, none of which resolve a
     *                      read-side display language for anything else either)
     */
    String formatFull(final ProjectId projectId, final UseCase uc, final String displayLocale) {
        final Map<ResourceId, ResolvedRole> rolesById = resolveRolesFor(projectId, uc, displayLocale);
        final Map<ResourceId, ResolvedRequirement> requirementsById = resolveRequirementsFor(projectId, uc);
        final Map<ResourceId, ResolvedTerm> usesTermsById = resolveUsesTermsFor(projectId, uc);
        final Map<ResourceId, ResolvedConstraint> constraintsById = resolveConstraintsFor(projectId, uc);

        final StringBuilder sb = new StringBuilder();
        sb.append(uc.code().value()).append(' ').append(uc.title()).append('\n');
        sb.append("  goal: ").append(uc.goal()).append('\n');
        appendOptional(sb, "scope", uc.scope());
        appendOptional(sb, "trigger", uc.trigger());
        sb.append("  primaryRole: ").append(renderRole(uc.primaryRole(), rolesById)).append('\n');
        if (!uc.supportingRoles().isEmpty()) {
            sb.append("  supportingRoles: ")
                    .append(uc.supportingRoles().stream().map(ref -> renderRole(ref, rolesById))
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

    /** Renders one role reference: its resolved business code, or its bare IRI as a fallback. */
    private static String renderRole(final RoleRef ref, final Map<ResourceId, ResolvedRole> rolesById) {
        final ResolvedRole role = rolesById.get(ref.value());
        return role != null ? role.code().value() : ref.value().value();
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
     * Batch-resolves every role referenced by {@code uc} (its primary role plus its supporting
     * roles) in exactly one call to {@link ResolveRoles#resolveExisting} (ADR-37/
     * kogn-io/arknet#405 Part C - the role resource type replaced the actor register as the
     * resolution source here, mirroring issue #336's own replacement of the resolution source
     * one level up).
     *
     * <p><strong>Structurally cannot throw on a duplicate key (mirrors
     * {@code RequirementPresenter#resolveTermsFor}).</strong> {@link ResolveRoles} promises at
     * most one {@link ResolvedRole} per identity, but this method must not rely on every
     * implementation upholding that: a plain {@code Collectors.toMap(r -> r.id(), r -> r)} throws
     * {@code IllegalStateException} the moment two returned {@link ResolvedRole}s share an
     * identity, turning a display concern into a thrown exception - the very thing this rendering
     * path exists to avoid. The merge function below keeps the first entry for a duplicate key
     * instead; which one is kept is immaterial here, since rendering only ever reads
     * {@link ResolvedRole#code()}.</p>
     */
    private Map<ResourceId, ResolvedRole> resolveRolesFor(
            final ProjectId projectId, final UseCase uc, final String displayLocale) {
        final ResourceId[] ids = Stream.concat(
                        Stream.of(uc.primaryRole()), uc.supportingRoles().stream())
                .map(RoleRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveRoles.resolveExisting(projectId, displayLocale, ids).stream()
                .collect(Collectors.toMap(ResolvedRole::id, r -> r, (first, second) -> first));
    }

    /**
     * Batch-resolves every requirement referenced by {@code uc}'s steps (the union of all
     * {@code realises} references) in exactly one call to
     * {@link ResolveRequirements#resolveRequirements} - same merge-function reasoning as
     * {@link #resolveRolesFor}.
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
        return resolveRequirements.resolveRequirements(projectId, ids).stream()
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
     * {@link #resolveRolesFor}'s own batch call against a different port and, since issue #336,
     * a structurally different identity space ({@code arknet-actor}'s register vs. the glossary).
     * Same duplicate-key-tolerant merge reasoning as {@link #resolveRolesFor}.
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
     * in exactly one call to {@link ResolveConstraints#resolveConstraints}. Same duplicate-key-
     * tolerant merge reasoning as {@link #resolveRolesFor}, mirroring
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
        return resolveConstraints.resolveConstraints(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedConstraint::id, c -> c, (first, second) -> first));
    }

    /**
     * The business codes {@code uc}'s {@code usesTerms} resolve to, in the order the use case
     * carries them - the "before"/"after" side of {@code uc_update}'s {@code usesTerm} diff line
     * (kogn-io/arknet#598). An id {@link ResolveTerms} cannot resolve contributes its bare IRI,
     * exactly as {@link #renderTerm} does elsewhere - a term missing from the glossary must still
     * show up on both sides of the diff, or removing it would read as if nothing had changed.
     */
    List<String> usesTermCodesOf(final ProjectId projectId, final UseCase uc) {
        final Map<ResourceId, ResolvedTerm> byId = resolveUsesTermsFor(projectId, uc);
        return uc.usesTerms().stream().map(ref -> renderTerm(ref, byId)).toList();
    }

    /**
     * The business codes {@code uc}'s {@code supportingRoles} resolve to, in the order the use
     * case carries them - the "before"/"after" side of {@code uc_update}'s {@code supportingRole}
     * diff line (kogn-io/arknet#598). Resolves through the same batch call
     * {@link #formatFull}/{@link #resolveRolesFor} use for {@code primaryRole} together with
     * {@code supportingRoles}; the primary role's own resolution is simply unused here.
     */
    List<String> supportingRoleCodesOf(final ProjectId projectId, final UseCase uc) {
        final Map<ResourceId, ResolvedRole> byId = resolveRolesFor(projectId, uc, null);
        return uc.supportingRoles().stream().map(ref -> renderRole(ref, byId)).toList();
    }

    private static void appendOptional(final StringBuilder sb, final String field, final String value) {
        if (value != null) {
            sb.append("  ").append(field).append(": ").append(value).append('\n');
        }
    }
}
