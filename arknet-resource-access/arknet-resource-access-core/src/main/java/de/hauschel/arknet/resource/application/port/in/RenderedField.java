// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;

/**
 * The values one property gives a rendered resource or child, or, for an edge pointing at a
 * resource, the resources it comes from.
 *
 * @param iri    the property
 * @param name   the property as prefixed name
 * @param label  its label in the display language, its local name if the ontology gives none
 * @param values its values
 */
public record RenderedField(String iri, String name, String label, List<RenderedValue> values) {

    /** Checks the components. */
    public RenderedField {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        values = List.copyOf(values);
    }
}
