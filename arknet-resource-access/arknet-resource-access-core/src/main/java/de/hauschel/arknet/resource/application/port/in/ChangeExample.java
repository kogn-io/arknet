// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.Objects;

/**
 * A Turtle document that changes the resource the create example of a type makes.
 *
 * @param activity the activity the document shows, as a prefixed name such as {@code arkres:Update}
 * @param document the document for {@link SubmitDocument}; it declares the prefixes it uses itself
 */
public record ChangeExample(String activity, String document) {

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
