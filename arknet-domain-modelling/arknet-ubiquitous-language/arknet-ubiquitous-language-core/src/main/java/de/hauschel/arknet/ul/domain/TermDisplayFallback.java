// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

/**
 * Whether {@code term_list} had to fall back past the requested/project-default display language
 * for a term's {@code prefLabel}/{@code definition}, and if so, the language tag of the variant
 * actually shown (kogn-io/arknet#475).
 *
 * <p>{@link de.hauschel.arknet.kernel.DisplayLocale#select} always degrades gracefully and never
 * signals whether it had to - a missing {@code @en} label looks identical to a present one once
 * collapsed to a plain {@link String}. This type carries that signal separately, so {@code
 * term_list} can surface the gap instead of letting it pass as a silent match.</p>
 *
 * <p>A {@code null} field means that field's requested/default language was found - no fallback,
 * nothing to show. A non-{@code null} field is the tag of the variant actually displayed instead:
 * a BCP-47 tag (e.g. {@code "en"}), or the empty string {@code ""} for an untagged legacy
 * literal.</p>
 *
 * @param prefLabelTag  the fallen-back tag shown for {@code prefLabel}, or {@code null} if none
 * @param definitionTag the fallen-back tag shown for {@code definition}, or {@code null} if none
 */
public record TermDisplayFallback(String prefLabelTag, String definitionTag) {

    /**
     * @return {@code true} if neither field fell back - nothing worth surfacing in a list line
     */
    public boolean isEmpty() {
        return prefLabelTag == null && definitionTag == null;
    }
}
