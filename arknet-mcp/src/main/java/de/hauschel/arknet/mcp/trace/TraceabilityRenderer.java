// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.mention.LabelMentions;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.ResourceRenderer;
import de.hauschel.arknet.mcp.store.StoreResource;

/**
 * Renders the compact, token-cheap text digests {@code trace_matrix}/{@code orphan_check}/
 * {@code impact_analysis}/{@code actor_usecase_matrix}/{@code term_cooccurrence} return, given a
 * {@link TraceabilityGraph} already built over one project's statements.
 *
 * <p>Pure: it consumes only a {@link TraceabilityGraph} plus a {@link Prefixes} resolver, so it
 * is unit-testable without any store I/O - the same split {@link
 * de.hauschel.arknet.mcp.store.DigestRenderer}/{@link de.hauschel.arknet.mcp.store.StoreReader}
 * already establish for {@code store_overview}. The handle printed for a resource prefers its
 * {@code dcterms:identifier} (business code, e.g. {@code FR-1}) - the whole point of these
 * tools is reporting business codes, not opaque IRIs - falling back to a CURIE, then the full
 * IRI, for the rare resource that carries neither (store-first data, ADR-005).</p>
 */
public final class TraceabilityRenderer {

    private final Prefixes prefixes;

    /**
     * @param prefixes the CURIE resolver used for the identifier-less fallback
     */
    public TraceabilityRenderer(Prefixes prefixes) {
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
    }

    /**
     * Renders {@code trace_matrix}: one line per requirement (FR and NFR alike) listing the
     * glossary terms it uses and the use case(s) realising it.
     *
     * @param projectId the project the graph was read from
     * @param graph       the traceability graph to report on
     * @return the digest text
     */
    public String traceMatrix(ProjectId projectId, TraceabilityGraph graph) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(graph, "graph");
        List<String> requirementIris = graph.requirementIris();

        StringBuilder out = new StringBuilder();
        out.append("# Traceability matrix -- project ").append(projectId.value())
                .append(" -- ").append(requirementIris.size()).append(" requirement(s)\n\n");
        if (requirementIris.isEmpty()) {
            out.append("- no requirements in this project\n");
            return out.toString();
        }
        for (String requirementIri : requirementIris) {
            out.append(displayLine(graph, requirementIri)).append('\n');
            out.append("    uses terms  : ").append(codesOrNone(graph, graph.usedTerms(requirementIri))).append('\n');
            out.append("    realised by : ")
                    .append(codesOrNone(graph, graph.realisingUseCases(requirementIri))).append('\n');
        }
        return out.toString();
    }

    /**
     * Renders {@code orphan_check}: requirements no use case realises, glossary terms never
     * used (neither via {@code arkreq:usesTerm}/actor role nor as a bounded context's ubiquitous
     * language), and terms a requirement's or bounded context's prose names without the edge to
     * back it up.
     *
     * @param projectId the project the graph was read from
     * @param graph       the traceability graph to report on
     * @return the digest text
     */
    public String orphanCheck(ProjectId projectId, TraceabilityGraph graph) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(graph, "graph");

        List<String> orphanRequirements = graph.requirementIris().stream()
                .filter(iri -> graph.realisingUseCases(iri).isEmpty())
                .toList();
        List<String> orphanTerms = graph.termIris().stream()
                .filter(iri -> !graph.isReferencedTerm(iri))
                .toList();
        List<TraceabilityGraph.UnlinkedMention> unlinkedMentions = graph.unlinkedMentions();

        StringBuilder out = new StringBuilder();
        out.append("# Orphan check -- project ").append(projectId.value()).append('\n');
        out.append("\n## Requirements without a realising use case (")
                .append(orphanRequirements.size()).append(")\n");
        appendLines(out, graph, orphanRequirements);
        out.append("\n## Terms never referenced (").append(orphanTerms.size()).append(")\n");
        appendLines(out, graph, orphanTerms);
        out.append("\n## Mentioned in text but not linked (").append(unlinkedMentions.size()).append(")\n");
        appendUnlinkedMentions(out, graph, unlinkedMentions);
        return out.toString();
    }

    /**
     * Renders {@code impact_analysis}: every resource transitively affected if {@code
     * targetIri} changes (see {@link TraceabilityGraph#dependents(String)}).
     *
     * <p>{@link HandleResolver} only expands a CURIE/bare-id syntactically or via a store lookup
     * that can itself go stale between resolution and read - it never guarantees {@code
     * targetIri} actually carries a statement. Reporting "Transitively affected (0)" for such a
     * handle would read as "nothing depends on this" when the truth is "no such resource exists"
     * (issue #135) - the same distinction {@code resource_get} already draws via {@link
     * ResourceRenderer#notFoundMessage}, reused here so both tools describe an unknown handle
     * identically.</p>
     *
     * @param projectId the project the graph was read from
     * @param graph       the traceability graph to report on
     * @param targetIri   the already-resolved target resource IRI
     * @return the digest text
     */
    public String impactAnalysis(ProjectId projectId, TraceabilityGraph graph, String targetIri) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(targetIri, "targetIri");

        if (!graph.knows(targetIri)) {
            return ResourceRenderer.notFoundMessage(prefixes, targetIri);
        }

        List<String> affected = graph.dependents(targetIri);
        StringBuilder out = new StringBuilder();
        out.append("# Impact analysis -- project ").append(projectId.value())
                .append(" -- target: ").append(displayLine(graph, targetIri)).append('\n');
        out.append("\n## Transitively affected (").append(affected.size()).append(")\n");
        appendLines(out, graph, affected);
        return out.toString();
    }

    /**
     * Renders {@code actor_usecase_matrix}: the raw bipartite view of which actor plays a role
     * (primary or supporting) in which use case, in both directions - no clustering, no verdict
     * about bounded-context boundaries, just the {@code arkreq:primaryActor}/{@code
     * supportingActor} edges as data for a human or agent to draw that boundary themselves
     * (issue #108). The "Actors" section lists <em>every</em> actor in the project ({@link
     * TraceabilityGraph#actorIris()}), not only the ones a use case happens to reference - an
     * actor no use case references yet is exactly the strongest signal for "a use case is
     * missing here" or "this actor belongs in a different bounded context", so it must not go
     * missing from an inventory whose own description promises "for every actor" (issue #147).
     *
     * @param projectId the project the graph was read from
     * @param graph       the traceability graph to report on
     * @return the digest text
     */
    public String actorUseCaseMatrix(ProjectId projectId, TraceabilityGraph graph) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(graph, "graph");
        List<String> useCaseIris = graph.useCaseIris();
        Set<String> actorIris = new TreeSet<>(graph.actorIris());
        for (String useCaseIri : useCaseIris) {
            actorIris.addAll(graph.actorsOf(useCaseIri));
        }

        StringBuilder out = new StringBuilder();
        out.append("# Actor/use-case matrix -- project ").append(projectId.value()).append('\n');
        out.append("\n## Actors (").append(actorIris.size()).append(")\n");
        if (actorIris.isEmpty()) {
            out.append("- none\n");
        }
        for (String actorIri : actorIris) {
            out.append("- ").append(displayLine(graph, actorIri)).append('\n');
            out.append("    use cases : ").append(codesOrNone(graph, graph.useCasesOf(actorIri))).append('\n');
        }
        out.append("\n## Use cases (").append(useCaseIris.size()).append(")\n");
        if (useCaseIris.isEmpty()) {
            out.append("- none\n");
        }
        for (String useCaseIri : useCaseIris) {
            out.append("- ").append(displayLine(graph, useCaseIri)).append('\n');
            out.append("    actors    : ").append(codesOrNone(graph, graph.actorsOf(useCaseIri))).append('\n');
        }
        return out.toString();
    }

    /**
     * Renders {@code term_cooccurrence}: which glossary terms are named together in the same
     * requirement or use-case text - literal text co-occurrence, no comparison against the model's
     * edges. Raw data for the question "is this one term or two homonyms with a different meaning
     * per context?", deliberately stopping short of any clustering verdict (issue #108).
     *
     * @param projectId the project the graph was read from
     * @param graph       the traceability graph to report on
     * @return the digest text
     */
    public String termCooccurrence(ProjectId projectId, TraceabilityGraph graph) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(graph, "graph");
        List<Cooccurrence> pairs = termCooccurrences(graph);

        StringBuilder out = new StringBuilder();
        out.append("# Term co-occurrence -- project ").append(projectId.value()).append('\n');
        out.append("\n## Term pairs named together in the same text (").append(pairs.size()).append(")\n");
        if (pairs.isEmpty()) {
            out.append("- none\n");
            return out.toString();
        }
        for (Cooccurrence pair : pairs) {
            out.append("- ").append(displayLine(graph, pair.firstTermIri()))
                    .append(" + ").append(displayLine(graph, pair.secondTermIri()))
                    .append(" -- ").append(pair.sourceIris().size()).append(" text(s): ")
                    .append(pair.sourceIris().stream()
                            .map(iri -> handle(graph, iri))
                            .sorted()
                            .collect(Collectors.joining(", ")))
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * Scans every requirement's prose ({@link TraceabilityGraph#requirementProseTexts(String)})
     * and every use case's goal ({@link TraceabilityGraph#useCaseProseTexts(String)}) for term
     * mentions via the same {@link LabelMentions} engine {@code orphan_check} uses, and pairs up
     * every two terms found anywhere across one resource's prose fields - the resource is the
     * unit of "co-occurrence" (issue #108), not the individual field: a requirement's description
     * naming one term and its acceptance criterion naming another still counts as that
     * requirement mentioning both.
     */
    private List<Cooccurrence> termCooccurrences(TraceabilityGraph graph) {
        Map<String, String> termLabels = graph.termLabels();
        if (termLabels.isEmpty()) {
            return List.of();
        }
        LabelMentions<String> matcher = LabelMentions.of(
                termLabels.keySet().stream().sorted().toList(), termLabels::get);

        Map<TermPair, Set<String>> sourcesByPair = new LinkedHashMap<>();
        for (String requirementIri : graph.requirementIris()) {
            recordCooccurrences(matcher, graph.requirementProseTexts(requirementIri), requirementIri, sourcesByPair);
        }
        for (String useCaseIri : graph.useCaseIris()) {
            recordCooccurrences(matcher, graph.useCaseProseTexts(useCaseIri), useCaseIri, sourcesByPair);
        }

        return sourcesByPair.entrySet().stream()
                .map(entry -> new Cooccurrence(
                        entry.getKey().firstTermIri(), entry.getKey().secondTermIri(), Set.copyOf(entry.getValue())))
                .sorted(Comparator.comparing(Cooccurrence::firstTermIri).thenComparing(Cooccurrence::secondTermIri))
                .toList();
    }

    private void recordCooccurrences(
            LabelMentions<String> matcher, List<String> texts, String sourceIri, Map<TermPair, Set<String>> sourcesByPair) {
        List<String> mentioned = matcher.mentionedIn(texts).stream().sorted().toList();
        for (int i = 0; i < mentioned.size(); i++) {
            for (int j = i + 1; j < mentioned.size(); j++) {
                TermPair pair = new TermPair(mentioned.get(i), mentioned.get(j));
                sourcesByPair.computeIfAbsent(pair, key -> new LinkedHashSet<>()).add(sourceIri);
            }
        }
    }

    private void appendLines(StringBuilder out, TraceabilityGraph graph, List<String> iris) {
        if (iris.isEmpty()) {
            out.append("- none\n");
            return;
        }
        for (String iri : iris) {
            out.append("- ").append(displayLine(graph, iri)).append('\n');
        }
    }

    private void appendUnlinkedMentions(
            StringBuilder out, TraceabilityGraph graph, List<TraceabilityGraph.UnlinkedMention> mentions) {
        if (mentions.isEmpty()) {
            out.append("- none\n");
            return;
        }
        for (TraceabilityGraph.UnlinkedMention mention : mentions) {
            out.append("- ").append(handle(graph, mention.sourceIri()))
                    .append(" mentions \"").append(mention.termLabel()).append('"')
                    .append(" (").append(handle(graph, mention.termIri())).append(')')
                    .append(" -- no ").append(mention.edgeLocalName()).append(" edge\n");
        }
    }

    private String codesOrNone(TraceabilityGraph graph, List<String> iris) {
        if (iris.isEmpty()) {
            return "(none)";
        }
        return iris.stream().map(iri -> handle(graph, iri)).collect(Collectors.joining(", "));
    }

    private String displayLine(TraceabilityGraph graph, String iri) {
        StringBuilder line = new StringBuilder(handle(graph, iri));
        String type = primaryDisplayType(graph, iri);
        if (!type.isEmpty()) {
            line.append(" [").append(type).append(']');
        }
        graph.labelOf(iri).ifPresent(label -> line.append(" \"").append(label).append('"'));
        return line.toString();
    }

    private String primaryDisplayType(TraceabilityGraph graph, String iri) {
        // Local name, not the full CURIE - matches DigestRenderer's per-resource type display
        // (StoreResource#types()/DigestRenderer.renderResourceLine); the CURIE prefix legend
        // already establishes the namespace once, repeating it per line would be noise.
        return graph.typesOf(iri).stream().sorted().findFirst().map(StoreResource::localName).orElse("");
    }

    private String handle(TraceabilityGraph graph, String iri) {
        return graph.identifierOf(iri).orElseGet(() -> prefixes.toCurie(iri));
    }

    /** An unordered pair of term IRIs, canonicalised {@code firstTermIri < secondTermIri} by the caller. */
    private record TermPair(String firstTermIri, String secondTermIri) {
    }

    /**
     * @param firstTermIri  the lexicographically smaller of the two co-occurring term IRIs
     * @param secondTermIri the lexicographically larger of the two co-occurring term IRIs
     * @param sourceIris    every requirement/use-case IRI whose text names both terms
     */
    private record Cooccurrence(String firstTermIri, String secondTermIri, Set<String> sourceIris) {
    }
}
