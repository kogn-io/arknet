package de.hauschel.arknet.mcp.trace;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.StoreResource;

/**
 * Renders the compact, token-cheap text digests {@code trace_matrix}/{@code orphan_check}/
 * {@code impact_analysis} return, given a {@link TraceabilityGraph} already built over one
 * workspace's statements.
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
     * @param workspaceId the workspace the graph was read from
     * @param graph       the traceability graph to report on
     * @return the digest text
     */
    public String traceMatrix(WorkspaceId workspaceId, TraceabilityGraph graph) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(graph, "graph");
        List<String> requirementIris = graph.requirementIris();

        StringBuilder out = new StringBuilder();
        out.append("# Traceability matrix -- workspace ").append(workspaceId.value())
                .append(" -- ").append(requirementIris.size()).append(" requirement(s)\n\n");
        if (requirementIris.isEmpty()) {
            out.append("- no requirements in this workspace\n");
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
     * Renders {@code orphan_check}: requirements no use case realises, and glossary terms
     * never used (neither via {@code arkreq:usesTerm} nor as a use-case actor).
     *
     * @param workspaceId the workspace the graph was read from
     * @param graph       the traceability graph to report on
     * @return the digest text
     */
    public String orphanCheck(WorkspaceId workspaceId, TraceabilityGraph graph) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(graph, "graph");

        List<String> orphanRequirements = graph.requirementIris().stream()
                .filter(iri -> graph.realisingUseCases(iri).isEmpty())
                .toList();
        List<String> orphanTerms = graph.termIris().stream()
                .filter(iri -> !graph.isReferencedTerm(iri))
                .toList();

        StringBuilder out = new StringBuilder();
        out.append("# Orphan check -- workspace ").append(workspaceId.value()).append('\n');
        out.append("\n## Requirements without a realising use case (")
                .append(orphanRequirements.size()).append(")\n");
        appendLines(out, graph, orphanRequirements);
        out.append("\n## Terms never referenced (").append(orphanTerms.size()).append(")\n");
        appendLines(out, graph, orphanTerms);
        return out.toString();
    }

    /**
     * Renders {@code impact_analysis}: every resource transitively affected if {@code
     * targetIri} changes (see {@link TraceabilityGraph#dependents(String)}).
     *
     * @param workspaceId the workspace the graph was read from
     * @param graph       the traceability graph to report on
     * @param targetIri   the already-resolved target resource IRI
     * @return the digest text
     */
    public String impactAnalysis(WorkspaceId workspaceId, TraceabilityGraph graph, String targetIri) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(targetIri, "targetIri");

        List<String> affected = graph.dependents(targetIri);
        StringBuilder out = new StringBuilder();
        out.append("# Impact analysis -- workspace ").append(workspaceId.value())
                .append(" -- target: ").append(displayLine(graph, targetIri)).append('\n');
        out.append("\n## Transitively affected (").append(affected.size()).append(")\n");
        appendLines(out, graph, affected);
        return out.toString();
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
}
