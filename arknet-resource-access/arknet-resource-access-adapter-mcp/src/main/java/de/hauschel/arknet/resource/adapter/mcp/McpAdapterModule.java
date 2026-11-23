// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

/**
 * Marks the MCP driving adapter of generic resource access before it holds a tool class.
 *
 * <p>The module carries the MCP tools of generic resource access and calls the in-ports of
 * {@code arknet-resource-access-core}. Until the first tool class exists, this one gives
 * {@code arknet-architecture-tests} a class to see in the module; a rule whose {@code that()}
 * clause matches nothing fails rather than passes. It goes away with the first tool class.</p>
 */
public final class McpAdapterModule {

    private McpAdapterModule() {
    }
}
