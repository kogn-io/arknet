// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

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
 */
public final class OrphanCheck {

    private OrphanCheck() {
    }

    /** One orphaned resource: its IRI, business handle (if any), primary type and label. */
    public record ResourceFinding(String iri, String handle, String typeLocalName, String label) {
    }

    /**
     * One prose mention of a term with no edge backing it up - mirrors {@link
     * TraceabilityGraph.UnlinkedMention}. {@code fields} names where the term was found
     * (kogn-io/arknet#697), e.g. {@code description}, {@code acceptanceCriterion#2}.
     */
    public record MentionFinding(
            String sourceIri, String sourceHandle, String termIri, String termHandle, String termLabel,
            String edgeLocalName, List<String> fields) {
    }

    /**
     * A mention that is listed as a hint but is not a finding and not part of {@link Result#total()}
     * (kogn-io/arknet#697): the term appears only in non-normative text, or only next to a reference
     * to a resource that already carries the edge.
     *
     * @param reason          why it was downgraded, never {@link TraceabilityGraph.MentionClassification#FINDING}
     * @param referencedCodes for {@code NEXT_TO_REFERENCE}, the business codes of the referenced resources
     */
    public record DowngradedMention(
            String sourceIri, String sourceHandle, String termIri, String termHandle, String termLabel,
            String edgeLocalName, List<String> fields, TraceabilityGraph.MentionClassification reason,
            List<String> referencedCodes) {
    }

    /**
     * The findings lists {@code orphan_check}/{@code store_check ORPHAN} report, plus the weaker
     * {@code downgradedMentions} hints, which are not findings and not counted by {@link #total()}.
     */
    public record Result(
            List<ResourceFinding> orphanRequirements, List<ResourceFinding> orphanTerms,
            List<MentionFinding> unlinkedMentions, List<ResourceFinding> orphanConstraints,
            List<DowngradedMention> downgradedMentions) {

        /** A result without downgraded hints. */
        public Result(List<ResourceFinding> orphanRequirements, List<ResourceFinding> orphanTerms,
                List<MentionFinding> unlinkedMentions, List<ResourceFinding> orphanConstraints) {
            this(orphanRequirements, orphanTerms, unlinkedMentions, orphanConstraints, List.of());
        }

        /** @return the total number of findings across the four finding lists (hints excluded). */
        public int total() {
            return orphanRequirements.size() + orphanTerms.size() + unlinkedMentions.size()
                    + orphanConstraints.size();
        }
    }

    /**
     * @param graph the traceability graph to report on
     * @return the four findings lists plus the downgraded hints, in the order the tool has always reported them
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
        final List<TraceabilityGraph.UnlinkedMention> mentions = graph.unlinkedMentions();
        final List<MentionFinding> unlinkedMentions = mentions.stream()
                .filter(mention -> mention.classification() == TraceabilityGraph.MentionClassification.FINDING)
                .map(mention -> new MentionFinding(
                        mention.sourceIri(), graph.identifierOf(mention.sourceIri()).orElse(null),
                        mention.termIri(), graph.identifierOf(mention.termIri()).orElse(null),
                        mention.termLabel(), mention.edgeLocalName(), mention.fields()))
                .toList();
        final List<DowngradedMention> downgradedMentions = mentions.stream()
                .filter(mention -> mention.classification() != TraceabilityGraph.MentionClassification.FINDING)
                .map(mention -> new DowngradedMention(
                        mention.sourceIri(), graph.identifierOf(mention.sourceIri()).orElse(null),
                        mention.termIri(), graph.identifierOf(mention.termIri()).orElse(null),
                        mention.termLabel(), mention.edgeLocalName(), mention.fields(),
                        mention.classification(), mention.referencedCodes()))
                .toList();
        final List<ResourceFinding> orphanConstraints = graph.constraintIris().stream()
                .filter(iri -> !graph.isConstraintReferenced(iri))
                .map(iri -> resourceFinding(graph, iri))
                .toList();
        return new Result(orphanRequirements, orphanTerms, unlinkedMentions, orphanConstraints, downgradedMentions);
    }

    private static ResourceFinding resourceFinding(final TraceabilityGraph graph, final String iri) {
        final String typeLocalName = graph.typesOf(iri).stream().sorted()
                .findFirst().map(StoreResource::localName).orElse(null);
        return new ResourceFinding(iri, graph.identifierOf(iri).orElse(null), typeLocalName,
                graph.labelOf(iri).orElse(null));
    }
}
