// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

/**
 * Whether {@code adr_list} had to fall back past the requested/project-default display language
 * for a decision's {@code name} - the only multilingual field {@code adr_list}'s compact line
 * shows - and if so, the language tag of the variant actually shown (kogn-io/arknet#475). Mirrors
 * {@code TermDisplayFallback} (arknet-ubiquitous-language) and its siblings in the other BCs,
 * narrowed to the single field this list line renders (unlike {@code context}/{@code decision}/
 * consequences/considered options, none of which {@code adr_list} ever shows).
 *
 * <p>{@code null} means the requested/default language was found - no fallback, nothing to
 * show. A non-{@code null} value is the tag of the variant actually displayed instead: a BCP-47
 * tag (e.g. {@code "en"}), or the empty string {@code ""} for an untagged legacy literal.</p>
 *
 * @param nameTag the fallen-back tag shown for {@code name}, or {@code null} if none
 */
public record AdrDisplayFallback(String nameTag) {

    /**
     * @return {@code true} if {@code name} did not fall back - nothing worth surfacing in a list
     *         line
     */
    public boolean isEmpty() {
        return nameTag == null;
    }
}
