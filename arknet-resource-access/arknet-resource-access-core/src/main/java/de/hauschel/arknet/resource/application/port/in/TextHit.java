// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.Objects;
import java.util.Optional;

/**
 * One matching statement, reported under the resource it belongs to.
 *
 * @param handle   the handle {@code resource_get} reads the resource by: its business code, else its
 *                 prefixed name or IRI; a node no resource owns shows as it is stored, even a blank
 *                 node
 * @param field    the matched property, as prefixed name or IRI
 * @param viaField the property that leads from the resource to the child holding the match, empty
 *                 if the match sits on the resource itself
 * @param language the language tag of the matching literal, empty if it has none
 * @param snippet  a short excerpt of the literal around the match
 */
public record TextHit(String handle, String field, Optional<String> viaField, Optional<String> language,
        String snippet) {

    /** Checks the components. */
    public TextHit {
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(viaField, "viaField");
        Objects.requireNonNull(language, "language");
        Objects.requireNonNull(snippet, "snippet");
    }
}
