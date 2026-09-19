// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

/**
 * Whether {@code uc_list} had to fall back past the requested/project-default display language
 * for a use case's {@code title}/{@code goal} - the two fields {@code uc_list}'s compact line
 * shows - and if so, the language tag of the variant actually shown (kogn-io/arknet#475). Mirrors
 * {@code TermDisplayFallback} (arknet-ubiquitous-language) and {@code RequirementDisplayFallback}
 * (arknet-requirements), one BC over; deliberately narrower than {@code UseCase}'s full set of
 * multilingual fields (scope/trigger/precondition/postcondition/steps/extensions), none of which
 * {@code uc_list} ever renders.
 *
 * <p>A {@code null} field means that field's requested/default language was found - no fallback,
 * nothing to show. A non-{@code null} field is the tag of the variant actually displayed instead:
 * a BCP-47 tag (e.g. {@code "en"}), or the empty string {@code ""} for an untagged legacy
 * literal.</p>
 *
 * @param titleTag the fallen-back tag shown for {@code title}, or {@code null} if none
 * @param goalTag  the fallen-back tag shown for {@code goal}, or {@code null} if none
 */
public record UseCaseDisplayFallback(String titleTag, String goalTag) {

    /**
     * @return {@code true} if neither field fell back - nothing worth surfacing in a list line
     */
    public boolean isEmpty() {
        return titleTag == null && goalTag == null;
    }
}
