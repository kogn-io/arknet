package de.hauschel.arknet.mcp;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates the top-level directory of the git working tree that contains a given
 * directory, if any.
 *
 * <p>Used by {@link WorkspaceIdResolver} to derive a per-project workspace
 * identity when none is configured explicitly. Implementations must be lenient:
 * a directory that is not inside a git working tree (or an environment without a
 * usable {@code git}) yields {@link Optional#empty()} rather than an error.</p>
 */
@FunctionalInterface
public interface GitToplevelLocator {

    /**
     * Returns the top-level directory of the git working tree containing
     * {@code dir}.
     *
     * @param dir the directory to inspect
     * @return the git top-level directory, or empty if {@code dir} is not inside a
     *         git working tree or git is unavailable
     */
    Optional<Path> toplevelOf(Path dir);
}
