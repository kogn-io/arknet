// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.Objects;
import java.util.Optional;

/**
 * One line of a listing.
 *
 * @param iri       the resource's IRI
 * @param handle    its business code, else its prefixed name, else its IRI
 * @param typeLabel the label of its resource type in the display language
 * @param label     the value of its label field, empty for a type without one or a node whose types
 *                  reach two; then {@code typeLabel} and {@code handle} stand in
 * @param status    the value of its status field, empty for a type without one or a resource not
 *                  carrying one
 */
public record ListedResource(String iri, String handle, String typeLabel, Optional<RenderedValue.Text> label,
        Optional<RenderedValue.Reference> status) {

    /** Checks the components. */
    public ListedResource {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(typeLabel, "typeLabel");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(status, "status");
    }
}
