// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.domain;

import java.util.Objects;

/**
 * A text-only correction for the existing main-flow step at {@code position}: every other
 * aspect of that step (its {@code realises} references, its very existence) is untouched.
 *
 * <p>{@code text} is mandatory and must not be blank: a patch is a correction, not a way to
 * accidentally clear or no-op a step's wording while still reporting success - see
 * {@link Step#text()}, whose invariant this mirrors.</p>
 *
 * <p>Lives in the domain package (issue #96), not in {@code application.port.in}, because
 * {@link UseCase#withStepTextPatches(de.hauschel.arknet.kernel.ProjectId, java.util.List)} takes
 * it as a parameter - the domain must not depend on the application layer.</p>
 *
 * @param position the 1-based position of the existing step to correct - must match a step
 *                 already present in the use case
 * @param text     the corrected, non-blank step text
 */
public record StepTextPatch(int position, String text) {

    public StepTextPatch {
        Objects.requireNonNull(text, "text");
        if (text.isBlank()) {
            throw new IllegalArgumentException("StepTextPatch text must not be blank");
        }
    }
}
