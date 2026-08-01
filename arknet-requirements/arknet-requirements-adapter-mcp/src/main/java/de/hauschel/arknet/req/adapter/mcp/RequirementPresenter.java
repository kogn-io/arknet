// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementSchemaTerm;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;

/**
 * Renders {@link Requirement}s (and the requirement schema) into the plain-text strings
 * {@link RequirementMcpTools} returns from its tool calls. Split out of that class because the
 * two carry independent reasons to change: {@link RequirementMcpTools} changes when a tool's
 * parameter contract changes, this class changes when the rendered text does - the two were
 * previously mixed into a single class body.
 *
 * <p><strong>Term display resolution.</strong> {@link TermRef} carries a
 * linked term's opaque subject identity, not its business code - but a human who typed
 * {@code TERM-1} into {@code req_link_term} expects to see {@code TERM-1} again, not a raw IRI
 * they cannot re-type. {@link RequirementMcpTools} is the gate into the requirements hexagon,
 * not part of its core, so this presenter may borrow a driving port of a <em>different</em>
 * hexagon ({@link ResolveTerms}, owned by ubiquitous-language) to answer that purely for
 * display - the requirements core itself still never depends on
 * {@code arknet-ubiquitous-language-core}, and {@code req_link_term}'s own write path still
 * resolves via the decoupled {@code TermLookup} out-port. {@link #format(ProjectId, Requirement)}
 * always calls {@link ResolveTerms#resolve} exactly once per rendering, batched across every
 * {@link TermRef} involved (never once per {@link TermRef}, and for {@code req_list} never once
 * per requirement); an id {@link ResolveTerms} could not resolve simply falls back to the bare
 * IRI - {@code format} never throws and never drops a term.</p>
 */
final class RequirementPresenter {

    private final ResolveTerms resolveTerms;

    /**
     * @param resolveTerms ubiquitous-language driving port used only to render a linked
     *                     term's business code instead of its bare IRI
     */
    RequirementPresenter(final ResolveTerms resolveTerms) {
        this.resolveTerms = Objects.requireNonNull(resolveTerms, "resolveTerms");
    }

    /** Renders a single requirement, resolving its own linked terms in one batch call. */
    String format(final ProjectId projectId, final Requirement r) {
        return format(r, resolveTermsFor(projectId, List.of(r)));
    }

    /**
     * Renders {@code r} using an already-resolved {@code termsById} lookup - never itself calls
     * {@link ResolveTerms}, so callers control the batching (one call for a single requirement,
     * one call total for {@code req_list}). Never throws: a {@link TermRef} missing from
     * {@code termsById} (unresolvable, or simply not looked up) falls back to its bare IRI.
     */
    String format(final Requirement r, final Map<ResourceId, ResolvedTerm> termsById) {
        final String priority = r.priority() == null ? "" : " {" + r.priority() + "}";
        final String terms = r.usesTerms().isEmpty()
                ? ""
                : " [terms: " + r.usesTerms().stream().map(ref -> renderTerm(ref, termsById))
                        .reduce((a, b) -> a + ", " + b).orElse("") + "]";
        final String criteria = " [done when: " + String.join("; ", r.acceptanceCriteria()) + "]";
        return "%s [%s] %s (%s)%s%s%s".formatted(
                r.code().value(), r.type(), r.title(), r.status(), priority, terms, criteria);
    }

    /** Renders one schema term as {@code term: definition (values: A, B, ...)}. */
    String formatSchemaTerm(final RequirementSchemaTerm t) {
        return "%s: %s (values: %s)".formatted(t.term(), t.definition(), String.join(", ", t.values()));
    }

    /** Renders one term reference: its resolved business code, or its bare IRI as a fallback. */
    private static String renderTerm(final TermRef ref, final Map<ResourceId, ResolvedTerm> termsById) {
        final ResolvedTerm term = termsById.get(ref.value());
        return term != null ? term.code().value() : ref.value().value();
    }

    /**
     * Batch-resolves every term referenced by {@code requirements} in exactly one call to
     * {@link ResolveTerms#resolve} - the union of all their {@link TermRef}s, deduplicated, not
     * one call per requirement and not one call per {@link TermRef}. Missing ids are simply
     * absent from the returned map, which {@link #renderTerm} treats as "fall back to the IRI".
     *
     * <p><strong>Structurally cannot throw on a duplicate key.</strong>
     * {@link ResolveTerms} promises at most one {@link ResolvedTerm} per identity, but this method
     * must not rely on every implementation upholding that: a plain {@code Collectors.toMap(t ->
     * t.id(), t -> t)} throws {@code IllegalStateException} the moment two returned
     * {@link ResolvedTerm}s share an identity, turning a display concern into a thrown exception -
     * the very thing this rendering path exists to avoid. The merge function below keeps the
     * first entry for a duplicate key instead; which one is kept is immaterial here, since
     * rendering only ever reads {@link ResolvedTerm#code()} and any legitimate duplicate (e.g. a
     * store-first term with more than one {@code dcterms:identifier}, see
     * {@code KognioRdfTermRepository#findByIds}) carries the same code on every row.</p>
     */
    Map<ResourceId, ResolvedTerm> resolveTermsFor(final ProjectId projectId, final List<Requirement> requirements) {
        final ResourceId[] ids = requirements.stream()
                .flatMap(r -> r.usesTerms().stream())
                .map(TermRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveTerms.resolve(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedTerm::id, t -> t, (first, second) -> first));
    }
}
