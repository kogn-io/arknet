// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

/**
 * Whether {@code bc_list} had to fall back past the requested/project-default display language for
 * a bounded context's {@code name}/{@code domainVision}, and if so, the language tag of the variant
 * actually shown. Mirrors {@code RoleDisplayFallback}/{@code ConstraintDisplayFallback}
 * (kogn-io/arknet#475/kogn-io/arknet#520), one resource type over.
 *
 * <p>A {@code null} field means that field's requested/default language was found - no fallback,
 * nothing to show. A non-{@code null} field is the tag of the variant actually displayed instead:
 * a BCP-47 tag (e.g. {@code "en"}), or the empty string {@code ""} for an untagged legacy
 * literal.</p>
 *
 * @param nameTag         the fallen-back tag shown for {@code name}, or {@code null} if none
 * @param domainVisionTag the fallen-back tag shown for {@code domainVision}, or {@code null} if none
 */
public record BoundedContextDisplayFallback(String nameTag, String domainVisionTag) {

    /**
     * @return {@code true} if neither field fell back - nothing worth surfacing in a list line
     */
    public boolean isEmpty() {
        return nameTag == null && domainVisionTag == null;
    }
}
