// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.application.port.in.GetBoundedContext;
import de.hauschel.arknet.bc.application.port.in.LinkTerm;
import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;

/**
 * Driving (in) adapter of the bounded-context component: exposes the bounded-context use-cases as
 * MCP tools ({@code bc_add}, {@code bc_list}, {@code bc_get}, {@code bc_link_term}) and delegates
 * each tool call to the corresponding in-port.
 *
 * <p>This adapter belongs to the bounded-context hexagon (symmetric to the out-adapter
 * {@code arknet-bounded-context-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods - the tool name, description and JSON
 * input schema are derived from the annotations and method signature, not hand-written. This
 * adapter does <strong>not</strong> bootstrap an MCP server or wire any transport; that remains
 * the concern of the composition root (arknet-mcp).</p>
 *
 * <p><strong>Identity vs. code.</strong> Every tool takes a bounded-context identity as a plain
 * {@code String} - what a human types, e.g. {@code BC-1} - and maps it to a
 * {@link BoundedContextCode}, never to the opaque
 * {@link de.hauschel.arknet.bc.domain.BoundedContextId}. The identity itself is a store-internal
 * detail that never needs to cross the MCP boundary; responses render the code back to the
 * caller, not the underlying resource identity.</p>
 *
 * <p><strong>Term display resolution (ADR-008).</strong> {@link TermRef} carries a linked term's
 * opaque subject identity, not its business code - but a human who typed {@code TERM-1} into
 * {@code bc_link_term} expects to see {@code TERM-1} again, not a raw IRI they cannot re-type.
 * This adapter is the gate into the bounded-context hexagon, not part of its core, so it may
 * borrow a driving port of a <em>different</em> hexagon ({@link ResolveTerms}, owned by
 * ubiquitous-language) to answer that purely for display - the bounded-context core itself still
 * never depends on {@code arknet-ubiquitous-language-core}, and {@code bc_link_term}'s own write
 * path still resolves via the decoupled {@code TermLookup} out-port. {@link #format} always calls
 * {@link ResolveTerms#getById} exactly once per rendering, batched across every {@link TermRef}
 * involved; an id {@link ResolveTerms} could not resolve simply falls back to the bare IRI -
 * {@link #format} never throws and never drops a term.</p>
 *
 * <p><strong>Project (resolved per call).</strong> Every in-port takes a
 * {@link ProjectId} routing key. arknet-mcp runs as one shared server for every
 * workspace on the machine (issue #137), so there is no single injected workspace any
 * more: each tool call resolves its own workspace from the request's origin directory,
 * carried in the MCP transport context under {@link ProjectResolver#ANCHOR_KEY}.
 * The framework hands this adapter that context as an {@link McpSyncRequestContext}
 * parameter - a framework type, excluded from the generated tool input schema, so it is
 * not a caller-facing argument. The concrete resolution (git top-level, slugging,
 * explicit-id override) stays behind {@link ProjectResolver} in the composition root.</p>
 */
public final class BoundedContextMcpTools {

    private final AddBoundedContext addBoundedContext;
    private final ListBoundedContexts listBoundedContexts;
    private final GetBoundedContext getBoundedContext;
    private final LinkTerm linkTerm;
    private final ResolveTerms resolveTerms;
    private final ProjectResolver projects;

    /**
     * Creates the adapter with its four driving in-ports, the borrowed ubiquitous-language display
     * port and the resolver that maps each call's origin directory to a project.
     *
     * @param addBoundedContext   in-port backing {@code bc_add}
     * @param listBoundedContexts in-port backing {@code bc_list}
     * @param getBoundedContext   in-port backing {@code bc_get}
     * @param linkTerm            in-port backing {@code bc_link_term}
     * @param resolveTerms        ubiquitous-language driving port used only to render a linked
     *                            term's business code instead of its bare IRI
     * @param projects          resolves each call's target project from its origin directory
     */
    public BoundedContextMcpTools(
            final AddBoundedContext addBoundedContext,
            final ListBoundedContexts listBoundedContexts,
            final GetBoundedContext getBoundedContext,
            final LinkTerm linkTerm,
            final ResolveTerms resolveTerms,
            final ProjectResolver projects) {
        this.addBoundedContext = Objects.requireNonNull(addBoundedContext, "addBoundedContext");
        this.listBoundedContexts = Objects.requireNonNull(listBoundedContexts, "listBoundedContexts");
        this.getBoundedContext = Objects.requireNonNull(getBoundedContext, "getBoundedContext");
        this.linkTerm = Objects.requireNonNull(linkTerm, "linkTerm");
        this.resolveTerms = Objects.requireNonNull(resolveTerms, "resolveTerms");
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

    @McpTool(name = "bc_add", description = "Register a new DDD bounded context (an explicit "
            + "semantic boundary within which a domain model is consistent).")
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The context's human-readable name, e.g. OrderManagement")
            final String name,
            @McpToolParam(description = "One sentence stating what this context does and why it exists "
                    + "(min. 10 characters)")
            final String domainVision,
            @McpToolParam(description = "Strategic subdomain classification (optional): CORE_DOMAIN, "
                    + "SUPPORTING_DOMAIN or GENERIC_DOMAIN", required = false)
            final String subdomain,
            @McpToolParam(description = "Owning team name (optional)", required = false)
            final String ownedBy,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final Subdomain subdomainValue = blankToNull(subdomain) == null
                ? null
                : Subdomain.valueOf(subdomain.trim());
        final BoundedContext created = addBoundedContext.add(projectId,
                new NewBoundedContext(name, domainVision, subdomainValue, blankToNull(ownedBy)));
        return format(projectId, created);
    }

    @McpTool(name = "bc_list", description = "List all managed bounded contexts.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final List<BoundedContext> all = listBoundedContexts.list(projectId);
        if (all.isEmpty()) {
            return "(no bounded contexts)";
        }
        // One batch resolution across every context's linked terms, not one per context.
        final Map<ResourceId, ResolvedTerm> termsById = resolveTermsFor(projectId, all);
        return all.stream().map(bc -> format(bc, termsById))
                .reduce((a, b) -> a + "\n" + b).orElse("(no bounded contexts)");
    }

    @McpTool(name = "bc_get", description = "Fetch a single bounded context by its identity (e.g. BC-1).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Bounded-context identity, e.g. BC-1") final String id,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final BoundedContextCode code = new BoundedContextCode(id);
        return getBoundedContext.get(projectId, code)
                .map(bc -> format(projectId, bc))
                .orElse("Bounded context not found: " + code.value());
    }

    @McpTool(name = "bc_link_term",
            description = "Link a bounded context to a glossary term of the ubiquitous language it "
                    + "names. The term must already exist (create it with term_add first). Linking the "
                    + "same term twice is a no-op.")
    public String linkTerm(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Bounded-context identity, e.g. BC-1") final String bcId,
            @McpToolParam(description = "Term code, e.g. TERM-1 (the term's business code, resolved "
                    + "against the glossary - not its skos:prefLabel or its store IRI)")
            final String termId,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final BoundedContext updated =
                linkTerm.linkTerm(projectId, new BoundedContextCode(bcId), termId);
        return format(projectId, updated);
    }

    /** Renders a single bounded context, resolving its own linked terms in one batch call. */
    private String format(final ProjectId projectId, final BoundedContext bc) {
        return format(bc, resolveTermsFor(projectId, List.of(bc)));
    }

    /**
     * Renders {@code bc} using an already-resolved {@code termsById} lookup - never itself calls
     * {@link ResolveTerms}, so callers control the batching (one call for a single context, one
     * call total for {@code bc_list}). Never throws: a {@link TermRef} missing from
     * {@code termsById} (unresolvable, or simply not looked up) falls back to its bare IRI.
     */
    private static String format(final BoundedContext bc, final Map<ResourceId, ResolvedTerm> termsById) {
        final String subdomain = bc.subdomain() == null ? "" : " {" + bc.subdomain() + "}";
        final String ownedBy = bc.ownedBy() == null ? "" : " <" + bc.ownedBy() + ">";
        final String terms = bc.usesTerms().isEmpty()
                ? ""
                : " [terms: " + bc.usesTerms().stream().map(ref -> renderTerm(ref, termsById))
                        .reduce((a, b) -> a + ", " + b).orElse("") + "]";
        return "%s %s (%s)%s%s%s".formatted(
                bc.code().value(), bc.name(), bc.domainVision(), subdomain, ownedBy, terms);
    }

    /** Renders one term reference: its resolved business code, or its bare IRI as a fallback. */
    private static String renderTerm(final TermRef ref, final Map<ResourceId, ResolvedTerm> termsById) {
        final ResolvedTerm term = termsById.get(ref.value());
        return term != null ? term.code().value() : ref.value().value();
    }

    /**
     * Batch-resolves every term referenced by {@code boundedContexts} in exactly one call to
     * {@link ResolveTerms#getById} - the union of all their {@link TermRef}s, deduplicated, not
     * one call per context and not one call per {@link TermRef}. Missing ids are simply absent
     * from the returned map, which {@link #renderTerm} treats as "fall back to the IRI". The merge
     * function keeps the first entry for a duplicate key rather than throwing, so a
     * {@link ResolveTerms} implementation returning two {@link ResolvedTerm}s for one identity
     * cannot turn a display concern into a thrown exception.
     */
    private Map<ResourceId, ResolvedTerm> resolveTermsFor(
            final ProjectId projectId, final List<BoundedContext> boundedContexts) {
        final ResourceId[] ids = boundedContexts.stream()
                .flatMap(bc -> bc.usesTerms().stream())
                .map(TermRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveTerms.getById(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedTerm::id, t -> t, (first, second) -> first));
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
