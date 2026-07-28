// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.kernel.WorkspaceResolver;
import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirementSchema;
import de.hauschel.arknet.req.application.port.in.LinkTerm;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.SetRequirementStatus;
import de.hauschel.arknet.req.application.port.in.UpdateRequirement;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementSchemaTerm;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;

/**
 * Driving (in) adapter of the requirements component: exposes the requirement
 * use-cases as MCP tools ({@code req_add}, {@code req_list}, {@code req_get},
 * {@code req_set_status}, {@code req_link_term}, {@code req_update}, {@code req_schema}) and
 * delegates each tool call to the corresponding in-port.
 *
 * <p>This adapter belongs to the requirements hexagon (symmetric to the out-adapter
 * {@code arknet-requirements-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods - the tool name, description
 * and JSON input schema are derived from the annotations and method signature, not
 * hand-written. This adapter does <strong>not</strong> bootstrap an MCP server or wire
 * any transport; that remains the concern of the composition root (arknet-mcp).</p>
 *
 * <p><strong>Registration.</strong> The composition root (arknet-mcp, a Spring Boot MCP
 * server on Spring AI 2.0) declares this class as a bean; the Spring AI MCP annotation
 * scanner discovers the {@code @McpTool} methods and registers them automatically. No
 * manual tool-specification bridge is needed - the earlier {@code tools()} adapter (which
 * pre-built {@code SyncToolSpecification}s for a raw-SDK composition root) was removed
 * with the arknet-mcp migration (#27).</p>
 *
 * <p><strong>Identity vs. code.</strong> Every tool takes a requirement identity as a plain
 * {@code String} - what a human types, e.g. {@code FR-1} - and maps it to a
 * {@link RequirementCode}, never to the opaque {@link de.hauschel.arknet.req.domain.RequirementId}.
 * The identity itself is a store-internal detail that never needs to cross the MCP boundary;
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
 *
 * <p><strong>Term display resolution (issue #77 nachtrag).</strong> {@link TermRef} carries a
 * linked term's opaque subject identity, not its business code - but a human who typed
 * {@code TERM-1} into {@code req_link_term} expects to see {@code TERM-1} again, not a raw IRI
 * they cannot re-type. This adapter is the gate into the requirements hexagon, not part of its
 * core, so it may borrow a driving port of a <em>different</em> hexagon
 * ({@link ResolveTerms}, owned by ubiquitous-language) to answer that purely for display - the
 * requirements core itself still never depends on {@code arknet-ubiquitous-language-core}, and
 * {@code req_link_term}'s own write path still resolves via the decoupled {@code TermLookup}
 * out-port. {@link #format} always calls {@link ResolveTerms#getById} exactly once per
 * rendering, batched across every {@link TermRef} involved (never once per {@link TermRef}, and
 * for {@code req_list} never once per requirement); an id {@link ResolveTerms} could not resolve
 * simply falls back to the bare IRI - {@link #format} never throws and never drops a term.</p>
 */
public final class RequirementMcpTools {

    private final AddRequirement addRequirement;
    private final ListRequirements listRequirements;
    private final GetRequirement getRequirement;
    private final SetRequirementStatus setRequirementStatus;
    private final LinkTerm linkTerm;
    private final UpdateRequirement updateRequirement;
    private final GetRequirementSchema getRequirementSchema;
    private final ResolveTerms resolveTerms;
    private final WorkspaceResolver workspaces;

    /**
     * Creates the adapter with its seven driving in-ports, the borrowed ubiquitous-language
     * display port and the resolver that maps each call's origin directory to a workspace.
     *
     * @param addRequirement        in-port backing {@code req_add}
     * @param listRequirements      in-port backing {@code req_list}
     * @param getRequirement        in-port backing {@code req_get}
     * @param setRequirementStatus  in-port backing {@code req_set_status}
     * @param linkTerm              in-port backing {@code req_link_term}
     * @param updateRequirement     in-port backing {@code req_update}
     * @param getRequirementSchema  in-port backing {@code req_schema}
     * @param resolveTerms          ubiquitous-language driving port used only to render a linked
     *                              term's business code instead of its bare IRI
     * @param workspaces            resolves each call's target workspace from its origin directory
     */
    public RequirementMcpTools(
            final AddRequirement addRequirement,
            final ListRequirements listRequirements,
            final GetRequirement getRequirement,
            final SetRequirementStatus setRequirementStatus,
            final LinkTerm linkTerm,
            final UpdateRequirement updateRequirement,
            final GetRequirementSchema getRequirementSchema,
            final ResolveTerms resolveTerms,
            final WorkspaceResolver workspaces) {
        this.addRequirement = Objects.requireNonNull(addRequirement, "addRequirement");
        this.listRequirements = Objects.requireNonNull(listRequirements, "listRequirements");
        this.getRequirement = Objects.requireNonNull(getRequirement, "getRequirement");
        this.setRequirementStatus = Objects.requireNonNull(setRequirementStatus, "setRequirementStatus");
        this.linkTerm = Objects.requireNonNull(linkTerm, "linkTerm");
        this.updateRequirement = Objects.requireNonNull(updateRequirement, "updateRequirement");
        this.getRequirementSchema = Objects.requireNonNull(getRequirementSchema, "getRequirementSchema");
        this.resolveTerms = Objects.requireNonNull(resolveTerms, "resolveTerms");
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

    @McpTool(name = "req_add", description = "Register a new requirement (functional or non-functional).")
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Short human-readable summary of the requirement") final String title,
            @McpToolParam(description = "The normative statement, e.g. 'The system shall ...'")
            final String description,
            @McpToolParam(description = "Classification: FUNCTIONAL or NON_FUNCTIONAL") final String type,
            @McpToolParam(description = "Testable 'Done when ...' criteria (at least one) that make this "
                    + "requirement's completion checkable")
            final List<String> acceptanceCriteria,
            @McpToolParam(description = "MoSCoW priority (optional): MUST_HAVE, SHOULD_HAVE, COULD_HAVE or "
                    + "WONT_HAVE", required = false)
            final String priority,
            @McpToolParam(description = "IRI of the arkreq:Goal this requirement is motivated by (optional)",
                    required = false)
            final String motivatedBy,
            @McpToolParam(description = "Free-text quality category (optional, e.g. performance, security, "
                    + "reliability); only meaningful for NON_FUNCTIONAL requirements", required = false)
            final String qualityCategory) {
        final WorkspaceId workspaceId = workspaces.resolve(originDir(context));
        final RequirementType requirementType = RequirementType.valueOf(type);
        final Priority requirementPriority = blankToNull(priority) == null
                ? null
                : Priority.valueOf(priority.trim());
        final Requirement created = addRequirement.add(workspaceId,
                new NewRequirement(title, description, requirementType, requirementPriority,
                        blankToNull(motivatedBy), blankToNull(qualityCategory),
                        acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria)));
        return format(workspaceId, created);
    }

    @McpTool(name = "req_list", description = "List all managed requirements.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(final McpSyncRequestContext context) {
        final WorkspaceId workspaceId = workspaces.resolve(originDir(context));
        final List<Requirement> all = listRequirements.list(workspaceId);
        if (all.isEmpty()) {
            return "(no requirements)";
        }
        // One batch resolution across every requirement's linked terms, not one per requirement.
        final Map<ResourceId, ResolvedTerm> termsById = resolveTermsFor(workspaceId, all);
        return all.stream().map(r -> format(r, termsById))
                .reduce((a, b) -> a + "\n" + b).orElse("(no requirements)");
    }

    @McpTool(name = "req_get", description = "Fetch a single requirement by its identity (e.g. FR-1, NFR-7).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Requirement identity, e.g. FR-1 or NFR-7") final String id) {
        final WorkspaceId workspaceId = workspaces.resolve(originDir(context));
        final RequirementCode code = new RequirementCode(id);
        return getRequirement.get(workspaceId, code)
                .map(r -> format(workspaceId, r))
                .orElse("Requirement not found: " + code.value());
    }

    @McpTool(name = "req_set_status", description = "Change the lifecycle status of a requirement.")
    public String setStatus(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Requirement identity, e.g. FR-1 or NFR-7") final String id,
            @McpToolParam(description = "Target status: PROPOSED or ACCEPTED") final String status) {
        final WorkspaceId workspaceId = workspaces.resolve(originDir(context));
        final RequirementCode code = new RequirementCode(id);
        final RequirementStatus requirementStatus = RequirementStatus.valueOf(status);
        final Requirement updated =
                setRequirementStatus.setStatus(workspaceId, code, requirementStatus);
        return format(workspaceId, updated);
    }

    @McpTool(name = "req_link_term",
            description = "Link a requirement to a glossary term of the ubiquitous language it uses. "
                    + "The term must already exist (create it with term_add first). Linking the same "
                    + "term twice is a no-op.")
    public String linkTerm(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Requirement identity, e.g. FR-1 or NFR-7") final String reqId,
            @McpToolParam(description = "Term code, e.g. TERM-1 (the term's business code, resolved "
                    + "against the glossary - not its skos:prefLabel or its store IRI)")
            final String termId) {
        final WorkspaceId workspaceId = workspaces.resolve(originDir(context));
        final Requirement updated =
                linkTerm.linkTerm(workspaceId, new RequirementCode(reqId), termId);
        return format(workspaceId, updated);
    }

    @McpTool(name = "req_update",
            description = "Correct an already-created requirement's title, description, acceptance "
                    + "criteria and/or MoSCoW priority. Every argument is optional - an omitted one leaves "
                    + "that field unchanged; an omitted priority never removes an already-set one. "
                    + "Does not touch status (use req_set_status) or linked terms (use req_link_term).")
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Requirement identity, e.g. FR-1 or NFR-7") final String id,
            @McpToolParam(description = "New short human-readable summary (optional, unchanged if omitted)",
                    required = false)
            final String title,
            @McpToolParam(description = "New normative statement, e.g. 'The system shall ...' (optional, "
                    + "unchanged if omitted)", required = false)
            final String description,
            @McpToolParam(description = "New testable 'Done when ...' criteria, replacing the existing ones "
                    + "wholesale (optional, unchanged if omitted)", required = false)
            final List<String> acceptanceCriteria,
            @McpToolParam(description = "New MoSCoW priority: MUST_HAVE, SHOULD_HAVE, COULD_HAVE or "
                    + "WONT_HAVE (optional, unchanged if omitted - omitting it cannot clear a priority "
                    + "that is already set)", required = false)
            final String priority) {
        final WorkspaceId workspaceId = workspaces.resolve(originDir(context));
        final RequirementCode code = new RequirementCode(id);
        final Priority requirementPriority = blankToNull(priority) == null
                ? null
                : Priority.valueOf(priority.trim());
        final Requirement updated = updateRequirement.update(workspaceId, code, blankToNull(title),
                blankToNull(description), acceptanceCriteria == null ? null : List.copyOf(acceptanceCriteria),
                requirementPriority);
        return format(workspaceId, updated);
    }

    @McpTool(name = "req_schema",
            description = "Describe the arkreq: requirement vocabulary as data: for RequirementType, "
                    + "RequirementStatus and Priority, its ontology-sourced definition and the exact "
                    + "values req_add/req_set_status accept - so a client does not have to guess them.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String schema() {
        return getRequirementSchema.schema().stream()
                .map(RequirementMcpTools::formatSchemaTerm)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    /** Renders a single requirement, resolving its own linked terms in one batch call. */
    private String format(final WorkspaceId workspaceId, final Requirement r) {
        return format(r, resolveTermsFor(workspaceId, List.of(r)));
    }

    /**
     * Renders {@code r} using an already-resolved {@code termsById} lookup - never itself calls
     * {@link ResolveTerms}, so callers control the batching (one call for a single requirement,
     * one call total for {@code req_list}). Never throws: a {@link TermRef} missing from
     * {@code termsById} (unresolvable, or simply not looked up) falls back to its bare IRI.
     */
    private static String format(final Requirement r, final Map<ResourceId, ResolvedTerm> termsById) {
        final String priority = r.priority() == null ? "" : " {" + r.priority() + "}";
        final String terms = r.usesTerms().isEmpty()
                ? ""
                : " [terms: " + r.usesTerms().stream().map(ref -> renderTerm(ref, termsById))
                        .reduce((a, b) -> a + ", " + b).orElse("") + "]";
        final String criteria = " [done when: " + String.join("; ", r.acceptanceCriteria()) + "]";
        return "%s [%s] %s (%s)%s%s%s".formatted(
                r.code().value(), r.type(), r.title(), r.status(), priority, terms, criteria);
    }

    /** Renders one schema term as {@code term: definition (values: A, B, ...)}. */
    private static String formatSchemaTerm(final RequirementSchemaTerm t) {
        return "%s: %s (values: %s)".formatted(t.term(), t.definition(), String.join(", ", t.values()));
    }

    /** Renders one term reference: its resolved business code, or its bare IRI as a fallback. */
    private static String renderTerm(final TermRef ref, final Map<ResourceId, ResolvedTerm> termsById) {
        final ResolvedTerm term = termsById.get(ref.value());
        return term != null ? term.code().value() : ref.value().value();
    }

    /**
     * Batch-resolves every term referenced by {@code requirements} in exactly one call to
     * {@link ResolveTerms#getById} - the union of all their {@link TermRef}s, deduplicated, not
     * one call per requirement and not one call per {@link TermRef}. Missing ids are simply
     * absent from the returned map, which {@link #renderTerm} treats as "fall back to the IRI".
     *
     * <p><strong>Structurally cannot throw on a duplicate key (issue #77, second nachtrag).</strong>
     * {@link ResolveTerms} promises at most one {@link ResolvedTerm} per identity, but this method
     * must not rely on every implementation upholding that: a plain {@code Collectors.toMap(t ->
     * t.id(), t -> t)} throws {@code IllegalStateException} the moment two returned
     * {@link ResolvedTerm}s share an identity, turning a display concern into a thrown exception -
     * the very thing this rendering path exists to avoid. The merge function below keeps the
     * first entry for a duplicate key instead; which one is kept is immaterial here, since
     * rendering only ever reads {@link ResolvedTerm#code()} and any legitimate duplicate (e.g. a
     * store-first term with more than one {@code dcterms:identifier}, see
     * {@code KognioRdfTermRepository#findByIds}) carries the same code on every row.</p>
     */
    private Map<ResourceId, ResolvedTerm> resolveTermsFor(
            final WorkspaceId workspaceId, final List<Requirement> requirements) {
        final ResourceId[] ids = requirements.stream()
                .flatMap(r -> r.usesTerms().stream())
                .map(TermRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveTerms.getById(workspaceId, ids).stream()
                .collect(Collectors.toMap(ResolvedTerm::id, t -> t, (first, second) -> first));
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
