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
     */
    private ProjectId resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "term_add",
            description = "Register a new ubiquitous-language term (minted as a SKOS concept in the glossary).")
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The term itself (its preferred label), e.g. 'Gutschrift'")
            final String label,
            @McpToolParam(description = "The meaning of the term (its definition)") final String definition,
            @McpToolParam(description = "Optional: mark this term as an actor (a skos:Concept that is "
                    + "additionally an arkproc:Actor). Actor kind: HUMAN or SYSTEM", required = false)
            final String actorKind,
            @McpToolParam(description = "Optional: the actor's role in the bounded context "
                    + "(arkproc:actorRole); only meaningful together with actorKind", required = false)
            final String actorRole,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final ActorFacet facet = parseActorFacet(actorKind, actorRole);
        final Term created = addTerm.add(projectId, new NewTerm(label, definition, facet));
        return format(created);
    }

    @McpTool(name = "term_list", description = "List all glossary terms.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final List<Term> all = listTerms.list(projectId);
        return all.stream().map(UbiquitousLanguageMcpTools::format)
                .reduce((a, b) -> a + "\n" + b).orElse("(no terms)");
    }

    @McpTool(name = "term_get", description = "Fetch a single glossary term by its identity (e.g. TERM-1).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Term identity, e.g. TERM-1") final String id,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final TermCode code = new TermCode(id);
        return getTerm.get(projectId, code)
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
            @McpToolParam(description = "New definition (optional, unchanged if omitted)", required = false)
            final String definition,
            @McpToolParam(description = "Optional: (re-)mark this term as an actor. Actor kind: HUMAN or SYSTEM. "
                    + "Leaves an already-set actor facette unchanged if omitted", required = false)
            final String actorKind,
            @McpToolParam(description = "Optional: the actor's role in the bounded context "
                    + "(arkproc:actorRole); only meaningful together with actorKind. Omitting it while "
                    + "giving actorKind leaves an already-set role unchanged (it does not clear it)",
                    required = false)
            final String actorRole,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final TermCode code = new TermCode(id);
        final ActorFacet facet = parseActorFacet(actorKind, actorRole);
        final Term updated = updateTerm.update(
                projectId, code, blankToNull(label), blankToNull(definition), facet);
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
