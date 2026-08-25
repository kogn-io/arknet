// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.mcp;

import static de.hauschel.arknet.adr.adapter.mcp.ToolArguments.blankToNull;
import static de.hauschel.arknet.adr.adapter.mcp.ToolArguments.effectiveDisplayLocale;

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
import de.hauschel.arknet.adr.application.port.in.CountSkippedAdrs;
import de.hauschel.arknet.adr.application.port.in.DeleteAdr;
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
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsequenceCorrection;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.ConsideredOption;
import de.hauschel.arknet.adr.domain.ConsideredOptionCorrection;
import de.hauschel.arknet.adr.domain.NewConsequence;
import de.hauschel.arknet.adr.domain.NewConsideredOption;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts.ResolvedBoundedContext;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;

/**
 * Driving (in) adapter of the ADR component: exposes the architecture-decision use cases as MCP
 * tools ({@code adr_add}, {@code adr_list}, {@code adr_get}, {@code adr_update},
 * {@code adr_set_status}, {@code adr_supersede}, {@code adr_delete}) and delegates each tool call to
 * the corresponding in-port.
 *
 * <p>This adapter belongs to the ADR hexagon (symmetric to the out-adapter
 * {@code arknet-adr-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods.</p>
 *
 * <p><strong>Consequences and considered options (kogn-io/arknet#357).</strong> Both travel as
 * listed parameters of {@code adr_add}/{@code adr_update} - never their own {@code adr_add_option}
 * tool (decided in the issue) - via the small input records {@link NewConsequenceInput}/
 * {@link ConsequenceCorrectionInput}/{@link NewConsideredOptionInput}/
 * {@link ConsideredOptionCorrectionInput}, each converted to the matching domain type here. Their
 * {@code type}/{@code outcome} fields arrive as plain strings (parsed case-insensitively against
 * {@link ConsequenceType}/{@link OptionOutcome}) rather than a generated enum schema, mirroring how
 * {@code adr_set_status}'s own {@code status} argument is parsed.</p>
 *
 * <p><strong>Language (kogn-io/arknet#357).</strong> {@code adr_add}/{@code adr_update} take a
 * single {@code language} argument for the whole call - see {@code AdrService}'s class javadoc for
 * why this is deliberately coarser than the requirements bounded context's per-field arguments.
 * {@code adr_get}/{@code adr_list} take {@code displayLocale}, merged with the resolved project's
 * own default language exactly as {@code req_get}/{@code req_list} do.</p>
 *
 * <p><strong>Reference display resolution (ADR-008).</strong> {@link RequirementRef}/
 * {@link BoundedContextRef} carry a referenced resource's opaque subject identity, not its business
 * code - resolved for display via the borrowed {@link ResolveRequirements}/
 * {@link ResolveBoundedContexts} ports, batched across every reference involved.</p>
 *
 * <p><strong>Project (resolved per call).</strong> Every in-port takes a {@link ProjectId} routing
 * key, resolved per call from the request's anchor (ADR-016).</p>
 */
public final class AdrMcpTools {

    private static final String PROJECT_ANCHOR_DESCRIPTION =
            "Optional anchor identifying the project this call targets, used INSTEAD of the anchor "
                    + "your transport sends in the X-Arknet-Project-Anchor header. Only needed for a "
                    + "client that cannot set that header - most callers should omit this and let "
                    + "their transport identify the project. Must be an anchor already registered for "
                    + "the project; project_list shows what is registered.";

    private static final String LANGUAGE_DESCRIPTION =
            "BCP-47 language tag every multilingual text this call writes (name, adrContext, "
                    + "decision, and any consequence/considered-option text) is recorded under. "
                    + "Optional - falls back to the target project's configured default language, "
                    + "and is only required at all when this call actually writes a multilingual "
                    + "field.";

    private final AddAdr addAdr;
    private final ListAdrs listAdrs;
    private final CountSkippedAdrs countSkippedAdrs;
    private final GetAdr getAdr;
    private final UpdateAdr updateAdr;
    private final AcceptAdr acceptAdr;
    private final RejectAdr rejectAdr;
    private final DeprecateAdr deprecateAdr;
    private final SupersedeAdr supersedeAdr;
    private final DeleteAdr deleteAdr;
    private final ResolveRequirements resolveRequirements;
    private final ResolveBoundedContexts resolveBoundedContexts;
    private final ProjectResolver projects;

    /**
     * Creates the adapter with its ten driving in-ports, the two borrowed display ports and the
     * resolver that maps each call's anchor to a project.
     *
     * @param addAdr                 in-port backing {@code adr_add}
     * @param listAdrs               in-port backing {@code adr_list}
     * @param countSkippedAdrs       in-port backing the skipped-decision note {@code adr_list} appends
     *                               to its own output (kogn-io/arknet#359)
     * @param getAdr                 in-port backing {@code adr_get}
     * @param updateAdr              in-port backing {@code adr_update}
     * @param acceptAdr              in-port backing {@code adr_set_status}'s {@code ACCEPTED} target
     * @param rejectAdr              in-port backing {@code adr_set_status}'s {@code REJECTED} target
     * @param deprecateAdr           in-port backing {@code adr_set_status}'s {@code DEPRECATED}
     *                               target
     * @param supersedeAdr           in-port backing {@code adr_supersede}
     * @param deleteAdr              in-port backing {@code adr_delete}
     * @param resolveRequirements    requirements driving port used only to render an addressed
     *                               requirement's business code instead of its bare IRI
     * @param resolveBoundedContexts bounded-context driving port used only to render an affected
     *                               context's business code instead of its bare IRI
     * @param projects               resolves each call's target project from its anchor
     */
    public AdrMcpTools(
            final AddAdr addAdr,
            final ListAdrs listAdrs,
            final CountSkippedAdrs countSkippedAdrs,
            final GetAdr getAdr,
            final UpdateAdr updateAdr,
            final AcceptAdr acceptAdr,
            final RejectAdr rejectAdr,
            final DeprecateAdr deprecateAdr,
            final SupersedeAdr supersedeAdr,
            final DeleteAdr deleteAdr,
            final ResolveRequirements resolveRequirements,
            final ResolveBoundedContexts resolveBoundedContexts,
            final ProjectResolver projects) {
        this.addAdr = Objects.requireNonNull(addAdr, "addAdr");
        this.listAdrs = Objects.requireNonNull(listAdrs, "listAdrs");
        this.countSkippedAdrs = Objects.requireNonNull(countSkippedAdrs, "countSkippedAdrs");
        this.getAdr = Objects.requireNonNull(getAdr, "getAdr");
        this.updateAdr = Objects.requireNonNull(updateAdr, "updateAdr");
        this.acceptAdr = Objects.requireNonNull(acceptAdr, "acceptAdr");
        this.rejectAdr = Objects.requireNonNull(rejectAdr, "rejectAdr");
        this.deprecateAdr = Objects.requireNonNull(deprecateAdr, "deprecateAdr");
        this.supersedeAdr = Objects.requireNonNull(supersedeAdr, "supersedeAdr");
        this.deleteAdr = Objects.requireNonNull(deleteAdr, "deleteAdr");
        this.resolveRequirements = Objects.requireNonNull(resolveRequirements, "resolveRequirements");
        this.resolveBoundedContexts = Objects.requireNonNull(resolveBoundedContexts, "resolveBoundedContexts");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /**
     * One consequence to append via {@code adr_add}/{@code adr_update}.
     *
     * @param statement the consequence text
     * @param type      {@code POSITIVE}, {@code NEGATIVE} or {@code NEUTRAL} (case-insensitive)
     */
    public record NewConsequenceInput(String statement, String type) {
    }

    /**
     * A correction of an existing consequence, addressed by its position.
     *
     * @param position  the 1-based position of the consequence to correct
     * @param statement the corrected consequence text
     * @param type      the corrected type, {@code POSITIVE}, {@code NEGATIVE} or {@code NEUTRAL}
     */
    public record ConsequenceCorrectionInput(int position, String statement, String type) {
    }

    /**
     * One considered option to append via {@code adr_add}/{@code adr_update}.
     *
     * @param name      the short option name
     * @param rationale why it was chosen or rejected
     * @param outcome   {@code CHOSEN} or {@code REJECTED} (case-insensitive) - at most one option
     *                  per decision may be {@code CHOSEN}
     */
    public record NewConsideredOptionInput(String name, String rationale, String outcome) {
    }

    /**
     * A correction of an existing considered option, addressed by its position.
     *
     * @param position  the 1-based position of the option to correct
     * @param name      the corrected option name
     * @param rationale the corrected rationale
     * @param outcome   the corrected outcome, {@code CHOSEN} or {@code REJECTED}
     */
    public record ConsideredOptionCorrectionInput(int position, String name, String rationale, String outcome) {
    }

    private static ConsequenceType parseConsequenceType(final String value) {
        try {
            return ConsequenceType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException(
                    "consequence type must be POSITIVE, NEGATIVE or NEUTRAL, was: " + value);
        }
    }

    private static OptionOutcome parseOptionOutcome(final String value) {
        try {
            return OptionOutcome.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IllegalArgumentException("option outcome must be CHOSEN or REJECTED, was: " + value);
        }
    }

    private static List<NewConsequence> toNewConsequences(final List<NewConsequenceInput> inputs) {
        if (inputs == null) {
            return null;
        }
        return inputs.stream()
                .map(input -> new NewConsequence(input.statement(), parseConsequenceType(input.type())))
                .toList();
    }

    private static List<ConsequenceCorrection> toConsequenceCorrections(
            final List<ConsequenceCorrectionInput> inputs) {
        if (inputs == null) {
            return null;
        }
        return inputs.stream()
                .map(input -> new ConsequenceCorrection(
                        input.position(), input.statement(), parseConsequenceType(input.type())))
                .toList();
    }

    private static List<NewConsideredOption> toNewConsideredOptions(final List<NewConsideredOptionInput> inputs) {
        if (inputs == null) {
            return null;
        }
        return inputs.stream()
                .map(input -> new NewConsideredOption(
                        input.name(), input.rationale(), parseOptionOutcome(input.outcome())))
                .toList();
    }

    private static List<ConsideredOptionCorrection> toConsideredOptionCorrections(
            final List<ConsideredOptionCorrectionInput> inputs) {
        if (inputs == null) {
            return null;
        }
        return inputs.stream()
                .map(input -> new ConsideredOptionCorrection(
                        input.position(), input.name(), input.rationale(), parseOptionOutcome(input.outcome())))
                .toList();
    }

    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }

    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "adr_add", description = "Record a new architecture decision (context, decision, "
            + "consequences, considered options) as an ADR. It starts out PROPOSED; accept it later "
            + "with adr_set_status. Consequences and considered options are each a list of "
            + "structured entries (statement+type / name+rationale+outcome) - at most one considered "
            + "option may have outcome CHOSEN. It can already name the requirements it addresses, "
            + "the bounded contexts it affects and the peer decisions it is related to; all fields "
            + "stay correctable with adr_update. The assigned code runs ADR-1, ADR-2, ... per "
            + "project and is unrelated to the numbering of any markdown decision records the "
            + "repository may also keep.")
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The decision's title, e.g. 'Use an embedded triple store'")
            final String name,
            @McpToolParam(description = "Why was this decision necessary? Forces and constraints "
                    + "(min. 5 characters)")
            final String adrContext,
            @McpToolParam(description = "What was decided (min. 5 characters)") final String decision,
            @McpToolParam(description = "Consequences of the decision, each with a statement and a "
                    + "type (POSITIVE, NEGATIVE or NEUTRAL). Optional.", required = false)
            final List<NewConsequenceInput> consequences,
            @McpToolParam(description = "Options considered while making the decision, each with a "
                    + "name, a rationale and an outcome (CHOSEN or REJECTED) - at most one may be "
                    + "CHOSEN. Optional.", required = false)
            final List<NewConsideredOptionInput> consideredOptions,
            @McpToolParam(description = LANGUAGE_DESCRIPTION, required = false)
            final String language,
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
                    + "decision itself. Optional.", required = false)
            final List<String> relatedTo,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final AdrDetail created = addAdr.add(project.id(), new NewAdr(name, adrContext, decision,
                toNewConsequences(consequences), toNewConsideredOptions(consideredOptions),
                blankToNull(language), addressesRequirements, affectsContexts, relatedTo),
                project.defaultLanguage());
        return format(project, created);
    }

    @McpTool(name = "adr_list", description = "List all recorded architecture decisions, one compact "
            + "line each (code, status, title, and the codes it addresses/affects/supersedes/is "
            + "superseded by/is related to). Use adr_get for a decision's full text.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = "BCP-47 language tag overriding which candidate of a "
                    + "multilingual field is shown; falls back to the project's configured default "
                    + "language.", required = false)
            final String displayLocale,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final List<AdrDetail> all = listAdrs.list(project.id(), effectiveDisplayLocale(project, displayLocale));
        // all.size() is the materialised subset this call already holds - handed over so the count
        // does not re-read the whole decision graph behind adr_list's back (kogn-io/arknet#359).
        final int skipped = countSkippedAdrs.skippedCount(project.id(), all.size());
        if (all.isEmpty()) {
            return skipped == 0 ? "(no ADRs)" : skippedNote(skipped) + "\n(no other ADRs)";
        }
        // One batch resolution per borrowed port across every decision, not one per decision.
        final Map<ResourceId, ResolvedRequirement> requirements = resolveRequirementsFor(project.id(), all);
        final Map<ResourceId, ResolvedBoundedContext> contexts = resolveContextsFor(project.id(), all);
        final String lines = all.stream()
                .map(detail -> summaryLine(detail, requirements, contexts))
                .collect(Collectors.joining("\n"));
        return skipped == 0 ? lines : lines + "\n" + skippedNote(skipped);
    }

    /**
     * The note appended to {@code adr_list} whenever {@link CountSkippedAdrs#skippedCount} is nonzero
     * (kogn-io/arknet#359) - a store-first (ADR-005) status/{@code supersededBy} anomaly used to be
     * visible only as a {@code WARN} log line an MCP caller never sees; this puts the same count in
     * the tool's own output instead.
     */
    private static String skippedNote(final int skipped) {
        return "(" + skipped + (skipped == 1 ? " decision" : " decisions")
                + " skipped: unresolvable store-first status or supersededBy data - see server logs)";
    }

    @McpTool(name = "adr_get", description = "Fetch a single architecture decision by its identity "
            + "(e.g. ADR-1), including its full context/decision/consequences/considered-options "
            + "text, both directions of the supersedes relation, and every decision it is related "
            + "to.", annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "ADR identity, e.g. ADR-1") final String id,
            @McpToolParam(description = "BCP-47 language tag overriding which candidate of a "
                    + "multilingual field is shown; falls back to the project's configured default "
                    + "language.", required = false)
            final String displayLocale,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final AdrCode code = new AdrCode(id);
        return getAdr.get(project.id(), code, effectiveDisplayLocale(project, displayLocale))
                .map(detail -> format(project, detail))
                .orElse("ADR not found: " + code.value());
    }

    @McpTool(name = "adr_update", description = "Correct an already-recorded architecture decision. "
            + "Every field except the identity is optional - omit (or leave blank/empty) what should "
            + "stay as it is; omitting a field never removes it. name/adrContext/decision can only "
            + "be corrected while the decision is PROPOSED, UNLESS the call writes a language none "
            + "of the three fields carries yet (a translation) - that is allowed in every status. "
            + "Correcting an existing language variant instead is refused with a status-specific "
            + "remedy: linked with adr_supersede while the decision is ACCEPTED (the only status that "
            + "edge accepts), or recorded as a standalone new decision from REJECTED/DEPRECATED/"
            + "SUPERSEDED. newConsequences/newConsideredOptions append and are allowed in every "
            + "status; consequenceCorrections/consideredOptionCorrections correct an existing entry "
            + "by position and carry the same per-position translation exemption: writing a language "
            + "that position never carried yet is allowed in every status, correcting the wording of a "
            + "language that position already carries is PROPOSED-only, and changing consequenceType/"
            + "optionOutcome is never exempt regardless of language once no longer PROPOSED. The three "
            + "reference lists stay correctable in EVERY status: passing a list replaces that relation "
            + "wholesale, passing an empty list removes every edge of it, omitting it leaves it "
            + "untouched. Status and the supersededBy relation are not changed here - use "
            + "adr_set_status and adr_supersede.")
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "ADR identity, e.g. ADR-1") final String id,
            @McpToolParam(description = "The corrected title (optional; unchanged if omitted)",
                    required = false)
            final String name,
            @McpToolParam(description = "The corrected forces and constraints (optional; unchanged "
                    + "if omitted, min. 5 characters)", required = false)
            final String adrContext,
            @McpToolParam(description = "The corrected decision (optional; unchanged if omitted, "
                    + "min. 5 characters)", required = false)
            final String decision,
            @McpToolParam(description = "Consequences to append (optional; never removes an "
                    + "already-recorded one)", required = false)
            final List<NewConsequenceInput> newConsequences,
            @McpToolParam(description = "Text/type corrections for existing consequences, addressed "
                    + "by position (optional; only while PROPOSED)", required = false)
            final List<ConsequenceCorrectionInput> consequenceCorrections,
            @McpToolParam(description = "Considered options to append (optional)", required = false)
            final List<NewConsideredOptionInput> newConsideredOptions,
            @McpToolParam(description = "Corrections for existing considered options, addressed by "
                    + "position (optional; only while PROPOSED)", required = false)
            final List<ConsideredOptionCorrectionInput> consideredOptionCorrections,
            @McpToolParam(description = LANGUAGE_DESCRIPTION, required = false)
            final String language,
            @McpToolParam(description = "Business codes of the requirements this decision should "
                    + "address going forward, replacing the existing ones wholesale. Pass an empty "
                    + "list to remove all of them; omit to leave them unchanged. Correctable in "
                    + "every status.", required = false)
            final List<String> addressesRequirements,
            @McpToolParam(description = "Business codes of the bounded contexts this decision should "
                    + "affect going forward, with the same tri-state as addressesRequirements. "
                    + "Correctable in every status.", required = false)
            final List<String> affectsContexts,
            @McpToolParam(description = "Business codes of the decisions this one should be related "
                    + "to going forward, with the same tri-state again. Correctable in every "
                    + "status.", required = false)
            final List<String> relatedTo,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final AdrCorrection correction = AdrCorrection.builder()
                .name(blankToNull(name))
                .context(blankToNull(adrContext))
                .decision(blankToNull(decision))
                .newConsequences(toNewConsequences(newConsequences))
                .consequenceCorrections(toConsequenceCorrections(consequenceCorrections))
                .newConsideredOptions(toNewConsideredOptions(newConsideredOptions))
                .consideredOptionCorrections(toConsideredOptionCorrections(consideredOptionCorrections))
                .language(blankToNull(language))
                .addressesRequirementCodes(addressesRequirements)
                .affectsContextCodes(affectsContexts)
                .relatedToCodes(relatedTo)
                .build();
        return format(project,
                updateAdr.update(project.id(), new AdrCode(id), correction, project.defaultLanguage()));
    }

    @McpTool(name = "adr_set_status", description = "Change the lifecycle status of an architecture "
            + "decision. Supported transitions: PROPOSED -> ACCEPTED, PROPOSED -> REJECTED, and "
            + "ACCEPTED -> DEPRECATED (for a decision that became obsolete without a successor - use "
            + "adr_supersede instead when a newer decision replaces it). REJECTED means the option "
            + "was considered and turned down - a record worth keeping, because it is what stops the "
            + "same option coming back a year later. It is NOT the way to get rid of a decision "
            + "recorded by mistake: use adr_delete for that, which removes a PROPOSED decision "
            + "outright. Moving to ACCEPTED or REJECTED also records the decision date - this is "
            + "the only place it is ever set, because that is the moment the decision is made; pass "
            + "decidedOn only for a decision that was really made on an earlier day.")
    public String setStatus(
            final McpSyncRequestContext context,
            @McpToolParam(description = "ADR identity, e.g. ADR-1") final String id,
            @McpToolParam(description = "Target status: ACCEPTED, REJECTED or DEPRECATED")
            final String status,
            @McpToolParam(description = "The day the decision was actually made, as ISO-8601 "
                    + "yyyy-MM-dd. Optional and rarely needed: omit it and today is recorded, which "
                    + "is right whenever the decision is being made now. Pass it only when recording "
                    + "a decision that was genuinely taken earlier and is only now being entered. "
                    + "Not accepted together with DEPRECATED, which does not make a decision but "
                    + "retires one that was already made - its date stays as it was.",
                    required = false)
            final String decidedOn,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final AdrCode code = new AdrCode(id);
        final LocalDate decisionDay = parseDate(decidedOn);
        AdrStatus target;
        try {
            target = AdrStatus.valueOf(status.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            target = null;
        }
        return switch (target) {
            case ACCEPTED -> format(project, acceptAdr.accept(project.id(), code, decisionDay));
            case REJECTED -> format(project, rejectAdr.reject(project.id(), code, decisionDay));
            // Refused rather than ignored: a caller passing a date here means it to land somewhere,
            // and DEPRECATED has nowhere to put it - the decision's own date belongs to the day it
            // was accepted, and deprecating does not re-decide anything.
            case DEPRECATED -> {
                if (decisionDay != null) {
                    throw new IllegalArgumentException(
                            "decidedOn only applies to ACCEPTED or REJECTED - deprecating a decision "
                                    + "does not make one, and leaves the date it was accepted on "
                                    + "untouched");
                }
                yield format(project, deprecateAdr.deprecate(project.id(), code));
            }
            case SUPERSEDED -> throw new IllegalArgumentException(
                    "adr_set_status does not set SUPERSEDED directly - it needs a successor decision, "
                            + "which only adr_supersede can name; use adr_supersede instead");
            case null, default -> throw new IllegalArgumentException(
                    "adr_set_status only supports transitioning an ADR to ACCEPTED, REJECTED or "
                            + "DEPRECATED, not " + status);
        };
    }

    @McpTool(name = "adr_supersede", description = "Record that one architecture decision replaces an "
            + "older one. Both must already be ACCEPTED. Sets the older decision's status to "
            + "SUPERSEDED and its supersededBy edge to the newer decision, together in one write - "
            + "the older decision's own record is what this call returns. Recording the same pair "
            + "twice is a no-op; naming a different successor for an already-superseded decision is "
            + "refused.")
    public String supersede(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The superseding (newer) ADR identity, e.g. ADR-2")
            final String id,
            @McpToolParam(description = "The superseded (older) ADR identity, e.g. ADR-1")
            final String supersededId,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final AdrDetail updated =
                supersedeAdr.supersede(project.id(), new AdrCode(id), new AdrCode(supersededId));
        return format(project, updated);
    }

    @McpTool(name = "adr_delete", description = "Delete a recorded architecture decision and every "
            + "triple it carries - the whole resource goes away, this is not a field correction. "
            + "Only a PROPOSED decision can be deleted: this tool undoes a record created by mistake "
            + "(a duplicate, a draft that belongs elsewhere). From ACCEPTED on the record stays, "
            + "because what was decided is exactly what a decision record exists to keep: an "
            + "ACCEPTED decision is superseded with adr_supersede or marked obsolete with "
            + "adr_set_status DEPRECATED, while a REJECTED, DEPRECATED or SUPERSEDED one simply "
            + "stays as it is - neither of those two paths is open there, and the refusal names "
            + "the one that fits the status. REJECTED in particular is not a way to get rid of a "
            + "record: it means the option was considered and turned down, which is itself a "
            + "decision worth keeping. The delete "
            + "is refused while another decision still points at this one - naming it as its own "
            + "successor (supersededBy), or via relatedTo; the refusal names those decisions. The "
            + "freed code is NOT handed out again - the next adr_add continues above it.")
    public String delete(
            final McpSyncRequestContext context,
            @McpToolParam(description = "ADR identity, e.g. ADR-1") final String id,
            @McpToolParam(description = PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final AdrCode code = new AdrCode(id);
        deleteAdr.delete(project.id(), code);
        return "Deleted: " + code.value();
    }

    // --- Rendering ------------------------------------------------------------

    /** Renders a single decision in full, resolving its own references in one batch call per port. */
    private String format(final ResolvedProject project, final AdrDetail detail) {
        final List<AdrDetail> one = List.of(detail);
        return fullText(detail, resolveRequirementsFor(project.id(), one), resolveContextsFor(project.id(), one));
    }

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

    private static String fullText(final AdrDetail detail,
            final Map<ResourceId, ResolvedRequirement> requirements,
            final Map<ResourceId, ResolvedBoundedContext> contexts) {
        final StringBuilder out = new StringBuilder("%s [%s] %s".formatted(
                detail.adr().code().value(), detail.adr().status(), detail.adr().name()));
        appendField(out, "context", detail.adr().context());
        appendField(out, "decision", detail.adr().decision());
        appendField(out, "consequences", joinOrNull(consequenceLines(detail.adr().consequences())));
        appendField(out, "considered options", joinOrNull(optionLines(detail.adr().consideredOptions())));
        appendField(out, "decided", detail.adr().decisionDate() == null
                ? null : detail.adr().decisionDate().toString());
        appendField(out, "addresses", joinOrNull(requirementCodes(detail, requirements)));
        appendField(out, "affects", joinOrNull(contextCodes(detail, contexts)));
        appendField(out, "supersedes", joinOrNull(codeValues(detail.supersedes())));
        appendField(out, "superseded by", joinOrNull(codeValues(detail.supersededBy())));
        appendField(out, "related to", joinOrNull(codeValues(detail.relatedTo())));
        return out.toString();
    }

    /** One rendered line per consequence: {@code [position] TYPE: statement}. */
    private static List<String> consequenceLines(final List<Consequence> consequences) {
        return consequences.stream()
                .map(c -> "[%d] %s: %s".formatted(c.position(), c.type(), c.statement()))
                .toList();
    }

    /** One rendered line per considered option: {@code [position] OUTCOME name - rationale}. */
    private static List<String> optionLines(final List<ConsideredOption> options) {
        return options.stream()
                .map(o -> "[%d] %s %s - %s".formatted(
                        o.position(), o.outcome() == null ? "?" : o.outcome(), o.name(), o.rationale()))
                .toList();
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

    private static LocalDate parseDate(final String value) {
        final String trimmed = blankToNull(value);
        if (trimmed == null) {
            return null;
        }
        try {
            return LocalDate.parse(trimmed.trim());
        } catch (DateTimeParseException e) {
            final IllegalArgumentException translated = new IllegalArgumentException(
                    "decidedOn must be an ISO-8601 date (yyyy-MM-dd), was: " + trimmed);
            translated.addSuppressed(e);
            throw translated;
        }
    }
}
