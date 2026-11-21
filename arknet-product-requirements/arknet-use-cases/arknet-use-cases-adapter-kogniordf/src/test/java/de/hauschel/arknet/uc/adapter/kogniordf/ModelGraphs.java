// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;

import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;

/** Test-only bridge from hand-built RDF4J models to kognio-rdf graphs. */
final class ModelGraphs {

    private static final SimpleRdf RDF = new SimpleRdf();

    private ModelGraphs() {
    }

    static Graph toGraph(final Model model) {
        final Graph graph = RDF.createGraph();
        for (final Statement st : model) {
            graph.add((BlankNodeOrIRI) toTerm(st.getSubject()), RDF.createIRI(st.getPredicate().stringValue()),
                    toTerm(st.getObject()));
        }
        return graph;
    }

    private static RDFTerm toTerm(final Value value) {
        if (value instanceof BNode bnode) {
            return RDF.createBlankNode(bnode.getID());
        }
        if (value instanceof Literal literal) {
            if (literal.getLanguage().isPresent()) {
                return RDF.createLiteral(literal.getLabel(), literal.getLanguage().get());
            }
            return RDF.createLiteral(literal.getLabel(), RDF.createIRI(literal.getDatatype().stringValue()));
        }
        return RDF.createIRI(value.stringValue());
    }
}
