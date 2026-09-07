// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.check;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreResource;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;
import de.hauschel.arknet.persistence.ArkprocVocabulary;

/**
 * Finds a role ({@code arkproc:Role}) and a glossary term ({@code skos:Concept}) that carry the
 * same name (kogn-io/arknet#512).
 *
 * <p><strong>Report, never reject.</strong> The record removed by the #433 audit, ADR-39 ("a role
 * is not a glossary term"), held a modelling detail, not a decision about the system's shape - it
 * was rightly deleted rather than kept alive as a decision record. Its statement still needed a
 * durable home, but not as a write-time rejection: gating {@code role_add}/{@code term_add}
 * against each other would pull in a cross-context edge (ubiquitous-language &lt;-&gt; actor)
 * that ADR-37 deliberately avoids for a rule that only ever reports. This check is that home -
 * read-only, over the same generic {@link StoreSnapshot} every other {@code store_check} selector
 * reads, so no new dependency between the two hexagons is needed.</p>
 *
 * <p><strong>Comparison.</strong> A role's {@code arknet:name} is genuinely translated per
 * language (different words per tag, e.g. {@code "Requirements Engineer"@en} vs.
 * {@code "Anforderungsingenieur"@de}), while a term's {@code skos:prefLabel} carries the very same
 * word under every tag (FR-10 - only {@code skos:definition} is translated). Every one of the
 * role's language variants is therefore compared, case-insensitively and trimmed, against every
 * one of the term's {@code prefLabel} variants; a match on any pair is one finding.</p>
 *
 * <p>A resource missing its own business code ({@code dcterms:identifier}) is skipped rather than
 * addressed by a guessed handle - same discipline {@link LanguageGapCheck} follows for a
 * positioned child resource with no single owner.</p>
 */
public final class RoleTermDuplicateCheck {

    private static final String SKOS_CONCEPT_TYPE = "http://www.w3.org/2004/02/skos/core#Concept";
    private static final String PREF_LABEL_PREDICATE = "http://www.w3.org/2004/02/skos/core#prefLabel";
    private static final String NAME_PREDICATE = "https://w3id.org/arknet/core#name";
    private static final String ROLE_TYPE = ArkprocVocabulary.ROLE_TYPE;

    /**
     * One role and one term found to carry the same name.
     *
     * @param roleCode the role's business code ({@code ROLE-N})
     * @param termCode the term's business code ({@code TERM-N})
     * @param name     the shared name, spelled as the term's own {@code skos:prefLabel} carries it
     */
    public record Finding(String roleCode, String termCode, String name) {

        public Finding {
            Objects.requireNonNull(roleCode, "roleCode");
            Objects.requireNonNull(termCode, "termCode");
            Objects.requireNonNull(name, "name");
        }
    }

    private RoleTermDuplicateCheck() {
    }

    /**
     * Runs the check over one snapshot.
     *
     * @param snapshot the model snapshot, already free of the provenance and identity graphs
     *                 {@code StoreReader} hides
     * @return every finding, ordered by role code then term code so two runs over an unchanged
     *         store produce the same report
     */
    public static List<Finding> run(final StoreSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        final List<StoreResource> roles = new ArrayList<>();
        final List<StoreResource> terms = new ArrayList<>();
        for (final StoreResource resource : snapshot.resources()) {
            final List<String> types = resource.types();
            if (types.contains(ROLE_TYPE)) {
                roles.add(resource);
            }
            if (types.contains(SKOS_CONCEPT_TYPE)) {
                terms.add(resource);
            }
        }
        final List<Finding> findings = new ArrayList<>();
        for (final StoreResource role : roles) {
            final Optional<String> roleCode = role.identifier();
            if (roleCode.isEmpty()) {
                continue;
            }
            final Map<String, String> roleNames = normalizedLiterals(role, NAME_PREDICATE);
            if (roleNames.isEmpty()) {
                continue;
            }
            for (final StoreResource term : terms) {
                final Optional<String> termCode = term.identifier();
                if (termCode.isEmpty()) {
                    continue;
                }
                final String shared = firstShared(roleNames, normalizedLiterals(term, PREF_LABEL_PREDICATE));
                if (shared != null) {
                    findings.add(new Finding(roleCode.get(), termCode.get(), shared));
                }
            }
        }
        return findings.stream()
                .sorted(Comparator.comparing(Finding::roleCode).thenComparing(Finding::termCode))
                .toList();
    }

    /**
     * @return the term's original-cased text for the first normalized key both maps share, or
     *         {@code null} when the two names have nothing in common
     */
    private static String firstShared(final Map<String, String> roleNames, final Map<String, String> termLabels) {
        for (final Map.Entry<String, String> term : termLabels.entrySet()) {
            if (roleNames.containsKey(term.getKey())) {
                return term.getValue();
            }
        }
        return null;
    }

    /**
     * @return every literal of {@code predicate} on {@code resource}, keyed by its
     *         trimmed-and-lowercased form and mapped to the first-seen trimmed original text -
     *         the normalized key is what two names are compared on, the original text is what a
     *         finding shows
     */
    private static Map<String, String> normalizedLiterals(final StoreResource resource, final String predicate) {
        final Map<String, String> byNormalized = new LinkedHashMap<>();
        for (final Triple triple : resource.outgoing()) {
            if (predicate.equals(triple.predicate()) && triple.object() instanceof RdfNode.Literal literal) {
                final String trimmed = literal.lexicalForm().strip();
                if (!trimmed.isEmpty()) {
                    byNormalized.putIfAbsent(trimmed.toLowerCase(Locale.ROOT), trimmed);
                }
            }
        }
        return byNormalized;
    }
}
