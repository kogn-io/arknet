// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A resource type as {@link DescribeResourceType#describe} renders it.
 *
 * @param summary the type itself
 * @param fields  its fields, required ones first, then by name; the edges to other resources and the
 *                owned children are fields too
 * @param status  the status rules, empty for a type whose shapes mark no status field
 * @param rules   the messages of the rules that span several fields, in the display language
 * @param example a Turtle document for {@link SubmitDocument} that creates one resource of this type,
 *                runnable as it stands against an empty project; it declares the prefixes it uses itself
 * @param changeExamples a document for each activity the type admits on an existing resource -
 *                {@code Update}, {@code AddTo} and {@code RemoveFrom} for a type with owned children,
 *                {@code Delete} - in an order that runs after the create example once
 *                {@link ChangeExample#PLACEHOLDER} is replaced by the code or IRI submit returned for it
 * @param prefixDeclarations a Turtle prefix declaration for every prefix any of the components writes -
 *                type, field and value names, datatypes, choices, rule sentences and the example
 */
public record ResourceTypeDescription(ResourceTypeSummary summary, List<FieldDescription> fields,
        Optional<StatusRules> status, List<String> rules, String example, List<ChangeExample> changeExamples, String prefixDeclarations) {

    /**
     * Copies the lists.
     *
     * @param summary the type itself
     * @param fields  its fields
     * @param status  its status rules
     * @param rules   the rules spanning several fields
     * @param example the runnable example
     * @param changeExamples the documents that change the resource of the example
     * @param prefixDeclarations the prefix declarations behind every name
     */
    public ResourceTypeDescription {
        Objects.requireNonNull(summary, "summary");
        fields = List.copyOf(Objects.requireNonNull(fields, "fields"));
        Objects.requireNonNull(status, "status");
        rules = List.copyOf(Objects.requireNonNull(rules, "rules"));
        Objects.requireNonNull(example, "example");
        changeExamples = List.copyOf(Objects.requireNonNull(changeExamples, "changeExamples"));
        Objects.requireNonNull(prefixDeclarations, "prefixDeclarations");
    }
}
