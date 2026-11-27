// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import de.hauschel.arknet.resource.application.port.in.ListedResource;
import de.hauschel.arknet.resource.application.port.in.ResourceListing;

/**
 * Renders what {@code ListResources} returns as the text of {@code resource_list}: one line per
 * resource, or the {@code resource_get} rendering of each, and always the position in the whole
 * listing, so a listing that does not fit says what is left and where to continue.
 */
final class ListingRenderer {

    private static final String PARAMETER_OFFSET = "offset";

    private ListingRenderer() {
    }

    /**
     * Renders one window of a listing.
     *
     * @param listing the listing
     * @param full    whether the full rendering of each resource was asked for
     * @return the text
     */
    static String listing(final ResourceListing listing, final boolean full) {
        StringBuilder out = new StringBuilder();
        if (listing.total() == 0) {
            return out.append("No resources match.\n").toString();
        }
        if (listing.resources().isEmpty()) {
            return out.append("# ").append(listing.total()).append(" resources match; the offset ")
                    .append(listing.offset()).append(" is past the last one. Call again with a smaller ")
                    .append(PARAMETER_OFFSET).append(".\n").toString();
        }
        out.append("# Resources ").append(listing.offset() + 1).append('-')
                .append(listing.offset() + listing.resources().size()).append(" of ").append(listing.total())
                .append(", in code order\n");
        if (full) {
            out.append('\n').append(ReadingRenderer.readings(listing.readings()));
        } else {
            listing.resources().forEach(row -> line(out, row));
        }
        listing.nextOffset().ifPresent(next -> out.append("\nNOT SHOWN: ").append(listing.total() - next)
                .append(" more of ").append(listing.total()).append(" (at most ").append(listing.limit())
                .append(" per call). Call again with ").append(PARAMETER_OFFSET).append('=').append(next)
                .append(" to continue.\n"));
        return out.toString();
    }

    private static void line(final StringBuilder out, final ListedResource row) {
        out.append("- ").append(row.handle()).append(" [").append(row.typeLabel()).append(']');
        row.label().ifPresent(label -> out.append(' ').append(ReadingRenderer.text(label)));
        row.status().ifPresent(status -> out.append(" - Status: ").append(ReadingRenderer.reference(status)));
        out.append('\n');
    }
}
