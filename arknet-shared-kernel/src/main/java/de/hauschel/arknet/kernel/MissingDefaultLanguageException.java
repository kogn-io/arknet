// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

/**
 * Raised by {@link LanguageTag#resolveWriteLanguage(String, String)} when a write call supplied
 * no explicit {@code language} argument <em>and</em> the project it targets has no configured
 * {@link ResolvedProject#defaultLanguage()} to fall back to.
 *
 * <p>Before this type existed, that combination silently wrote an untagged literal instead of
 * failing - the same untagged write a caller who explicitly wants an untagged literal has no way
 * to distinguish from one who simply forgot {@code language}, and the one every existing
 * language-tagged literal on the same field then collides with the moment it is written
 * (issue #258). Rejecting the call here, before anything is persisted, forces the caller to either
 * name a language explicitly or set the project's default first ({@code project_update}) - there is
 * no third option, and deliberately no fallback to a hard-coded server default (mirrors
 * {@link UnresolvedProjectAnchorException}'s "no default, no guess" stance for an unresolvable
 * anchor).</p>
 */
public class MissingDefaultLanguageException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public MissingDefaultLanguageException() {
        super("no language given and the project has no configured default language - pass an "
                + "explicit language or set one via project_update");
    }
}
