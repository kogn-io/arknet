// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.in;

import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: undo a mistaken {@code adr_supersede} call, restoring a
 * {@link de.hauschel.arknet.adr.domain.AdrStatus#SUPERSEDED} decision to
 * {@link de.hauschel.arknet.adr.domain.AdrStatus#ACCEPTED} and clearing its
 * {@code arkarch:supersededBy} edge, together in one write (kogn-io/arknet#354).
 *
 * <p>Backs the tool {@code adr_unsupersede}. Takes a single business code, unlike
 * {@link SupersedeAdr}'s two: the successor is not touched by this call at all, only read as part of
 * the edge this port removes, so there is nothing to name it by.</p>
 *
 * <p><strong>A lifecycle act, not a field correction.</strong> Mirrors why {@code supersededBy} keeps
 * its own tool ({@code adr_supersede}) rather than travelling through {@code adr_update}'s four
 * tri-state reference lists: reversing "this decision replaces that one" is itself a decision about
 * the decision, not the completion of a reference that could not be written earlier.</p>
 *
 * <p><strong>{@code defaultLanguage} (issue #468).</strong> Same reasoning as {@link SupersedeAdr}:
 * this call touches no language-tagged field itself, but still needs the project's own default
 * language for the read behind it, so an untouched field is echoed back under the project's own
 * language rather than the process-wide configured one.</p>
 */
public interface UnsupersedeAdr {

    /**
     * Restores the decision {@code code} from {@link de.hauschel.arknet.adr.domain.AdrStatus#SUPERSEDED}
     * to {@link de.hauschel.arknet.adr.domain.AdrStatus#ACCEPTED}, clearing its
     * {@code supersededBy} edge in the same write. Neither reads nor writes the former successor's own
     * record.
     *
     * @param projectId       the project (architecture model) the decision lives in
     * @param code            the decision's code, e.g. {@code ADR-1}; must currently be
     *                        {@link de.hauschel.arknet.adr.domain.AdrStatus#SUPERSEDED}
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - consulted only for the read this call
     *                        makes to echo an untouched field back, never for a write
     * @return the restored decision
     * @throws de.hauschel.arknet.adr.domain.AdrNotFoundException if no decision with {@code code}
     *                                                             exists
     * @throws IllegalStateException                              if the decision is not currently
     *                                                              {@code SUPERSEDED}
     */
    AdrDetail unsupersede(ProjectId projectId, AdrCode code, String defaultLanguage);
}
