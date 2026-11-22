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
 * @param droppedEdges     every edge not carried over, with its value: a blank node on any
 *                         property, a literal on a property whose shape requires another
 *                         resource, or an edge on a property the current model no longer has
 * @param droppedSubjects  every subject of the old component graphs that is neither a resource of a
 *                         known type nor a child of one, with the number of its triples dropped
 * @param retainedCodes    the codes of resources deleted under the earlier layout, kept out of
 *                         circulation
 * @param carriedPastGate  every resource whose stored form the current SHACL gate rejects and that
 *                         was therefore carried over unchanged, unvalidated, as
 *                         {@code <type> <code> (<iri>): <violation>}; the tools read it with a
 *                         placeholder for the missing part, and its next regular update has to
 *                         pass the gate
 * @param convertedCriteria every requirement whose acceptance criteria were stored as literals and
 *                         became criterion resources, as
 *                         {@code <code> (<iri>): <n> literal acceptance criteria became criterion
 *                         resources: 1. <text>, 2. <text>} with each text in N-Triples form
 * @param failure          why the migration failed, {@code null} unless {@code outcome} is
 *                         {@link Outcome#FAILED}
 */
public record LegacyLayoutMigrationReport(String dataset, Outcome outcome, Map<String, Integer> resourcesByType,
        List<DroppedEdge> droppedEdges, List<String> droppedSubjects, List<String> retainedCodes,
        List<String> carriedPastGate, List<String> convertedCriteria, String failure) {

    /** Whether a dataset needed a migration and how it went. */
    public enum Outcome {
        /** The dataset holds no graph of the earlier layout; nothing was done. */
        NOT_NEEDED,
        /** The dataset was moved to the content graph in one transaction. */
        MIGRATED,
        /** The migration was rolled back; the dataset still holds the earlier layout. */
        FAILED
    }

    /** Why an edge was dropped. */
    public enum DropReason {
        /** Its object is a blank node, which the versioned write path cannot name. */
        BLANK_NODE,
        /** Its object is a literal on a property whose shape requires another resource. */
        LITERAL,
        /**
         * Its property is one the current model no longer has and no tool reads or writes: the
         * {@code arkreq:primaryActor}/{@code arkreq:supportingActor} of a use case written before
         * v0.8.0, which named actors where the model now names roles.
         */
        RETIRED_PROPERTY
    }

    /**
     * One edge the migration did not carry over.
     *
     * @param subject  the IRI of the edge's subject
     * @param code     the subject's business code, {@code null} for a child resource
     * @param property the property IRI
     * @param reason   why the edge was dropped
     * @param value    the dropped object as written: a blank-node label, a literal's text with its
     *                 language tag or datatype in Turtle form, or an IRI in angle brackets followed by
     *                 the business code of the resource it names, where it has one
     */
    public record DroppedEdge(String subject, String code, String property, DropReason reason, String value) {

        /**
         * Requires every component but {@code code}.
         */
        public DroppedEdge {
            Objects.requireNonNull(subject, "subject");
            Objects.requireNonNull(property, "property");
            Objects.requireNonNull(reason, "reason");
            Objects.requireNonNull(value, "value");
        }

        /**
         * The report line of this edge.
         *
         * @return {@code [<code> ]<subject> <property> <value> (<reason>)}
         */
        public String line() {
            return (code == null ? "" : code + " ") + "<" + subject + "> <" + property + "> " + value + " ("
                    + reason.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ') + ")";
        }
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
        convertedCriteria = List.copyOf(convertedCriteria);
    }

    static LegacyLayoutMigrationReport notNeeded(String dataset) {
        return new LegacyLayoutMigrationReport(dataset, Outcome.NOT_NEEDED, Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), null);
    }

    static LegacyLayoutMigrationReport failed(String dataset, String failure) {
        return new LegacyLayoutMigrationReport(dataset, Outcome.FAILED, Map.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), Objects.requireNonNull(failure, "failure"));
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
                + counts + "]; dropped edges: " + droppedEdges.size()
                + "; dropped unattached subjects: " + droppedSubjects.size()
                + "; retained codes of deleted resources: " + retainedCodes.size()
                + "; carried over past the current write gate: " + carriedPastGate.size()
                + "; requirements whose literal acceptance criteria became criterion resources: "
                + convertedCriteria.size();
    }
}
