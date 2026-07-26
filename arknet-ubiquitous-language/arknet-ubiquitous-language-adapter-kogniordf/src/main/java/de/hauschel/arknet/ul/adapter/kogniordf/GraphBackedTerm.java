// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.kogniordf;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * A {@link Term} that holds no fields but an RDF graph: every accessor reads triples of one
 * subject, every mutator deletes and re-adds them. The object <em>is</em> the graph (spike,
 * issue #168).
 *
 * <p>Consequences that make this more than a stylistic variation:</p>
 *
 * <ul>
 *   <li><strong>One predicate mapping instead of two.</strong> The field-to-predicate knowledge
 *       lives here once, rather than split between a candidate-graph builder on the write side
 *       and a result-row assembler on the read side.</li>
 *   <li><strong>Nothing is lost on a round trip.</strong> A predicate this class never reads -
 *       a store-first {@code skos:altLabel}, a second language-tagged {@code skos:prefLabel},
 *       an {@code arkreq:usesTerm} edge pointing here from elsewhere - still sits in the graph
 *       and is written back untouched. That is what lets the repository replace a subject
 *       wholesale again instead of patching it predicate by predicate.</li>
 *   <li><strong>The graph is detached, not live.</strong> It is an in-memory snapshot, never a
 *       cursor over an open transaction. A term handed back to a driving adapter after the
 *       transaction closed is therefore still readable - and a mutation applied to it after the
 *       fact silently changes nothing in the store. That trap is the price of the setters.</li>
 * </ul>
 *
 * <p>Multi-valued literals are resolved on read exactly the way the row-based predecessor did:
 * {@code skos:prefLabel} through the injected {@link DisplayLocale} fallback chain (issue #80),
 * {@code skos:definition} as the first-seen value with a {@code WARN} when several distinct ones
 * exist (issue #81). What disappeared is the per-subject row grouping those two selections used
 * to need - a graph is a set of triples, so a multi-valued predicate never multiplied anything
 * that would have to be regrouped.</p>
 */
final class GraphBackedTerm implements Term {

    private static final Logger LOG = LoggerFactory.getLogger(GraphBackedTerm.class);

    private final RDF rdf = new SimpleRdf();
    private final Graph graph;
    private final IRI subject;
    private final DisplayLocale displayLocale;

    GraphBackedTerm(final Graph graph, final IRI subject, final DisplayLocale displayLocale) {
        this.graph = Objects.requireNonNull(graph, "graph");
        this.subject = Objects.requireNonNull(subject, "subject");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
    }

    /**
     * The subject's triples. Package-private on purpose: this is the seam the repository casts
     * to, and the reason {@link Term} carries no {@code asGraph()}.
     */
    Graph graph() {
        return graph;
    }

    /** The subject IRI this term's triples hang off. */
    IRI subject() {
        return subject;
    }

    @Override
    public TermId id() {
        return new TermId(ResourceId.of(subject.getIRIString()));
    }

    @Override
    public TermCode code() {
        return new TermCode(firstLexicalForm(UlVocabulary.IDENTIFIER_IRI)
                .orElseThrow(() -> new IllegalStateException(
                        "term " + subject.getIRIString() + " carries no dcterms:identifier")));
    }

    @Override
    public String prefLabel() {
        final List<LocalizedLiteral> candidates = literals(UlVocabulary.PREF_LABEL_IRI).stream()
                .map(literal -> new LocalizedLiteral(literal.getLexicalForm(),
                        literal.getLanguageTag().orElse(null)))
                .toList();
        return displayLocale.select(candidates)
                .map(LocalizedLiteral::value)
                .orElseThrow(() -> new IllegalStateException(
                        "term " + subject.getIRIString() + " carries no skos:prefLabel"));
    }

    @Override
    public void prefLabel(final String value) {
        replaceLiteral(UlVocabulary.PREF_LABEL_IRI, Term.requireLabel(value));
    }

    @Override
    public String definition() {
        final List<String> candidates = literals(UlVocabulary.DEFINITION_IRI).stream()
                .map(Literal::getLexicalForm)
                .toList();
        if (candidates.isEmpty()) {
            throw new IllegalStateException(
                    "term " + subject.getIRIString() + " carries no skos:definition");
        }
        final long distinct = candidates.stream().distinct().count();
        if (distinct > 1) {
            LOG.warn("Term {}: field 'definition' had {} distinct values, returning the first",
                    subject.getIRIString(), distinct);
        }
        return candidates.get(0);
    }

    @Override
    public void definition(final String value) {
        replaceLiteral(UlVocabulary.DEFINITION_IRI, Term.requireDefinition(value));
    }

    @Override
    public ActorFacet actorFacet() {
        final ActorKind kind = actorKind();
        if (kind == null) {
            return null;
        }
        return new ActorFacet(kind, firstLexicalForm(UlVocabulary.ACTOR_ROLE_IRI).orElse(null));
    }

    @Override
    public void actorFacet(final ActorFacet value) {
        removeAll(graph.stream(subject, VocabRdf.TYPE, UlVocabulary.HUMAN_ACTOR_IRI).toList());
        removeAll(graph.stream(subject, VocabRdf.TYPE, UlVocabulary.SYSTEM_ACTOR_IRI).toList());
        removeAll(graph.stream(subject, UlVocabulary.ACTOR_ROLE_IRI, null).toList());
        if (value == null) {
            return;
        }
        graph.add(subject, VocabRdf.TYPE,
                value.kind() == ActorKind.HUMAN ? UlVocabulary.HUMAN_ACTOR_IRI : UlVocabulary.SYSTEM_ACTOR_IRI);
        if (value.role() != null) {
            graph.add(subject, UlVocabulary.ACTOR_ROLE_IRI, rdf.createLiteral(value.role()));
        }
    }

    @Override
    public boolean equals(final Object other) {
        return Term.equal(this, other);
    }

    @Override
    public int hashCode() {
        return Term.hash(this);
    }

    @Override
    public String toString() {
        return "GraphBackedTerm[" + subject.getIRIString() + ", " + graph.size() + " triples]";
    }

    private ActorKind actorKind() {
        if (graph.stream(subject, VocabRdf.TYPE, UlVocabulary.HUMAN_ACTOR_IRI).findAny().isPresent()) {
            return ActorKind.HUMAN;
        }
        if (graph.stream(subject, VocabRdf.TYPE, UlVocabulary.SYSTEM_ACTOR_IRI).findAny().isPresent()) {
            return ActorKind.SYSTEM;
        }
        return null;
    }

    private List<Literal> literals(final IRI predicate) {
        return graph.stream(subject, predicate, null)
                .map(Triple::getObject)
                .filter(Literal.class::isInstance)
                .map(Literal.class::cast)
                .toList();
    }

    private Optional<String> firstLexicalForm(final IRI predicate) {
        return literals(predicate).stream().map(Literal::getLexicalForm).findFirst();
    }

    private void replaceLiteral(final IRI predicate, final String value) {
        removeAll(graph.stream(subject, predicate, null).toList());
        graph.add(subject, predicate, rdf.createLiteral(value));
    }

    private void removeAll(final List<Triple> triples) {
        triples.forEach(graph::remove);
    }
}
