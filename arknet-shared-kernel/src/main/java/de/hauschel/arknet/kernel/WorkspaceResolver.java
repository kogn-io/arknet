package de.hauschel.arknet.kernel;

/**
 * Resolves which {@link WorkspaceId} a single tool call targets from the origin
 * directory the calling client supplied - its project root.
 *
 * <p>arknet-mcp runs as one shared server for every workspace on the machine (issue #137):
 * a single process, a single port, no per-workspace daemon. There is therefore no longer
 * one {@link WorkspaceId} per process to inject as a singleton; instead every in-adapter
 * resolves the workspace <em>per call</em> from the request's origin directory. The
 * concrete resolution (git top-level derivation, slugging, explicit-id override) stays in
 * the composition root's implementation - a bounded context only depends on this neutral
 * port, never on the transport or on git.</p>
 *
 * <p>This is a shared-kernel concept for the same reason {@link WorkspaceId} is: several
 * bounded contexts (requirements, ubiquitous-language, ...) all address the same
 * per-project workspace and therefore share one way of resolving it rather than each
 * inventing its own.</p>
 */
public interface WorkspaceResolver {

    /**
     * Key under which the client's origin directory travels in the MCP transport context.
     * Both the server-side context extractor (which reads it off the request header) and
     * the in-adapters (which pass it to {@link #resolve(String)}) must agree on this name,
     * so it lives on the shared port both sides see. The value carried under it is the
     * project root the client started in (its {@code ${PWD}}).
     */
    String WORKSPACE_DIR_KEY = "arknet.workspace.dir";

    /**
     * Resolves the workspace a tool call targets from the {@code originDir} the client
     * supplied. Falls back to the server's own default workspace when {@code originDir} is
     * {@code null} or blank (e.g. a client that sends no origin), so a call always routes
     * to <em>some</em> workspace and never fails for a missing header.
     *
     * @param originDir the calling client's project root, or {@code null}/blank if none was
     *                  supplied
     * @return the resolved workspace, never {@code null}
     */
    WorkspaceId resolve(String originDir);
}
