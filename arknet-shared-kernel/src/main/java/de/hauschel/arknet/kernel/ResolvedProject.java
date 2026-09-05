// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.List;
import java.util.Objects;

/**
 * The result of resolving a client-supplied anchor (see {@link ProjectResolver}): the
 * {@link ProjectId} a tool call routes to, together with that project's default display
 * language, if it has one.
 *
 * <p>Bundled into one result rather than two separate lookups because both values come from the
 * very same registry read that {@link ProjectResolver#resolve(String)} already performs on
 * every tool call - a bounded context that needs the default language (today, ubiquitous-language,
 * for picking a term's write/display language when a caller does not state one explicitly) gets it
 * "for free" alongside the routing id, rather than the composition root's project component being
 * borrowed a second time just to ask a question the anchor resolution already had the
 * answer to.</p>
 *
 * @param id                  the project a tool call routes to, never {@code null}
 * @param defaultLanguage     the project's configured default display language, as a BCP-47
 *                            language tag (e.g. {@code "de"}), or {@code null} if the project has
 *                            none configured. A <em>fallback</em>: which language a call that
 *                            names none is written and read under
 * @param maintainedLanguages the BCP-47 tags of the languages the project undertakes to maintain
 *                            its model in (kogn-io/arknet#412), never {@code null} but empty for a
 *                            project that declares no such set. A <em>commitment</em>, and
 *                            therefore the only thing against which a field carrying some
 *                            languages but not others can be called incomplete at all - which is
 *                            why {@code store_check} reads it from here rather than asking the
 *                            project component a second time
 */
public record ResolvedProject(ProjectId id, String defaultLanguage, List<String> maintainedLanguages) {

    public ResolvedProject {
        Objects.requireNonNull(id, "id");
        maintainedLanguages = maintainedLanguages == null ? List.of() : List.copyOf(maintainedLanguages);
    }

    /**
     * Resolution result of a project that declares no maintained language set - the shape every
     * call site had before kogn-io/arknet#412 added one, kept as a secondary constructor so a
     * caller that only ever cared about the routing id and the fallback language did not have to
     * start passing an empty list.
     */
    public ResolvedProject(ProjectId id, String defaultLanguage) {
        this(id, defaultLanguage, List.of());
    }
}
