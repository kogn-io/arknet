// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: count how many recorded decisions {@link ListAdrs#list} could not include.
 *
 * <p>Split off {@link ListAdrs} rather than added to it as a second method (kogn-io/arknet#359):
 * several report-building tests in {@code arknet-mcp} construct a {@link ListAdrs} inline as a
 * lambda, which a second abstract method would break at every one of those call sites for a concern
 * they have nothing to do with. Backs the note {@code adr_list} appends to its own output.</p>
 */
public interface CountSkippedAdrs {

    /**
     * Returns how many decisions {@link ListAdrs#list} could not include: a store-first
     * decision whose {@code arkarch:adrStatus} does not resolve to a known lifecycle value, or whose
     * status and {@code arkarch:supersededBy} edge disagree with the bi-implication {@code Adr}'s
     * compact constructor enforces (kogn-io/arknet#357) - either is skipped with a {@code WARN} log
     * entry deep in the out-adapter rather than crashing the whole listing, and until now that log
     * line was the only trace of it (kogn-io/arknet#359: a caller reading {@code adr_list} had no way
     * to tell a genuinely empty-of-anomalies project from one silently missing decisions). {@code 0}
     * for a project with nothing to skip, which is every project this hexagon itself ever wrote
     * through {@code adr_add}/{@code adr_update}/{@code adr_supersede} - the gap is reachable only
     * through a store-first edit that bypassed the SHACL write gate entirely.
     *
     * <p><strong>The caller passes in what it already read.</strong> The count is the difference
     * between every recorded decision and the subset {@link ListAdrs#list} could materialise, and the
     * caller is holding that subset by the time it asks - so it hands over its size rather than
     * making this port read the whole decision graph a second time just to rediscover it. Reading it
     * here would double the cost of the hexagon's most expensive read path for a note that is
     * {@code 0} in every project written through these tools.</p>
     *
     * @param projectId          the project (architecture model) to count skipped decisions in
     * @param materialisedCount  how many decisions the caller's own {@link ListAdrs#list} returned
     *                           for the same project, immediately before this call
     * @return the number of decisions {@link ListAdrs#list} silently omitted, {@code 0} if none
     * @throws IllegalArgumentException if {@code materialisedCount} is negative
     */
    int skippedCount(ProjectId projectId, int materialisedCount);
}
