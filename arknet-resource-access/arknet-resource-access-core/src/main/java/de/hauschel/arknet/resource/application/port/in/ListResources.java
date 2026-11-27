// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Lists the resources of a project, of one resource type or of all, in the order of their business
 * codes - compact as one line per resource, or in full as {@link ReadResources} renders them.
 *
 * <p>A listing never cuts silently: it carries the total and the offset the next call continues at.
 * A type is named by prefixed name or by class label; it includes the subclasses of a class, so a
 * superclass lists every resource type below it.</p>
 */
public interface ListResources {

    /** The most resources one compact listing shows. */
    int COMPACT_LIMIT = 200;

    /** The most resources one full listing shows. */
    int FULL_LIMIT = 50;

    /**
     * Lists resources.
     *
     * @param projectId     the project to read
     * @param type          a prefixed name or the label of a resource class, {@code null} or blank for
     *                      every resource type
     * @param status        a status of the type's status field, as prefixed name or label; {@code null}
     *                      or blank for any; only allowed when every listed type has a status field
     * @param displayLocale the display language, {@code null} or blank for the default
     * @param full          whether each resource shows as {@code ReadResources} renders it, with
     *                      {@value #FULL_LIMIT} at most per call, instead of one line with
     *                      {@value #COMPACT_LIMIT} at most
     * @param offset        how many matching resources to skip, in code order; {@code 0} for the first
     * @return the listing
     * @throws IllegalArgumentException if the type is unknown, the status does not fit the type or the
     *                                  offset is negative
     */
    ResourceListing list(ProjectId projectId, String type, String status, String displayLocale, boolean full,
            int offset);
}
