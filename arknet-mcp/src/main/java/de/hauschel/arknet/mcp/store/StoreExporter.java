// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * The backup read path into a project dataset: a single {@code SELECT ?g ?s ?p ?o} spanning
 * every named graph, serialised as TriG text.
 *
 * <p>Unlike {@link StoreReader}, this class hides nothing. {@link StoreReader} excludes the
 * provenance and project-identity graphs because it feeds a view of the current model, not of
 * its own machinery; a backup exists precisely to restore both the model and that machinery, so
 * excluding either graph here would silently drop data from the backup rather than declutter a
 * view of it. For the same reason a row that does not fit the expected shape - graph and
 * predicate must be an {@code IRI}, subject a {@code BlankNodeOrIRI} (RDF-legally either), object
 * any {@code RDFTerm} - is never dropped the way {@link StoreReader#toTriple} drops one: a backup
 * that silently loses a row is worse than one that fails loudly. The subject is deliberately
 * widened to {@code BlankNodeOrIRI} rather than narrowed to {@code IRI}: {@code arkreq:usesTerm}
 * carries no {@code sh:nodeKind} constraint, so its target may legally be a blank node (see
 * {@code KognioRdfRequirementRepository#replaceTriples}), and that blank node's own triples (it as
 * subject) are a case this export must serialise, not crash on.</p>
 *
 * <p><strong>{@code IRI#ntriplesString()} is used only for a non-IRI object</strong> (a
 * {@code Literal} or a {@code BlankNode}), never for a graph, subject or predicate, or an object
 * that is itself an IRI - the RDF4J-backed {@code IRI} implementation this store runs on returns
 * its bare {@code getIRIString()} from {@code ntriplesString()}, without the surrounding
 * {@code <...>} the N-Triples/TriG grammar requires for an IRI term. {@link #iriref(IRI)} adds
 * that wrapping explicitly wherever a term is known to be an IRI.</p>
 */
public final class StoreExporter {

    private final DatasetLifecycle lifecycle;

    /**
     * @param lifecycle the shared kognio-rdf dataset lifecycle (must not be {@code null})
     */
    public StoreExporter(final DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    /**
     * Reads every statement of every named graph in the project and serialises it as TriG text.
     *
     * @param projectId the project to export
     * @return the complete TriG text of the project's dataset
     */
    public String exportTrig(final ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        final String query = "SELECT DISTINCT ?g ?s ?p ?o WHERE { GRAPH ?g { ?s ?p ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            final Map<String, List<String>> linesByGraph = new TreeMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                final GraphTriple triple = toGraphTriple(row);
                linesByGraph.computeIfAbsent(triple.graph(), ignored -> new ArrayList<>())
                        .add(triple.line());
            });
            return render(linesByGraph);
        }
    }

    private static String render(final Map<String, List<String>> linesByGraph) {
        final StringBuilder trig = new StringBuilder();
        linesByGraph.forEach((graph, lines) -> {
            trig.append('<').append(graph).append("> {\n");
            lines.stream().sorted(Comparator.naturalOrder()).forEach(line -> trig.append("    ").append(line).append('\n'));
            trig.append("}\n\n");
        });
        return trig.toString();
    }

    private static GraphTriple toGraphTriple(final BindingSet row) {
        final RDFTerm graph = row.getValue("g").orElse(null);
        final RDFTerm subject = row.getValue("s").orElse(null);
        final RDFTerm predicate = row.getValue("p").orElse(null);
        final RDFTerm object = row.getValue("o").orElse(null);
        if (!(graph instanceof IRI graphIri)) {
            throw new IllegalStateException("expected graph ?g to be an IRI, was: " + graph);
        }
        if (!(subject instanceof BlankNodeOrIRI subjectTerm)) {
            throw new IllegalStateException("expected subject ?s to be an IRI or a blank node, was: " + subject);
        }
        if (!(predicate instanceof IRI predicateIri)) {
            throw new IllegalStateException("expected predicate ?p to be an IRI, was: " + predicate);
        }
        if (object == null) {
            throw new IllegalStateException("expected object ?o to be bound, was null");
        }
        final String line = subjectTerm(subjectTerm) + " " + iriref(predicateIri) + " " + objectTerm(object) + " .";
        return new GraphTriple(graphIri.getIRIString(), line);
    }

    /**
     * A subject is RDF-legally either an IRI or a blank node - {@code arkreq:usesTerm} carries no
     * {@code sh:nodeKind} constraint, so a store-first edge can legally point at one
     * (see {@code KognioRdfRequirementRepository#replaceTriples}), and that blank node's own
     * triples (it as subject) are exactly what this method must not crash on.
     */
    private static String subjectTerm(final BlankNodeOrIRI subject) {
        return subject instanceof IRI subjectIri ? iriref(subjectIri) : subject.ntriplesString();
    }

    private static String objectTerm(final RDFTerm object) {
        return object instanceof IRI objectIri ? iriref(objectIri) : object.ntriplesString();
    }

    private static String iriref(final IRI iri) {
        return "<" + iri.getIRIString() + ">";
    }

    private record GraphTriple(String graph, String line) {
    }
}
