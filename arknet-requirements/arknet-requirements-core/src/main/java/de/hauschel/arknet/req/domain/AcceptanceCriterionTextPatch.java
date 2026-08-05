// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * A text-only correction for the existing acceptance criterion at {@code position}: every other
 * criterion, and the very existence/ordering of every criterion, is untouched.
 *
 * <p>{@code text} is mandatory and must not be blank: a patch is a correction, not a way to
 * accidentally clear or no-op a criterion's wording while still reporting success - see
 * {@link AcceptanceCriterion#text()}, whose invariant this mirrors. Mirrors
 * {@code de.hauschel.arknet.uc.domain.StepTextPatch} (issue #266): in-place-only, no reorder - see
 * {@link Requirement#withAcceptanceCriteriaTextPatches} for why that restriction is deliberate
 * here (position is the only identity a criterion has, and this bounded context never lets a
 * caller renumber one).</p>
 *
 * @param position the 1-based position of the existing criterion to correct - must match a
 *                 criterion already present in the requirement
 * @param text     the corrected, non-blank criterion text
 */
public record AcceptanceCriterionTextPatch(int position, String text) {

    public AcceptanceCriterionTextPatch {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("AcceptanceCriterionTextPatch text must not be blank");
        }
    }
}
