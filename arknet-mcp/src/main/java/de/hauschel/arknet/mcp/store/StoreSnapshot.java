// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * An in-memory, domain-agnostic view over the statements read from a project dataset.
 *
 * <p>Built purely from a flat {@code List<Triple>} (one generic {@code SELECT ?s ?p ?o}),
 * so it works identically for requirements, glossary terms or any future bounded context.
 * It groups statements by subject into {@link StoreResource}s, indexes resources by their
 * primary {@code rdf:type}, and detects dangling references to arknet instance resources.</p>
 *
 * <p><strong>Primary type.</strong> A resource may carry several {@code rdf:type}s (e.g. a
 * glossary term that is also an actor). To place each resource under exactly one heading -
 * and keep per-type counts summing to the resource count - the snapshot picks the
 * alphabetically smallest type IRI as the primary type. Resources without any type land in
 * a single untyped bucket ({@link #UNTYPED}).</p>
 */
public final class StoreSnapshot {

    /** Bucket key for resources carrying no {@code rdf:type}. */
    public static final String UNTYPED = "";

    /** A reference from {@code subject} via {@code predicate} to an instance IRI with no statements. */
    public record DanglingRef(String subject, String predicate, String target) {
        public DanglingRef {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(predicate, "predicate");
            Objects.requireNonNull(target, "target");
        }
    }

    private final List<Triple> triples;
    private final List<StoreResource> resources;
    private final Map<String, List<StoreResource>> byPrimaryType;
    private final List<DanglingRef> danglingReferences;

    private StoreSnapshot(List<Triple> triples) {
        this.triples = List.copyOf(triples);
        this.resources = groupBySubject(this.triples);
        this.byPrimaryType = indexByPrimaryType(this.resources);
        this.danglingReferences = detectDangling(this.triples, this.resources);
    }

    /**
     * Builds a snapshot from all statements of a project.
     *
     * @param triples the flat statement list (must not be {@code null})
     * @return the assembled snapshot
     */
    public static StoreSnapshot of(List<Triple> triples) {
        return new StoreSnapshot(Objects.requireNonNull(triples, "triples"));
    }

    /** @return all subject resources, ordered by primary type then IRI. */
    public List<StoreResource> resources() {
        return resources;
    }

    /** @return the total number of statements. */
    public int tripleCount() {
        return triples.size();
    }

    /** @return the number of distinct subject resources. */
    public int resourceCount() {
        return resources.size();
    }

    /** @return the number of distinct primary types (buckets). */
    public int typeCount() {
        return byPrimaryType.size();
    }

    /** @return resources grouped by primary type IRI, buckets ordered by type IRI. */
    public Map<String, List<StoreResource>> byPrimaryType() {
        return byPrimaryType;
    }

    /** @return count of resources per primary type IRI, ordered by type IRI. */
    public Map<String, Integer> typeCounts() {
        Map<String, Integer> counts = new TreeMap<>();
        byPrimaryType.forEach((type, members) -> counts.put(type, members.size()));
        return counts;
    }

    /** @return references to arknet instance IRIs that carry no statements of their own. */
    public List<DanglingRef> danglingReferences() {
        return danglingReferences;
    }

    private static List<StoreResource> groupBySubject(List<Triple> triples) {
        Map<String, List<Triple>> bySubject = new LinkedHashMap<>();
        for (Triple triple : triples) {
            bySubject.computeIfAbsent(triple.subject(), s -> new ArrayList<>()).add(triple);
        }
        return bySubject.entrySet().stream()
                .map(e -> new StoreResource(e.getKey(), e.getValue()))
                .sorted(Comparator.comparing(StoreSnapshot::primaryType).thenComparing(StoreResource::iri))
                .toList();
    }

    private static Map<String, List<StoreResource>> indexByPrimaryType(List<StoreResource> resources) {
        Map<String, List<StoreResource>> index = new TreeMap<>();
        for (StoreResource resource : resources) {
            index.computeIfAbsent(primaryType(resource), t -> new ArrayList<>()).add(resource);
        }
        return index;
    }

    /**
     * The alphabetically smallest {@code rdf:type} IRI, or {@link #UNTYPED} if untyped.
     *
     * @param resource the resource to classify
     * @return the primary type IRI used to group and label the resource
     */
    public static String primaryType(StoreResource resource) {
        return resource.types().stream().min(Comparator.naturalOrder()).orElse(UNTYPED);
    }

    private static List<DanglingRef> detectDangling(List<Triple> triples, List<StoreResource> resources) {
        Set<String> subjects = resources.stream().map(StoreResource::iri).collect(Collectors.toSet());
        List<DanglingRef> dangling = new ArrayList<>();
        for (Triple triple : triples) {
            if (StoreResource.RDF_TYPE.equals(triple.predicate())) {
                continue;
            }
            if (triple.object() instanceof RdfNode.Resource resource
                    && resource.iri().startsWith(Prefixes.INSTANCE_BASE)
                    && !subjects.contains(resource.iri())) {
                dangling.add(new DanglingRef(triple.subject(), triple.predicate(), resource.iri()));
            }
        }
        return dangling;
    }
}
