// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.mcp;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.kernel.WorkspaceResolver;
import de.hauschel.arknet.ul.application.port.in.AddTerm;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.application.port.in.GetTerm;
import de.hauschel.arknet.ul.application.port.in.ListTerms;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Driving (in) adapter of the ubiquitous-language component: exposes the glossary
 * use-cases as MCP tools ({@code term_add}, {@code term_list}, {@code term_get})
 * and delegates each tool call to the corresponding in-port.
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
 * <p><strong>Workspace (resolved per call).</strong> Every in-port takes a
 * {@link WorkspaceId} routing key. arknet-mcp runs as one shared server for every
 * workspace on the machine (issue #137), so there is no single injected workspace any
 * more: each tool call resolves its own workspace from the request's origin directory,
 * carried in the MCP transport context under {@link WorkspaceResolver#WORKSPACE_DIR_KEY}.
 * The framework hands this adapter that context as an {@link McpSyncRequestContext}
 * parameter - a framework type, excluded from the generated tool input schema, so it is
 * not a caller-facing argument. The concrete resolution (git top-level, slugging,
 * explicit-id override) stays behind {@link WorkspaceResolver} in the composition root.</p>
 */
public final class UbiquitousLanguageMcpTools {

    private final AddTerm addTerm;
    private final ListTerms listTerms;
    private final GetTerm getTerm;
    private final WorkspaceResolver workspaces;

    /**
     * Creates the adapter with its three driving in-ports and the resolver that maps each
     * call's origin directory to a workspace.
     *
     * @param addTerm     in-port backing {@code term_add}
     * @param listTerms   in-port backing {@code term_list}
     * @param getTerm     in-port backing {@code term_get}
     * @param workspaces  resolves each call's target workspace from its origin directory
     */
    public UbiquitousLanguageMcpTools(
            final AddTerm addTerm,
            final ListTerms listTerms,
            final GetTerm getTerm,
            final WorkspaceResolver workspaces) {
        this.addTerm = Objects.requireNonNull(addTerm, "addTerm");
        this.listTerms = Objects.requireNonNull(listTerms, "listTerms");
        this.getTerm = Objects.requireNonNull(getTerm, "getTerm");
        this.workspaces = Objects.requireNonNull(workspaces, "workspaces");
    }

    /**
     * Extracts the calling client's origin directory from the per-call transport context -
     * the value the server's context extractor placed there off the request header (issue
     * #137). Null-tolerant on every hop: a call without a context, without a transport
     * context, or without the key resolves to {@code null}, which {@link WorkspaceResolver}
     * turns into the server's default workspace.
     */
    private static String originDir(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object dir = transport == null ? null : transport.get(WorkspaceResolver.WORKSPACE_DIR_KEY);
        return dir == null ? null : dir.toString();
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
            final String actorRole) {
        final WorkspaceId workspaceId = workspaces.resolve(originDir(context));
        final ActorFacet facet = blankToNull(actorKind) == null
                ? null
                : new ActorFacet(ActorKind.valueOf(actorKind.trim()), blankToNull(actorRole));
        final Term created = addTerm.add(workspaceId, new NewTerm(label, definition, facet));
        return format(created);
    }

    @McpTool(name = "term_list", description = "List all glossary terms.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(final McpSyncRequestContext context) {
        final WorkspaceId workspaceId = workspaces.resolve(originDir(context));
        final List<Term> all = listTerms.list(workspaceId);
        return all.stream().map(UbiquitousLanguageMcpTools::format)
                .reduce((a, b) -> a + "\n" + b).orElse("(no terms)");
    }

    @McpTool(name = "term_get", description = "Fetch a single glossary term by its identity (e.g. TERM-1).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Term identity, e.g. TERM-1") final String id) {
        final WorkspaceId workspaceId = workspaces.resolve(originDir(context));
        final TermCode code = new TermCode(id);
        return getTerm.get(workspaceId, code)
                .map(UbiquitousLanguageMcpTools::format)
                .orElse("Term not found: " + code.value());
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
