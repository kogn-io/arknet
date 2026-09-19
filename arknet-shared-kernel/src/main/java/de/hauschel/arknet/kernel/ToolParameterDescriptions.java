// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

/**
 * Short {@code @McpToolParam} description texts for the three parameters nearly every
 * {@code *McpTools} class repeats: {@code projectAnchor}, {@code language} and
 * {@code displayLocale}.
 *
 * <p>Before kogn-io/arknet#522 every adapter module carried its own copy of these texts, several
 * with slightly diverging wording ({@code "to check"} vs. {@code "to analyse"} vs.
 * {@code "to search"} the project) - and the inlined {@code projectAnchor} description alone made
 * up 19.7% of the whole server's {@code tools/list} payload, because Spring AI inlines every
 * parameter schema per tool rather than sharing a {@code $ref}. Centralising the three texts here,
 * the one module every {@code *-adapter-mcp} module and {@code arknet-mcp} already depend on,
 * removes the duplication; kogn-io/arknet#561 is expected to move this class into a dedicated
 * tool-adapter support module alongside {@link ProjectResolver}.</p>
 *
 * <p>The texts are deliberately short - just enough to say <em>when</em> a caller sets the
 * parameter. The fuller explanation ({@code projectAnchor} overriding the
 * {@code X-Arknet-Project-Anchor} header, the project-default fallback both {@code language} and
 * {@code displayLocale} use) lives once in {@code arknet-mcp}'s
 * {@code spring.ai.mcp.server.instructions}, not repeated on every tool; the full
 * {@code displayLocale} fallback chain is documented on {@link DisplayLocale#select}.</p>
 */
public final class ToolParameterDescriptions {

    /** Description for the {@code projectAnchor} parameter every model-BC tool accepts. */
    public static final String PROJECT_ANCHOR_DESCRIPTION =
            "Optional: project anchor override. Omit unless your client cannot send the request header.";

    /** Description for the {@code language} parameter a writing tool accepts. */
    public static final String LANGUAGE_DESCRIPTION =
            "Optional: BCP-47 language tag this call writes under. Falls back to the project default.";

    /** Description for the {@code displayLocale} parameter a reading tool accepts. */
    public static final String DISPLAY_LOCALE_DESCRIPTION =
            "Optional: BCP-47 language tag to display this call's text in, overriding the project default.";

    private ToolParameterDescriptions() {
    }
}
