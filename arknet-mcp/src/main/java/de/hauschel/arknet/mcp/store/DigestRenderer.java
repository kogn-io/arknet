// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Renders the compact, token-cheap text digest an agent gets back from {@code
 * store_overview}: project header with counters, a prefix legend, per-{@code rdf:type}
 * counts, one line per resource with a {@code -> resource_get(...)} drill-down affordance,
 * a next-step block and an integrity hint.
 *
 * <p>Pure and domain-agnostic: it consumes only a {@link StoreSnapshot} plus a
 * {@link Prefixes} resolver, so it is fully unit-testable and renders any bounded context's
 * data the same way. The handle it prints is never the label; the preference is (1) a CURIE,
 * if the subject IRI shortens against a {@link Prefixes} namespace, (2) else the resource's
 * {@code dcterms:identifier} (a bare business id, e.g. {@code FR-1}), if it carries one, (3)
 * else the full IRI. Case (2) is what keeps opaque, kernel-minted identities
 * (a {@link de.hauschel.arknet.kernel.ResourceId} is not bound to any CURIE namespace)
 * human-readable in the digest.</p>
 */
public final class DigestRenderer {

    private final Prefixes prefixes;

    /**
     * @param prefixes the CURIE resolver used to shorten IRIs for display
     */
    public DigestRenderer(Prefixes prefixes) {
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
    }

    /**
     * Renders the digest for a project snapshot.
     *
     * @param projectId the project the snapshot was read from
     * @param label       the project's registered label, or {@link Optional#empty()}
     *                    if {@code projectId} is not (or no longer) found in the registry - the
     *                    header then falls back to the raw id, exactly as before this label was
     *                    available
     * @param snapshot    the snapshot to render
     * @return the digest text
     */
    public String render(ProjectId projectId, Optional<String> label, StoreSnapshot snapshot) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(snapshot, "snapshot");

        StringBuilder out = new StringBuilder();
        out.append("# Project ").append(headerName(projectId, label))
                .append(" -- ").append(snapshot.resourceCount()).append(" resources, ")
                .append(snapshot.tripleCount()).append(" triples, ")
                .append(snapshot.typeCount()).append(" types\n");

        appendPrefixLegend(out, snapshot);
        out.append("# Handle for resource_get is the IRI (as a CURIE), NOT the label.\n\n");

        appendTypeCounts(out, snapshot);
        out.append('\n');

        appendResources(out, snapshot);

        appendNextSteps(out);
        appendIntegrity(out, snapshot);
        return out.toString();
    }

    /**
     * The project name shown in the header: the registered label with its id alongside (matching
     * {@code project_list}'s own {@code "label (id: ...)"} rendering), or the raw id alone when no
     * label is available.
     */
    private static String headerName(ProjectId projectId, Optional<String> label) {
        return label.map(value -> value + " (id: " + projectId.value() + ")").orElseGet(projectId::value);
    }

    private void appendPrefixLegend(StringBuilder out, StoreSnapshot snapshot) {
        List<Prefixes.Prefix> used = usedPrefixes(snapshot);
        if (used.isEmpty()) {
            return;
        }
        int width = used.stream().mapToInt(p -> p.prefix().length()).max().orElse(0);
        out.append("# Prefixes:\n");
        for (Prefixes.Prefix prefix : used) {
            out.append("#   ").append(padRight(prefix.prefix() + ":", width + 1))
                    .append(" = ").append(prefix.namespace()).append('\n');
        }
    }

    private List<Prefixes.Prefix> usedPrefixes(StoreSnapshot snapshot) {
        Set<String> iris = new LinkedHashSet<>();
        for (Triple triple : allTriples(snapshot)) {
            iris.add(triple.subject());
            iris.add(triple.predicate());
            if (triple.object() instanceof RdfNode.Resource resource) {
                iris.add(resource.iri());
            }
        }
        List<Prefixes.Prefix> used = new ArrayList<>();
        for (Prefixes.Prefix prefix : prefixes.bindings()) {
            if (iris.stream().anyMatch(iri -> iri.startsWith(prefix.namespace()))) {
                used.add(prefix);
            }
        }
        return used;
    }

    private static List<Triple> allTriples(StoreSnapshot snapshot) {
        List<Triple> all = new ArrayList<>();
        snapshot.resources().forEach(resource -> all.addAll(resource.outgoing()));
        return all;
    }

    private void appendTypeCounts(StringBuilder out, StoreSnapshot snapshot) {
        Map<String, Integer> counts = snapshot.typeCounts();
        counts.forEach((type, count) -> out.append(count).append(' ')
                .append(displayType(type)).append('\n'));
    }

    private void appendResources(StringBuilder out, StoreSnapshot snapshot) {
        snapshot.byPrimaryType().forEach((type, members) -> {
            out.append("## ").append(displayType(type)).append(" (").append(members.size()).append(")\n");
            for (StoreResource resource : members) {
                out.append(renderResourceLine(resource)).append('\n');
            }
            out.append('\n');
        });
    }

    private String renderResourceLine(StoreResource resource) {
        String handle = handleFor(resource);
        StringBuilder line = new StringBuilder(handle);
        String types = String.join(",", resource.types().stream().map(StoreResource::localName).toList());
        if (!types.isEmpty()) {
            line.append(" [").append(types).append(']');
        }
        resource.label().ifPresent(label -> line.append(" \"").append(label).append('"'));
        resource.status().ifPresent(status -> line.append(' ').append(status));
        resource.priority().ifPresent(priority -> line.append(' ').append(priority));
        line.append("  -> resource_get(\"").append(handle).append("\")");
        return line.toString();
    }

    /**
     * The display/drill-down handle for one resource: a CURIE if the subject IRI shortens
     * against a known {@link Prefixes} namespace, else its {@code dcterms:identifier} (bare
     * business id), else the full IRI. See the class-level note for why case (2) matters.
     */
    private String handleFor(StoreResource resource) {
        String curie = prefixes.toCurie(resource.iri());
        if (!curie.equals(resource.iri())) {
            return curie;
        }
        return resource.identifier().orElse(resource.iri());
    }

    private void appendNextSteps(StringBuilder out) {
        out.append("# Next step (for the agent)\n");
        out.append("- Details of ONE resource : resource_get(\"<curie-or-iri>\")"
                + " -> all triples + in/out links\n");
        out.append("- Neighbours / what points here: resource_get(...) also lists incoming refs\n");
        out.append("- Human view              : the written HTML report (path returned below)\n");
    }

    private void appendIntegrity(StringBuilder out, StoreSnapshot snapshot) {
        out.append("\n# Integrity\n");
        List<StoreSnapshot.DanglingRef> dangling = snapshot.danglingReferences();
        if (dangling.isEmpty()) {
            out.append("- no dangling references\n");
            return;
        }
        out.append("- ").append(dangling.size()).append(" dangling reference(s):\n");
        for (StoreSnapshot.DanglingRef ref : dangling) {
            out.append("  ").append(prefixes.toCurie(ref.subject()))
                    .append(" ").append(prefixes.toCurie(ref.predicate()))
                    .append(" -> ").append(prefixes.toCurie(ref.target())).append(" (missing)\n");
        }
    }

    private String displayType(String typeIri) {
        return typeIri.isEmpty() ? "(untyped)" : prefixes.toCurie(typeIri);
    }

    private static String padRight(String value, int width) {
        if (value.length() >= width) {
            return value;
        }
        return value + " ".repeat(width - value.length());
    }
}
