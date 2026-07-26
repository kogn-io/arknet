// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.kogniordf;

import java.util.Objects;

import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.ul.application.port.out.TermFactory;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Creates the {@link GraphBackedTerm} instances {@link KognioRdfTermRepository} can persist
 * without translating them (spike, issue #168).
 *
 * <p>This is where the write-side field-to-predicate mapping ended up. It did not disappear:
 * the triples the repository's {@code create} used to assemble are assembled here instead, minus
 * the ones the setters already know how to write. What changed is that they are now assembled
 * <em>once</em> - the read path reverses no mapping, because it does not translate at all.</p>
 */
final class GraphBackedTermFactory implements TermFactory {

    private final RDF rdf = new SimpleRdf();
    private final DisplayLocale displayLocale;

    GraphBackedTermFactory(final DisplayLocale displayLocale) {
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
    }

    @Override
    public Term newTerm(final TermId id, final TermCode code, final String prefLabel, final String definition,
            final ActorFacet actorFacet) {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");

        // ResourceId#of (issue #83) validates IRIREF-safety at construction, so the wrapped IRI
        // is already guaranteed safe to embed.
        final IRI subject = rdf.createIRI(id.value().value());
        final Graph graph = rdf.createGraph();
        graph.add(subject, VocabRdf.TYPE, UlVocabulary.CONCEPT_TYPE_IRI);
        graph.add(subject, UlVocabulary.IN_SCHEME_IRI, UlVocabulary.GLOSSARY_SCHEME_IRI);
        graph.add(subject, UlVocabulary.IDENTIFIER_IRI, rdf.createLiteral(code.value()));

        final GraphBackedTerm term = new GraphBackedTerm(graph, subject, displayLocale);
        term.prefLabel(prefLabel);
        term.definition(definition);
        term.actorFacet(actorFacet);
        return term;
    }
}
