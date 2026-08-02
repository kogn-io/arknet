// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.regex.Pattern;

/**
 * Turns an arbitrary string into a filesystem-safe path segment: every character outside
 * {@code [A-Za-z0-9._-]} becomes {@code _}. Shared by {@link StoreExportTools} (project label/id)
 * and {@link StoreReportTools} (project id) - both build an on-disk path from a value a client
 * ultimately controls. {@link de.hauschel.arknet.kernel.ProjectId} is, by its own javadoc,
 * "deliberately unconstrained beyond non-blankness"; passing it straight to {@link
 * java.nio.file.Path#resolve} would let a stray {@code /} or filesystem-invalid character reach
 * the path API unfiltered (issue #146).
 */
final class FileNameSanitizer {

    private static final Pattern UNSAFE_CHARS = Pattern.compile("[^A-Za-z0-9._-]");

    private FileNameSanitizer() {
    }

    /**
     * @param value the value to turn into a safe path segment
     * @return {@code value} with every character outside {@code [A-Za-z0-9._-]} replaced by
     *         {@code _}
     */
    static String sanitize(final String value) {
        return UNSAFE_CHARS.matcher(value).replaceAll("_");
    }
}
