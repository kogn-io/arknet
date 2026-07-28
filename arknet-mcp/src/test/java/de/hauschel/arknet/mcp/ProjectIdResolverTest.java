// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Unit test for the workspace resolution chain. A fake {@link GitToplevelLocator}
 * stands in for git so the four resolution branches (explicit, git top-level,
 * working directory, default) are exercised deterministically without spawning a
 * process.
 */
class ProjectIdResolverTest {

    private static final Path WORKING_DIR = Path.of("/home/dev/projects/my-app");

    @Test
    void explicitIdWinsAndGitIsNotConsulted() {
        ProjectIdResolver resolver = new ProjectIdResolver(dir -> {
            throw new AssertionError("git must not be consulted when an explicit id is set");
        });

        assertThat(resolver.resolve("noistill", WORKING_DIR)).isEqualTo(new ProjectId("noistill"));
    }

    @Test
    void explicitIdIsTrimmed() {
        ProjectIdResolver resolver = new ProjectIdResolver(dir -> Optional.empty());

        assertThat(resolver.resolve("  team-x  ", WORKING_DIR)).isEqualTo(new ProjectId("team-x"));
    }

    @Test
    void derivesSluggedGitToplevelNameWhenExplicitBlank() {
        ProjectIdResolver resolver =
                new ProjectIdResolver(dir -> Optional.of(Path.of("/home/dev/projects/ArkNet")));

        assertThat(resolver.resolve("   ", WORKING_DIR)).isEqualTo(new ProjectId("arknet"));
    }

    @Test
    void derivesGitToplevelNameWhenExplicitNull() {
        ProjectIdResolver resolver =
                new ProjectIdResolver(dir -> Optional.of(Path.of("/home/dev/projects/arknet-issue-26")));

        assertThat(resolver.resolve(null, WORKING_DIR)).isEqualTo(new ProjectId("arknet-issue-26"));
    }

    @Test
    void fallsBackToSluggedWorkingDirNameWhenNoGit() {
        ProjectIdResolver resolver = new ProjectIdResolver(dir -> Optional.empty());

        assertThat(resolver.resolve(null, Path.of("/home/dev/projects/My Project!")))
                .isEqualTo(new ProjectId("my-project"));
    }

    @Test
    void fallsBackToDefaultWhenNameHasNoUsableCharacters() {
        ProjectIdResolver resolver = new ProjectIdResolver(dir -> Optional.empty());

        assertThat(resolver.resolve(null, Path.of("/"))).isEqualTo(ProjectId.DEFAULT);
    }

    @Test
    void rejectsNullWorkingDir() {
        ProjectIdResolver resolver = new ProjectIdResolver(dir -> Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullLocator() {
        assertThatThrownBy(() -> new ProjectIdResolver(null)).isInstanceOf(NullPointerException.class);
    }
}
