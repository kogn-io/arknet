// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
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
    private static final String HASH_ALGORITHM = "SHA-256";
    private static final int HASH_SUFFIX_HEX_CHARS = 16;

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

    /**
     * Collision-resistant variant of {@link #sanitize(String)} for a caller that builds a single
     * filesystem path segment from one value with no second, independent field to pair it with
     * (unlike {@link StoreExportTools#exportOne}, which pairs a project's label with its id).
     * {@link #sanitize(String)} alone is not injective - every character outside the safe set
     * collapses onto the identical {@code _}, so two different raw values can sanitize to the
     * same segment (e.g. {@code "team/main"} and {@code "team_main"} both become
     * {@code "team_main"}). Two projects mapped onto the same on-disk segment would silently
     * share, and overwrite, one directory - exactly the isolation a project-scoped subdirectory
     * exists to guarantee (issue #147/#146 follow-up).
     *
     * <p>Appending a deterministic digest of the raw value closes that gap: distinct inputs that
     * collapse onto the same sanitized prefix still diverge in their hash suffix with
     * overwhelming probability, while the very same raw value reproduces the identical segment on
     * every call - required so a later call resolves the same directory an earlier one wrote to.
     * As a side effect, the segment can no longer literally equal {@code ".."} for any input (the
     * hash suffix never sanitizes away), which makes {@link StoreReportTools}'s
     * {@code fallbackDirFor} containment check unreachable rather than merely unlikely.</p>
     *
     * @param value the value to turn into a collision-resistant, filesystem-safe path segment
     * @return {@link #sanitize(String)} of {@code value}, followed by {@code -} and a hex digest
     *         of {@code value}
     */
    static String uniqueSegment(final String value) {
        return sanitize(value) + "-" + hexDigest(value);
    }

    private static String hexDigest(final String value) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance(HASH_ALGORITHM);
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(HASH_ALGORITHM + " is a JDK-mandated algorithm", impossible);
        }
        final byte[] hashed = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hashed, 0, HASH_SUFFIX_HEX_CHARS / 2);
    }
}
