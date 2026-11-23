// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import java.util.List;
import java.util.Objects;

/**
 * Everything a client needs before its first document: the resource types, the vocabulary a document
 * is written in, and the prefixes behind the names.
 *
 * @param types              every resource type, sorted by name
 * @param activityVocabulary the activities and their properties a document consists of
 * @param prefixDeclarations a Turtle prefix declaration for every prefix the names use, ready to copy
 */
public record ResourceCatalog(List<ResourceTypeSummary> types, List<VocabularyTerm> activityVocabulary,
        String prefixDeclarations) {

    /**
     * Copies the lists.
     *
     * @param types              the resource types
     * @param activityVocabulary the activity vocabulary
     * @param prefixDeclarations the prefix declarations
     */
    public ResourceCatalog {
        types = List.copyOf(Objects.requireNonNull(types, "types"));
        activityVocabulary = List.copyOf(Objects.requireNonNull(activityVocabulary, "activityVocabulary"));
        Objects.requireNonNull(prefixDeclarations, "prefixDeclarations");
    }

    /**
     * A class or property of the activity vocabulary.
     *
     * @param name    the term as prefixed name
     * @param label   its label in the display language
     * @param comment its comment in the display language
     */
    public record VocabularyTerm(String name, String label, String comment) {

        /**
         * Checks the components.
         *
         * @param name    the prefixed name
         * @param label   the label
         * @param comment the comment
         */
        public VocabularyTerm {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(comment, "comment");
        }
    }
}
