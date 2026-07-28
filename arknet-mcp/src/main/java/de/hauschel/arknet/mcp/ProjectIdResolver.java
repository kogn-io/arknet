// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Resolves the {@link ProjectId} a single arknet MCP server instance operates
 * against - one server process serves exactly one workspace, so requirements from
 * different projects land in isolated datasets (see
 * {@code KognioRdfRequirementRepository}, which maps {@code ProjectId} 1:1 to a
 * kognio-rdf dataset).
 *
 * <p>Resolution order:</p>
 * <ol>
 *   <li>an explicit id (Spring property {@code arknet.workspace.id}) - used verbatim
 *       (trimmed) if non-blank, so an operator can pin a stable workspace name in a
 *       project's {@code .mcp.json};</li>
 *   <li>otherwise the slugged directory name of the git top-level of the working
 *       directory's <em>main</em> checkout - shared by every linked
 *       {@code git worktree}, so a worktree lands on the same workspace/store as
 *       its main checkout instead of an empty one of its own (#136) - (zero-config
 *       per git project);</li>
 *   <li>otherwise the slugged working-directory name (non-git projects);</li>
 *   <li>otherwise {@link ProjectId#DEFAULT} (e.g. the filesystem root, which has no
 *       usable name).</li>
 * </ol>
 *
 * <p>Only the <em>derived</em> names are slugged (lower-cased, non-alphanumeric runs
 * collapsed to a single hyphen, leading/trailing hyphens trimmed); an explicitly
 * configured id is respected as given.</p>
 */
public final class ProjectIdResolver {

    private final GitToplevelLocator gitToplevelLocator;

    /** Creates a resolver deriving git top-levels via {@link ProcessGitToplevelLocator}. */
    public ProjectIdResolver() {
        this(new ProcessGitToplevelLocator());
    }

    /**
     * Creates a resolver with an explicit git locator (used by tests to avoid
     * spawning a process).
     *
     * @param gitToplevelLocator the git top-level locator (must not be {@code null})
     */
    public ProjectIdResolver(GitToplevelLocator gitToplevelLocator) {
        this.gitToplevelLocator = Objects.requireNonNull(gitToplevelLocator, "gitToplevelLocator");
    }

    /**
     * Resolves the workspace identity per the order documented on this class.
     *
     * @param explicitId the explicitly configured id, or {@code null}/blank if none
     * @param workingDir the server's working directory (typically the launched
     *                   project root); must not be {@code null}
     * @return the resolved {@link ProjectId}, never {@code null}
     */
    public ProjectId resolve(String explicitId, Path workingDir) {
        Objects.requireNonNull(workingDir, "workingDir");
        if (explicitId != null && !explicitId.isBlank()) {
            return new ProjectId(explicitId.trim());
        }
        Path projectDir = gitToplevelLocator.toplevelOf(workingDir).orElse(workingDir);
        String slug = slug(fileName(projectDir));
        return slug.isBlank() ? ProjectId.DEFAULT : new ProjectId(slug);
    }

    private static String fileName(Path dir) {
        Path name = dir.getFileName();
        return name == null ? "" : name.toString();
    }

    private static String slug(String raw) {
        return raw.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-+|-+$", "");
    }
}
