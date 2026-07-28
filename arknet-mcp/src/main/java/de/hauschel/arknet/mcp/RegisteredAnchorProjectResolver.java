// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.prj.application.port.in.ResolveProject;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.UnknownAnchorException;

/**
 * The composition root's {@link ProjectResolver}: answers every tool call's routing question by
 * looking the caller's anchor up in the project registry (ADR-016).
 *
 * <p><strong>This class is the switch-over.</strong> Its predecessor derived a {@link ProjectId}
 * from the calling client's directory - {@code slug(basename(git-common-dir))} - and so mapped two
 * identically named directories in different places onto one dataset, mixing their data with
 * nothing to see at either call site (issue #175). Nothing is computed here any more: the anchor
 * arrives opaque, is matched whole against what was registered, and either hits exactly one
 * project or fails. The collision is gone structurally, not by being made less likely.</p>
 *
 * <p><strong>Where the bounded contexts meet.</strong> This is a composition-root class wiring the
 * kernel's {@link ProjectResolver} port onto the project component's {@link ResolveProject} in-port
 * - the same in-adapter-as-gateway role ADR-008 grants, here for routing rather than for display.
 * The four model bounded contexts stay unaware of it: they depend on the neutral port, never on
 * {@code arknet-project}. That direction matters, because the project component is what answers the
 * routing question and therefore cannot itself sit behind an answer to it.</p>
 *
 * <p><strong>Deliberately uncached</strong>, unlike the git-based resolver it replaces. That one
 * cached because resolution spawned a {@code git} process per call, which is expensive enough to be
 * worth a staleness window; this one performs a local store read on a subject the registry indexes
 * directly. Caching it would buy little and cost correctness at the edge that matters most: a
 * project registered or adopted a moment ago must resolve on the very next call, or the remedy the
 * error message just named would appear not to work.</p>
 */
final class RegisteredAnchorProjectResolver implements ProjectResolver {

    /**
     * Remedy for a call that carried no anchor at all. Names both entry points, because the caller
     * may or may not already own a dataset: {@code project_add} for a new project,
     * {@code project_adopt} for data written before ADR-016.
     */
    static final String NO_ANCHOR_MESSAGE =
            "This call carries no project anchor, so the project it belongs to cannot be "
                    + "determined. There is no default project and no fallback to a server-side "
                    + "working directory. Either send the '" + AnchorHttpTransportConfiguration.ANCHOR_HEADER
                    + "' header, or pass the tool's optional 'projectAnchor' parameter with an anchor "
                    + "already registered for your project. Use project_list to see the registered "
                    + "projects, project_add to register a new one, or project_adopt to claim a "
                    + "dataset that existed before projects were registered.";

    private final ResolveProject projects;

    /**
     * @param projects the project component's driving port that resolves an anchor to its project
     */
    RegisteredAnchorProjectResolver(final ResolveProject projects) {
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @Override
    public ProjectId resolve(final String anchor) {
        if (anchor == null || anchor.isBlank()) {
            throw new UnresolvedProjectAnchorException(null, NO_ANCHOR_MESSAGE);
        }
        try {
            return projects.resolve(new Anchor(anchor, AnchorType.PATH)).id();
        } catch (final UnknownAnchorException e) {
            // Translated at the port boundary rather than propagated: the four model bounded
            // contexts see only the kernel's port, and an exception from arknet-project's domain
            // crossing into them would make them depend on the very component they must not know.
            throw new UnresolvedProjectAnchorException(anchor, unknownAnchorMessage(anchor), e);
        }
    }

    /**
     * The remedy for an anchor nobody registered. Deliberately distinct from
     * {@link #NO_ANCHOR_MESSAGE}: the caller here <em>did</em> send something, so telling it to
     * send an anchor would read as if the server had not received one, and it would look for a
     * transport fault instead of registering the project it is actually missing.
     *
     * <p>The {@link AnchorType#PATH} above is not part of the lookup: an anchor's identity is its
     * value alone (see {@code Anchor#equals}), so the type never influences which project is hit
     * and is only a constructor requirement here.</p>
     */
    private static String unknownAnchorMessage(final String anchor) {
        return "No project is registered for anchor '" + anchor + "'. An unknown anchor is an error, "
                + "not a route to a default project - arknet will not guess which project this call "
                + "belongs to. Call project_add to register this anchor as a new project, or "
                + "project_adopt to attach it to a dataset that already holds this project's data. "
                + "project_list shows what is registered.";
    }
}
