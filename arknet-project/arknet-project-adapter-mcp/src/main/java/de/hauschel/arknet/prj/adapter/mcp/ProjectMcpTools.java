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

import de.hauschel.arknet.kernel.WorkspaceResolver;
import de.hauschel.arknet.prj.application.port.in.AttachAnchor;
import de.hauschel.arknet.prj.application.port.in.ListProjects;
import de.hauschel.arknet.prj.application.port.in.RegisterProject;
import de.hauschel.arknet.prj.application.port.in.RenameProject;
import de.hauschel.arknet.prj.application.port.in.ResolveProject;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;

/**
 * Driving (in) adapter of the project component: exposes the project-registry use-cases as MCP
 * tools ({@code project_add}, {@code project_attach_anchor}, {@code project_rename},
 * {@code project_list}) and delegates each tool call to the corresponding in-port.
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
 * their full opaque {@link de.hauschel.arknet.prj.domain.ProjectId} so a later surface without an
 * anchor of its own (a web UI, e.g. issue #149's review UI) still has a stable, tool-addressable
 * value to hold onto.</p>
 *
 * <p><strong>No {@link WorkspaceResolver} here, on purpose.</strong> Every other bounded context's
 * MCP adapter resolves a {@code WorkspaceId} per call by handing the client's origin directory to
 * {@link WorkspaceResolver}, which <em>derives</em> an id from it (git top-level, slugging). That
 * derivation is exactly what ADR-016 replaces: a project's identity is a registered anchor
 * relationship, not something computed from a directory name. This adapter therefore imports
 * {@link WorkspaceResolver} only for its {@link WorkspaceResolver#WORKSPACE_DIR_KEY} constant -
 * the key under which the calling client's origin directory travels in the MCP transport context
 * - and never calls {@link WorkspaceResolver#resolve(String)}. The raw value read under that key
 * is instead wrapped directly as an {@link Anchor} of {@link AnchorType#PATH} and looked up
 * against the registry. The key name itself is still the old one because this issue (#178) is
 * purely additive - it introduces the registry alongside the existing derived-workspace path
 * without touching it; issue #179 is the follow-up that renames the transport header and this
 * constant once every bounded context has moved onto registered anchors.</p>
 *
 * <p><strong>No default, no fallback (ADR-016 decision 3).</strong> A call whose transport context
 * carries no origin directory, and which was not given an explicit anchor parameter either, is a
 * caller error - never a silent fallback to some server-side working directory. Such a call would
 * have no way to know which project it belongs to, and inventing an answer is precisely the
 * failure mode this bounded context exists to close off. {@link #add}, {@link #attachAnchor} and
 * {@link #rename} all throw {@link IllegalArgumentException} in that situation, rather than
 * guessing.</p>
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

    private final RegisterProject registerProject;
    private final AttachAnchor attachAnchor;
    private final RenameProject renameProject;
    private final ListProjects listProjects;
    private final ResolveProject resolveProject;

    /**
     * Creates the adapter with its five driving in-ports.
     *
     * @param registerProject in-port backing {@code project_add}
     * @param attachAnchor    in-port backing {@code project_attach_anchor}
     * @param renameProject   in-port backing {@code project_rename}
     * @param listProjects    in-port backing {@code project_list}
     * @param resolveProject  in-port used to resolve the caller's own project from its context
     *                        anchor, so {@code project_attach_anchor} and {@code project_rename}
     *                        never need a project identity as a caller-facing parameter
     */
    public ProjectMcpTools(
            final RegisterProject registerProject,
            final AttachAnchor attachAnchor,
            final RenameProject renameProject,
            final ListProjects listProjects,
            final ResolveProject resolveProject) {
        this.registerProject = Objects.requireNonNull(registerProject, "registerProject");
        this.attachAnchor = Objects.requireNonNull(attachAnchor, "attachAnchor");
        this.renameProject = Objects.requireNonNull(renameProject, "renameProject");
        this.listProjects = Objects.requireNonNull(listProjects, "listProjects");
        this.resolveProject = Objects.requireNonNull(resolveProject, "resolveProject");
    }

    /**
     * Extracts the calling client's origin directory from the per-call transport context.
     * Null-tolerant on every hop: a call without a context, without a transport context, or
     * without the key resolves to {@code null}. Unlike every other bounded context's adapter,
     * this value is never handed to a {@link WorkspaceResolver} - see the class Javadoc.
     */
    private static String originDir(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object dir = transport == null ? null : transport.get(WorkspaceResolver.WORKSPACE_DIR_KEY);
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
        final String origin = originDir(context);
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
            final String anchorType) {
        final Anchor resolvedAnchor = isBlank(anchor)
                ? requireContextAnchor(context, NO_CONTEXT_ANCHOR_MESSAGE_ADD)
                : new Anchor(anchor, parseAnchorType(anchorType));
        final Project created = registerProject.register(label, resolvedAnchor);
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

    @McpTool(name = "project_list", description = "List all registered projects.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list() {
        final List<Project> all = listProjects.list();
        if (all.isEmpty()) {
            return "(no projects)";
        }
        return all.stream().map(ProjectMcpTools::format).collect(Collectors.joining("\n"));
    }

    /**
     * Renders a project as its label, every anchor it is reachable by (typed, e.g.
     * {@code path:/home/f/DEV/arknet}), and its opaque identity - the identity is not a
     * caller-facing tool parameter anywhere in this adapter, but a later surface without an
     * anchor of its own (issue #149) needs a stable value to address a project by.
     */
    private static String format(final Project project) {
        final String anchors = project.anchors().stream()
                .map(ProjectMcpTools::formatAnchor)
                .collect(Collectors.joining(", "));
        return "%s [%s] (id: %s)".formatted(project.label(), anchors, project.id().value());
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
}
