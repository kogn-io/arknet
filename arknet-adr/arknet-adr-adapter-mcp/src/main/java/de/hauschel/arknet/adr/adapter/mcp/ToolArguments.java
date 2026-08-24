// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.mcp;

import de.hauschel.arknet.kernel.ResolvedProject;

/**
 * Argument normalisation shared by {@link AdrMcpTools} - mirrors
 * {@code de.hauschel.arknet.req.adapter.mcp.ToolArguments} (arknet-adr-adapter-mcp has no
 * dependency on the requirements adapter it could reuse that one through).
 */
final class ToolArguments {

    private ToolArguments() {
    }

    /**
     * Merges an explicit, caller-supplied {@code displayLocale} argument with {@code project}'s own
     * configured default language for the read tools ({@code adr_get}, {@code adr_list}): the
     * explicit value wins if the caller gave a non-blank one, otherwise the project's default is
     * used (or {@code null} if it has none, leaving the decision to
     * {@link de.hauschel.arknet.kernel.DisplayLocale#select}'s own remaining fallback chain).
     *
     * @param project  the resolved target project of this call
     * @param explicit the caller's {@code displayLocale} argument, possibly {@code null}/blank
     * @return the language tag to read under, or {@code null} if neither source has one
     */
    static String effectiveDisplayLocale(final ResolvedProject project, final String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return project.defaultLanguage();
    }

    /**
     * Normalises an optional text argument: a {@code null} or blank value means "omitted", which
     * every in-port of this component expresses as {@code null}.
     *
     * @param value the raw argument as the client sent it
     * @return {@code value}, or {@code null} if it was {@code null} or blank
     */
    static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
