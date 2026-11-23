// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

/**
 * Describes the resource types a document for {@link SubmitDocument} can write, read off the shapes
 * and the ontology at run time (ADR-59, ADR-69).
 *
 * <p>Every text a description carries - a type's or a field's label and comment, a rule's message -
 * is shown in one display language, picked from the tagged variants the files ship.</p>
 */
public interface DescribeResourceType {

    /**
     * Lists every resource type, the activity vocabulary and the prefixes behind the names.
     *
     * @param displayLocale the BCP-47 tag to show texts in, {@code null} for the default
     * @return the catalog
     */
    ResourceCatalog catalog(String displayLocale);

    /**
     * Describes one resource type: its fields, edges, owned children, rules and a runnable example.
     *
     * @param type          the type as prefixed name (as {@link #catalog} names it) or full IRI
     * @param displayLocale the BCP-47 tag to show texts in, {@code null} for the default
     * @return the description
     * @throws IllegalArgumentException if {@code type} names no resource type; the message lists them
     */
    ResourceTypeDescription describe(String type, String displayLocale);
}
