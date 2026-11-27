// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Optional;

/**
 * What {@link ListResources} found: one window of the matching resources in code order.
 *
 * @param total     how many resources match
 * @param offset    how many matching resources the window skips
 * @param limit     the most resources a window of this kind shows
 * @param resources the resources of the window, one line each
 * @param readings  the same resources as {@link ReadResources} renders them, in the same order; empty
 *                  unless the full listing was asked for
 */
public record ResourceListing(int total, int offset, int limit, List<ListedResource> resources,
        List<ResourceReading> readings) {

    /** Copies the lists. */
    public ResourceListing {
        resources = List.copyOf(resources);
        readings = List.copyOf(readings);
    }

    /**
     * The offset that continues the listing.
     *
     * @return the offset of the first resource not shown, empty if the window reaches the end
     */
    public Optional<Integer> nextOffset() {
        int next = offset + resources.size();
        return next < total ? Optional.of(next) : Optional.empty();
    }
}
