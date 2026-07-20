// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import java.nio.file.Path;
import java.util.Optional;

/**
 * Locates the top-level directory of the <em>main</em> git checkout that a given
 * directory belongs to, if any - the same directory for the main working tree and
 * every {@code git worktree} linked to it, since all of them share one
 * {@code .git} directory (the "git common dir").
 *
 * <p>Used by {@link WorkspaceIdResolver} to derive a per-project workspace
 * identity when none is configured explicitly. Deriving from the common dir
 * rather than each working tree's own top-level is deliberate: a linked worktree
 * must resolve to the same workspace (and therefore the same store) as its main
 * checkout, not to an empty store of its own (see #136). Implementations must be
 * lenient: a directory that is not inside a git working tree (or an environment
 * without a usable {@code git}) yields {@link Optional#empty()} rather than an
 * error.</p>
 */
@FunctionalInterface
public interface GitToplevelLocator {

    /**
     * Returns the top-level directory of the main git checkout containing
     * {@code dir} - identical for the main checkout and any of its linked
     * worktrees.
     *
     * @param dir the directory to inspect
     * @return the main checkout's top-level directory, or empty if {@code dir} is
     *         not inside a git working tree or git is unavailable
     */
    Optional<Path> toplevelOf(Path dir);
}
