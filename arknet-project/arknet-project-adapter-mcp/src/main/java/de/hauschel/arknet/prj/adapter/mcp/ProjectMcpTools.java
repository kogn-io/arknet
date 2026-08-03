// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.mcp;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.application.port.in.AdoptProject;
import de.hauschel.arknet.prj.application.port.in.AttachAnchor;
import de.hauschel.arknet.prj.application.port.in.ListAdoptableDatasets;
import de.hauschel.arknet.prj.application.port.in.ListProjects;
import de.hauschel.arknet.prj.application.port.in.RegisterProject;
import de.hauschel.arknet.prj.application.port.in.RenameProject;
import de.hauschel.arknet.prj.application.port.in.ResolveProject;
import de.hauschel.arknet.prj.application.port.in.UpdateProject;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;

/**
 * Driving (in) adapter of the project component: exposes the project-registry use-cases as MCP
 * tools ({@code project_add}, {@code project_adopt}, {@code project_attach_anchor},
 * {@code project_rename}, {@code project_list}) and delegates each tool call to the corresponding
 * in-port.
 *
 * <p>This adapter belongs to the project hexagon (symmetric to the out-adapter
 * {@code arknet-project-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods - the tool name, description and JSON
 * input schema are derived from the annotations and method signature, not hand-written. This
 * adapter does <strong>not</strong> bootstrap an MCP server or wire any transport; that remains
 * the concern of the composition root (arknet-mcp).</p>
 *
 * <p><strong>No business code, unlike every other model bounded context.</strong> {@code BC-1},
 * {@code REQ-1}, {@code TERM-1} and {@code UC-1} are short, typeable, ratable addresses that work
 * precisely because they only ever have to be unique <em>inside</em> one project's dataset - an
 * agent guessing a nearby number can only ever land on a resource of the very project it is
 * already working in. A project identity has no such boundary: it is the boundary. A short,
 * guessable {@code PRJ-1}-style code shared across every project on the machine would invite a
 * language model that mistypes or extrapolates one digit to silently address a <em>different</em>
 * project's data - exactly the cross-project bleed ADR-016 exists to close. Projects are
 * therefore addressed only by the {@link Anchor}s a client actually presented and registered
 * (never by a short code a human could mistype into someone else's project), and rendered with
 * their full opaque {@link de.hauschel.arknet.kernel.ProjectId} so a later surface without an
 * anchor of its own (a web UI without a client working directory of its own) still has a stable,
 * tool-addressable value to hold onto.</p>
 *
 * <p><strong>No {@link ProjectResolver} here, on purpose.</strong> Every other bounded context's
 * MCP adapter turns the caller's anchor into a {@code ProjectId} through {@link ProjectResolver},
 * whose composition-root implementation answers by consulting <em>this</em> component's registry.
 * That is the reason this adapter cannot use it: the component answering the routing question
 * cannot itself sit behind an answer to it. It therefore imports {@link ProjectResolver} only for
 * its {@link ProjectResolver#ANCHOR_KEY} constant - the key under which the calling client's anchor
 * travels in the MCP transport context - and never calls {@link ProjectResolver#resolve(String)}.
 * The raw value read under that key is wrapped directly as an {@link Anchor} and looked up here.</p>
 *
 * <p><strong>No default, no fallback (ADR-016 decision 3).</strong> A call whose transport context
 * carries no anchor, and which was not given an explicit anchor parameter either, is a caller error
 * - never a silent fallback to some server-side working directory. Such a call would have no way to
 * know which project it belongs to, and inventing an answer is precisely the failure mode this
 * bounded context exists to close off. {@link #add}, {@link #adopt}, {@link #attachAnchor} and
 * {@link #rename} all throw {@link IllegalArgumentException} in that situation, rather than
 * guessing.</p>
 *
 * <p><strong>{@link #adopt} exists because the server cannot repair the past on its own.</strong>
 * Datasets written before ADR-016 sit under ids derived from a directory name
 * ({@code slug(basename(git-common-dir))}), and that derivation is not invertible: the server
 * cannot know which directory the dataset {@code arknet} once meant, and guessing is the thing
 * ADR-016 removes. Only the person at the keyboard knows, so adoption is a tool rather than a
 * migration that runs at startup - the anchor arrives from the calling client as it always does,
 * and the dataset is named explicitly. {@link #list} renders the adoptable datasets alongside the
 * registered projects so that name never has to be guessed either.</p>
 *
 * <p><strong>Both anchor paths open to every tool (ADR-016 decision 2).</strong> This is not a
 * relaxation of the paragraph above - a missing anchor is still a hard error - but ADR-016 decision
 * 2 is explicit that the transport-context path and the explicit-parameter path "are both open to
 * every MCP client", not just to one tool. {@link #add} has always accepted an explicit first
 * anchor via its {@code anchor} parameter. {@link #attachAnchor} and {@link #rename} resolve a
 * second project identity beyond their own primary parameter - the caller's <em>own</em> project,
 * the one a new anchor is attached to or whose label changes - and for that reason accept the same
 * explicit path under a distinct name, {@code callerAnchor}, so it is never confused with the
 * primary anchor parameter those two tools already carry ({@code anchor} on
 * {@link #attachAnchor}). A client without transport-context control can therefore reach all three
 * writing tools by passing an explicit anchor every time, never just the first one.</p>
 */
public final class ProjectMcpTools {

    /**
     * Used by {@link #add} only: at this call site, no project has been registered yet, so the
     * remedy is to supply {@code project_add}'s own {@code anchor} parameter instead of relying on
     * the transport context.
     */
    private static final String NO_CONTEXT_ANCHOR_MESSAGE_ADD =
            "No anchor available: the calling client's transport supplied no origin directory, so "
                    + "the project this call belongs to cannot be determined. There is no default and no "
                    + "fallback to a server-side working directory. project_add also accepts an explicit "
                    + "'anchor' parameter for clients that cannot supply an origin directory via their "
                    + "transport context - pass that instead.";

    /**
     * Used by {@link #attachAnchor} and {@link #rename} only: both already presuppose a registered
     * project, so the remedy is never "register with project_add" - it is their own
     * {@code callerAnchor} parameter, which names an anchor already registered for the caller's
     * project.
     */
    private static final String NO_CONTEXT_ANCHOR_MESSAGE_CALLER =
            "No anchor available: the calling client's transport supplied no origin directory, and "
                    + "no explicit 'callerAnchor' parameter was given either, so the project this call "
                    + "belongs to cannot be determined. There is no default and no fallback to a "
                    + "server-side working directory. Pass the 'callerAnchor' parameter with an anchor "
                    + "already registered for this project instead, for clients that cannot supply an "
                    + "origin directory via their transport context.";

    /**
     * Used by {@link #adopt} only: like {@link #add} the caller owns no registered anchor yet, but
     * the remedy names {@code project_adopt}'s own {@code anchor} parameter rather than
     * {@code project_add}'s - sending it to the wrong tool would create a second, empty project
     * beside the data it is trying to reach.
     */
    private static final String NO_CONTEXT_ANCHOR_MESSAGE_ADOPT =
            "No anchor available: the calling client's transport supplied no anchor, so the project "
                    + "this dataset should be adopted under cannot be determined. There is no default "
                    + "and no fallback to a server-side working directory. project_adopt also accepts "
                    + "an explicit 'anchor' parameter for clients that cannot supply one via their "
                    + "transport context - pass that instead.";

    private final RegisterProject registerProject;
    private final AdoptProject adoptProject;
    private final AttachAnchor attachAnchor;
    private final RenameProject renameProject;
    private final UpdateProject updateProject;
    private final ListProjects listProjects;
    private final ListAdoptableDatasets listAdoptableDatasets;
    private final ResolveProject resolveProject;

    /**
     * Creates the adapter with its eight driving in-ports.
     *
     * @param registerProject       in-port backing {@code project_add}
     * @param adoptProject          in-port backing {@code project_adopt}
     * @param attachAnchor          in-port backing {@code project_attach_anchor}
     * @param renameProject         in-port backing {@code project_rename}
     * @param updateProject         in-port backing {@code project_update}
     * @param listProjects          in-port backing {@code project_list}
     * @param listAdoptableDatasets in-port backing {@code project_list}'s second section
     * @param resolveProject        in-port used to resolve the caller's own project from its context
     *                              anchor, so {@code project_attach_anchor}, {@code project_rename}
     *                              and {@code project_update} never need a project identity as a
     *                              caller-facing parameter
     */
    public ProjectMcpTools(
            final RegisterProject registerProject,
            final AdoptProject adoptProject,
            final AttachAnchor attachAnchor,
            final RenameProject renameProject,
            final UpdateProject updateProject,
            final ListProjects listProjects,
            final ListAdoptableDatasets listAdoptableDatasets,
            final ResolveProject resolveProject) {
        this.registerProject = Objects.requireNonNull(registerProject, "registerProject");
        this.adoptProject = Objects.requireNonNull(adoptProject, "adoptProject");
        this.attachAnchor = Objects.requireNonNull(attachAnchor, "attachAnchor");
        this.renameProject = Objects.requireNonNull(renameProject, "renameProject");
        this.updateProject = Objects.requireNonNull(updateProject, "updateProject");
        this.listProjects = Objects.requireNonNull(listProjects, "listProjects");
        this.listAdoptableDatasets = Objects.requireNonNull(listAdoptableDatasets, "listAdoptableDatasets");
        this.resolveProject = Objects.requireNonNull(resolveProject, "resolveProject");
    }

    /**
     * Extracts the calling client's origin directory from the per-call transport context.
     * Null-tolerant on every hop: a call without a context, without a transport context, or
     * without the key resolves to {@code null}. Unlike every other bounded context's adapter,
     * this value is never handed to a {@link ProjectResolver} - see the class Javadoc.
     */
    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object dir = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return dir == null ? null : dir.toString();
    }

    /**
     * Resolves the calling client's own anchor from its transport context, as a {@link
     * AnchorType#PATH} anchor - never invented, never defaulted (ADR-016 decision 3).
     *
     * @param noAnchorMessage call-site-specific remedy, since the right advice differs between
     *                        {@link #add} (register via its own {@code anchor} parameter) and
     *                        {@link #attachAnchor}/{@link #rename} (resolve the caller via their
     *                        {@code callerAnchor} parameter)
     * @throws IllegalArgumentException if the context carried no origin directory
     */
    private static Anchor requireContextAnchor(final McpSyncRequestContext context, final String noAnchorMessage) {
        final String origin = contextAnchor(context);
        if (origin == null || origin.isBlank()) {
            throw new IllegalArgumentException(noAnchorMessage);
        }
        return new Anchor(origin, AnchorType.PATH);
    }

    /**
     * Resolves the project the current call comes from: the explicit {@code callerAnchor}
     * parameter if the caller supplied one, otherwise the transport context's origin-directory
     * anchor (ADR-016 decision 2 - both paths are open to every MCP client, not only to
     * {@link #add}). Shared by {@link #attachAnchor} and {@link #rename}, the two tools that need
     * to know which project the call itself belongs to before they can act on it.
     *
     * <p><strong>There is deliberately no {@code callerAnchorType} parameter</strong>, unlike
     * {@link #add}'s {@code anchor}/{@code anchorType} pair. The difference is what happens to the
     * anchor: {@link #add} <em>persists</em> the anchor it is given, so the type it carries is
     * stored and has to come from the caller. A caller anchor is only ever <em>looked up</em> and
     * never written, and lookup resolves on the anchor's value alone - the storage identity is a
     * digest over the value ({@code ProjectGraphs}) and {@link Anchor} compares on the value for
     * exactly that reason. A type parameter here would therefore change no outcome whatsoever
     * while implying to a language model reading the tool schema that the caller has to get the
     * type right for the lookup to hit, which is the very misconception the value-only anchor
     * identity removes. The {@link AnchorType#PATH} below is an arbitrary, unused placeholder that
     * the {@link Anchor} constructor requires.</p>
     *
     * @throws IllegalArgumentException if neither an explicit {@code callerAnchor} nor a context
     *                                   anchor was available
     */
    private Project resolveCaller(final McpSyncRequestContext context, final String callerAnchor) {
        final Anchor callerHandle = isBlank(callerAnchor)
                ? requireContextAnchor(context, NO_CONTEXT_ANCHOR_MESSAGE_CALLER)
                : new Anchor(callerAnchor, AnchorType.PATH);
        return resolveProject.resolve(callerHandle);
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "project_add", description = "Register a new project. The calling client's "
            + "origin directory becomes the project's first anchor; every later call from that "
            + "directory resolves to this project.")
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The project's human-readable, cross-project-unique name.")
            final String label,
            @McpToolParam(description = "Optional explicit first anchor, used INSTEAD of the calling "
                    + "client's origin directory. Only needed for a client that cannot supply an origin "
                    + "directory via its transport context (ADR-016) - most callers should omit this and "
                    + "let their calling directory become the anchor.", required = false)
            final String anchor,
            @McpToolParam(description = "Type of the explicit 'anchor' parameter above: 'path', 'url' "
                    + "or 'uuid'. Defaults to 'path'. Ignored when 'anchor' is omitted.", required = false)
            final String anchorType,
            @McpToolParam(description = "Optional free-text description of the project (issue #110). May be "
                    + "written in several languages over time via project_update - each call replaces only "
                    + "the variant carrying the same 'language' tag.", required = false)
            final String description,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the 'description' is "
                    + "written in, or omitted for a plain, untagged literal. Ignored if 'description' is "
                    + "omitted.", required = false)
            final String language,
            @McpToolParam(description = "Optional: the project's default display/write language, as a "
                    + "BCP-47 tag (e.g. 'de'). Used by other tools (e.g. term_get) as a fallback display "
                    + "language, and never as an implicit write-time default - a write that omits its own "
                    + "language argument always writes untagged, regardless of this value.", required = false)
            final String defaultLanguage) {
        final Anchor resolvedAnchor = isBlank(anchor)
                ? requireContextAnchor(context, NO_CONTEXT_ANCHOR_MESSAGE_ADD)
                : new Anchor(anchor, parseAnchorType(anchorType));
        final Project created = registerProject.register(label, resolvedAnchor, blankToNull(description),
                blankToNull(language), blankToNull(defaultLanguage));
        return format(created);
    }

    @McpTool(name = "project_attach_anchor", description = "Attach a further anchor to the project "
            + "the call comes from. Use this when the same project is worked on from a second "
            + "directory (a git worktree, another checkout, another IDE workspace).")
    public String attachAnchor(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The NEW anchor to attach to the caller's own project - not "
                    + "the calling directory itself, and not the 'callerAnchor' parameter below. This is "
                    + "the anchor being added to whichever project the call belongs to.")
            final String anchor,
            @McpToolParam(description = "Type of the 'anchor' parameter above: 'path', 'url' or "
                    + "'uuid'. Defaults to 'path'.", required = false)
            final String anchorType,
            @McpToolParam(description = "Optional anchor ALREADY REGISTERED for the caller's own "
                    + "project, used INSTEAD of the calling client's transport context to find which "
                    + "project the new anchor above is attached to. Only needed for a client that cannot "
                    + "supply an origin directory via its transport context (ADR-016); most callers should "
                    + "omit this and let their calling directory identify the project. Not the anchor "
                    + "being attached - that is the 'anchor' parameter above. No type is needed: a "
                    + "caller anchor is only looked up, and lookup matches on its value alone.",
                    required = false)
            final String callerAnchor) {
        final Project caller = resolveCaller(context, callerAnchor);
        final Project updated = attachAnchor.attach(caller.id(), new Anchor(anchor, parseAnchorType(anchorType)));
        return format(updated);
    }

    @McpTool(name = "project_rename", description = "Rename the project the call comes from. The "
            + "project's identity and its anchors are unaffected - only its human-readable label "
            + "changes.")
    public String rename(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The project's new human-readable, cross-project-unique name.")
            final String label,
            @McpToolParam(description = "Optional anchor ALREADY REGISTERED for the caller's own "
                    + "project, used INSTEAD of the calling client's transport context to find which "
                    + "project to rename. Only needed for a client that cannot supply an origin directory "
                    + "via its transport context (ADR-016); most callers should omit this and let their "
                    + "calling directory identify the project. No type is needed: a caller anchor is only "
                    + "looked up, and lookup matches on its value alone.", required = false)
            final String callerAnchor) {
        final Project caller = resolveCaller(context, callerAnchor);
        final Project updated = renameProject.rename(caller.id(), label);
        return format(updated);
    }

    @McpTool(name = "project_update", description = "Correct the project the call comes from: its "
            + "optional description and/or default display language. Every argument is optional - an "
            + "omitted one leaves that field unchanged. Unlike project_rename, this never touches the "
            + "project's label or anchors.")
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "New description (optional, unchanged if omitted). Replaces only "
                    + "the variant carrying the same 'language' tag as this call - a description written in "
                    + "another language survives untouched.", required = false)
            final String description,
            @McpToolParam(description = "Optional: BCP-47 language tag (e.g. 'de') the new 'description' "
                    + "is written in, or omitted for a plain, untagged literal. Ignored if 'description' is "
                    + "omitted.", required = false)
            final String language,
            @McpToolParam(description = "New default display/write language, as a BCP-47 tag (e.g. 'de') "
                    + "(optional, unchanged if omitted).", required = false)
            final String defaultLanguage,
            @McpToolParam(description = "Optional anchor ALREADY REGISTERED for the caller's own "
                    + "project, used INSTEAD of the calling client's transport context to find which "
                    + "project to correct. Only needed for a client that cannot supply an origin directory "
                    + "via its transport context (ADR-016); most callers should omit this and let their "
                    + "calling directory identify the project. No type is needed: a caller anchor is only "
                    + "looked up, and lookup matches on its value alone.", required = false)
            final String callerAnchor) {
        final Project caller = resolveCaller(context, callerAnchor);
        final Project updated = updateProject.update(caller.id(), blankToNull(description), blankToNull(language),
                blankToNull(defaultLanguage));
        return format(updated);
    }

    @McpTool(name = "project_adopt", description = "Claim an EXISTING dataset as the project this "
            + "call comes from - for data written before projects were registered, or for a dataset "
            + "restored from a backup. The calling client's anchor becomes the project's first "
            + "anchor; the dataset keeps its identity and all its data. Use project_list to see "
            + "which datasets are available for adoption. For a new, empty project use project_add.")
    public String adopt(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Identity of the existing dataset to adopt, exactly as "
                    + "project_list reports it under 'unregistered datasets' (e.g. 'arknet'). It "
                    + "becomes this project's identity unchanged - nothing is renamed or migrated.")
            final String datasetId,
            @McpToolParam(description = "The project's human-readable, cross-project-unique name.")
            final String label,
            @McpToolParam(description = "Optional explicit anchor to adopt the dataset under, used "
                    + "INSTEAD of the calling client's own anchor. Only needed for a client that cannot "
                    + "supply an anchor via its transport context - most callers should omit this and "
                    + "let their calling directory become the anchor.", required = false)
            final String anchor,
            @McpToolParam(description = "Type of the explicit 'anchor' parameter above: 'path', 'url' "
                    + "or 'uuid'. Defaults to 'path'. Ignored when 'anchor' is omitted.", required = false)
            final String anchorType) {
        final Anchor resolvedAnchor = isBlank(anchor)
                ? requireContextAnchor(context, NO_CONTEXT_ANCHOR_MESSAGE_ADOPT)
                : new Anchor(anchor, parseAnchorType(anchorType));
        final Project adopted = adoptProject.adopt(new ProjectId(datasetId), label, resolvedAnchor);
        return format(adopted);
    }

    @McpTool(name = "project_list", description = "List all registered projects, and any datasets "
            + "in the store that no project claims yet (adoptable with project_adopt).",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list() {
        final List<Project> all = listProjects.list();
        final List<ProjectId> adoptable = listAdoptableDatasets.adoptable();
        final String projects = all.isEmpty()
                ? "(no projects)"
                : all.stream().map(ProjectMcpTools::format).collect(Collectors.joining("\n"));
        if (adoptable.isEmpty()) {
            return projects;
        }
        // Rendered here rather than as a tool of its own: a caller asking what exists should not
        // have to know that "registered" and "present in the store" can differ before it can find
        // out that they do. The section disappears once everything is adopted.
        return projects + "\n\n# Unregistered datasets (adoptable with project_adopt)\n"
                + adoptable.stream().map(ProjectId::value).collect(Collectors.joining("\n"));
    }

    /**
     * Renders a project as its label, every anchor it is reachable by (typed, e.g.
     * {@code path:/home/f/DEV/arknet}), its opaque identity, and its description/default
     * language if either is set - the identity is not a caller-facing tool parameter anywhere in
     * this adapter, but a later surface without an anchor of its own needs a stable value to
     * address a project by.
     */
    private static String format(final Project project) {
        final String anchors = project.anchors().stream()
                .map(ProjectMcpTools::formatAnchor)
                .collect(Collectors.joining(", "));
        final StringBuilder rendered = new StringBuilder(
                "%s [%s] (id: %s)".formatted(project.label(), anchors, project.id().value()));
        if (project.description() != null) {
            rendered.append(" - ").append(project.description());
        }
        if (project.defaultLanguage() != null) {
            rendered.append(" [defaultLanguage: ").append(project.defaultLanguage()).append(']');
        }
        return rendered.toString();
    }

    private static String formatAnchor(final Anchor anchor) {
        return anchor.type().name().toLowerCase(Locale.ROOT) + ":" + anchor.value();
    }

    private static AnchorType parseAnchorType(final String anchorType) {
        if (isBlank(anchorType)) {
            return AnchorType.PATH;
        }
        try {
            return AnchorType.valueOf(anchorType.trim().toUpperCase(Locale.ROOT));
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown anchor type '" + anchorType
                    + "': expected one of 'path', 'url', 'uuid'.", e);
        }
    }

    private static boolean isBlank(final String value) {
        return value == null || value.isBlank();
    }

    private static String blankToNull(final String value) {
        return isBlank(value) ? null : value;
    }
}
