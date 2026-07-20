// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.kernel.WorkspaceResolver;

/**
 * Resolves a resource handle - CURIE, full IRI, or bare business id - to an absolute IRI.
 *
 * <p>Extracted out of {@link StoreReportTools} (issue #131) so the traceability tools
 * ({@code trace_matrix}/{@code orphan_check}/{@code impact_analysis}, package
 * {@code de.hauschel.arknet.mcp.trace}) share the exact same handle contract
 * {@code resource_get} uses, instead of a second, drifting implementation growing next to
 * it.</p>
 *
 * <p>Resolution order: (1) a full IRI, or a CURIE against a known {@link Prefixes} binding, is
 * authoritative; (2) anything else is a bare business id, resolved via
 * {@link StoreReader#findByIdentifier} ({@code dcterms:identifier}), rejecting ambiguity across
 * bounded contexts with a didactic message instead of guessing. Domain-agnostic, like
 * {@link StoreReader}/{@link Prefixes}: it knows nothing about requirements, terms or use
 * cases.</p>
 */
public final class HandleResolver {

    private final StoreReader storeReader;
    private final Prefixes prefixes;

    /**
     * @param storeReader the generic store read path, used for the bare-id fallback lookup
     * @param prefixes    the CURIE / IRI resolver
     */
    public HandleResolver(StoreReader storeReader, Prefixes prefixes) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
    }

    /**
     * Resolves a handle to an absolute IRI, following the contract described in the class-level
     * Javadoc.
     *
     * @param workspaceId the workspace to resolve a bare business id against
     * @param id          the handle: a CURIE (e.g. {@code req:FR-1}), a full IRI, or a bare
     *                    business id (e.g. {@code FR-1})
     * @return the resolved absolute IRI
     * @throws IllegalArgumentException if the handle is empty, uses an unknown prefix, or a
     *                                  bare id resolves to zero or more than one resource
     */
    public String resolve(WorkspaceId workspaceId, String id) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        final String handle = Objects.requireNonNull(id, "id").strip();
        if (handle.isEmpty()) {
            throw new IllegalArgumentException("Empty resource handle. Pass a CURIE (req:FR-1),"
                    + " a full IRI, or a bare business id (FR-1).");
        }

        final Optional<String> resolved = prefixes.toIri(handle);
        if (resolved.isPresent()) {
            return resolved.get();
        }

        // A colon that is not part of a scheme means a CURIE with an unknown prefix - do not
        // guess, explain (the handle contract is CURIE/IRI first).
        if (handle.contains(":") && !handle.contains("://")) {
            final String known = prefixes.bindings().stream()
                    .map(Prefixes.Prefix::prefix).sorted().reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException("Unknown prefix in handle '" + handle + "'."
                    + " Known prefixes: " + known + ". Pass a full IRI instead, or a bare business id.");
        }

        // Bare business id: resolve via dcterms:identifier; reject ambiguity across contexts.
        final List<String> matches = storeReader.findByIdentifier(workspaceId, handle);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No resource found for id '" + handle + "'."
                    + " Use a CURIE (req:FR-1) or full IRI, or check the id via store_overview.");
        }
        if (matches.size() > 1) {
            final String candidates = matches.stream()
                    .map(prefixes::toCurie).reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException("Ambiguous id '" + handle + "' matches several resources"
                    + " across bounded contexts: " + candidates + ". Re-call with the exact CURIE or IRI"
                    + " of the one you mean.");
        }
        return matches.get(0);
    }

    /**
     * Resolves the optional {@code workspace} argument every read tool accepts to a concrete
     * {@link WorkspaceId}: the trimmed value when present, otherwise the server's default. Shared
     * by {@link StoreReportTools} ({@code store_overview}/{@code resource_get}) and the
     * traceability tools ({@code trace_matrix}/{@code orphan_check}/{@code impact_analysis}),
     * which each expose the same optional-workspace parameter, instead of each carrying its own
     * copy of the blank-check-and-default fallback.
     *
     * @param workspace          the raw tool argument, may be {@code null} or blank
     * @param defaultWorkspaceId the workspace used when {@code workspace} is null or blank
     * @return the resolved workspace id
     */
    public static WorkspaceId resolveWorkspace(String workspace, WorkspaceId defaultWorkspaceId) {
        Objects.requireNonNull(defaultWorkspaceId, "defaultWorkspaceId");
        return (workspace == null || workspace.isBlank())
                ? defaultWorkspaceId
                : new WorkspaceId(workspace.trim());
    }

    /**
     * Extracts the calling client's origin directory from the per-call transport context (issue
     * #137). Null-tolerant on every hop; a {@code null} result is turned into the server's
     * default workspace by {@link WorkspaceResolver}. Shared by {@link StoreReportTools} and
     * {@code de.hauschel.arknet.mcp.trace.TraceabilityMcpTools} instead of each carrying its own
     * copy, the same reasoning as {@link #resolveWorkspace}.
     *
     * @param context the per-call request context, may itself be {@code null}
     * @return the origin directory, or {@code null} if none was supplied
     */
    public static String originDir(McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object dir = transport == null ? null : transport.get(WorkspaceResolver.WORKSPACE_DIR_KEY);
        return dir == null ? null : dir.toString();
    }
}
