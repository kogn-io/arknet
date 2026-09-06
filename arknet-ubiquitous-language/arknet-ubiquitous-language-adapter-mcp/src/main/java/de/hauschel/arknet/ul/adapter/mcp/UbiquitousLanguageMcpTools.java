// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.ul.application.port.in.AddTerm;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.application.port.in.DeleteTerm;
import de.hauschel.arknet.ul.application.port.in.DescribeTermDisplayFallback;
import de.hauschel.arknet.ul.application.port.in.GetTerm;
import de.hauschel.arknet.ul.application.port.in.ListTerms;
import de.hauschel.arknet.ul.application.port.in.UpdateTerm;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermDisplayFallback;

/**
 * Driving (in) adapter of the ubiquitous-language component: exposes the glossary
 * use-cases as MCP tools ({@code term_add}, {@code term_list}, {@code term_get},
 * {@code term_update}) and delegates each tool call to the corresponding in-port.
 *
 * <p>This adapter belongs to the ubiquitous-language hexagon (symmetric to the
 * out-adapter {@code arknet-ubiquitous-language-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods - the tool name, description
 * and JSON input schema are derived from the annotations and method signature, not
 * hand-written. This adapter does <strong>not</strong> bootstrap an MCP server or
 * wire any transport; that remains the concern of the composition root (arknet-mcp),
 * which declares this class as a bean so the Spring AI MCP annotation scanner
 * discovers the {@code @McpTool} methods automatically.</p>
 *
 * <p><strong>Identity vs. code.</strong> {@code term_get} takes a term identity as a plain
 * {@code String} - what a human types, e.g. {@code TERM-1} - and maps it to a
 * {@link TermCode}, never to the opaque {@link de.hauschel.arknet.ul.domain.TermId}. The
 * identity itself is a store-internal detail that never needs to cross the MCP boundary;
 * responses render the code back to the caller, not the underlying resource identity.</p>
 *
 * <p><strong>Project (resolved per call).</strong> Every in-port takes a
 * {@link ProjectId} routing key. arknet-mcp runs as one shared server for every
 * project on the machine, so there is no single injected project any
 * more: each tool call resolves its own project from the request's anchor,
 * carried in the MCP transport context under {@link ProjectResolver#ANCHOR_KEY}.
 * The framework hands this adapter that context as an {@link McpSyncRequestContext}
 * parameter - a framework type, excluded from the generated tool input schema, so it is
 * not a caller-facing argument. The anchor is looked up in the project registry:
 * it arrives opaque, is matched whole against what was registered, and either hits exactly
 * one project or fails with an error message naming the possible remedies.</p>
 */
public final class UbiquitousLanguageMcpTools {

    /**
     * The prose markup this tool's free-text fields accept, appended to every writing tool's
     * description (issue #388).
     *
     * <p>It belongs on the tool, not only in the module docs: the writing agent reads the tool
     * schema and nothing else, which is exactly why the {@code white-space:pre-line} mechanism of
     * issue #385 was never used by anyone. The same sentence is repeated in each bounded
     * context's MCP adapter rather than shared, because these adapters deliberately have no
     * common module - a shared string is not reason enough to create one.</p>
     */
    private static final String PROSE_MARKUP = " Free-text fields accept a narrow Markdown subset:"
            + " **bold**, *italic*, `code`, lines starting with '- ' as a bullet list, and a blank line"
            + " for a new paragraph. Links, headings, tables and HTML are deliberately not interpreted -"
            + " a reference belongs in the model (an edge such as usesTerm), not in a hand-written link.";

    private static final String PROJECT_ANCHOR_DESCRIPTION = "Optional anchor identifying the project this call "
            + "targets, used INSTEAD of the anchor your transport sends in the "
            + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
            + "header - most callers should omit this and let their transport identify the "
            + "project. Must be an anchor already registered for the project; project_list "
            + "shows what is registered.";

    private final AddTerm addTerm;
    private final ListTerms listTerms;
    private final DescribeTermDisplayFallback describeTermDisplayFallback;
    private final GetTerm getTerm;
    private final UpdateTerm updateTerm;
    private final DeleteTerm deleteTerm;
    private final ProjectResolver projects;

    /**
     * Creates the adapter with its six driving in-ports and the resolver that maps each
     * call's origin directory to a project.
     *
     * @param addTerm     in-port backing {@code term_add}
     * @param listTerms   in-port backing {@code term_list}
     * @param describeTermDisplayFallback in-port backing {@code term_list}'s fallback-visibility
     *                    line (kogn-io/arknet#475)
     * @param getTerm     in-port backing {@code term_get}
     * @param updateTerm  in-port backing {@code term_update}
     * @param deleteTerm  in-port backing {@code term_delete}
     * @param projects  resolves each call's target project from its origin directory
     */
    public UbiquitousLanguageMcpTools(
            final AddTerm addTerm,
            final ListTerms listTerms,
            final DescribeTermDisplayFallback describeTermDisplayFallback,
            final GetTerm getTerm,
            final UpdateTerm updateTerm,
            final DeleteTerm deleteTerm,
            final ProjectResolver projects) {
        this.addTerm = Objects.requireNonNull(addTerm, "addTerm");
        this.listTerms = Objects.requireNonNull(listTerms, "listTerms");
        this.describeTermDisplayFallback =
                Objects.requireNonNull(describeTermDisplayFallback, "describeTermDisplayFallback");
        this.getTerm = Objects.requireNonNull(getTerm, "getTerm");
        this.updateTerm = Objects.requireNonNull(updateTerm, "updateTerm");
        this.deleteTerm = Objects.requireNonNull(deleteTerm, "deleteTerm");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /**
     * Extracts the calling client's project anchor from the per-call transport context - the value
     * the server's context extractor placed there off the request header. Null-tolerant
     * on every hop: a call without a context, without a transport context, or without the key
     * resolves to {@code null}, which is a caller error rather than a route to a default.
     */
    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }

    /**
     * Resolves the project this call targets: the explicit {@code projectAnchor} parameter if the
     * caller supplied one, otherwise the anchor its transport carried; both delivery paths are open
     * to every MCP client. Neither present is a caller error; there is no default project and no
     * fallback to a server-side working directory.
     *
     * <p>Returns the full {@link ResolvedProject}, not just its {@link ProjectId}: this component
     * needs the resolved project's configured default language for two, independent purposes -
     * {@link #effectiveDisplayLocale} merges it into the read tools' ({@code term_get}'s and,
     * since kogn-io/arknet#475, {@code term_list}'s own) {@code displayLocale} default;
     * {@code term_add}/{@code term_update} instead pass {@link ResolvedProject#defaultLanguage()}
     * straight through to their in-port as the {@code defaultLanguage} a write falls back to when
     * the caller omits {@code language} (issue #258). Two different consumers of the very same
     * field.</p>
     */
    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    /**
     * Merges an explicit, caller-supplied {@code displayLocale} argument with {@code project}'s
     * own configured default language for {@code term_get}/{@code term_list} (the latter since
     * kogn-io/arknet#475): the explicit value wins if the caller gave a non-blank one, otherwise
     * the project's default is used (or {@code null} if it
     * has none, leaving the decision to {@link de.hauschel.arknet.kernel.DisplayLocale#select}'s
     * own remaining fallback chain).
     *
     * <p><strong>Independent of the write-side use of the same field.</strong> {@code
     * term_add}/{@code term_update} do not call this method - they pass {@link
     * ResolvedProject#defaultLanguage()} straight through to their in-port instead, as the {@code
     * defaultLanguage} a write falls back to when the caller omits {@code language} (issue #258).
     * Both are the very same field on {@link ResolvedProject}, merged differently for their
     * respective purpose: this method merges it against an explicit {@code displayLocale} override
     * for reading, the write tools hand it to the in-port unmerged for the in-port's own
     * resolve-or-reject decision.</p>
     */
    private static String effectiveDisplayLocale(final ResolvedProject project, final String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return project.defaultLanguage();
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "term_add",
            description = "Register a new ubiquitous-language term (minted as a SKOS concept in the glossary)." + PROSE_MARKUP)
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The term itself (its preferred label), e.g. 'Gutschrift' - the same "
                    + "word under every language the term is later given (label in the language the domain "
                    + "community actually uses for it); only the definition is translated")
            final String label,
            @McpToolParam(description = "The meaning of the term (its definition). Domain meaning only - no "
                    + "architecture, technology, or implementation decisions (source-of-record, persistence, "
                    + "tenancy, who-triggers-what, ...). Those belong in an ADR (adr_add)")
            final String definition,
            @McpToolParam(description = "Optional: identity (e.g. TERM-1) of an already-existing term this one "
                    + "specializes - its broader, superordinate term (skos:broader), e.g. 'Human Actor' as the "
                    + "broader term of 'Customer'. Rejected if the code does not resolve to an existing term",
                    required = false)
            final String broader,
            @McpToolParam(description = "Optional: identities (e.g. TERM-1) of already-existing terms this one "
                    + "is associatively related to (skos:related) - a non-hierarchical connection, e.g. 'Anchor' "
                    + "and 'Project'. Use broader instead when one term specializes the other. The relation is "
                    + "symmetric: it shows on both terms, and naming it on either one is enough. Rejected if a "
                    + "code does not resolve to an existing term, or names this term itself", required = false)
            final List<String> related,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the label and definition "
                    + "are written in. Falls back to the project's configured default language "
                    + "(project_update) if omitted; if the project has no default either, the call is "
                    + "rejected rather than writing an untagged literal.", required = false)
            final String language,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final TermCode broaderCode = blankToNull(broader) == null ? null : new TermCode(broader.trim());
        final Term created = addTerm.add(project.id(),
                new NewTerm(label, definition, blankToNull(language), broaderCode, toTermCodes(related)),
                project.defaultLanguage());
        return format(created);
    }

    @McpTool(name = "term_list", description = "List all glossary terms. A term shown under a fallen-back "
            + "language (its label/definition is missing in the requested/project-default language) carries "
            + "an inline [fallback: ...] tag naming the language actually shown - see displayLocale.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') to display every term's "
                    + "label and definition in, overriding the project's own configured default language for "
                    + "this one call (kogn-io/arknet#475). Falls back to the project default, then to the "
                    + "server's own default, then to an untagged literal, then deterministically to any "
                    + "literal a term carries - a term whose shown variant is not this call's requested/"
                    + "project-default language is marked with an inline [fallback: ...] tag.",
                    required = false)
            final String displayLocale,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final String effective = effectiveDisplayLocale(project, displayLocale);
        final List<Term> all = listTerms.list(project.id(), effective);
        if (all.isEmpty()) {
            return "(no terms)";
        }
        final Map<TermCode, TermDisplayFallback> fallbacks =
                describeTermDisplayFallback.describe(project.id(), effective);
        return all.stream().map(t -> format(t) + fallbackSuffix(fallbacks.get(t.code())))
                .reduce((a, b) -> a + "\n" + b).orElse("(no terms)");
    }

    @McpTool(name = "term_get", description = "Fetch a single glossary term by its identity (e.g. TERM-1).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Term identity, e.g. TERM-1") final String id,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') to display the label and "
                    + "definition in, overriding the project's own configured default language for this one "
                    + "call. Falls back to the project default, then to the server's own default, then to an "
                    + "untagged literal, then deterministically to any literal the term carries.",
                    required = false)
            final String displayLocale,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final TermCode code = new TermCode(id);
        final String effective = effectiveDisplayLocale(project, displayLocale);
        return getTerm.get(project.id(), code, effective)
                .map(UbiquitousLanguageMcpTools::format)
                .orElse("Term not found: " + code.value());
    }

    @McpTool(name = "term_update",
            description = "Correct an already-created term's preferred label, definition and/or relations, "
                    + "keeping its identity and every existing link into it (e.g. arkreq:usesTerm) unchanged. "
                    + "Every argument is optional - an omitted one leaves that field unchanged." + PROSE_MARKUP)
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Term identity, e.g. TERM-1") final String id,
            @McpToolParam(description = "New preferred label (optional, unchanged if omitted). Two distinct "
                    + "meanings depending on language: omitted -> renames the term under EVERY language tag it "
                    + "carries at once (a glossary term is the same word in every language, only the definition "
                    + "is translated); given -> must equal the label the term already carries, and is then only "
                    + "added/refreshed under that one language tag - a differing word is rejected, naming the "
                    + "existing label, so a caller who meant to rename knows to omit language instead. To "
                    + "translate the term, leave label out and supply definition alone.", required = false)
            final String label,
            @McpToolParam(description = "New definition (optional, unchanged if omitted). Domain meaning only - "
                    + "no architecture, technology, or implementation decisions (source-of-record, persistence, "
                    + "tenancy, who-triggers-what, ...). Those belong in an ADR (adr_add)", required = false)
            final String definition,
            @McpToolParam(description = "Optional: identity (e.g. TERM-1) of an already-existing term this one "
                    + "specializes - its broader, superordinate term (skos:broader). Omit to leave an "
                    + "already-set broader term unchanged; pass an empty string to explicitly clear it; pass "
                    + "a term identity to set/replace it. Rejected if the code does not resolve to an existing "
                    + "term, or if it would make the term its own (direct or transitive) broader term",
                    required = false)
            final String broader,
            @McpToolParam(description = "Optional: identities (e.g. TERM-1) of the already-existing terms this "
                    + "one should be associatively related to going forward (skos:related), replacing the "
                    + "existing ones wholesale. Omit to leave them unchanged; pass an empty list to remove all "
                    + "of them. The relation is symmetric, but only this term's own edges are rewritten - an "
                    + "edge another term asserts towards this one is cleared with a term_update on that term. "
                    + "Rejected if a code does not resolve to an existing term, or names this term itself",
                    required = false)
            final List<String> related,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the new label/definition "
                    + "is written in. Falls back to the project's configured default language (see "
                    + "term_add's same parameter) if omitted; if the project has no default either, the "
                    + "call is rejected rather than writing an untagged literal. Only the existing literal "
                    + "carrying the tag actually written is replaced - every other language variant of a "
                    + "field being corrected survives untouched, except a stale untagged one left over from "
                    + "before a language was ever supplied, which is swept away when the resolved tag equals "
                    + "the project's default. For label specifically, this is what decides between the two "
                    + "meanings above: omitted -> rename under every tag, given -> that one tag only.",
                    required = false)
            final String language,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final TermCode code = new TermCode(id);
        final Optional<TermCode> broaderPatch = parseBroaderPatch(broader);
        final Term updated = updateTerm.update(project.id(), code, blankToNull(label), blankToNull(definition),
                blankToNull(language), project.defaultLanguage(), broaderPatch, toTermCodes(related));
        return format(updated);
    }

    @McpTool(name = "term_delete",
            description = "Delete an already-created term and every triple it carries (its label and "
                    + "definition, in every language) - not just a correction, the whole resource goes "
                    + "away. Rejected if anything else still references it: a requirement's or use case's "
                    + "arkreq:usesTerm, an architecture decision's arkarch:usesTerm, a bounded context's "
                    + "ubiquitousLanguageTerm, or another term's broader or related. Remove those edges first "
                    + "(req_update/uc_update, adr_update, bc_link_term, or term_update on the other term to "
                    + "clear its broader/related).")
    public String delete(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Term identity, e.g. TERM-1") final String id,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final TermCode code = new TermCode(id);
        deleteTerm.delete(project.id(), code);
        return "Deleted: " + code.value();
    }

    /**
     * Parses {@code term_update}'s {@code broader} argument into {@link UpdateTerm}'s
     * {@code null}-or-{@link Optional} tri-state (see that port's class-level "Broader" note):
     * unlike every other {@code term_update} argument, an omitted/blank {@code broader} cannot
     * share the usual "leave unchanged" meaning with "explicitly clear" - a caller needs a way to
     * say both. Omitting the argument entirely ({@code broader == null}) leaves an already-set
     * broader term unchanged; explicitly passing an empty/blank string clears it; any other value
     * is the code of the term to set/replace it with.
     */
    private static Optional<TermCode> parseBroaderPatch(final String broader) {
        if (broader == null) {
            return null;
        }
        return broader.isBlank() ? Optional.empty() : Optional.of(new TermCode(broader.trim()));
    }

    /**
     * Maps {@code term_add}/{@code term_update}'s {@code related} argument onto {@link TermCode}s,
     * keeping the "omitted" signal intact: {@code null} stays {@code null} (leave unchanged for
     * {@code term_update}, none for {@code term_add}), while an explicitly empty list stays an
     * empty list (clear all). Blank entries are dropped rather than turned into a code no term can
     * carry, mirroring {@code blankToNull} on the scalar arguments.
     */
    private static List<TermCode> toTermCodes(final List<String> codes) {
        if (codes == null) {
            return null;
        }
        return codes.stream()
                .filter(code -> blankToNull(code) != null)
                .map(code -> new TermCode(code.trim()))
                .toList();
    }

    private static String format(final Term t) {
        final String broader = t.broader() == null ? "" : " [broader:%s]".formatted(t.broader().value());
        final String related = t.related().isEmpty() ? "" : " [related:%s]".formatted(
                t.related().stream().map(TermCode::value).reduce((a, b) -> a + "," + b).orElseThrow());
        return "%s %s - %s%s%s".formatted(t.code().value(), t.prefLabel(), t.definition(), broader, related);
    }

    /**
     * The {@code [fallback: ...]} suffix {@code term_list} appends to a line whenever {@code
     * fallback} names at least one field that had to degrade past the requested/project-default
     * language (kogn-io/arknet#475) - empty string (no visible change) when {@code fallback} is
     * {@code null} or carries no fallen-back field, matching the requirement that the normal case
     * stays noise-free.
     */
    private static String fallbackSuffix(final TermDisplayFallback fallback) {
        if (fallback == null || fallback.isEmpty()) {
            return "";
        }
        final List<String> parts = new ArrayList<>();
        if (fallback.prefLabelTag() != null) {
            parts.add("prefLabel=" + displayTag(fallback.prefLabelTag()));
        }
        if (fallback.definitionTag() != null) {
            parts.add("definition=" + displayTag(fallback.definitionTag()));
        }
        return " [fallback: " + String.join(", ", parts) + "]";
    }

    private static String displayTag(final String tag) {
        return tag.isEmpty() ? "untagged" : tag;
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
