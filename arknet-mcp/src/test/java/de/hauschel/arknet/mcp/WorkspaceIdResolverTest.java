package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Unit test for the workspace resolution chain. A fake {@link GitToplevelLocator}
 * stands in for git so the four resolution branches (explicit, git top-level,
 * working directory, default) are exercised deterministically without spawning a
 * process.
 */
class WorkspaceIdResolverTest {

    private static final Path WORKING_DIR = Path.of("/home/dev/projects/my-app");

    @Test
    void explicitIdWinsAndGitIsNotConsulted() {
        WorkspaceIdResolver resolver = new WorkspaceIdResolver(dir -> {
            throw new AssertionError("git must not be consulted when an explicit id is set");
        });

        assertThat(resolver.resolve("noistill", WORKING_DIR)).isEqualTo(new WorkspaceId("noistill"));
    }

    @Test
    void explicitIdIsTrimmed() {
        WorkspaceIdResolver resolver = new WorkspaceIdResolver(dir -> Optional.empty());

        assertThat(resolver.resolve("  team-x  ", WORKING_DIR)).isEqualTo(new WorkspaceId("team-x"));
    }

    @Test
    void derivesSluggedGitToplevelNameWhenExplicitBlank() {
        WorkspaceIdResolver resolver =
                new WorkspaceIdResolver(dir -> Optional.of(Path.of("/home/dev/projects/ArkNet")));

        assertThat(resolver.resolve("   ", WORKING_DIR)).isEqualTo(new WorkspaceId("arknet"));
    }

    @Test
    void derivesGitToplevelNameWhenExplicitNull() {
        WorkspaceIdResolver resolver =
                new WorkspaceIdResolver(dir -> Optional.of(Path.of("/home/dev/projects/arknet-issue-26")));

        assertThat(resolver.resolve(null, WORKING_DIR)).isEqualTo(new WorkspaceId("arknet-issue-26"));
    }

    @Test
    void fallsBackToSluggedWorkingDirNameWhenNoGit() {
        WorkspaceIdResolver resolver = new WorkspaceIdResolver(dir -> Optional.empty());

        assertThat(resolver.resolve(null, Path.of("/home/dev/projects/My Project!")))
                .isEqualTo(new WorkspaceId("my-project"));
    }

    @Test
    void fallsBackToDefaultWhenNameHasNoUsableCharacters() {
        WorkspaceIdResolver resolver = new WorkspaceIdResolver(dir -> Optional.empty());

        assertThat(resolver.resolve(null, Path.of("/"))).isEqualTo(WorkspaceId.DEFAULT);
    }

    @Test
    void rejectsNullWorkingDir() {
        WorkspaceIdResolver resolver = new WorkspaceIdResolver(dir -> Optional.empty());

        assertThatThrownBy(() -> resolver.resolve(null, null)).isInstanceOf(NullPointerException.class);
    }

    @Test
    void rejectsNullLocator() {
        assertThatThrownBy(() -> new WorkspaceIdResolver(null)).isInstanceOf(NullPointerException.class);
    }
}
