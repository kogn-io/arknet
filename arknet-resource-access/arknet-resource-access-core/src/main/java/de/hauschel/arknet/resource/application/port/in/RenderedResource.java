// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A resource rendered from its shapes and the ontology.
 *
 * @param iri             its IRI
 * @param handle          its business code, else its IRI
 * @param typeLabel       the label of its resource type in the display language
 * @param types           its {@code rdf:type} values
 * @param label           the value of its label field ({@code arkres:labelField}), empty for a type
 *                        without one or a node whose types reach two
 * @param status          the value of its status field, empty for a type without one
 * @param created         when its first history entry was recorded, ISO-8601
 * @param lastChanged     when its latest history entry was recorded, ISO-8601
 * @param fields          the fields its shapes give it, in the order {@link DescribeResourceType}
 *                        lists them, edges declared inverse or symmetric by the ontology merged in
 * @param incoming        the edges of other resources pointing at it, one field per property, each
 *                        value the resource the edge comes from (the owner, for an edge of a child)
 * @param otherStatements the statements outside its shapes, one field per property
 */
public record RenderedResource(String iri, String handle, String typeLabel, List<RenderedValue.Reference> types,
        Optional<RenderedValue.Text> label, Optional<RenderedValue.Reference> status, Optional<String> created,
        Optional<String> lastChanged, List<RenderedField> fields, List<RenderedField> incoming,
        List<RenderedField> otherStatements) {

    /** Checks the components. */
    public RenderedResource {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(handle, "handle");
        Objects.requireNonNull(typeLabel, "typeLabel");
        types = List.copyOf(types);
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(created, "created");
        Objects.requireNonNull(lastChanged, "lastChanged");
        fields = List.copyOf(fields);
        incoming = List.copyOf(incoming);
        otherStatements = List.copyOf(otherStatements);
    }

    /**
     * The name to show for the resource: its label, or, for a type without a label field, its type
     * label and handle.
     *
     * @return the display name
     */
    public String displayLabel() {
        return label.map(RenderedValue.Text::text).orElse(typeLabel + " " + handle);
    }
}
