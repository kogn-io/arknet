package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Deterministic check that the process-based locator degrades gracefully: a
 * directory that is not inside a git working tree (a fresh temp dir) yields an
 * empty result. This also covers the git-absent case, since a missing {@code git}
 * binary is mapped to the same empty result.
 */
class ProcessGitToplevelLocatorTest {

    @TempDir
    Path nonGitDir;

    @Test
    void returnsEmptyForDirectoryOutsideAnyGitWorkingTree() {
        assertThat(new ProcessGitToplevelLocator().toplevelOf(nonGitDir)).isEmpty();
    }
}
