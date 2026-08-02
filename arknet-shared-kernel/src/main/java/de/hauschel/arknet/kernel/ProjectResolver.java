// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

/**
 * Resolves which {@link ProjectId} a single tool call targets, from the opaque anchor the calling
 * client supplied.
 *
 * <p>arknet-mcp runs as one shared server for every project on the machine (ADR-009): a single
 * process, a single port, no per-project daemon. There is therefore no single {@link ProjectId}
 * per process to inject as a singleton; instead every in-adapter resolves the project <em>per
 * call</em> from the anchor the request carries.</p>
 *
 * <p><strong>Looked up, never derived (ADR-016).</strong> An anchor is an opaque string a client
 * registered beforehand - the server matches it against the project registry and does not
 * interpret it. It used to be the client's working directory, from which the server
 * <em>computed</em> an id by slugging its git top-level's basename; two identically named
 * directories in different places therefore collapsed onto one store, silently mixing two
 * projects' data. The slug was not invertible, so no amount of care at the call site
 * could have caught that. Resolution is now a registry lookup on a value nothing shortens, which
 * makes the collision structurally impossible rather than unlikely.</p>
 *
 * <p><strong>No default, no fallback (ADR-016 decision 3).</strong> A missing or unregistered
 * anchor raises {@link UnresolvedProjectAnchorException}; it never routes to a server-side working
 * directory or to some implicit default project. There is deliberately no "resolve or return
 * empty" variant: a call site handed an {@link java.util.Optional} would be tempted to invent
 * exactly the silent fallback this design removes, and a write whose project is unclear is the
 * very failure this closes off.</p>
 *
 * <p>This is a shared-kernel concept for the same reason {@link ProjectId} is: several bounded
 * contexts (requirements, ubiquitous-language, use-cases, bounded-context) all address the same
 * per-project dataset and therefore share one way of resolving it rather than each inventing its
 * own. The implementation - which registry answers the lookup - stays in the composition root; a
 * bounded context depends only on this neutral port, never on the transport and never on the
 * project component whose registry ultimately answers.</p>
 */
public interface ProjectResolver {

    /**
     * Key under which the calling client's anchor travels in the MCP transport context. Both the
     * server-side context extractor (which reads it off the request header) and the in-adapters
     * (which pass it to {@link #resolve(String)}) must agree on this name, so it lives on the
     * shared port both sides see.
     */
    String ANCHOR_KEY = "arknet.project.anchor";

    /**
     * Resolves the project a tool call targets from the {@code anchor} the client supplied.
     *
     * @param anchor the opaque, registered anchor identifying the calling client's project, or
     *               {@code null}/blank if the call carried none
     * @return the resolved project identity, together with its configured default display
     *         language if it has one (see {@link ResolvedProject}); never {@code null}
     * @throws UnresolvedProjectAnchorException if {@code anchor} is {@code null}, blank, or not
     *                                          registered with any project - both are caller
     *                                          errors, never a route to a default
     */
    ResolvedProject resolve(String anchor);
}
