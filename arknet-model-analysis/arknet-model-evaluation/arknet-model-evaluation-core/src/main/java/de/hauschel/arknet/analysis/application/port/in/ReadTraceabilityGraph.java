// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.application.port.in;

import de.hauschel.arknet.analysis.domain.TraceabilityGraph;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Reads the whole traced model of one project as a graph: the in-port behind {@code
 * trace_matrix}, {@code store_check ORPHAN} (kogn-io/arknet#473, formerly its own {@code
 * orphan_check} tool), {@code impact_analysis}, {@code role_usecase_matrix} and
 * {@code term_cooccurrence}.
 *
 * <p>All five callers answer different questions about the same graph, so they share one in-port
 * rather than carrying five that each re-read the store. The graph itself is the read model: it
 * is built once per call from the published language of every context and then queried in
 * memory, which is why the driving adapter never sees a store.</p>
 */
public interface ReadTraceabilityGraph {

    /**
     * @param projectId the project whose model is read
     * @param locale    the display language the graph resolves labels under; the caller merges
     *                  the project's own default language into the process-wide fallback before
     *                  calling, so a term's label means the same here as in {@code term_get}
     * @return the graph of that project's model, never {@code null}
     */
    TraceabilityGraph read(ProjectId projectId, DisplayLocale locale);
}
