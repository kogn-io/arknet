package de.hauschel.arknet.mcp;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

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
 * top-level is stable for the process lifetime, so the cache never goes stale, and the number
 * of distinct origins on one machine is small and bounded.</p>
 *
 * <p>When a call carries no origin (a client that sends no directory), it falls back to the
 * server's own working directory - the same value the pre-#137 single-workspace boot used - so
 * behaviour degrades to "the daemon's own project" rather than failing.</p>
 */
final class GitWorkspaceResolver implements WorkspaceResolver {

    private final WorkspaceIdResolver delegate;
    private final String explicitId;
    private final Path fallbackDir;
    private final Map<Path, WorkspaceId> cache = new ConcurrentHashMap<>();

    /**
     * @param delegate    the underlying id resolver (git top-level derivation, slugging)
     * @param explicitId  a pinned workspace id ({@code arknet.workspace.id}), or {@code null}/blank
     *                    to derive from the directory
     * @param fallbackDir the directory used when a call supplies no origin (the server's own
     *                    working directory)
     */
    GitWorkspaceResolver(final WorkspaceIdResolver delegate, final String explicitId, final Path fallbackDir) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.explicitId = explicitId;
        this.fallbackDir = Objects.requireNonNull(fallbackDir, "fallbackDir");
    }

    @Override
    public WorkspaceId resolve(final String originDir) {
        final Path dir = (originDir == null || originDir.isBlank()) ? fallbackDir : Path.of(originDir);
        return cache.computeIfAbsent(dir, d -> delegate.resolve(explicitId, d));
    }
}
