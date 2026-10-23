// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.persistence.StoreResource;

/**
 * Finds every orphaned artifact of a project (kogn-io/arknet#473, folding {@code orphan_check}
 * into {@code store_check ORPHAN}): a requirement no use case satisfies or realises, a glossary term never
 * referenced, a requirement's/use case's/bounded context's/architecture decision's prose naming a
 * term without the matching edge, and a constraint no requirement or use case is bound by.
 *
 * <p>Unlike {@link LanguageGapCheck}/{@link RoleTermDuplicateCheck}/{@link StepAcceptanceCheck},
 * this check runs over an already-built {@link TraceabilityGraph} rather than a raw {@link
 * de.hauschel.arknet.persistence.StoreSnapshot}: the traversal it needs (which term is referenced
 * by which edge, which prose mentions a term without the backing edge) is exactly what {@link
 * TraceabilityGraph} already provides for {@code trace_matrix}/{@code impact_analysis} - so this
 * class calls those methods rather than re-deriving the same traversal against raw triples.</p>
 *
 * <p>A term mention without its edge is a finding only when it sits in normative text; one that
 * lies only in an acceptance criterion, or whose every normative hit is next to a reference to a
 * resource already carrying the edge, is listed separately as a {@link WeakMention} hint and not
 * counted (kogn-io/arknet#697, see {@link TraceabilityGraph#classifiedMentions()}).</p>
 */
public final class OrphanCheck {

    private OrphanCheck() {
    }

    /** One orphaned resource: its IRI, business handle (if any), primary type and label. */
    public record ResourceFinding(String iri, String handle, String typeLocalName, String label) {
    }

    /**
     * One prose mention of a term with no edge backing it up - mirrors {@link
     * TraceabilityGraph.UnlinkedMention}. {@code fields} names where the term was found (local
     * names of the predicates, such as {@code description} or {@code stepText}), so a reader need
     * not open the resource (kogn-io/arknet#697).
     */
    public record MentionFinding(
            String sourceIri, String sourceHandle, String termIri, String termHandle, String termLabel,
            String edgeLocalName, List<String> fields) {
    }

    /**
     * A mention downgraded to a hint (kogn-io/arknet#697): same shape as {@link MentionFinding}
     * plus why it is not a finding. {@code coveredBy} holds, for {@link
     * TraceabilityGraph.WeakReason#COVERED_BY_REFERENCE}, the business codes of the referenced
     * resources that already carry the edge (a reference is found through its code, so one always
     * exists; the IRI fallback in {@link #run} is defensive only).
     */
    public record WeakMention(
            String sourceIri, String sourceHandle, String termIri, String termHandle, String termLabel,
            String edgeLocalName, List<String> fields, TraceabilityGraph.WeakReason reason,
            List<String> coveredBy) {
    }

    /**
     * The findings lists {@code store_check ORPHAN}/{@code orphan_check} report: four that count,
     * plus {@code weakMentions}, the hints that do not (kogn-io/arknet#697).
     */
    public record Result(
            List<ResourceFinding> orphanRequirements, List<ResourceFinding> orphanTerms,
            List<MentionFinding> unlinkedMentions, List<ResourceFinding> orphanConstraints,
            List<WeakMention> weakMentions) {

        /** @return the total number of findings across the four finding lists; hints are not counted. */
        public int total() {
            return orphanRequirements.size() + orphanTerms.size() + unlinkedMentions.size()
                    + orphanConstraints.size();
        }
    }

    /**
     * @param graph the traceability graph to report on
     * @return the findings lists, in the order the tool has always reported them, then the hints
     */
    public static Result run(final TraceabilityGraph graph) {
        Objects.requireNonNull(graph, "graph");
        final List<ResourceFinding> orphanRequirements = graph.requirementIris().stream()
                .filter(iri -> !graph.isCarriedByUseCase(iri))
                .map(iri -> resourceFinding(graph, iri))
                .toList();
        final List<ResourceFinding> orphanTerms = graph.termIris().stream()
                .filter(iri -> !graph.isReferencedTerm(iri))
                .map(iri -> resourceFinding(graph, iri))
                .toList();
        final List<MentionFinding> unlinkedMentions = new ArrayList<>();
        final List<WeakMention> weakMentions = new ArrayList<>();
        for (final TraceabilityGraph.UnlinkedMention mention : graph.classifiedMentions()) {
            final String sourceHandle = graph.identifierOf(mention.sourceIri()).orElse(null);
            final String termHandle = graph.identifierOf(mention.termIri()).orElse(null);
            if (mention.weakReason() == null) {
                unlinkedMentions.add(new MentionFinding(mention.sourceIri(), sourceHandle, mention.termIri(),
                        termHandle, mention.termLabel(), mention.edgeLocalName(), mention.fields()));
            } else {
                weakMentions.add(new WeakMention(mention.sourceIri(), sourceHandle, mention.termIri(),
                        termHandle, mention.termLabel(), mention.edgeLocalName(), mention.fields(),
                        mention.weakReason(),
                        mention.coveredBy().stream().map(iri -> graph.identifierOf(iri).orElse(iri)).toList()));
            }
        }
        final List<ResourceFinding> orphanConstraints = graph.constraintIris().stream()
                .filter(iri -> !graph.isConstraintReferenced(iri))
                .map(iri -> resourceFinding(graph, iri))
                .toList();
        return new Result(orphanRequirements, orphanTerms, List.copyOf(unlinkedMentions), orphanConstraints,
                List.copyOf(weakMentions));
    }

    private static ResourceFinding resourceFinding(final TraceabilityGraph graph, final String iri) {
        final String typeLocalName = graph.typesOf(iri).stream().sorted()
                .findFirst().map(StoreResource::localName).orElse(null);
        return new ResourceFinding(iri, graph.identifierOf(iri).orElse(null), typeLocalName,
                graph.labelOf(iri).orElse(null));
    }
}
