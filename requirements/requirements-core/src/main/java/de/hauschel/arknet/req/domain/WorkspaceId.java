package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * Identity of a workspace, i.e. which architecture model a requirement belongs to.
 *
 * <p>Value object wrapping the routing key used to address a workspace. A local
 * single-user deployment operates against exactly one workspace ({@link #DEFAULT});
 * a remote/team deployment (backed by kognio-memory) will use this same type as the
 * mandatory routing key to address one of several workspaces/projects.</p>
 *
 * @param value the non-blank workspace identifier
 */
public record WorkspaceId(String value) {

    /** The implicit workspace used by the local single-user adapter. */
    public static final WorkspaceId DEFAULT = new WorkspaceId("default");

    public WorkspaceId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) {
            throw new IllegalArgumentException("WorkspaceId must not be blank");
        }
    }
}
