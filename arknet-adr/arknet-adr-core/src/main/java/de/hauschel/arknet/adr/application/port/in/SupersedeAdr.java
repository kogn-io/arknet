// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: record that one architecture decision replaces an older one.
 *
 * <p>Backs the tool {@code adr_supersede}. Both arguments are what a human types ({@code ADR-1}),
 * never an IRI - the MCP boundary never surfaces the store-internal identity.</p>
 *
 * <p><strong>Self-referential, so no cross-context lookup.</strong> Unlike
 * {@code addressesRequirement}/{@code affectsContext}, whose targets live in neighbour hexagons and
 * therefore need a driven lookup port each, both ends of {@code arkarch:supersedes} are this
 * hexagon's own resources: the application service resolves the superseded code through its own
 * repository, and an unknown one is an ordinary
 * {@link de.hauschel.arknet.adr.domain.AdrNotFoundException} rather than a didactic cross-context
 * rejection.</p>
 *
 * <p>Only the forward edge is written. The ontology's {@code arkarch:supersededBy} is declared
 * {@code owl:inverseOf arkarch:supersedes}, and deriving it is a reader's job - materialising it as
 * a second, independently maintained triple is exactly the drift this codebase avoids: nothing here
 * reasons over inverses, so the two would have to be kept in step by hand.</p>
 */
public interface SupersedeAdr {

    /**
     * Records that the decision {@code code} supersedes the decision {@code supersededCode}.
     * Recording the same pair twice is an idempotent no-op.
     *
     * @param projectId      the project (architecture model) both decisions live in
     * @param code           the superseding (newer) decision's code, e.g. {@code ADR-2}
     * @param supersededCode the superseded (older) decision's code, e.g. {@code ADR-1}
     * @return the superseding decision including the edge
     * @throws de.hauschel.arknet.adr.domain.AdrNotFoundException if either decision does not exist
     * @throws IllegalArgumentException                           if both codes name the same
     *                                                            decision
     */
    AdrDetail supersede(ProjectId projectId, AdrCode code, AdrCode supersededCode);
}
