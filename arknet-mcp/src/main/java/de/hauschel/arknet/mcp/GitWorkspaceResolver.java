package de.hauschel.arknet.mcp;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.kernel.WorkspaceResolver;

/**
 * The composition root's {@link WorkspaceResolver}: maps a tool call's origin directory to a
 * {@link WorkspaceId} through the existing {@link WorkspaceIdResolver} (explicit-id override,
 * else the git top-level / working-directory name; #136).
 *
 * <p>arknet-mcp runs as one shared server for every workspace on the machine (issue #137), so
 * this resolver is called <em>per tool call</em> rather than once at boot. Resolution spawns a
 * {@code git} process to find the common dir, which would be wasteful to repeat for every call
 * from the same project; results are therefore cached by origin directory. A directory's git
 * top-level is <em>usually</em> stable for the daemon's (potentially very long, ADR-009)
 * lifetime, but not always: a directory can be turned into a {@code git worktree} of a different
 * project while the daemon keeps running (the very #136 scenario this repo addresses), which
 * would silently misroute that origin to its old workspace forever. Entries therefore expire
 * after {@link #CACHE_TTL_NANOS} instead of living for the process lifetime, bounding the
 * staleness window while still avoiding a {@code git} spawn on every single call.</p>
 *
 * <p>When a call carries no origin (a client that sends no directory), it falls back to the
 * server's own working directory - the same value the pre-#137 single-workspace boot used - so
 * behaviour degrades to "the daemon's own project" rather than failing.</p>
 */
final class GitWorkspaceResolver implements WorkspaceResolver {

    private static final long CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);

    private final WorkspaceIdResolver delegate;
    private final String explicitId;
    private final Path fallbackDir;
    private final LongSupplier nanoClock;
    private final Map<Path, CacheEntry> cache = new ConcurrentHashMap<>();

    private record CacheEntry(WorkspaceId workspaceId, long cachedAtNanos) {
    }

    /**
     * @param delegate    the underlying id resolver (git top-level derivation, slugging)
     * @param explicitId  a pinned workspace id ({@code arknet.workspace.id}), or {@code null}/blank
     *                    to derive from the directory
     * @param fallbackDir the directory used when a call supplies no origin (the server's own
     *                    working directory)
     */
    GitWorkspaceResolver(final WorkspaceIdResolver delegate, final String explicitId, final Path fallbackDir) {
        this(delegate, explicitId, fallbackDir, System::nanoTime);
    }

    /**
     * Test-only constructor: injects a fake clock so cache-expiry can be exercised without
     * waiting real wall-clock time (mirrors the fake-{@link GitToplevelLocator} pattern
     * {@link WorkspaceIdResolver} uses to avoid spawning a process in tests).
     */
    GitWorkspaceResolver(final WorkspaceIdResolver delegate, final String explicitId, final Path fallbackDir,
            final LongSupplier nanoClock) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.explicitId = explicitId;
        this.fallbackDir = Objects.requireNonNull(fallbackDir, "fallbackDir");
        this.nanoClock = Objects.requireNonNull(nanoClock, "nanoClock");
    }

    @Override
    public WorkspaceId resolve(final String originDir) {
        final Path dir = (originDir == null || originDir.isBlank()) ? fallbackDir : Path.of(originDir);
        final long now = nanoClock.getAsLong();
        final CacheEntry cached = cache.get(dir);
        if (cached != null && now - cached.cachedAtNanos() < CACHE_TTL_NANOS) {
            return cached.workspaceId();
        }
        final WorkspaceId resolved = delegate.resolve(explicitId, dir);
        cache.put(dir, new CacheEntry(resolved, now));
        return resolved;
    }
}
