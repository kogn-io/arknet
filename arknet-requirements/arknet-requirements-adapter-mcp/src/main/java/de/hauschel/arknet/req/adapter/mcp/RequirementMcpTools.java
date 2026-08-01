// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.mcp;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.req.application.port.in.AcceptRequirement;
import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirementSchema;
import de.hauschel.arknet.req.application.port.in.LinkTerm;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.UpdateRequirement;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
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
 * with the arknet-mcp migration.</p>
 *
 * <p><strong>Identity vs. code.</strong> Every tool takes a requirement identity as a plain
 * {@code String} - what a human types, e.g. {@code FR-1} - and maps it to a
 * {@link RequirementCode}, never to the opaque {@link de.hauschel.arknet.req.domain.RequirementId}.
 * The identity itself is a store-internal detail that never needs to cross the MCP boundary;
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
 *
 * <p><strong>Rendering.</strong> This class only dispatches tool calls to their in-port and
 * turns the result into the returned string via {@link RequirementPresenter} - it holds no
 * rendering logic of its own. See {@link RequirementPresenter} for the term
 * display resolution that borrows {@link ResolveTerms} purely for
 * display.</p>
 */
public final class RequirementMcpTools {

    private final AddRequirement addRequirement;
    private final ListRequirements listRequirements;
    private final GetRequirement getRequirement;
    private final AcceptRequirement acceptRequirement;
    private final LinkTerm linkTerm;
    private final UpdateRequirement updateRequirement;
    private final GetRequirementSchema getRequirementSchema;
    private final ProjectResolver projects;
    private final RequirementPresenter presenter;

    /**
     * Creates the adapter with its seven driving in-ports, the borrowed ubiquitous-language
     * display port and the resolver that maps each call's origin directory to a project.
     *
     * @param addRequirement        in-port backing {@code req_add}
     * @param listRequirements      in-port backing {@code req_list}
     * @param getRequirement        in-port backing {@code req_get}
     * @param acceptRequirement     in-port backing {@code req_set_status}
     * @param linkTerm              in-port backing {@code req_link_term}
     * @param updateRequirement     in-port backing {@code req_update}
     * @param getRequirementSchema  in-port backing {@code req_schema}
     * @param resolveTerms          ubiquitous-language driving port used only to render a linked
     *                              term's business code instead of its bare IRI
     * @param projects            resolves each call's target project from its origin directory
     */
    public RequirementMcpTools(
            final AddRequirement addRequirement,
            final ListRequirements listRequirements,
            final GetRequirement getRequirement,
            final AcceptRequirement acceptRequirement,
            final LinkTerm linkTerm,
            final UpdateRequirement updateRequirement,
            final GetRequirementSchema getRequirementSchema,
            final ResolveTerms resolveTerms,
            final ProjectResolver projects) {
        this.addRequirement = Objects.requireNonNull(addRequirement, "addRequirement");
        this.listRequirements = Objects.requireNonNull(listRequirements, "listRequirements");
        this.getRequirement = Objects.requireNonNull(getRequirement, "getRequirement");
        this.acceptRequirement = Objects.requireNonNull(acceptRequirement, "acceptRequirement");
        this.linkTerm = Objects.requireNonNull(linkTerm, "linkTerm");
        this.updateRequirement = Objects.requireNonNull(updateRequirement, "updateRequirement");
        this.getRequirementSchema = Objects.requireNonNull(getRequirementSchema, "getRequirementSchema");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.presenter = new RequirementPresenter(resolveTerms);
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
            final String qualityCategory,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final RequirementType requirementType = RequirementType.valueOf(type);
        final Priority requirementPriority = blankToNull(priority) == null
                ? null
                : Priority.valueOf(priority.trim());
        final Requirement created = addRequirement.add(projectId,
                new NewRequirement(title, description, requirementType, requirementPriority,
                        blankToNull(motivatedBy), blankToNull(qualityCategory),
                        acceptanceCriteria == null ? List.of() : List.copyOf(acceptanceCriteria)));
        return presenter.format(projectId, created);
    }

    @McpTool(name = "req_list", description = "List all managed requirements.",
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
        final List<Requirement> all = listRequirements.list(projectId);
        if (all.isEmpty()) {
            return "(no requirements)";
        }
        // One batch resolution across every requirement's linked terms, not one per requirement.
        final Map<ResourceId, ResolvedTerm> termsById = presenter.resolveTermsFor(projectId, all);
        return all.stream().map(r -> presenter.format(r, termsById))
                .reduce((a, b) -> a + "\n" + b).orElse("(no requirements)");
    }

    @McpTool(name = "req_get", description = "Fetch a single requirement by its identity (e.g. FR-1, NFR-7).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Requirement identity, e.g. FR-1 or NFR-7") final String id,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final RequirementCode code = new RequirementCode(id);
        return getRequirement.get(projectId, code)
                .map(r -> presenter.format(projectId, r))
                .orElse("Requirement not found: " + code.value());
    }

    @McpTool(name = "req_set_status", description = "Change the lifecycle status of a requirement.")
    public String accept(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Requirement identity, e.g. FR-1 or NFR-7") final String id,
            @McpToolParam(description = "Target status: PROPOSED or ACCEPTED") final String status,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final RequirementCode code = new RequirementCode(id);
        // The requirements lifecycle permits exactly one transition: the tool's
        // external "status" parameter is kept for API stability, but AcceptRequirement itself no
        // longer takes a target status - only ACCEPTED can ever legally result from this call.
        final RequirementStatus requirementStatus = RequirementStatus.valueOf(status);
        if (requirementStatus != RequirementStatus.ACCEPTED) {
            throw new IllegalArgumentException(
                    "req_set_status only supports transitioning a requirement to ACCEPTED, not "
                            + requirementStatus);
        }
        final Requirement updated = acceptRequirement.accept(projectId, code);
        return presenter.format(projectId, updated);
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
            final String termId,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final Requirement updated =
                linkTerm.linkTerm(projectId, new RequirementCode(reqId), termId);
        return presenter.format(projectId, updated);
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
            final String priority,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final RequirementCode code = new RequirementCode(id);
        final Priority requirementPriority = blankToNull(priority) == null
                ? null
                : Priority.valueOf(priority.trim());
        final Requirement updated = updateRequirement.update(projectId, code, blankToNull(title),
                blankToNull(description), acceptanceCriteria == null ? null : List.copyOf(acceptanceCriteria),
                requirementPriority);
        return presenter.format(projectId, updated);
    }

    @McpTool(name = "req_schema",
            description = "Describe the arkreq: requirement vocabulary as data: for RequirementType, "
                    + "RequirementStatus and Priority, its ontology-sourced definition and the exact "
                    + "values req_add/req_set_status accept - so a client does not have to guess them.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String schema() {
        return getRequirementSchema.schema().stream()
                .map(presenter::formatSchemaTerm)
                .reduce((a, b) -> a + "\n" + b)
                .orElse("");
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
