// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import de.hauschel.arknet.analysis.domain.TraceabilityGraph.MentionScan;
import de.hauschel.arknet.analysis.domain.TraceabilityGraph.ProseField;
import de.hauschel.arknet.analysis.domain.TraceabilityGraph.UnlinkedMention;

/**
 * The scan behind {@link TraceabilityGraph#mentionScan()}: sweeps every resource's prose for
 * glossary-term mentions the resource does not link, and splits the (resource, term) pairs into
 * findings and hints. A hit (one mention of a term in one field) is exempt when its field is
 * non-normative or when it stands directly next to the business code of another resource that
 * links the term. A pair is a finding when at least one hit is not exempt, otherwise a hint.
 * Reads the graph through its public read methods only.
 */
final class MentionClassifier {

    /**
     * The one place that names the fields whose mentions are not normative: an acceptance
     * criterion is illustrative, so a missing edge there is only a hint.
     */
    private static final String NON_NORMATIVE_FIELD = "acceptanceCriterion";
    private static final int MAX_SEPARATOR_LENGTH = 3;
    private static final String SEPARATORS = " \t()[],:-/–—";

    /** One mention of a term in one field, with the exemption it earns (or {@code null}). */
    private record Hit(String field, MentionExemption exemption) {
    }

    private final TraceabilityGraph graph;
    private final Map<String, String> termLabels;
    private final LabelMentions<String> termMatcher;
    private final LabelMentions<String> codeMatcher;
    private final Map<String, String> iriByCode;
    private final List<UnlinkedMention> findings = new ArrayList<>();
    private final List<UnlinkedMention> hints = new ArrayList<>();

    private MentionClassifier(final TraceabilityGraph graph, final Map<String, String> termLabels,
            final List<String> termIris) {
        this.graph = graph;
        this.termLabels = termLabels;
        this.termMatcher = LabelMentions.of(termIris, termLabels::get);
        this.iriByCode = graph.iriByBusinessCode();
        this.codeMatcher = LabelMentions.of(List.copyOf(iriByCode.keySet()), code -> code);
    }

    static MentionScan scan(final TraceabilityGraph graph) {
        final Map<String, String> termLabels = graph.termLabels();
        if (termLabels.isEmpty()) {
            return new MentionScan(List.of(), List.of());
        }
        final List<String> termIris = termLabels.keySet().stream()
                .sorted(Comparator.comparing(iri -> graph.identifierOf(iri).orElse(iri)))
                .toList();
        final MentionClassifier classifier = new MentionClassifier(graph, termLabels, termIris);
        classifier.sweepAll(termIris);
        return new MentionScan(List.copyOf(classifier.findings), List.copyOf(classifier.hints));
    }

    private void sweepAll(final List<String> termIris) {
        for (final String requirementIri : graph.requirementIris()) {
            sweep(requirementIri, graph.requirementProseFields(requirementIri),
                    new HashSet<>(graph.usedTerms(requirementIri)), "usesTerm");
        }
        for (final String useCaseIri : graph.useCaseIris()) {
            final Set<String> linked = new HashSet<>(graph.usedTerms(useCaseIri));
            linked.addAll(graph.rolesOf(useCaseIri));
            sweep(useCaseIri, graph.useCaseProseFields(useCaseIri), linked, "usesTerm");
        }
        for (final String boundedContextIri : graph.boundedContextIris()) {
            sweep(boundedContextIri, graph.boundedContextProseFields(boundedContextIri),
                    new HashSet<>(graph.linkedTerms(boundedContextIri)), "ubiquitousLanguageTerm");
        }
        for (final String termIri : termIris) {
            final Set<String> linked = new HashSet<>(graph.relatedTerms(termIri));
            graph.broaderTerm(termIri).ifPresent(linked::add);
            linked.addAll(graph.narrowerTerms(termIri));
            linked.add(termIri);
            sweep(termIri, graph.termProseFields(termIri), linked, TraceabilityGraph.GLOSSARY_EDGE_LOCAL_NAME);
        }
        for (final String adrIri : graph.adrIris()) {
            sweep(adrIri, graph.adrProseFields(adrIri), new HashSet<>(graph.adrUsedTerms(adrIri)), "usesTerm");
        }
    }

    private void sweep(final String sourceIri, final List<ProseField> fields, final Set<String> linked,
            final String edgeLocalName) {
        final Map<String, List<Hit>> hitsByTerm = new LinkedHashMap<>();
        for (final ProseField field : fields) {
            final List<LabelMentions.Mention<String>> codeMentions = codeMatcher.in(field.text());
            for (final LabelMentions.Mention<String> mention : termMatcher.in(field.text())) {
                if (linked.contains(mention.item())) {
                    continue;
                }
                final MentionExemption exemption = NON_NORMATIVE_FIELD.equals(field.field())
                        ? new MentionExemption.NonNormativeField(field.field())
                        : referenceExemption(sourceIri, field.text(), mention, codeMentions);
                hitsByTerm.computeIfAbsent(mention.item(), k -> new ArrayList<>())
                        .add(new Hit(field.field(), exemption));
            }
        }
        hitsByTerm.forEach((termIri, hits) -> {
            final List<String> counted = hits.stream().filter(h -> h.exemption() == null)
                    .map(Hit::field).distinct().toList();
            if (!counted.isEmpty()) {
                findings.add(new UnlinkedMention(
                        sourceIri, termIri, termLabels.get(termIri), edgeLocalName, counted, List.of()));
                return;
            }
            final List<String> where = hits.stream().map(Hit::field).distinct().toList();
            final List<MentionExemption> exemptions = hits.stream().map(Hit::exemption).distinct().toList();
            hints.add(new UnlinkedMention(
                    sourceIri, termIri, termLabels.get(termIri), edgeLocalName, where, exemptions));
        });
    }

    /** @return the exemption earned by an adjacent reference, or {@code null} */
    private MentionExemption referenceExemption(final String sourceIri, final String text,
            final LabelMentions.Mention<String> mention, final List<LabelMentions.Mention<String>> codeMentions) {
        for (final LabelMentions.Mention<String> code : codeMentions) {
            final String referencedIri = iriByCode.get(code.item());
            if (referencedIri == null || referencedIri.equals(sourceIri)
                    || !linkedTermsOf(referencedIri).contains(mention.item())) {
                continue;
            }
            final boolean adjacent = code.start() >= mention.end()
                    ? onlySeparators(text, mention.end(), code.start())
                    : code.end() <= mention.start() && onlySeparators(text, code.end(), mention.start());
            if (adjacent) {
                return new MentionExemption.AdjacentReference(code.item());
            }
        }
        return null;
    }

    /** The term IRIs a resource links, whatever its type - each type only carries its own edge. */
    private Set<String> linkedTermsOf(final String iri) {
        final Set<String> linked = new HashSet<>(graph.usedTerms(iri));
        linked.addAll(graph.rolesOf(iri));
        linked.addAll(graph.adrUsedTerms(iri));
        linked.addAll(graph.linkedTerms(iri));
        linked.addAll(graph.relatedTerms(iri));
        graph.broaderTerm(iri).ifPresent(linked::add);
        linked.addAll(graph.narrowerTerms(iri));
        return linked;
    }

    /**
     * Only blanks, brackets, comma, colon, hyphen, dash and slash - at most {@value
     * #MAX_SEPARATOR_LENGTH} of them - lie between the two. Sentence ends ({@code . ! ? ;}) and
     * line breaks are not separators, so a reference never reaches into the next sentence.
     */
    private static boolean onlySeparators(final String text, final int from, final int to) {
        return to - from <= MAX_SEPARATOR_LENGTH
                && text.substring(from, to).chars().allMatch(c -> SEPARATORS.indexOf(c) >= 0);
    }
}
