// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import java.util.Objects;
import java.util.function.Predicate;

/**
 * One listed difference between a typed core and {@code submit}: an intended difference
 * ({@code docs/rules-inventory.md} section 7), a documented exception (section 6), or a finding
 * the comparison turned up and no package has fixed yet.
 *
 * <p>An entry never filters silently. A case that names it must still show the difference
 * without it - a deviation that no longer occurs fails its case - and every entry of a list must
 * be named by at least one case ({@link DeviationList#assertEveryEntryUsed()}).</p>
 *
 * <p>Two kinds:</p>
 * <ul>
 *   <li>{@link Kind#OUTCOME}: one side accepts what the other rejects; the case states which.</li>
 *   <li>{@link Kind#GRAPH}: both sides accept, but the graphs differ in the statements the entry
 *       drops; equal after dropping them.</li>
 * </ul>
 *
 * @param id          short id, e.g. {@code G-1}; findings are {@code F-<type>-<n>}
 * @param source      where the difference is recorded, e.g. {@code rules-inventory.md section 7, row 1}
 * @param description what differs, in one sentence
 * @param kind        outcome or graph
 * @param coreAccepts for {@link Kind#OUTCOME}: whether the core is the side that accepts
 * @param dropped     for {@link Kind#GRAPH}: the canonical statements the difference consists of
 */
public record Deviation(String id, String source, String description, Kind kind, boolean coreAccepts,
        Predicate<String> dropped) {

    /** How a deviation shows. */
    public enum Kind {
        /** One side accepts, the other rejects. */
        OUTCOME,
        /** Both accept; the graphs differ by the dropped statements. */
        GRAPH
    }

    /** Validates the entry. */
    public Deviation {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.GRAPH) {
            Objects.requireNonNull(dropped, "dropped");
        }
    }

    /**
     * The core accepts what {@code submit} rejects.
     *
     * @param id          short id
     * @param source      where it is recorded
     * @param description what differs
     * @return the entry
     */
    public static Deviation onlyCoreAccepts(final String id, final String source, final String description) {
        return new Deviation(id, source, description, Kind.OUTCOME, true, null);
    }

    /**
     * {@code submit} accepts what the core rejects.
     *
     * @param id          short id
     * @param source      where it is recorded
     * @param description what differs
     * @return the entry
     */
    public static Deviation onlySubmitAccepts(final String id, final String source, final String description) {
        return new Deviation(id, source, description, Kind.OUTCOME, false, null);
    }

    /**
     * Both accept, and the graphs differ exactly in the statements {@code dropped} matches.
     *
     * <p>Keep the predicate narrow: it names the subject and the property of the statements that
     * differ, never a property alone - a broad one would hide a new difference in the same field.
     * A case fails when the predicate drops a statement both sides hold, or a statement of a
     * resource without a code (a context relationship, named by its content): dropping one would
     * hide a second or a missing resource.</p>
     *
     * @param id          short id
     * @param source      where it is recorded
     * @param description what differs
     * @param dropped     matches a canonical statement line that belongs to the difference
     * @return the entry
     */
    public static Deviation inGraph(final String id, final String source, final String description,
            final Predicate<String> dropped) {
        return new Deviation(id, source, description, Kind.GRAPH, false, dropped);
    }

    @Override
    public String toString() {
        return id + " (" + source + "): " + description;
    }
}
