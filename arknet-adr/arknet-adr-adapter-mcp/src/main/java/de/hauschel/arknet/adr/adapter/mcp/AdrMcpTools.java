// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.mcp;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.adr.application.port.in.AcceptAdr;
import de.hauschel.arknet.adr.application.port.in.AddAdr;
import de.hauschel.arknet.adr.application.port.in.AddAdr.NewAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.DeprecateAdr;
import de.hauschel.arknet.adr.application.port.in.GetAdr;
import de.hauschel.arknet.adr.application.port.in.ListAdrs;
import de.hauschel.arknet.adr.application.port.in.RejectAdr;
import de.hauschel.arknet.adr.application.port.in.SupersedeAdr;
import de.hauschel.arknet.adr.application.port.in.UpdateAdr;
import de.hauschel.arknet.adr.application.port.in.UpdateAdr.AdrCorrection;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts.ResolvedBoundedContext;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;

/**
 * Driving (in) adapter of the ADR component: exposes the architecture-decision use cases as MCP
 * tools ({@code adr_add}, {@code adr_list}, {@code adr_get}, {@code adr_update},
 * {@code adr_set_status}, {@code adr_supersede}) and delegates each tool call to the corresponding
 * in-port.
 *
 * <p>This adapter belongs to the ADR hexagon (symmetric to the out-adapter
 * {@code arknet-adr-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods - the tool name, description and JSON input
 * schema are derived from the annotations and method signature, not hand-written. This adapter does
 * <strong>not</strong> bootstrap an MCP server or wire any transport; that remains the concern of
 * the composition root (arknet-mcp).</p>
 *
 * <p><strong>Identity vs. code.</strong> Every tool takes an ADR identity as a plain {@code String} -
 * what a human types, e.g. {@code ADR-1} - and maps it to an {@link AdrCode}, never to the opaque
 * {@link de.hauschel.arknet.adr.domain.AdrId}. The identity itself is a store-internal detail that
 * never needs to cross the MCP boundary; responses render the code back to the caller, not the
 * underlying resource identity.</p>
 *
 * <p><strong>Reference display resolution (ADR-008).</strong> {@link RequirementRef}/
 * {@link BoundedContextRef} carry a referenced resource's opaque subject identity, not its business
 * code - but a human who typed {@code FR-1} into {@code adr_add} expects to see {@code FR-1} again,
 * not a raw IRI they cannot re-type. This adapter is the gate into the ADR hexagon, not part of its
 * core, so it may borrow driving ports of <em>different</em> hexagons ({@link ResolveRequirements},
 * owned by requirements; {@link ResolveBoundedContexts}, owned by bounded-context) to answer that
 * purely for display - the ADR core itself still never depends on those modules, and
 * {@code adr_add}'s own write path still resolves via the decoupled {@code RequirementLookup}/
 * {@code BoundedContextLookup} out-ports. Every rendering calls each borrowed port at most once,
 * batched across every reference involved; an id a port could not resolve simply falls back to the
 * bare IRI - {@link #format} never throws and never drops a reference. The two self-referential
 * relations, {@code supersedes} and {@code relatedTo}, need no borrowing at all: they point back
 * into this very hexagon, so the application service resolves them and hands the codes over in
 * {@link AdrDetail} - {@code relatedTo} already merged into the one list a symmetric relation
 * deserves rather than split into two directions.</p>
 *
 * <p><strong>Project (resolved per call).</strong> Every in-port takes a {@link ProjectId} routing
 * key. arknet-mcp runs as one shared server for every project on the machine, so there is no single
 * injected project: each tool call resolves its own project from the request's anchor, carried in
 * the MCP transport context under {@link ProjectResolver#ANCHOR_KEY}. The framework hands this
 * adapter that context as an {@link McpSyncRequestContext} parameter - a framework type, excluded
 * from the generated tool input schema, so it is not a caller-facing argument. The anchor is looked
 * up in the project registry (ADR-016): it arrives opaque, is matched whole against what was
 * registered, and either hits exactly one project or fails with an error message naming the possible
 * remedies.</p>
 */
public final class AdrMcpTools {

    private static final String PROJECT_ANCHOR_DESCRIPTION =
            "Optional anchor identifying the project this call targets, used INSTEAD of the anchor "
                    + "your transport sends in the X-Arknet-Project-Anchor header. Only needed for a "
                    + "client that cannot set that header - most callers should omit this and let "
                    + "their transport identify the project. Must be an anchor already registered for "
                    + "the project; project_list shows what is registered.";

    private final AddAdr addAdr;
    private final ListAdrs listAdrs;
    private final GetAdr getAdr;
    private final UpdateAdr updateAdr;
    private final AcceptAdr acceptAdr;
    private final RejectAdr rejectAdr;
    private final DeprecateAdr deprecateAdr;
    private final SupersedeAdr supersedeAdr;
    private final ResolveRequirements resolveRequirements;
    private final ResolveBoundedContexts resolveBoundedContexts;
    private final ProjectResolver projects;

    /**
     * Creates the adapter with its eight driving in-ports, the two borrowed display ports and the
     * resolver that maps each call's anchor to a project.
     *
     * @param addAdr                 in-port backing {@code adr_add}
     * @param listAdrs               in-port backing {@code adr_list}
     * @param getAdr                 in-port backing {@code adr_get}
     * @param updateAdr              in-port backing {@code adr_update}
     * @param acceptAdr              in-port backing {@code adr_set_status}'s {@code ACCEPTED} target
     * @param rejectAdr              in-port backing {@code adr_set_status}'s {@code REJECTED} target
     * @param deprecateAdr           in-port backing {@code adr_set_status}'s {@code DEPRECATED}
     *                               target
     * @param supersedeAdr           in-port backing {@code adr_supersede}
     * @param resolveRequirements    requirements driving port used only to render an addressed
     *                               requirement's business code instead of its bare IRI
     * @param resolveBoundedContexts bounded-context driving port used only to render an affected
     *                               context's business code instead of its bare IRI
     * @param projects               resolves each call's target project from its anchor
     */
    public AdrMcpTools(
            final AddAdr addAdr,
            final ListAdrs listAdrs,
            final GetAdr getAdr,
            final UpdateAdr updateAdr,
            final AcceptAdr acceptAdr,
            final RejectAdr rejectAdr,
            final DeprecateAdr deprecateAdr,
            final SupersedeAdr supersedeAdr,
            final ResolveRequirements resolveRequirements,
            final ResolveBoundedContexts resolveBoundedContexts,
            final ProjectResolver projects) {
        this.addAdr = Objects.requireNonNull(addAdr, "addAdr");
        this.listAdrs = Objects.requireNonNull(listAdrs, "listAdrs");
        this.getAdr = Objects.requireNonNull(getAdr, "getAdr");
        this.updateAdr = Objects.requireNonNull(updateAdr, "updateAdr");
        this.acceptAdr = Objects.requireNonNull(acceptAdr, "acceptAdr");
        this.rejectAdr = Objects.requireNonNull(rejectAdr, "rejectAdr");
        this.deprecateAdr = Objects.requireNonNull(deprecateAdr, "deprecateAdr");
        this.supersedeAdr = Objects.requireNonNull(supersedeAdr, "supersedeAdr");
        this.resolveRequirements = Objects.requireNonNull(resolveRequirements, "resolveRequirements");
        this.resolveBoundedContexts = Objects.requireNonNull(resolveBoundedContexts, "resolveBoundedContexts");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /**
     * Extracts the calling client's project anchor from the per-call transport context - the value
     * the server's context extractor placed there off the request header (ADR-016). Null-tolerant on
     * every hop: a call without a context, without a transport context, or without the key resolves
     * to {@code null}, which is a caller error rather than a route to a default.
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
        return projects.resolve(explicit != null ? explicit : contextAnchor(context)).id();
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "adr_add", description = "Record a new architecture decision (context, decision, "
            + "consequences, considered options) as an ADR. It starts out PROPOSED; accept it later "
            + "with adr_set_status. It can already name the requirements it addresses, the bounded "
            + "contexts it affects and the peer decisions it is related to; all three stay "
            + "correctable with adr_update. The assigned code runs ADR-1, ADR-2, ... per project and "
            + "is unrelated to the numbering of any markdown decision records the repository may "
            + "also keep.")
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The decision's title, e.g. 'Use an embedded triple store'")
            final String name,
            @McpToolParam(description = "Why was this decision necessary? Forces and constraints "
                    + "(min. 5 characters)")
            final String adrContext,
            @McpToolParam(description = "What was decided (min. 5 characters)") final String decision,
            @McpToolParam(description = "Positive and negative consequences of the decision (optional)",
                    required = false)
            final String consequences,
            @McpToolParam(description = "Considered but rejected options, with a short rationale each "
                    + "(optional)", required = false)
            final String alternatives,
            @McpToolParam(description = "The day the decision was made, as ISO-8601 yyyy-MM-dd "
                    + "(optional)", required = false)
            final String decisionDate,
            @McpToolParam(description = "Business codes of the requirements this decision addresses, "
                    + "e.g. [\"FR-1\", \"NFR-2\"]. Each must already exist (create it with req_add "
                    + "first). Optional.", required = false)
            final List<String> addressesRequirements,
            @McpToolParam(description = "Business codes of the bounded contexts this decision affects, "
                    + "e.g. [\"BC-1\"]. Each must already exist (create it with bc_add first). "
                    + "Optional.", required = false)
            final List<String> affectsContexts,
            @McpToolParam(description = "Business codes of other decisions this one is related to "
                    + "('see also'), e.g. [\"ADR-3\"]. Each must already exist and must not be this "
                    + "decision itself. The relation reads both ways for a reader, but only this "
                    + "direction is stored - so a decision recorded later names the earlier one, and "
                    + "adr_update completes the link the other way round when needed. Optional.",
                    required = false)
            final List<String> relatedTo,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final AdrDetail created = addAdr.add(projectId, new NewAdr(name, adrContext, decision,
                blankToNull(consequences), blankToNull(alternatives), parseDate(decisionDate),
                addressesRequirements, affectsContexts, relatedTo));
        return format(projectId, created);
    }

    @McpTool(name = "adr_list", description = "List all recorded architecture decisions, one compact "
            + "line each (code, status, title, and the codes it addresses/affects/supersedes/is "
            + "superseded by/is related to). Use adr_get for a decision's full text.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final List<AdrDetail> all = listAdrs.list(projectId);
        if (all.isEmpty()) {
            return "(no ADRs)";
        }
        // One batch resolution per borrowed port across every decision, not one per decision.
        final Map<ResourceId, ResolvedRequirement> requirements = resolveRequirementsFor(projectId, all);
        final Map<ResourceId, ResolvedBoundedContext> contexts = resolveContextsFor(projectId, all);
        return all.stream()
                .map(detail -> summaryLine(detail, requirements, contexts))
                .collect(Collectors.joining("\n"));
    }

    @McpTool(name = "adr_get", description = "Fetch a single architecture decision by its identity "
            + "(e.g. ADR-1), including its full context/decision/consequences text, both "
            + "directions of the supersedes relation, and every decision it is related to.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "ADR identity, e.g. ADR-1") final String id,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final AdrCode code = new AdrCode(id);
        return getAdr.get(projectId, code)
                .map(detail -> format(projectId, detail))
                .orElse("ADR not found: " + code.value());
    }

    @McpTool(name = "adr_update", description = "Correct an already-recorded architecture decision. "
            + "Every field except the identity is optional - omit (or leave blank) what should stay "
            + "as it is; omitting a field never removes it. The text fields (name, adrContext, "
            + "decision, consequences, alternatives, decisionDate) can only be corrected while the "
            + "decision is PROPOSED: from ACCEPTED on (and likewise REJECTED/DEPRECATED) a text "
            + "change is refused, because a decision in force records what was decided at the time - "
            + "record the correction as a new decision with adr_add and link it with adr_supersede "
            + "instead. The three reference lists are the exception and stay correctable in EVERY "
            + "status, so an edge to a requirement, bounded context or peer decision that did not "
            + "exist yet when the decision was made can still be completed: passing a list replaces "
            + "that relation wholesale, passing an empty list removes every edge of it, omitting it "
            + "leaves it untouched. Status and the supersedes relation are not changed here - use "
            + "adr_set_status and adr_supersede.")
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "ADR identity, e.g. ADR-1") final String id,
            @McpToolParam(description = "The corrected title (optional; unchanged if omitted)",
                    required = false)
            final String name,
            @McpToolParam(description = "The corrected forces and constraints - why the decision was "
                    + "necessary (optional; unchanged if omitted, min. 5 characters)", required = false)
            final String adrContext,
            @McpToolParam(description = "The corrected decision (optional; unchanged if omitted, "
                    + "min. 5 characters)", required = false)
            final String decision,
            @McpToolParam(description = "The corrected consequences (optional; unchanged if omitted - "
                    + "omitting does not remove an already-recorded one)", required = false)
            final String consequences,
            @McpToolParam(description = "The corrected considered but rejected options (optional; "
                    + "unchanged if omitted)", required = false)
            final String alternatives,
            @McpToolParam(description = "The corrected decision date, as ISO-8601 yyyy-MM-dd "
                    + "(optional; unchanged if omitted)", required = false)
            final String decisionDate,
            @McpToolParam(description = "Business codes of the requirements this decision should "
                    + "address going forward, e.g. [\"FR-1\", \"NFR-2\"], replacing the existing ones "
                    + "wholesale. Each must already exist. Pass an empty list to remove all of them; "
                    + "omit to leave them unchanged. Correctable in every status.", required = false)
            final List<String> addressesRequirements,
            @McpToolParam(description = "Business codes of the bounded contexts this decision should "
                    + "affect going forward, e.g. [\"BC-1\"], replacing the existing ones wholesale. "
                    + "Each must already exist. Pass an empty list to remove all of them; omit to "
                    + "leave them unchanged. Correctable in every status.", required = false)
            final List<String> affectsContexts,
            @McpToolParam(description = "Business codes of the decisions this one should be related "
                    + "to going forward, e.g. [\"ADR-3\"], replacing the existing ones wholesale. "
                    + "Each must already exist and none may be this decision itself. Pass an empty "
                    + "list to remove all of them; omit to leave them unchanged. Correctable in "
                    + "every status - this is where a decision names a peer that was recorded after "
                    + "it.", required = false)
            final List<String> relatedTo,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        // Blank collapses to null - "leave this field alone" - for every text field, exactly as in
        // adr_add. The two lists are handed over as they arrive: unlike a blank string, an empty
        // list is a meaningful, distinct instruction here (clear the relation), so it must not be
        // normalised away.
        final AdrCorrection correction = AdrCorrection.builder()
                .name(blankToNull(name))
                .context(blankToNull(adrContext))
                .decision(blankToNull(decision))
                .consequences(blankToNull(consequences))
                .alternatives(blankToNull(alternatives))
                .decisionDate(parseDate(decisionDate))
                .addressesRequirementCodes(addressesRequirements)
                .affectsContextCodes(affectsContexts)
                .relatedToCodes(relatedTo)
                .build();
        return format(projectId, updateAdr.update(projectId, new AdrCode(id), correction));
    }

    @McpTool(name = "adr_set_status", description = "Change the lifecycle status of an architecture "
            + "decision. Supported transitions: PROPOSED -> ACCEPTED, PROPOSED -> REJECTED, and "
            + "ACCEPTED -> DEPRECATED (for a decision that became obsolete without a successor - use "
            + "adr_supersede instead when a newer decision replaces it).")
    public String setStatus(
            final McpSyncRequestContext context,
            @McpToolParam(description = "ADR identity, e.g. ADR-1") final String id,
            @McpToolParam(description = "Target status: ACCEPTED, REJECTED or DEPRECATED")
            final String status,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        // The tool's external "status" parameter mirrors req_set_status's surface, but each of the
        // three transition ports (AcceptAdr/RejectAdr/DeprecateAdr) takes no target status of its
        // own - the caller-visible dispatch happens only here. AdrStatus.valueOf is parsed
        // defensively rather than let directly: PROPOSED is a real enum value that is simply not a
        // legal target of this tool (you never transition into it), and SUPERSEDED is a real
        // ontology value AdrStatus deliberately never implements at all (it stays derived-only from
        // adr_supersede) - both, and anything unparseable, must reject with this method's own
        // message, not the JDK's raw "No enum constant ...".
        final AdrCode code = new AdrCode(id);
        AdrStatus target;
        try {
            target = AdrStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            target = null;
        }
        return switch (target) {
            case ACCEPTED -> format(projectId, acceptAdr.accept(projectId, code));
            case REJECTED -> format(projectId, rejectAdr.reject(projectId, code));
            case DEPRECATED -> format(projectId, deprecateAdr.deprecate(projectId, code));
            case null, default -> throw new IllegalArgumentException(
                    "adr_set_status only supports transitioning an ADR to ACCEPTED, REJECTED or "
                            + "DEPRECATED, not " + status);
        };
    }

    @McpTool(name = "adr_supersede", description = "Record that one architecture decision replaces an "
            + "older one. Both must already exist. Only the forward arkarch:supersedes edge is "
            + "written - the superseded decision reports it as 'superseded by' from a reverse read, "
            + "not from a second stored triple. Recording the same pair twice is a no-op.")
    public String supersede(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The superseding (newer) ADR identity, e.g. ADR-2")
            final String id,
            @McpToolParam(description = "The superseded (older) ADR identity, e.g. ADR-1")
            final String supersededId,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor);
        final AdrDetail updated =
                supersedeAdr.supersede(projectId, new AdrCode(id), new AdrCode(supersededId));
        return format(projectId, updated);
    }

    // --- Rendering ------------------------------------------------------------

    /** Renders a single decision in full, resolving its own references in one batch call per port. */
    private String format(final ProjectId projectId, final AdrDetail detail) {
        final List<AdrDetail> one = List.of(detail);
        return fullText(detail, resolveRequirementsFor(projectId, one), resolveContextsFor(projectId, one));
    }

    /**
     * The compact one-liner {@code adr_list} returns per decision: everything a reader needs to pick
     * which decision to fetch, without any of the long prose that would drown a list of twenty.
     */
    private static String summaryLine(final AdrDetail detail,
            final Map<ResourceId, ResolvedRequirement> requirements,
            final Map<ResourceId, ResolvedBoundedContext> contexts) {
        final StringBuilder line = new StringBuilder("%s [%s] %s".formatted(
                detail.adr().code().value(), detail.adr().status(), detail.adr().name()));
        appendInline(line, "addresses", requirementCodes(detail, requirements));
        appendInline(line, "affects", contextCodes(detail, contexts));
        appendInline(line, "supersedes", codeValues(detail.supersedes()));
        appendInline(line, "superseded by", codeValues(detail.supersededBy()));
        appendInline(line, "related to", codeValues(detail.relatedTo()));
        return line.toString();
    }

    /**
     * The multi-line rendering every single-decision tool returns: header line plus one indented line
     * per populated field. Absent optional fields are omitted entirely rather than printed empty -
     * a decision without considered options should not claim to have an empty list of them.
     */
    private static String fullText(final AdrDetail detail,
            final Map<ResourceId, ResolvedRequirement> requirements,
            final Map<ResourceId, ResolvedBoundedContext> contexts) {
        final StringBuilder out = new StringBuilder("%s [%s] %s".formatted(
                detail.adr().code().value(), detail.adr().status(), detail.adr().name()));
        appendField(out, "context", detail.adr().context());
        appendField(out, "decision", detail.adr().decision());
        appendField(out, "consequences", detail.adr().consequences());
        appendField(out, "alternatives", detail.adr().alternatives());
        appendField(out, "decided", detail.adr().decisionDate() == null
                ? null : detail.adr().decisionDate().toString());
        appendField(out, "addresses", joinOrNull(requirementCodes(detail, requirements)));
        appendField(out, "affects", joinOrNull(contextCodes(detail, contexts)));
        appendField(out, "supersedes", joinOrNull(codeValues(detail.supersedes())));
        appendField(out, "superseded by", joinOrNull(codeValues(detail.supersededBy())));
        appendField(out, "related to", joinOrNull(codeValues(detail.relatedTo())));
        return out.toString();
    }

    private static void appendField(final StringBuilder out, final String label, final String value) {
        if (value != null && !value.isBlank()) {
            out.append("\n    ").append(label).append(": ").append(value);
        }
    }

    private static void appendInline(final StringBuilder line, final String label, final List<String> values) {
        if (!values.isEmpty()) {
            line.append(" [").append(label).append(": ").append(String.join(", ", values)).append(']');
        }
    }

    private static String joinOrNull(final List<String> values) {
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private static List<String> codeValues(final List<AdrCode> codes) {
        return codes.stream().map(AdrCode::value).toList();
    }

    /** One reference rendering: its resolved business code, or its bare IRI as a fallback. */
    private static List<String> requirementCodes(final AdrDetail detail,
            final Map<ResourceId, ResolvedRequirement> resolved) {
        final List<String> rendered = new ArrayList<>();
        for (RequirementRef ref : detail.adr().addressesRequirements()) {
            final ResolvedRequirement match = resolved.get(ref.value());
            rendered.add(match != null ? match.code().value() : ref.value().value());
        }
        return rendered;
    }

    private static List<String> contextCodes(final AdrDetail detail,
            final Map<ResourceId, ResolvedBoundedContext> resolved) {
        final List<String> rendered = new ArrayList<>();
        for (BoundedContextRef ref : detail.adr().affectsContexts()) {
            final ResolvedBoundedContext match = resolved.get(ref.value());
            rendered.add(match != null ? match.code().value() : ref.value().value());
        }
        return rendered;
    }

    /**
     * Batch-resolves every requirement referenced by {@code details} in exactly one call to
     * {@link ResolveRequirements#resolveExisting} - the union of all their {@link RequirementRef}s,
     * deduplicated, not one call per decision and not one per reference. Missing ids are simply
     * absent from the returned map, which the renderers treat as "fall back to the IRI". The merge
     * function keeps the first entry for a duplicate key rather than throwing, so an implementation
     * returning two projections for one identity cannot turn a display concern into an exception.
     */
    private Map<ResourceId, ResolvedRequirement> resolveRequirementsFor(
            final ProjectId projectId, final List<AdrDetail> details) {
        final ResourceId[] ids = details.stream()
                .flatMap(detail -> detail.adr().addressesRequirements().stream())
                .map(RequirementRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveRequirements.resolveExisting(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedRequirement::id, r -> r, (first, second) -> first));
    }

    /** The bounded-context counterpart of {@link #resolveRequirementsFor}, with identical batching. */
    private Map<ResourceId, ResolvedBoundedContext> resolveContextsFor(
            final ProjectId projectId, final List<AdrDetail> details) {
        final ResourceId[] ids = details.stream()
                .flatMap(detail -> detail.adr().affectsContexts().stream())
                .map(BoundedContextRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveBoundedContexts.resolveExisting(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedBoundedContext::id, c -> c, (first, second) -> first));
    }

    /**
     * Parses the optional ISO-8601 decision date. A malformed value is rejected loudly rather than
     * dropped: silently recording a decision without the date its caller believed it had given is
     * worse than making them retype it.
     */
    private static LocalDate parseDate(final String value) {
        final String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed.trim());
        } catch (DateTimeParseException e) {
            // Deliberately not passed as this exception's cause: Spring AI's MCP tool callback
            // renders the deepest exception in the getCause() chain, not this one - chaining e here
            // would let the JDK's raw parse message win over this composed, actionable one (#186,
            // same trap as #137). Kept as a suppressed exception instead, so the original is still
            // on the stack trace without being able to win the walk.
            final IllegalArgumentException translated = new IllegalArgumentException(
                    "decisionDate must be an ISO-8601 date (yyyy-MM-dd), was: " + trimmed);
            translated.addSuppressed(e);
            throw translated;
        }
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
