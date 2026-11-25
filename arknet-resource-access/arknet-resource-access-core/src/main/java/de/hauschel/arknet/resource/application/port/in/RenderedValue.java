// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** One value of a {@link RenderedField}. */
public sealed interface RenderedValue
        permits RenderedValue.Text, RenderedValue.Reference, RenderedValue.Child {

    /**
     * A literal.
     *
     * @param text     its lexical form
     * @param language its language tag, empty for an untagged literal
     * @param datatype its datatype as prefixed name, empty for a plain or tagged string
     * @param fallback whether it stands in for a value in the display language the field does not
     *                 hold - set only on the one value shown for a multilingual field
     */
    record Text(String text, Optional<String> language, Optional<String> datatype, boolean fallback)
            implements RenderedValue {

        /** Checks the components. */
        public Text {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(language, "language");
            Objects.requireNonNull(datatype, "datatype");
        }
    }

    /**
     * An IRI: another resource, or a term of the ontology such as a status value.
     *
     * @param iri    the IRI
     * @param handle a resource's business code, else its prefixed name, else the IRI
     * @param label  a resource's label, or its type label and handle if its type has no label field;
     *               a term's label in the display language; else the handle
     */
    record Reference(String iri, String handle, String label) implements RenderedValue {

        /** Checks the components. */
        public Reference {
            Objects.requireNonNull(iri, "iri");
            Objects.requireNonNull(handle, "handle");
            Objects.requireNonNull(label, "label");
        }
    }

    /**
     * A node of the resource's slice, rendered in place: a child an owned field holds, or another
     * fragment or blank node an edge of the resource reaches.
     *
     * @param iri             the node's IRI ({@code <resource>#<kind>-<id>} for an owned child), or
     *                        {@code _:} and a reference for a blank node
     * @param position        its position, empty for a node outside an owned field or carrying none
     * @param fields          the fields its shapes give it, without the position
     * @param otherStatements the statements outside its shapes
     */
    record Child(String iri, Optional<Integer> position, List<RenderedField> fields,
            List<RenderedField> otherStatements) implements RenderedValue {

        /** Checks the components. */
        public Child {
            Objects.requireNonNull(iri, "iri");
            Objects.requireNonNull(position, "position");
            fields = List.copyOf(fields);
            otherStatements = List.copyOf(otherStatements);
        }
    }
}
