// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;

/**
 * The status rules of a resource type as {@link DescribeResourceType#describe} renders them: the rule
 * data its shapes carry on the property shape of its status field. Every name is a prefixed name.
 *
 * @param field         the status field
 * @param initialStatus the status a created resource starts in; a create writing another is rejected
 * @param transitions   the only changes of the status a document may make, sorted by source, then target
 * @param deletableIn   the statuses a resource may be deleted in, sorted; empty when every status is
 * @param frozenGroups  the field groups that only grow outside their editable statuses
 */
public record StatusRules(String field, String initialStatus, List<Transition> transitions,
        List<String> deletableIn, List<FieldGroup> frozenGroups) {

    /**
     * Checks the components and copies the lists.
     *
     * @param field         the status field
     * @param initialStatus the initial status
     * @param transitions   the allowed changes
     * @param deletableIn   the deletable statuses
     * @param frozenGroups  the field groups
     */
    public StatusRules {
        Objects.requireNonNull(field, "field");
        Objects.requireNonNull(initialStatus, "initialStatus");
        transitions = List.copyOf(Objects.requireNonNull(transitions, "transitions"));
        deletableIn = List.copyOf(Objects.requireNonNull(deletableIn, "deletableIn"));
        frozenGroups = List.copyOf(Objects.requireNonNull(frozenGroups, "frozenGroups"));
    }

    /**
     * One allowed change of the status.
     *
     * @param from       the status it leaves
     * @param to         the status it reaches
     * @param conditions the named shapes the resource must satisfy after the document that takes it,
     *                   sorted by name
     * @param defaults   the values the transition writes into a field the document does not name, sorted
     *                   by field
     */
    public record Transition(String from, String to, List<Condition> conditions, List<DefaultValue> defaults) {

        /**
         * Checks the components and copies the lists.
         *
         * @param from       the source status
         * @param to         the target status
         * @param conditions the conditions
         * @param defaults   the default values
         */
        public Transition {
            Objects.requireNonNull(from, "from");
            Objects.requireNonNull(to, "to");
            conditions = List.copyOf(Objects.requireNonNull(conditions, "conditions"));
            defaults = List.copyOf(Objects.requireNonNull(defaults, "defaults"));
        }
    }

    /**
     * A condition of a transition.
     *
     * @param shape    the named shape
     * @param messages the messages of the shape and of its property and SPARQL constraints, in the
     *                 display language
     */
    public record Condition(String shape, List<String> messages) {

        /**
         * Checks the components and copies the list.
         *
         * @param shape    the named shape
         * @param messages the messages
         */
        public Condition {
            Objects.requireNonNull(shape, "shape");
            messages = List.copyOf(Objects.requireNonNull(messages, "messages"));
        }
    }

    /**
     * A value a transition writes.
     *
     * @param field the field written
     * @param value the value as Turtle
     * @param today whether the value is {@code arkres:today}, the day the document is applied
     */
    public record DefaultValue(String field, String value, boolean today) {

        /**
         * Checks the components.
         *
         * @param field the field
         * @param value the value
         * @param today whether the value stands for the day of the document
         */
        public DefaultValue {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * Fields that change freely only in some statuses. In any other status they keep every value and
     * take values only in a language none of them carries yet; an owned field among them keeps every
     * child, takes new children after the last, and a kept child takes text only in a language new to it.
     *
     * @param fields     the fields of the group, sorted
     * @param editableIn the statuses in which they change freely, sorted
     */
    public record FieldGroup(List<String> fields, List<String> editableIn) {

        /**
         * Checks the components and copies the lists.
         *
         * @param fields     the fields
         * @param editableIn the editable statuses
         */
        public FieldGroup {
            fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
            editableIn = List.copyOf(Objects.requireNonNull(editableIn, "editableIn"));
        }
    }
}
