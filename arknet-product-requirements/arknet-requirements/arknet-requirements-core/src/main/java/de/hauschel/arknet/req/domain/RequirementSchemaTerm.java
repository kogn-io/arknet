// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.domain;

import java.util.List;
import java.util.Objects;

/**
 * A single term of the {@code arkreq:} requirement vocabulary, self-described as data for an
 * MCP client: what the term means and the exact values it accepts.
 *
 * <p><strong>{@code values} is sourced from the Java domain enum, not from the ontology's
 * {@code sh:in} SHACL enumeration.</strong> {@link RequirementStatus}, for instance,
 * deliberately implements only a documented MVP subset of {@code arkreq:RequirementStatus}'s
 * six ontology individuals (see its own Javadoc). Reporting the full ontology enumeration here
 * would tell a caller that {@code req_add}/{@code req_set_status} accept values the Java enum
 * parser actually rejects - the opposite of what this type exists for: raising an MCP client's
 * first-shot hit rate against the real tool contract, not against the richer ontology.</p>
 *
 * @param term       the vocabulary term's name, e.g. {@code "RequirementStatus"}
 * @param definition the term's ontology-sourced definition (the class-level {@code rdfs:comment}
 *                    in {@code arknet-requirements.ttl})
 * @param values     the exact values a caller may send for this term, in declaration order;
 *                   never {@code null} or empty
 */
public record RequirementSchemaTerm(String term, String definition, List<String> values) {

    public RequirementSchemaTerm {
        Objects.requireNonNull(term, "term");
        Objects.requireNonNull(definition, "definition");
        Objects.requireNonNull(values, "values");
        if (term.isBlank()) {
            throw new IllegalArgumentException("term must not be blank");
        }
        if (definition.isBlank()) {
            throw new IllegalArgumentException("definition must not be blank");
        }
        if (values.isEmpty()) {
            throw new IllegalArgumentException("values must not be empty");
        }
        values = List.copyOf(values);
    }
}
