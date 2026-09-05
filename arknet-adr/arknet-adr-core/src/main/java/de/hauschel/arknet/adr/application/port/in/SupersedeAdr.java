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
 * therefore need a driven lookup port each, both ends of {@code arkarch:supersededBy} are this
 * hexagon's own resources: the application service resolves both codes through its own repository,
 * and an unknown one is an ordinary {@link de.hauschel.arknet.adr.domain.AdrNotFoundException}
 * rather than a didactic cross-context rejection.</p>
 *
 * <p><strong>The write lands on the superseded decision, not the superseding one</strong>
 * (kogn-io/arknet#357). {@code arkarch:supersededBy} is written on the superseded decision, together
 * with its status transitioning to {@code Superseded}, in one write; the superseding decision's own
 * record is not touched by this call at all, beyond being read to check it is
 * {@link de.hauschel.arknet.adr.domain.AdrStatus#ACCEPTED}. This tool's own parameter order
 * ({@code code} first, {@code supersededCode} second) is unchanged by that flip - only which of the
 * two records this port actually writes.</p>
 *
 * <p><strong>{@code defaultLanguage} (issue #468).</strong> This call touches no language-tagged
 * field on the superseded record, but it still reads it to echo an untouched field back in the
 * reply: without the project's own default language, the read-modify-write round trip behind this
 * call would fall back to the process-wide configured language instead, the same defect issue
 * #456 fixed for {@code adr_update}, one call further.</p>
 */
public interface SupersedeAdr {

    /**
     * Records that the decision {@code code} supersedes the decision {@code supersededCode}.
     * Recording the same pair twice is an idempotent no-op.
     *
     * @param projectId       the project (architecture model) both decisions live in
     * @param code            the superseding (newer) decision's code, e.g. {@code ADR-2}; must
     *                        already be {@link de.hauschel.arknet.adr.domain.AdrStatus#ACCEPTED}
     * @param supersededCode  the superseded (older) decision's code, e.g. {@code ADR-1}; must
     *                        already be {@link de.hauschel.arknet.adr.domain.AdrStatus#ACCEPTED} (a
     *                        decision that is not is refused, naming its own status)
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - consulted only for the read of the
     *                        superseded record this call makes to echo an untouched field back,
     *                        never for a write
     * @return the superseded decision including the edge (not the superseding one)
     * @throws de.hauschel.arknet.adr.domain.AdrNotFoundException if either decision does not exist
     * @throws IllegalArgumentException                           if both codes name the same
     *                                                            decision
     * @throws IllegalStateException                               if the superseding decision is
     *                                                              not {@code ACCEPTED}, or if the
     *                                                              superseded decision is not
     *                                                              {@code ACCEPTED} (which includes
     *                                                              already being superseded by a
     *                                                              different decision)
     */
    AdrDetail supersede(ProjectId projectId, AdrCode code, AdrCode supersededCode, String defaultLanguage);
}
