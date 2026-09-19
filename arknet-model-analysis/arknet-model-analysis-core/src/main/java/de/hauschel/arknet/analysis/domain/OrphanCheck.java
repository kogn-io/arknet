// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.persistence.StoreResource;

/**
 * Finds every orphaned artifact of a project (kogn-io/arknet#473, folding {@code orphan_check}
 * into {@code store_check ORPHAN}): a requirement no use case realises, a glossary term never
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

    /** One prose mention of a term with no edge backing it up - mirrors {@link TraceabilityGraph.UnlinkedMention}. */
    public record MentionFinding(
            String sourceIri, String sourceHandle, String termIri, String termHandle, String termLabel,
            String edgeLocalName) {
    }

    /** The four findings lists {@code orphan_check}/{@code store_check ORPHAN} report. */
    public record Result(
            List<ResourceFinding> orphanRequirements, List<ResourceFinding> orphanTerms,
            List<MentionFinding> unlinkedMentions, List<ResourceFinding> orphanConstraints) {

        /** @return the total number of findings across all four lists. */
        public int total() {
            return orphanRequirements.size() + orphanTerms.size() + unlinkedMentions.size()
                    + orphanConstraints.size();
        }
    }

    /**
     * @param graph the traceability graph to report on
     * @return the four findings lists, in the order the tool has always reported them
     */
    public static Result run(final TraceabilityGraph graph) {
        Objects.requireNonNull(graph, "graph");
        final List<ResourceFinding> orphanRequirements = graph.requirementIris().stream()
                .filter(iri -> graph.realisingUseCases(iri).isEmpty())
                .map(iri -> resourceFinding(graph, iri))
                .toList();
        final List<ResourceFinding> orphanTerms = graph.termIris().stream()
                .filter(iri -> !graph.isReferencedTerm(iri))
                .map(iri -> resourceFinding(graph, iri))
                .toList();
        final List<MentionFinding> unlinkedMentions = graph.unlinkedMentions().stream()
                .map(mention -> new MentionFinding(
                        mention.sourceIri(), graph.identifierOf(mention.sourceIri()).orElse(null),
                        mention.termIri(), graph.identifierOf(mention.termIri()).orElse(null),
                        mention.termLabel(), mention.edgeLocalName()))
                .toList();
        final List<ResourceFinding> orphanConstraints = graph.constraintIris().stream()
                .filter(iri -> !graph.isConstraintReferenced(iri))
                .map(iri -> resourceFinding(graph, iri))
                .toList();
        return new Result(orphanRequirements, orphanTerms, unlinkedMentions, orphanConstraints);
    }

    private static ResourceFinding resourceFinding(final TraceabilityGraph graph, final String iri) {
        final String typeLocalName = graph.typesOf(iri).stream().sorted()
                .findFirst().map(StoreResource::localName).orElse(null);
        return new ResourceFinding(iri, graph.identifierOf(iri).orElse(null), typeLocalName,
                graph.labelOf(iri).orElse(null));
    }
}
