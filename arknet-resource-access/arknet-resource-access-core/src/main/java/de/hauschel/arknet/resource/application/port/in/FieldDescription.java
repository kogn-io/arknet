// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * One field of a resource type: a property the shapes constrain for it.
 *
 * @param iri            the property
 * @param name           the property as prefixed name
 * @param label          its label in the display language
 * @param comment        its comment in the display language, empty if the ontology gives none
 * @param kind           what its values are
 * @param presence       whether a resource must, should or may carry it
 * @param maxCount       the most values it takes, empty for no limit
 * @param datatype       the datatype of a literal value as prefixed name, empty if the shapes name none
 * @param valueTypes     for an edge or owned children, the classes a value must have, as prefixed names
 * @param allowedValues  for a choice, the values it takes, in Turtle
 * @param onePerLanguage whether it takes at most one value per language tag
 * @param assigned       whether {@link SubmitDocument} assigns it itself, so a document must not set it
 * @param immutable      whether it keeps exactly the values the create wrote ({@code arkres:immutable})
 * @param rules          the messages the shapes attach to it, in the display language
 * @param children       for owned children, how they are kept and their own fields
 */
public record FieldDescription(String iri, String name, String label, String comment, Kind kind,
        Presence presence, Optional<Integer> maxCount, Optional<String> datatype, List<String> valueTypes,
        List<String> allowedValues, boolean onePerLanguage, boolean assigned, boolean immutable,
        List<String> rules, Optional<OwnedChildren> children) {

    /**
     * Checks the components and copies the lists.
     *
     * @param iri            the property
     * @param name           the prefixed name
     * @param label          the label
     * @param comment        the comment
     * @param kind           what its values are
     * @param presence       must, should or may
     * @param maxCount       the most values
     * @param datatype       the literal datatype
     * @param valueTypes     the classes of a referenced or owned value
     * @param allowedValues  the values of a choice
     * @param onePerLanguage at most one value per language tag
     * @param assigned       assigned by submit
     * @param immutable      keeps the values the create wrote
     * @param rules          the attached messages
     * @param children       the owned children
     */
    public FieldDescription {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(comment, "comment");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(presence, "presence");
        Objects.requireNonNull(maxCount, "maxCount");
        Objects.requireNonNull(datatype, "datatype");
        valueTypes = List.copyOf(Objects.requireNonNull(valueTypes, "valueTypes"));
        allowedValues = List.copyOf(Objects.requireNonNull(allowedValues, "allowedValues"));
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        Objects.requireNonNull(children, "children");
    }

    /**
     * What the values of a field are.
     */
    public enum Kind {
        /** Text in a language: a literal with a language tag. */
        TEXT,
        /** A literal of a datatype. */
        VALUE,
        /** One of a closed list of values. */
        CHOICE,
        /** An edge: the IRI of another resource. */
        REFERENCE,
        /** Child resources the resource owns, written in place as blank nodes. */
        CHILDREN
    }

    /**
     * Whether a resource carries a field.
     */
    public enum Presence {
        /** A resource without it is rejected. */
        REQUIRED,
        /** A resource without it is accepted with a warning. */
        RECOMMENDED,
        /** A resource may leave it out. */
        OPTIONAL
    }

    /**
     * How the children of an owned field are kept.
     *
     * @param kind      the fragment kind naming each child ({@code <resource>#<kind>-<id>})
     * @param orderedBy the property carrying a child's position, as prefixed name
     * @param fields    the fields of a child
     */
    public record OwnedChildren(String kind, String orderedBy, List<FieldDescription> fields) {

        /**
         * Checks the components and copies the list.
         *
         * @param kind      the fragment kind
         * @param orderedBy the position property
         * @param fields    the child's fields
         */
        public OwnedChildren {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(orderedBy, "orderedBy");
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        }
    }
}
