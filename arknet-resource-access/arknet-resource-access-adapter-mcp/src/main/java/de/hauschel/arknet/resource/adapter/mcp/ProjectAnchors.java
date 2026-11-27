// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;

/** Resolves the project of a tool call from its explicit anchor or the anchor its transport carries. */
final class ProjectAnchors {

    private ProjectAnchors() {
    }

    /**
     * Resolves the project of a call.
     *
     * @param projects      the resolver
     * @param context       the request context carrying the transport's anchor, may be {@code null}
     * @param projectAnchor an explicit anchor overriding the context's, blank for none
     * @return the project
     */
    static ResolvedProject resolve(final ProjectResolver projects, final McpSyncRequestContext context,
            final String projectAnchor) {
        String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        McpTransportContext transport = context.transportContext();
        Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }
}
