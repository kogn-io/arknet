package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Unit test for the per-call workspace cache: within the TTL a repeated origin directory must
 * not re-consult git (avoids spawning a process on every call); once the TTL elapses - e.g.
 * because the directory has since become a {@code git worktree} of a different project while
 * the daemon kept running, issue #137 follow-up - it must re-resolve instead of serving a
 * possibly stale entry forever.
 */
class GitWorkspaceResolverTest {

    private static final Path ORIGIN = Path.of("/home/dev/projects/my-app");
    private static final Path FALLBACK = Path.of("/home/dev/projects/fallback");

    @Test
    void cachesWithinTtl() {
        AtomicInteger gitConsultations = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        GitWorkspaceResolver resolver = new GitWorkspaceResolver(
                countingResolver(gitConsultations, Path.of("/home/dev/projects/my-app")), null, FALLBACK,
                clock::get);

        assertThat(resolver.resolve(ORIGIN.toString())).isEqualTo(new WorkspaceId("my-app"));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(30));
        assertThat(resolver.resolve(ORIGIN.toString())).isEqualTo(new WorkspaceId("my-app"));

        assertThat(gitConsultations.get()).isEqualTo(1);
    }

    @Test
    void reResolvesAfterTtlExpires() {
        AtomicInteger gitConsultations = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        GitWorkspaceResolver resolver = new GitWorkspaceResolver(
                countingResolver(gitConsultations, Path.of("/home/dev/projects/my-app")), null, FALLBACK,
                clock::get);

        resolver.resolve(ORIGIN.toString());
        clock.addAndGet(TimeUnit.SECONDS.toNanos(61));
        resolver.resolve(ORIGIN.toString());

        assertThat(gitConsultations.get()).isEqualTo(2);
    }

    @Test
    void reResolvesWhenTheOriginsGitIdentityChanges() {
        AtomicInteger gitConsultations = new AtomicInteger();
        AtomicLong clock = new AtomicLong(0);
        AtomicReference<Path> toplevel = new AtomicReference<>(Path.of("/home/dev/projects/x"));
        WorkspaceIdResolver delegate = new WorkspaceIdResolver(dir -> {
            gitConsultations.incrementAndGet();
            return Optional.of(toplevel.get());
        });
        GitWorkspaceResolver resolver = new GitWorkspaceResolver(delegate, null, FALLBACK, clock::get);

        assertThat(resolver.resolve(ORIGIN.toString())).isEqualTo(new WorkspaceId("x"));

        // ORIGIN was turned into a worktree of a different project (issue #136 scenario) while
        // the daemon kept running.
        toplevel.set(Path.of("/home/dev/projects/y"));
        clock.addAndGet(TimeUnit.SECONDS.toNanos(61));

        assertThat(resolver.resolve(ORIGIN.toString())).isEqualTo(new WorkspaceId("y"));
    }

    @Test
    void fallsBackToServerWorkingDirectoryWhenOriginMissing() {
        GitWorkspaceResolver resolver =
                new GitWorkspaceResolver(new WorkspaceIdResolver(dir -> Optional.empty()), null, FALLBACK);

        assertThat(resolver.resolve(null)).isEqualTo(new WorkspaceId("fallback"));
        assertThat(resolver.resolve("  ")).isEqualTo(new WorkspaceId("fallback"));
    }

    private static WorkspaceIdResolver countingResolver(AtomicInteger gitConsultations, Path toplevel) {
        return new WorkspaceIdResolver(dir -> {
            gitConsultations.incrementAndGet();
            return Optional.of(toplevel);
        });
    }
}
