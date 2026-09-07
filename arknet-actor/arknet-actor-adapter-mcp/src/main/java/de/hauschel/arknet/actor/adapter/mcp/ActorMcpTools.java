// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.actor.application.port.in.AddActor;
import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.application.port.in.DeleteActor;
import de.hauschel.arknet.actor.application.port.in.DescribeActorDisplayFallback;
import de.hauschel.arknet.actor.application.port.in.GetActor;
import de.hauschel.arknet.actor.application.port.in.ListActors;
import de.hauschel.arknet.actor.application.port.in.UpdateActor;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorDisplayFallback;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.StaleTranslationHint;

/**
 * Driving (in) adapter of the actor component: exposes the actor use-cases as MCP tools
 * ({@code actor_add}, {@code actor_list}, {@code actor_get}, {@code actor_update}) and delegates
 * each tool call to the corresponding in-port.
 *
 * <p>This adapter belongs to the actor hexagon (symmetric to the out-adapter
 * {@code arknet-actor-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods - the tool name, description and JSON input
 * schema are derived from the annotations and method signature, not hand-written. This adapter does
 * <strong>not</strong> bootstrap an MCP server or wire any transport; that remains the concern of
 * the composition root (arknet-mcp).</p>
 *
 * <p><strong>Identity vs. code.</strong> Every tool takes an actor identity as a plain
 * {@code String} - what a human types, e.g. {@code ACTOR-1} - and maps it to an {@link ActorCode},
 * never to the opaque {@link de.hauschel.arknet.actor.domain.ActorId}. The identity itself is a
 * store-internal detail that never needs to cross the MCP boundary; responses render the code back
 * to the caller, not the underlying resource identity.</p>
 *
 * <p><strong>Language, mirroring {@link RoleMcpTools} (kogn-io/arknet#520).</strong>
 * {@code actor_add}/{@code actor_update} take an optional {@code language};
 * {@code actor_get}/{@code actor_list} take an optional {@code displayLocale}, with the same
 * project-default fallback and inline {@code [fallback: ...]} marking {@code actor_list} appends -
 * see {@link Actor}'s own javadoc for why an actor's name carries no FR-10 label-equality guard
 * despite being multilingual now (a proper noun, free to differ per language).</p>
 *
 * <p><strong>No borrowed neighbour port.</strong> Unlike {@code BoundedContextMcpTools} or
 * {@code AdrMcpTools}, this adapter borrows no other hexagon's in-port: an actor carries
 * no reference to a term, a requirement or a bounded context in this scope, so there is no opaque
 * identity to render as a business code.</p>
 *
 * <p><strong>Project (resolved per call).</strong> Every in-port takes a {@link ProjectId} routing
 * key. arknet-mcp runs as one shared server for every project on the machine, so there is no single
 * injected project: each tool call resolves its own project from the request's anchor, carried in
 * the MCP transport context under {@link ProjectResolver#ANCHOR_KEY}. The framework hands this
 * adapter that context as an {@link McpSyncRequestContext} parameter - a framework type, excluded
 * from the generated tool input schema, so it is not a caller-facing argument. The anchor is looked
 * up in the project registry: it arrives opaque, is matched whole against what was
 * registered, and either hits exactly one project or fails with an error message naming the possible
 * remedies.</p>
 */
public final class ActorMcpTools {

    /**
     * The prose markup this tool's free-text fields accept, appended to every writing tool's
     * description (issue #388).
     *
     * <p>It belongs on the tool, not only in the module docs: the writing agent reads the tool
     * schema and nothing else, which is exactly why the {@code white-space:pre-line} mechanism of
     * issue #385 was never used by anyone. The same sentence is repeated in each bounded
     * context's MCP adapter rather than shared, because these adapters deliberately have no
     * common module - a shared string is not reason enough to create one.</p>
     */
    private static final String PROSE_MARKUP = " Free-text fields accept a narrow Markdown subset:"
            + " **bold**, *italic*, `code`, lines starting with '- ' as a bullet list, and a blank line"
            + " for a new paragraph. Links, headings, tables and HTML are deliberately not interpreted -"
            + " a reference belongs in the model (an edge such as usesTerm), not in a hand-written link.";

    /**
     * The stale-translation signal, announced on every update tool that writes a multilingual
     * field (kogn-io/arknet#474/kogn-io/arknet#520). It belongs in the tool description for the
     * same reason {@link #PROSE_MARKUP} does: the writing agent reads the tool schema and nothing
     * else, and a signal it does not expect is a signal it does not act on. Mirrors
     * {@link RoleMcpTools}'s constant exactly.
     */
    private static final String STALE_TRANSLATION_NOTE = " If the project maintains several languages,"
            + " the answer names the fields that still carry a maintained language this call did not"
            + " write; repeat the call under each of those languages to keep the translations in step."
            + " A field that did not carry the written language yet is being translated, not corrected,"
            + " and is not reported.";

    private static final String NAME_FIELD = "name";
    private static final String DESCRIPTION_FIELD = "description";

    /**
     * The multilingual fields {@code actor_update} can write, as {@code FieldLanguageLookup} keys -
     * the local names of the predicates behind them. {@code arknet-architecture-tests} reads this
     * list reflectively and holds it against the {@code sh:uniqueLang} properties the shipped
     * shapes declare for this resource, so a typo or a renamed predicate fails a build instead of
     * silently muting the signal for that field.
     */
    private static final List<String> MULTILINGUAL_FIELDS = List.of(NAME_FIELD, DESCRIPTION_FIELD);

    private final AddActor addActor;
    private final ListActors listActors;
    private final DescribeActorDisplayFallback describeActorDisplayFallback;
    private final GetActor getActor;
    private final UpdateActor updateActor;
    private final DeleteActor deleteActor;
    private final ProjectResolver projects;
    private final ActorPresenter presenter = new ActorPresenter();
    private final StaleTranslationHint staleTranslations;

    /**
     * Creates the adapter with its six driving in-ports and the resolver that maps each call's
     * anchor to a project.
     *
     * @param addActor                     in-port backing {@code actor_add}
     * @param listActors                   in-port backing {@code actor_list}
     * @param describeActorDisplayFallback in-port backing {@code actor_list}'s fallback-visibility
     *                                     line
     * @param getActor                     in-port backing {@code actor_get}
     * @param updateActor                  in-port backing {@code actor_update}
     * @param deleteActor                  in-port backing {@code actor_delete}
     * @param projects                     resolves each call's target project from the anchor it
     *                                     carries
     * @param staleTranslations            renders {@code actor_update}'s stale-translation signal
     *                                     (kogn-io/arknet#474/kogn-io/arknet#520)
     */
    public ActorMcpTools(
            final AddActor addActor,
            final ListActors listActors,
            final DescribeActorDisplayFallback describeActorDisplayFallback,
            final GetActor getActor,
            final UpdateActor updateActor,
            final DeleteActor deleteActor,
            final ProjectResolver projects,
            final StaleTranslationHint staleTranslations) {
        this.addActor = Objects.requireNonNull(addActor, "addActor");
        this.listActors = Objects.requireNonNull(listActors, "listActors");
        this.describeActorDisplayFallback =
                Objects.requireNonNull(describeActorDisplayFallback, "describeActorDisplayFallback");
        this.getActor = Objects.requireNonNull(getActor, "getActor");
        this.updateActor = Objects.requireNonNull(updateActor, "updateActor");
        this.deleteActor = Objects.requireNonNull(deleteActor, "deleteActor");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.staleTranslations = Objects.requireNonNull(staleTranslations, "staleTranslations");
    }

    /**
     * Extracts the calling client's project anchor from the per-call transport context - the value
     * the server's context extractor placed there off the request header. Null-tolerant on
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
     * caller supplied one, otherwise the anchor its transport carried; both delivery paths are open
     * to every MCP client. Neither present is a caller error; there is no default project and no
     * fallback to a server-side working directory.
     */
    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "actor_add", description = "Register a new actor: someone or something that can "
            + "act on the system under description, hold an interest in it, or both. A regulator or a "
            + "department that never touches the system is as much an actor as a user who does. An "
            + "actor is a resource in its own right - it needs no glossary entry and no definition. "
            + "Use term_add separately if the actor's name is also a term worth defining." + PROSE_MARKUP)
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Classification: HUMAN (a natural person), SYSTEM (an external "
                    + "system or service), LEGAL (a legal person - organization, company, association) "
                    + "or GROUP (a group without a legal form of its own - department, committee, team). "
                    + "Fixed at creation: actor_update cannot change it.")
            final String type,
            @McpToolParam(description = "What this actor is called, e.g. Sachbearbeiter or PaymentService "
                    + "(min. 2 characters). May be worded differently per language - unlike a glossary "
                    + "term's prefLabel, an actor's name is not required to be the same word under every "
                    + "language tag.")
            final String name,
            @McpToolParam(description = "Free-text description of the actor (optional)", required = false)
            final String description,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the name and description "
                    + "are written in. Falls back to the project's configured default language "
                    + "(project_update) if omitted; if the project has no default either, the call is "
                    + "rejected rather than writing an untagged literal. To state the actor in a second "
                    + "language, call actor_update afterwards with that language.", required = false)
            final String language,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final Actor created = addActor.add(project.id(),
                new NewActor(parseType(type), name, blankToNull(description), blankToNull(language)),
                project.defaultLanguage());
        return presenter.format(created);
    }

    @McpTool(name = "actor_list", description = "List all managed actors. An actor shown under a "
            + "fallen-back language (its name/description is missing in the requested/project-default "
            + "language) carries an inline [fallback: ...] tag naming the language actually shown - see "
            + "displayLocale.", annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') to display every actor's "
                    + "name and description in, overriding the project's own configured default language for "
                    + "this one call. Falls back to the project default, then to the server's own default, "
                    + "then to an untagged literal, then deterministically to any literal an actor carries - "
                    + "an actor whose shown variant is not this call's requested/project-default language is "
                    + "marked with an inline [fallback: ...] tag.", required = false)
            final String displayLocale,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final ProjectId projectId = project.id();
        final String effective = effectiveDisplayLocale(project, displayLocale);
        final List<Actor> all = listActors.list(projectId, effective);
        if (all.isEmpty()) {
            return "(no actors)";
        }
        final Map<ActorCode, ActorDisplayFallback> fallbacks =
                describeActorDisplayFallback.describe(projectId, effective);
        return all.stream()
                .map(actor -> presenter.format(actor) + fallbackSuffix(fallbacks.get(actor.code())))
                .reduce((a, b) -> a + "\n" + b).orElse("(no actors)");
    }

    @McpTool(name = "actor_get", description = "Fetch a single actor by its identity (e.g. ACTOR-1).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Actor identity, e.g. ACTOR-1") final String id,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') to display the name and "
                    + "description in, overriding the project's own configured default language for this one "
                    + "call. Falls back to the project default, then to the server's own default, then to an "
                    + "untagged literal, then deterministically to any literal the actor carries.",
                    required = false)
            final String displayLocale,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final ActorCode code = new ActorCode(id);
        final String effective = effectiveDisplayLocale(project, displayLocale);
        return getActor.get(project.id(), code, effective)
                .map(presenter::format)
                .orElse("Actor not found: " + code.value());
    }

    @McpTool(name = "actor_update",
            description = "Correct an already-created actor's name and/or description, and/or state either "
                    + "in a further language. Both text arguments are optional - an omitted one leaves that "
                    + "field unchanged; omitting the description does NOT remove it. Cannot change the "
                    + "actor's type or code (ACTOR-N): both are fixed at creation, and everything already "
                    + "referring to the actor refers to that code." + PROSE_MARKUP + STALE_TRANSLATION_NOTE)
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Actor identity, e.g. ACTOR-1") final String id,
            @McpToolParam(description = "New name (optional, unchanged if omitted). May be worded "
                    + "differently per language - unlike a glossary term's prefLabel, an actor's name is "
                    + "not required to be the same word under every language tag.", required = false)
            final String name,
            @McpToolParam(description = "New description (optional, unchanged if omitted)", required = false)
            final String description,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'en') a non-omitted name/"
                    + "description is written in. Falls back to the project's configured default language "
                    + "(see actor_add's same parameter) if omitted; if the project has no default either, "
                    + "the call is rejected rather than writing an untagged literal. Only the existing "
                    + "literal carrying the tag actually written is replaced - every other language variant "
                    + "of a field being corrected survives untouched, except a stale untagged one left over "
                    + "from before a language was ever supplied, which is swept away when the resolved tag "
                    + "equals the project's default. This is the way to make an existing, single-language "
                    + "actor bilingual: restate its text under the second tag.", required = false)
            final String language,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final ActorCode code = new ActorCode(id);
        final String staleHint = staleTranslationHint(project, code, blankToNull(language), blankToNull(name),
                blankToNull(description));
        final Actor updated = updateActor.update(project.id(), code, blankToNull(name), blankToNull(description),
                blankToNull(language), project.defaultLanguage());
        return presenter.format(updated) + staleHint;
    }

    @McpTool(name = "actor_delete",
            description = "Delete an already-created actor and every triple it carries - not just a "
                    + "correction, the whole resource goes away. Rejected if a role still lists it in its "
                    + "filledBy occupants (role_add/role_update) - remove it there first. A resource that is "
                    + "also a glossary term (term_add) keeps its glossary entry - this only removes the actor "
                    + "resource itself.")
    public String delete(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Actor identity, e.g. ACTOR-1") final String id,
            @McpToolParam(description = "Optional anchor identifying the project this call "
                    + "targets, used INSTEAD of the anchor your transport sends in the "
                    + "X-Arknet-Project-Anchor header. Only needed for a client that cannot set that "
                    + "header - most callers should omit this and let their transport identify the "
                    + "project. Must be an anchor already registered for the project; project_list "
                    + "shows what is registered.", required = false)
            final String projectAnchor) {
        final ProjectId projectId = resolveProject(context, projectAnchor).id();
        final ActorCode code = new ActorCode(id);
        deleteActor.delete(projectId, code);
        return "Deleted: " + code.value();
    }

    /** Mirrors {@code RoleMcpTools#fallbackSuffix} exactly, for {@link ActorDisplayFallback}. */
    private static String fallbackSuffix(final ActorDisplayFallback fallback) {
        if (fallback == null || fallback.isEmpty()) {
            return "";
        }
        final List<String> parts = new ArrayList<>();
        if (fallback.nameTag() != null) {
            parts.add("name=" + displayTag(fallback.nameTag()));
        }
        if (fallback.descriptionTag() != null) {
            parts.add("description=" + displayTag(fallback.descriptionTag()));
        }
        return " [fallback: " + String.join(", ", parts) + "]";
    }

    private static String displayTag(final String tag) {
        return tag.isEmpty() ? "untagged" : tag;
    }

    /** Mirrors {@code ToolArguments#effectiveDisplayLocale} exactly. */
    private static String effectiveDisplayLocale(final ResolvedProject project, final String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return project.defaultLanguage();
    }

    /**
     * Parses {@code value} against {@link ActorType}, rejecting anything else - including an
     * unparseable or blank value - with this tool's own didactic message rather than the JDK's raw
     * {@code No enum constant ...}, mirroring {@code bc_link_context}'s own enum-parsing idiom.
     */
    private static ActorType parseType(final String value) {
        ActorType parsed;
        try {
            parsed = ActorType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (NullPointerException | IllegalArgumentException e) {
            parsed = null;
        }
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "actor_add only supports HUMAN, SYSTEM, LEGAL or GROUP as an actor type, not " + value);
        }
        return parsed;
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }

    /**
     * The stale-translation signal for an {@code actor_update} (kogn-io/arknet#474/
     * kogn-io/arknet#520): the multilingual fields this call is about to write, named as
     * {@code store_check} names them. Asked before the write and appended after it, because only
     * the state before tells a correction from a translation (see {@link StaleTranslationHint}).
     * Mirrors {@code RoleMcpTools#staleTranslationHint} exactly.
     */
    private String staleTranslationHint(final ResolvedProject project, final ActorCode code,
            final String language, final String name, final String description) {
        final List<String> fieldsWritten = new ArrayList<>();
        if (name != null) {
            fieldsWritten.add(NAME_FIELD);
        }
        if (description != null) {
            fieldsWritten.add(DESCRIPTION_FIELD);
        }
        if (fieldsWritten.isEmpty()) {
            return "";
        }
        return staleTranslations.forResource(project.id(), code.value(),
                LanguageTag.writtenLanguage(language, project.defaultLanguage()),
                project.maintainedLanguages(), fieldsWritten);
    }
}
