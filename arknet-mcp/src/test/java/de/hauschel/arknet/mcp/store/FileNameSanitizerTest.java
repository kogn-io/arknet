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
}
