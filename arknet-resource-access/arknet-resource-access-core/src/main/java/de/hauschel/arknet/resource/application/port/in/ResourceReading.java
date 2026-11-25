// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** What {@link ReadResources} found for one handle. */
public sealed interface ResourceReading
        permits ResourceReading.Found, ResourceReading.Deleted, ResourceReading.Unresolved {

    /**
     * The handle as the caller wrote it.
     *
     * @return the handle
     */
    String handle();

    /**
     * A living resource.
     *
     * @param handle   the handle as written
     * @param resource the rendered resource
     */
    record Found(String handle, RenderedResource resource) implements ResourceReading {

        /** Checks the components. */
        public Found {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(resource, "resource");
        }
    }

    /**
     * A deleted resource, or the code it kept.
     *
     * @param handle      the handle as written
     * @param iri         the IRI of the deleted resource
     * @param deletedAt   when it was deleted, empty for a deletion from before versioning
     * @param comment     the comment of the deletion, if any
     * @param code        the business code it kept, if any
     * @param lastVersion the fields of its last version, rendered like a living resource's; empty if
     *                    not recorded
     */
    record Deleted(String handle, String iri, Optional<String> deletedAt, Optional<String> comment,
            Optional<String> code, List<RenderedField> lastVersion) implements ResourceReading {

        /** Checks the components. */
        public Deleted {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(iri, "iri");
            Objects.requireNonNull(deletedAt, "deletedAt");
            Objects.requireNonNull(comment, "comment");
            Objects.requireNonNull(code, "code");
            lastVersion = List.copyOf(lastVersion);
        }
    }

    /**
     * A handle that names no resource, or more than one.
     *
     * @param handle the handle as written
     * @param reason why it resolves to nothing
     */
    record Unresolved(String handle, String reason) implements ResourceReading {

        /** Checks the components. */
        public Unresolved {
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(reason, "reason");
        }
    }
}
