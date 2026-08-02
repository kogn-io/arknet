// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

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
 * borrowed a second time (ADR-008) just to ask a question the anchor resolution already had the
 * answer to.</p>
 *
 * @param id              the project a tool call routes to, never {@code null}
 * @param defaultLanguage the project's configured default display language, as a BCP-47 language
 *                        tag (e.g. {@code "de"}), or {@code null} if the project has none
 *                        configured
 */
public record ResolvedProject(ProjectId id, String defaultLanguage) {

    public ResolvedProject {
        Objects.requireNonNull(id, "id");
    }
}
