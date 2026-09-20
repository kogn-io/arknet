// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link FileNameSanitizer}. */
class FileNameSanitizerTest {

    @Test
    void leavesAlphanumericDotHyphenAndUnderscoreUnchanged() {
        assertThat(FileNameSanitizer.sanitize("sample-project_1.0")).isEqualTo("sample-project_1.0");
    }

    @Test
    void replacesEveryFilesystemUnsafeCharacterWithAnUnderscore() {
        assertThat(FileNameSanitizer.sanitize("team/main (staging)")).isEqualTo("team_main__staging_");
    }

    @Test
    void replacesAPathTraversalAttemptsSlashesButLeavesTheDotsThemselvesUntouched() {
        // The dots alone are filesystem-safe characters; only the slashes are replaced. Callers
        // resolving this as a single path segment must still guard against the result being
        // exactly ".." (see StoreReportTools#fallbackDirFor's normalize()/containment check).
        assertThat(FileNameSanitizer.sanitize("../../etc")).isEqualTo(".._.._etc");
        assertThat(FileNameSanitizer.sanitize("..")).isEqualTo("..");
    }

    @Test
    void uniqueSegmentAppendsAHexDigestOfTheRawValueAfterTheSanitizedPrefix() {
        assertThat(FileNameSanitizer.uniqueSegment("sample-project"))
                .matches("sample-project-\\p{XDigit}{16}");
    }

    @Test
    void uniqueSegmentIsDeterministicForTheSameRawValue() {
        assertThat(FileNameSanitizer.uniqueSegment("team/main"))
                .isEqualTo(FileNameSanitizer.uniqueSegment("team/main"));
    }

    /**
     * Regression test for the #147 review follow-up (P1): {@link FileNameSanitizer#sanitize}
     * alone is not injective - two different raw values sanitizing to the identical prefix (here
     * {@code "team/main"} and {@code "team_main"}, both {@code "team_main"}) let two different
     * projects collide on one on-disk segment. {@link FileNameSanitizer#uniqueSegment} closes that
     * gap with a hash of the raw value, so the two remain distinguishable.
     */
    @Test
    void uniqueSegmentStaysDistinctForRawValuesThatCollideUnderPlainSanitizing() {
        assertThat(FileNameSanitizer.sanitize("team/main")).isEqualTo(FileNameSanitizer.sanitize("team_main"));

        assertThat(FileNameSanitizer.uniqueSegment("team/main"))
                .isNotEqualTo(FileNameSanitizer.uniqueSegment("team_main"));
    }

    /**
     * The hash suffix means a value that used to sanitize to exactly {@code ".."} (a path
     * traversal component) can no longer do so - {@code uniqueSegment} always appends extra
     * characters after the sanitized prefix.
     */
    @Test
    void uniqueSegmentNeverEqualsExactlyDotDot() {
        assertThat(FileNameSanitizer.uniqueSegment("..")).isNotEqualTo("..");
    }
}
