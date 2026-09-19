// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.mcp;

import de.hauschel.arknet.kernel.ResolvedProject;

/**
 * Argument normalisation shared by this MCP adapter's tool classes ({@link RequirementMcpTools},
 * {@link ConstraintMcpTools}) - the two rules every tool of this component applies to a raw,
 * client-supplied argument before it reaches an in-port.
 *
 * <p>Both rules are policy, not plumbing: an MCP client may send an omitted optional argument as
 * an empty string, and the display-language fallback chain is a decision about which language a
 * caller sees. Keeping them in one place means extending either (a further fallback step,
 * trimming) changes one method rather than every tool class of this package.</p>
 */
final class ToolArguments {

    private ToolArguments() {
    }

    /**
     * Merges an explicit, caller-supplied {@code displayLocale} argument with {@code project}'s own
     * configured default language for the read tools ({@code req_get}, {@code constraint_get}): the
     * explicit value wins if the caller gave a non-blank one, otherwise the project's default is
     * used (or {@code null} if it has none, leaving the decision to
     * {@link de.hauschel.arknet.kernel.DisplayLocale#select}'s own remaining fallback chain).
     *
     * <p>The write tools never call this - a write resolves its language through
     * {@code LanguageTag#resolveWriteLanguage} in the application service instead, which rejects
     * rather than degrades when neither an explicit tag nor a project default is available.</p>
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
