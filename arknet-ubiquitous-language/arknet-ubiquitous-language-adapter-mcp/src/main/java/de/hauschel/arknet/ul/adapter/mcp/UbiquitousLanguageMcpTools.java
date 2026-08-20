// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.mcp;

import java.util.List;
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
import de.hauschel.arknet.ul.application.port.in.GetTerm;
import de.hauschel.arknet.ul.application.port.in.ListTerms;
import de.hauschel.arknet.ul.application.port.in.UpdateTerm;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;

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
 * not a caller-facing argument. The anchor is looked up in the project registry (ADR-016):
 * it arrives opaque, is matched whole against what was registered, and either hits exactly
 * one project or fails with an error message naming the possible remedies.</p>
 */
public final class UbiquitousLanguageMcpTools {

    private static final String PROJECT_ANCHOR_DESCRIPTION = "Optional anchor identifying the project this call "
            + "targets, used INSTEAD of the anchor your transport sends in the "
            + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
            + "header - most callers should omit this and let their transport identify the "
            + "project. Must be an anchor already registered for the project; project_list "
            + "shows what is registered.";

    private final AddTerm addTerm;
    private final ListTerms listTerms;
    private final GetTerm getTerm;
    private final UpdateTerm updateTerm;
    private final DeleteTerm deleteTerm;
    private final ProjectResolver projects;

    /**
     * Creates the adapter with its five driving in-ports and the resolver that maps each
     * call's origin directory to a project.
     *
     * @param addTerm     in-port backing {@code term_add}
     * @param listTerms   in-port backing {@code term_list}
     * @param getTerm     in-port backing {@code term_get}
     * @param updateTerm  in-port backing {@code term_update}
     * @param deleteTerm  in-port backing {@code term_delete}
     * @param projects  resolves each call's target project from its origin directory
     */
    public UbiquitousLanguageMcpTools(
            final AddTerm addTerm,
            final ListTerms listTerms,
            final GetTerm getTerm,
            final UpdateTerm updateTerm,
            final DeleteTerm deleteTerm,
            final ProjectResolver projects) {
        this.addTerm = Objects.requireNonNull(addTerm, "addTerm");
        this.listTerms = Objects.requireNonNull(listTerms, "listTerms");
        this.getTerm = Objects.requireNonNull(getTerm, "getTerm");
        this.updateTerm = Objects.requireNonNull(updateTerm, "updateTerm");
        this.deleteTerm = Objects.requireNonNull(deleteTerm, "deleteTerm");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /**
     * Extracts the calling client's project anchor from the per-call transport context - the value
     * the server's context extractor placed there off the request header (ADR-016). Null-tolerant
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
     * caller supplied one, otherwise the anchor its transport carried (ADR-016 decision 2 - both
     * delivery paths are open to every MCP client). Neither present is a caller error; there is no
     * default project and no fallback to a server-side working directory (decision 3).
     *
     * <p>Returns the full {@link ResolvedProject}, not just its {@link ProjectId}: this component
     * needs the resolved project's configured default language for three, independent purposes -
     * {@link #effectiveDisplayLocale} merges it into the read tool's ({@code term_get}'s)
     * {@code displayLocale} default; {@code term_add}/{@code term_update} instead pass
     * {@link ResolvedProject#defaultLanguage()} straight through to their in-port as the {@code
     * defaultLanguage} a write falls back to when the caller omits {@code language} (issue #258);
     * and {@code term_list} - which, unlike {@code term_get}, exposes no explicit
     * {@code displayLocale} tool argument to merge against - likewise passes it straight through
     * as the display language every listed term's label is read in (issue #274). Three different
     * consumers of the very same field, not one the other two skip.</p>
     */
    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    /**
     * Merges an explicit, caller-supplied {@code displayLocale} argument with {@code project}'s
     * own configured default language for {@code term_get}: the explicit value wins if the
     * caller gave a non-blank one, otherwise the project's default is used (or {@code null} if it
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
            description = "Register a new ubiquitous-language term (minted as a SKOS concept in the glossary).")
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The term itself (its preferred label), e.g. 'Gutschrift'")
            final String label,
            @McpToolParam(description = "The meaning of the term (its definition). Domain meaning only - no "
                    + "architecture, technology, or implementation decisions (source-of-record, persistence, "
                    + "tenancy, who-triggers-what, ...). Those belong in an ADR (adr_add)")
            final String definition,
            @McpToolParam(description = "Optional: mark this term as an actor (a skos:Concept that is "
                    + "additionally an arkproc:Actor). Actor kind: HUMAN, SYSTEM or LEGAL (a legal person, "
                    + "e.g. an organization, company or association)", required = false)
            final String actorKind,
            @McpToolParam(description = "Optional: the actor's role in the bounded context "
                    + "(arkproc:actorRole); only meaningful together with actorKind", required = false)
            final String actorRole,
            @McpToolParam(description = "Optional: identity (e.g. TERM-1) of an already-existing term this one "
                    + "specializes - its broader, superordinate term (skos:broader), e.g. 'Human Actor' as the "
                    + "broader term of 'Customer'. Rejected if the code does not resolve to an existing term",
                    required = false)
            final String broader,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the label and definition "
                    + "are written in. Falls back to the project's configured default language "
                    + "(project_update) if omitted; if the project has no default either, the call is "
                    + "rejected rather than writing an untagged literal.", required = false)
            final String language,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final ActorFacet facet = parseActorFacet(actorKind, actorRole);
        final TermCode broaderCode = blankToNull(broader) == null ? null : new TermCode(broader.trim());
        final Term created = addTerm.add(project.id(),
                new NewTerm(label, definition, facet, blankToNull(language), broaderCode),
                project.defaultLanguage());
        return format(created);
    }

    @McpTool(name = "term_list", description = "List all glossary terms.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        // No explicit displayLocale tool argument to merge against here, unlike term_get - every
        // listed term's label is read straight under the resolved project's own configured
        // default language (issue #274), the same value term_add/term_update already pass through
        // for the write side.
        final List<Term> all = listTerms.list(project.id(), project.defaultLanguage());
        return all.stream().map(UbiquitousLanguageMcpTools::format)
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
            description = "Correct an already-created term's preferred label, definition and/or actor facette, "
                    + "keeping its identity and every existing link into it (e.g. arkreq:usesTerm) unchanged. "
                    + "Every argument is optional - an omitted one leaves that field unchanged.")
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Term identity, e.g. TERM-1") final String id,
            @McpToolParam(description = "New preferred label (optional, unchanged if omitted)", required = false)
            final String label,
            @McpToolParam(description = "New definition (optional, unchanged if omitted). Domain meaning only - "
                    + "no architecture, technology, or implementation decisions (source-of-record, persistence, "
                    + "tenancy, who-triggers-what, ...). Those belong in an ADR (adr_add)", required = false)
            final String definition,
            @McpToolParam(description = "Optional: (re-)mark this term as an actor. Actor kind: HUMAN, SYSTEM or "
                    + "LEGAL (a legal person, e.g. an organization, company or association). Leaves an "
                    + "already-set actor facette unchanged if omitted", required = false)
            final String actorKind,
            @McpToolParam(description = "Optional: the actor's role in the bounded context "
                    + "(arkproc:actorRole); only meaningful together with actorKind. Omitting it while "
                    + "giving actorKind leaves an already-set role unchanged (it does not clear it)",
                    required = false)
            final String actorRole,
            @McpToolParam(description = "Optional: identity (e.g. TERM-1) of an already-existing term this one "
                    + "specializes - its broader, superordinate term (skos:broader). Omit to leave an "
                    + "already-set broader term unchanged; pass an empty string to explicitly clear it; pass "
                    + "a term identity to set/replace it. Rejected if the code does not resolve to an existing "
                    + "term, or if it would make the term its own (direct or transitive) broader term",
                    required = false)
            final String broader,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the new label/definition "
                    + "is written in. Falls back to the project's configured default language (see "
                    + "term_add's same parameter) if omitted; if the project has no default either, the "
                    + "call is rejected rather than writing an untagged literal. Only the existing literal "
                    + "carrying the tag actually written is replaced - every other language variant of a "
                    + "field being corrected survives untouched, except a stale untagged one left over from "
                    + "before a language was ever supplied, which is swept away when the resolved tag equals "
                    + "the project's default.", required = false)
            final String language,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final TermCode code = new TermCode(id);
        final ActorFacet facet = parseActorFacet(actorKind, actorRole);
        final Optional<TermCode> broaderPatch = parseBroaderPatch(broader);
        final Term updated = updateTerm.update(project.id(), code, blankToNull(label), blankToNull(definition),
                facet, blankToNull(language), project.defaultLanguage(), broaderPatch);
        return format(updated);
    }

    @McpTool(name = "term_delete",
            description = "Delete an already-created term and every triple it carries (its label, definition "
                    + "and actor facette, in every language) - not just a correction, the whole resource goes "
                    + "away. Rejected if anything else still references it: a requirement's or use case's "
                    + "usesTerm, a bounded context's ubiquitousLanguageTerm, another term's broader, or - for "
                    + "a term also marked as an actor - a use case's primaryActor/supportingActor. Remove "
                    + "those edges first (req_update/uc_update, bc_link_term, or term_update to clear broader).")
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

    private static ActorFacet parseActorFacet(final String actorKind, final String actorRole) {
        return blankToNull(actorKind) == null
                ? null
                : new ActorFacet(ActorKind.valueOf(actorKind.trim()), blankToNull(actorRole));
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

    private static String format(final Term t) {
        final ActorFacet facet = t.actorFacet();
        final String actor = facet == null
                ? ""
                : " [actor:%s%s]".formatted(facet.kind(), facet.role() == null ? "" : " role=" + facet.role());
        final String broader = t.broader() == null ? "" : " [broader:%s]".formatted(t.broader().value());
        return "%s %s - %s%s%s".formatted(t.code().value(), t.prefLabel(), t.definition(), actor, broader);
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
