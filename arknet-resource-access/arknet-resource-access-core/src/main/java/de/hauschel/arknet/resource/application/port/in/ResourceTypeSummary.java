// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.Objects;
import java.util.Optional;

/**
 * A resource type as {@link DescribeResourceType#catalog} lists it.
 *
 * @param iri        the class
 * @param name       the class as prefixed name
 * @param label      its label in the display language
 * @param comment    its comment in the display language, empty if the ontology gives none
 * @param codePrefix the literal its business codes start with, empty for a type without codes
 */
public record ResourceTypeSummary(String iri, String name, String label, String comment,
        Optional<String> codePrefix) {

    /**
     * Checks the components.
     *
     * @param iri        the class
     * @param name       the class as prefixed name
     * @param label      its label in the display language
     * @param comment    its comment in the display language
     * @param codePrefix the prefix of its business codes
     */
    public ResourceTypeSummary {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(comment, "comment");
        Objects.requireNonNull(codePrefix, "codePrefix");
    }
}
