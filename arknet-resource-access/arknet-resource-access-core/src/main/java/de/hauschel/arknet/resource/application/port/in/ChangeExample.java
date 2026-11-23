// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.Objects;

/**
 * A Turtle document that changes the resource the create example of a type makes.
 *
 * <p>The document names that resource by {@link #PLACEHOLDER}: the code or IRI the resource gets is
 * known only from what {@code submit} returns for the create, and a fixed code such as {@code FR-1}
 * would change an unrelated resource in a project that already holds one. Submitted unreplaced, the
 * placeholder is a handle that names no resource, so the document is rejected and nothing is written.</p>
 *
 * @param activity the activity the document shows, as a prefixed name such as {@code arkres:Update}
 * @param document the document for {@link SubmitDocument}; it declares the prefixes it uses itself
 */
public record ChangeExample(String activity, String document) {

    /** Stands for the resource of the create example; replace it by the code or IRI submit returned. */
    public static final String PLACEHOLDER = "<CODE-OR-IRI-RETURNED-BY-SUBMIT>";

    /**
     * Checks the components.
     *
     * @param activity the activity the document shows
     * @param document the document
     */
    public ChangeExample {
        Objects.requireNonNull(activity, "activity");
        Objects.requireNonNull(document, "document");
    }
}
