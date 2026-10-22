// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

/**
 * Why a mention of a glossary term does not count as an unlinked mention: it stands in a
 * non-normative field, or directly next to a reference to a resource that already links the term.
 */
public sealed interface MentionExemption {

    /**
     * The mention stands in a field that does not state the requirement itself.
     *
     * @param field the field's name, as {@link TraceabilityGraph.ProseField#field()} reports it
     */
    record NonNormativeField(String field) implements MentionExemption {
    }

    /**
     * The mention stands directly next to the business code of a resource that links the term.
     *
     * @param referencedCode the business code of that resource
     */
    record AdjacentReference(String referencedCode) implements MentionExemption {
    }
}
