package de.hauschel.arknet.ul.adapter.mcp;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import de.hauschel.arknet.kernel.WorkspaceId;
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
 * <p><strong>Workspace (one server = one workspace).</strong> Every in-port takes a
 * {@link WorkspaceId} routing key. This adapter is single-user/local: it operates
 * against exactly one workspace - the {@code workspaceId} injected at construction -
 * and does not expose the workspace as a tool argument. The composition root resolves
 * that id per project; see {@code WorkspaceIdResolver}.</p>
 */
public final class UbiquitousLanguageMcpTools {

    private final AddTerm addTerm;
    private final ListTerms listTerms;
    private final GetTerm getTerm;
    private final WorkspaceId workspaceId;

    /**
     * Creates the adapter with its three driving in-ports and the workspace it serves.
     *
     * @param addTerm     in-port backing {@code term_add}
     * @param listTerms   in-port backing {@code term_list}
     * @param getTerm     in-port backing {@code term_get}
     * @param workspaceId the single workspace all tool calls route to
     */
    public UbiquitousLanguageMcpTools(
            final AddTerm addTerm,
            final ListTerms listTerms,
            final GetTerm getTerm,
            final WorkspaceId workspaceId) {
        this.addTerm = Objects.requireNonNull(addTerm, "addTerm");
        this.listTerms = Objects.requireNonNull(listTerms, "listTerms");
        this.getTerm = Objects.requireNonNull(getTerm, "getTerm");
        this.workspaceId = Objects.requireNonNull(workspaceId, "workspaceId");
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "term_add",
            description = "Register a new ubiquitous-language term (minted as a SKOS concept in the glossary).")
    public String add(
            @McpToolParam(description = "The term itself (its preferred label), e.g. 'Gutschrift'")
            final String label,
            @McpToolParam(description = "The meaning of the term (its definition)") final String definition,
            @McpToolParam(description = "Optional: mark this term as an actor (a skos:Concept that is "
                    + "additionally an arkproc:Actor). Actor kind: HUMAN or SYSTEM", required = false)
            final String actorKind,
            @McpToolParam(description = "Optional: the actor's role in the bounded context "
                    + "(arkproc:actorRole); only meaningful together with actorKind", required = false)
            final String actorRole) {
        final ActorFacet facet = blankToNull(actorKind) == null
                ? null
                : new ActorFacet(ActorKind.valueOf(actorKind.trim()), blankToNull(actorRole));
        final Term created = addTerm.add(workspaceId, new NewTerm(label, definition, facet));
        return format(created);
    }

    @McpTool(name = "term_list", description = "List all glossary terms.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list() {
        final List<Term> all = listTerms.list(workspaceId);
        return all.stream().map(UbiquitousLanguageMcpTools::format)
                .reduce((a, b) -> a + "\n" + b).orElse("(no terms)");
    }

    @McpTool(name = "term_get", description = "Fetch a single glossary term by its identity (e.g. TERM-1).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            @McpToolParam(description = "Term identity, e.g. TERM-1") final String id) {
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
