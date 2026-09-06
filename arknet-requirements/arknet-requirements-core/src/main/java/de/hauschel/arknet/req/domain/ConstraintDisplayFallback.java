// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

/**
 * Whether {@code constraint_list} had to fall back past the requested/project-default display
 * language for a constraint's {@code title}/{@code statement}, and if so, the language tag of the
 * variant actually shown (kogn-io/arknet#475). Mirrors {@code TermDisplayFallback}
 * (arknet-ubiquitous-language) and {@link RequirementDisplayFallback}, one field pair over.
 *
 * <p>A {@code null} field means that field's requested/default language was found - no fallback,
 * nothing to show. A non-{@code null} field is the tag of the variant actually displayed instead:
 * a BCP-47 tag (e.g. {@code "en"}), or the empty string {@code ""} for an untagged legacy
 * literal.</p>
 *
 * @param titleTag     the fallen-back tag shown for {@code title}, or {@code null} if none
 * @param statementTag the fallen-back tag shown for {@code statement}, or {@code null} if none
 */
public record ConstraintDisplayFallback(String titleTag, String statementTag) {

    /**
     * @return {@code true} if neither field fell back - nothing worth surfacing in a list line
     */
    public boolean isEmpty() {
        return titleTag == null && statementTag == null;
    }
}
