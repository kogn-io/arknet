// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import java.util.List;

import io.modelcontextprotocol.server.transport.DefaultServerTransportSecurityValidator;

/**
 * The one loopback-only Host allowlist every HTTP entry point of this daemon enforces against
 * DNS rebinding: the MCP transport
 * ({@link AnchorHttpTransportConfiguration}) and, since issue #391, the plain
 * {@code @RestController} report endpoint alike.
 *
 * <p>Extracted into one shared source rather than left as two literal copies of
 * {@code "127.0.0.1:*"}/{@code "localhost:*"}: a future change to this allowlist - say, a third
 * loopback spelling - must reach both enforcement points, and a single source is the only way a
 * change here cannot update one and silently leave the other behind.</p>
 */
public final class LoopbackHostSecurity {

    private LoopbackHostSecurity() {
    }

    /**
     * The daemon's fixed loopback host, in both the numeric and the {@code localhost} spelling a
     * browser's Host header may carry - port left as a {@code :*} wildcard rather than pinned to
     * the {@code application.properties} default, because {@code server.port} is itself
     * overridable via {@code arknet.mcp.port} and a validator pinned to the default port would
     * reject every request against an overridden one with no explanation (issue #295). The
     * wildcard is security-equivalent to a pinned port: the DNS-rebinding defense keys on the host
     * name, not the port, so any port on {@code 127.0.0.1}/{@code localhost} is still only
     * reachable from this machine.
     */
    private static final List<String> ALLOWED_HOSTS = List.of("127.0.0.1:*", "localhost:*");

    /**
     * A fresh validator over {@link #ALLOWED_HOSTS}. {@link DefaultServerTransportSecurityValidator}
     * carries no mutable state, so a caller may build one per bean without concern for sharing.
     */
    public static DefaultServerTransportSecurityValidator hostValidator() {
        return DefaultServerTransportSecurityValidator.builder().allowedHosts(ALLOWED_HOSTS).build();
    }
}
