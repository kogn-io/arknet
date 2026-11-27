// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import de.hauschel.arknet.resource.domain.ArkresVocabulary;

/**
 * A {@code submit} document built from IRI constants, never from Turtle text with prefixes: every
 * class and property a case names comes from an {@code Ark*Vocabulary} constant, so renaming a
 * vocabulary term (kogn-io/arknet#745) renames it in every comparison case as well.
 *
 * <p>Each activity is rendered as one Turtle statement over full IRIs; a created resource is the
 * blank node {@code _:new<n>}, a nested node (a child) a {@code [ ... ]} blank node.</p>
 */
public final class Document {

    private final List<String> activities = new ArrayList<>();
    private int created;

    /**
     * Starts an empty document.
     *
     * @return the document
     */
    public static Document document() {
        return new Document();
    }

    /**
     * A text literal under a language tag.
     *
     * @param text     the lexical form
     * @param language the BCP-47 tag
     * @return the value
     */
    public static Value text(final String text, final String language) {
        return new Value("\"" + escape(text) + "\"@" + language);
    }

    /**
     * An IRI reference.
     *
     * @param iri the IRI
     * @return the value
     */
    public static Value iri(final String iri) {
        return new Value("<" + iri + ">");
    }

    /**
     * A plain integer, as a position is written.
     *
     * @param number the number
     * @return the value
     */
    public static Value integer(final int number) {
        return new Value(Integer.toString(number));
    }

    /**
     * Creates a resource.
     *
     * @param node the new resource, its type and its fields
     * @return this document
     */
    public Document create(final Node node) {
        String handle = "_:new" + created++;
        activities.add("[] a <" + ArkresVocabulary.CREATE + "> ; <" + ArkresVocabulary.OBJECT + "> " + handle
                + " .\n" + handle + " " + node.body() + " .");
        return this;
    }

    /**
     * Sets fields of a stored resource; a multilingual field is replaced under the tag of the value only.
     *
     * @param object the resource
     * @param fields the fields and values to set
     * @return this document
     */
    public Document update(final String object, final Node fields) {
        activities.add("[] a <" + ArkresVocabulary.UPDATE + "> ; <" + ArkresVocabulary.OBJECT + "> <" + object
                + "> ; <" + ArkresVocabulary.SET + "> [ " + fields.body() + " ] .");
        return this;
    }

    /**
     * Clears a field of a stored resource.
     *
     * @param object the resource
     * @param field  the field
     * @return this document
     */
    public Document clear(final String object, final String field) {
        activities.add("[] a <" + ArkresVocabulary.UPDATE + "> ; <" + ArkresVocabulary.OBJECT + "> <" + object
                + "> ; <" + ArkresVocabulary.CLEAR + "> <" + field + "> .");
        return this;
    }

    /**
     * Adds a value or a child to a list field.
     *
     * @param object the resource
     * @param field  the list field
     * @param value  the value, or a {@link Node#value()} child
     * @return this document
     */
    public Document addTo(final String object, final String field, final Value value) {
        activities.add(listActivity(ArkresVocabulary.ADD_TO, object, field)
                + " ; <" + ArkresVocabulary.VALUE + "> " + value.turtle() + " .");
        return this;
    }

    /**
     * Removes a value from a list field.
     *
     * @param object the resource
     * @param field  the list field
     * @param value  the value
     * @return this document
     */
    public Document removeFrom(final String object, final String field, final Value value) {
        activities.add(listActivity(ArkresVocabulary.REMOVE_FROM, object, field)
                + " ; <" + ArkresVocabulary.VALUE + "> " + value.turtle() + " .");
        return this;
    }

    /**
     * Removes the child at a position of an ordered list field.
     *
     * @param object   the resource
     * @param field    the list field
     * @param position the position of the child
     * @return this document
     */
    public Document removeFrom(final String object, final String field, final int position) {
        activities.add(listActivity(ArkresVocabulary.REMOVE_FROM, object, field)
                + " ; <" + ArkresVocabulary.POSITION + "> " + position + " .");
        return this;
    }

    /**
     * Deletes a stored resource.
     *
     * @param object the resource
     * @return this document
     */
    public Document delete(final String object) {
        activities.add("[] a <" + ArkresVocabulary.DELETE + "> ; <" + ArkresVocabulary.OBJECT + "> <" + object
                + "> .");
        return this;
    }

    /**
     * The document as Turtle.
     *
     * @return the Turtle text
     */
    public String turtle() {
        return String.join("\n", activities) + "\n";
    }

    @Override
    public String toString() {
        return turtle();
    }

    private static String listActivity(final String activity, final String object, final String field) {
        return "[] a <" + activity + "> ; <" + ArkresVocabulary.OBJECT + "> <" + object + "> ; <"
                + ArkresVocabulary.FIELD + "> <" + field + ">";
    }

    private static String escape(final String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /**
     * One RDF term as Turtle.
     *
     * @param turtle the rendered term
     */
    public record Value(String turtle) {
    }

    /**
     * The fields of a created resource, of the set part of an update, or of a child.
     */
    public static final class Node {

        private final String type;
        private final Map<String, List<Value>> fields = new LinkedHashMap<>();

        private Node(final String type) {
            this.type = type;
        }

        /**
         * A node of a type, as a created resource needs.
         *
         * @param type the class IRI
         * @return the node
         */
        public static Node typed(final String type) {
            return new Node(type);
        }

        /**
         * A node without a type, as the set part of an update or a child needs.
         *
         * @return the node
         */
        public static Node fields() {
            return new Node(null);
        }

        /**
         * Adds a value of a field; called twice, the field holds both.
         *
         * @param field the property IRI
         * @param value the value
         * @return this node
         */
        public Node with(final String field, final Value value) {
            fields.computeIfAbsent(field, ignored -> new ArrayList<>()).add(value);
            return this;
        }

        /**
         * This node as a nested blank node, the value of a child field.
         *
         * @return the value
         */
        public Value value() {
            return new Value("[ " + body() + " ]");
        }

        private String body() {
            List<String> parts = new ArrayList<>();
            if (type != null) {
                parts.add("a <" + type + ">");
            }
            fields.forEach((field, values) -> parts.add("<" + field + "> "
                    + String.join(" , ", values.stream().map(Value::turtle).toList())));
            return String.join(" ; ", parts);
        }
    }
}
