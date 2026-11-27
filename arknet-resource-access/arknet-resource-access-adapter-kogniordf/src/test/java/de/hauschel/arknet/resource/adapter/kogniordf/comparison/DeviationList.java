// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The deviations one comparison test class lists. Each class keeps its own list, so no two
 * classes share a file; the list records which entries its cases named, and
 * {@link #assertEveryEntryUsed()} fails an entry no case names any more.
 */
public final class DeviationList {

    private final Map<String, Deviation> entries = new LinkedHashMap<>();
    private final Set<String> used = ConcurrentHashMap.newKeySet();

    private DeviationList() {
    }

    /**
     * The list of a test class.
     *
     * @param deviations every deviation the class lists; may be none
     * @return the list
     */
    public static DeviationList of(final Deviation... deviations) {
        DeviationList list = new DeviationList();
        for (Deviation deviation : deviations) {
            if (list.entries.putIfAbsent(deviation.id(), deviation) != null) {
                throw new IllegalArgumentException("deviation listed twice: " + deviation.id());
            }
        }
        return list;
    }

    /**
     * Looks up an entry and records that a case names it.
     *
     * @param id the id of the entry
     * @return the entry
     */
    Deviation use(final String id) {
        Deviation deviation = entries.get(id);
        if (deviation == null) {
            throw new IllegalArgumentException("deviation " + id + " is not listed in this class");
        }
        used.add(id);
        return deviation;
    }

    /**
     * Fails for every listed entry no case of the class named - to run after all cases of the class.
     */
    public void assertEveryEntryUsed() {
        List<Deviation> unused = entries.values().stream()
                .filter(deviation -> !used.contains(deviation.id()))
                .toList();
        assertTrue(unused.isEmpty(), "listed deviations no case names: " + unused);
    }
}
