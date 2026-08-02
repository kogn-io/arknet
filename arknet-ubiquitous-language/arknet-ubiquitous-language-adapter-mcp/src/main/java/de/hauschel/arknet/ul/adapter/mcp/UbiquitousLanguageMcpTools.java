// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.mcp;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.ul.application.port.in.AddTerm;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
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
    private final ProjectResolver projects;

    /**
     * Creates the adapter with its four driving in-ports and the resolver that maps each
     * call's origin directory to a project.
     *
     * @param addTerm     in-port backing {@code term_add}
     * @param listTerms   in-port backing {@code term_list}
     * @param getTerm     in-port backing {@code term_get}
     * @param updateTerm  in-port backing {@code term_update}
     * @param projects  resolves each call's target project from its origin directory
     */
    public UbiquitousLanguageMcpTools(
            final AddTerm addTerm,
            final ListTerms listTerms,
            final GetTerm getTerm,
            final UpdateTerm updateTerm,
            final ProjectResolver projects) {
        this.addTerm = Objects.requireNonNull(addTerm, "addTerm");
        this.listTerms = Objects.requireNonNull(listTerms, "listTerms");
        this.getTerm = Objects.requireNonNull(getTerm, "getTerm");
        this.updateTerm = Objects.requireNonNull(updateTerm, "updateTerm");
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
     * needs the resolved project's configured default display language too, for the read tool
     * ({@code term_get}'s {@code displayLocale} default) - see {@link #effectiveDisplayLocale}.
     * The write tools ({@code term_add}/{@code term_update}) deliberately do <strong>not</strong>
     * use it (see that method's javadoc for why), but still resolve the full project so both
     * {@code term_add}/{@code term_update} and {@code term_get} share one resolution path.</p>
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
     * <p><strong>Read-only, deliberately.</strong> The project default language is a display
     * preference, not a write instruction: {@code term_add}/{@code term_update} never call this
     * method and pass their own {@code language} argument straight through, untouched, even when
     * it is {@code null}. Folding the project default into an omitted write-time {@code language}
     * would silently retag what is written - a caller who omits {@code language} on an update
     * would then write a project-default-tagged literal while the field's existing value is very
     * likely still untagged (the language-scoped delete only ever removes the literal carrying
     * the <em>same</em> tag as what is being written), leaving the untagged original standing
     * next to a newly retagged duplicate on the very next correction. Untouched write-time
     * {@code null} therefore always means "write untagged", exactly as before this project
     * default existed.</p>
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
                    + "additionally an arkproc:Actor). Actor kind: HUMAN or SYSTEM", required = false)
            final String actorKind,
            @McpToolParam(description = "Optional: the actor's role in the bounded context "
                    + "(arkproc:actorRole); only meaningful together with actorKind", required = false)
            final String actorRole,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the label and definition "
                    + "are written in, or omitted for a plain, untagged literal. NOT defaulted from the "
                    + "project's configured default language - that default only affects how a term is "
                    + "displayed (term_get), never what gets written.", required = false)
            final String language,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final ActorFacet facet = parseActorFacet(actorKind, actorRole);
        final Term created = addTerm.add(project.id(), new NewTerm(label, definition, facet, blankToNull(language)));
        return format(created);
    }

    @McpTool(name = "term_list", description = "List all glossary terms.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final List<Term> all = listTerms.list(project.id());
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
            @McpToolParam(description = "Optional: (re-)mark this term as an actor. Actor kind: HUMAN or SYSTEM. "
                    + "Leaves an already-set actor facette unchanged if omitted", required = false)
            final String actorKind,
            @McpToolParam(description = "Optional: the actor's role in the bounded context "
                    + "(arkproc:actorRole); only meaningful together with actorKind. Omitting it while "
                    + "giving actorKind leaves an already-set role unchanged (it does not clear it)",
                    required = false)
            final String actorRole,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the new label/definition "
                    + "is written in, or omitted for a plain, untagged literal. NOT defaulted from the "
                    + "project's configured default language (see term_add's same parameter); only the "
                    + "existing literal carrying this same tag is replaced - every other language variant "
                    + "of a field being corrected survives untouched.", required = false)
            final String language,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final TermCode code = new TermCode(id);
        final ActorFacet facet = parseActorFacet(actorKind, actorRole);
        final Term updated = updateTerm.update(
                project.id(), code, blankToNull(label), blankToNull(definition), facet, blankToNull(language));
        return format(updated);
    }

    private static ActorFacet parseActorFacet(final String actorKind, final String actorRole) {
        return blankToNull(actorKind) == null
                ? null
                : new ActorFacet(ActorKind.valueOf(actorKind.trim()), blankToNull(actorRole));
    }

    private static String format(final Term t) {
        final ActorFacet facet = t.actorFacet();
        final String actor = facet == null
                ? ""
                : " [actor:%s%s]".formatted(facet.kind(), facet.role() == null ? "" : " role=" + facet.role());
        return "%s %s - %s%s".formatted(t.code().value(), t.prefLabel(), t.definition(), actor);
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
