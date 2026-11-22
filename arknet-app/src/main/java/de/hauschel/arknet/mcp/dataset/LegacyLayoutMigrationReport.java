// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * What the startup migration did with one project dataset (see {@link LegacyLayoutMigration}).
 *
 * @param dataset          the dataset id
 * @param outcome          whether the dataset needed a migration and whether it succeeded
 * @param resourcesByType  the migrated resources per resource type, each published once
 * @param droppedEdges     every edge not carried over because its object is a blank node, as
 *                         {@code <resource> <property>}
 * @param droppedSubjects  every subject of the old component graphs that is neither a resource of a
 *                         known type nor a child of one, with the number of its triples dropped
 * @param retainedCodes    the codes of resources deleted under the earlier layout, kept out of
 *                         circulation
 * @param carriedPastGate  every resource whose stored form the current SHACL gate rejects and that
 *                         was therefore carried over unchanged, unvalidated, as
 *                         {@code <type> <code> (<iri>): <violation>}; it stays readable, and its
 *                         next regular update has to pass the gate
 * @param failure          why the migration failed, {@code null} unless {@code outcome} is
 *                         {@link Outcome#FAILED}
 */
public record LegacyLayoutMigrationReport(String dataset, Outcome outcome, Map<String, Integer> resourcesByType,
        List<String> droppedEdges, List<String> droppedSubjects, List<String> retainedCodes,
        List<String> carriedPastGate, String failure) {

    /** Whether a dataset needed a migration and how it went. */
    public enum Outcome {
        /** The dataset holds no graph of the earlier layout; nothing was done. */
        NOT_NEEDED,
        /** The dataset was moved to the content graph in one transaction. */
        MIGRATED,
        /** The migration was rolled back; the dataset still holds the earlier layout. */
        FAILED
    }

    /**
     * Copies the collections, so a report cannot change after it is built.
     */
    public LegacyLayoutMigrationReport {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(outcome, "outcome");
        resourcesByType = Map.copyOf(resourcesByType);
        droppedEdges = List.copyOf(droppedEdges);
        droppedSubjects = List.copyOf(droppedSubjects);
        retainedCodes = List.copyOf(retainedCodes);
        carriedPastGate = List.copyOf(carriedPastGate);
    }

    static LegacyLayoutMigrationReport notNeeded(String dataset) {
        return new LegacyLayoutMigrationReport(dataset, Outcome.NOT_NEEDED, Map.of(), List.of(), List.of(), List.of(),
                List.of(), null);
    }

    static LegacyLayoutMigrationReport failed(String dataset, String failure) {
        return new LegacyLayoutMigrationReport(dataset, Outcome.FAILED, Map.of(), List.of(), List.of(), List.of(),
                List.of(), Objects.requireNonNull(failure, "failure"));
    }

    /**
     * The one-line summary the daemon logs for this dataset.
     *
     * @return the summary
     */
    public String summary() {
        if (outcome == Outcome.NOT_NEEDED) {
            return "dataset '" + dataset + "': no earlier storage layout, nothing to migrate";
        }
        if (outcome == Outcome.FAILED) {
            return "dataset '" + dataset + "': migration failed and was rolled back, the earlier layout is"
                    + " unchanged - " + failure;
        }
        String counts = resourcesByType.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .collect(Collectors.joining(", "));
        return "dataset '" + dataset + "': migrated to the content graph, each resource published once ["
                + counts + "]; dropped blank-node edges: " + droppedEdges.size()
                + "; dropped unattached subjects: " + droppedSubjects.size()
                + "; retained codes of deleted resources: " + retainedCodes.size()
                + "; carried over past the current write gate: " + carriedPastGate.size();
    }
}
