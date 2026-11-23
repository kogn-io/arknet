// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * The handles a submitted document names an existing resource by.
 *
 * <p>Turtle has no bare name, so a handle is written as a relative IRI, {@code <REQ-12>}. The
 * engine parses a document against {@link #BASE}, which turns it into {@code BASE + "REQ-12"}; every
 * IRI under the base is a handle and is replaced by the IRI of the resource it names before the
 * activities are read. The base itself is never stored and never shown: a handle that names no
 * resource is rejected, and a document that declares its own {@code @base} writes no handles.</p>
 */
public final class HandleReferences {

    /** The base a submitted document is parsed against; an IRI under it is a handle. */
    public static final String BASE = "https://w3id.org/arknet/resource/handle/";

    private static final RDF RDF_FACTORY = new SimpleRdf();

    private HandleReferences() {
    }

    /**
     * The handles a graph names, in subject or object position.
     *
     * @param graph the parsed document
     * @return the handles, sorted
     * @throws SubmitRejectedException if a handle stands in predicate position, where a field belongs
     */
    public static Set<String> in(final ReadableGraph graph) {
        Objects.requireNonNull(graph, "graph");
        Set<String> handles = new TreeSet<>();
        Set<String> asField = new TreeSet<>();
        graph.stream().forEach(triple -> {
            handle(triple.getSubject()).ifPresent(handles::add);
            handle(triple.getObject()).ifPresent(handles::add);
            handle(triple.getPredicate()).ifPresent(asField::add);
        });
        if (!asField.isEmpty()) {
            throw new SubmitRejectedException("a handle names a resource and cannot stand where a field belongs: "
                    + asField.stream().map(name -> "<" + name + ">").toList());
        }
        return handles;
    }

    /**
     * The graph with every handle replaced by the IRI of the resource it names.
     *
     * @param graph       the parsed document
     * @param iriByHandle the IRI each handle of the graph names
     * @return a new graph without handles
     */
    public static Graph replaced(final ReadableGraph graph, final Map<String, String> iriByHandle) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(iriByHandle, "iriByHandle");
        Graph replaced = RDF_FACTORY.createGraph();
        graph.stream().forEach(triple -> replaced.add(
                (BlankNodeOrIRI) resolved(triple.getSubject(), iriByHandle),
                triple.getPredicate(), resolved(triple.getObject(), iriByHandle)));
        return replaced;
    }

    private static RDFTerm resolved(final RDFTerm term, final Map<String, String> iriByHandle) {
        return handle(term).map(handle -> (RDFTerm) RDF_FACTORY.createIRI(
                Objects.requireNonNull(iriByHandle.get(handle), "no IRI for handle " + handle))).orElse(term);
    }

    private static Optional<String> handle(final RDFTerm term) {
        if (term instanceof IRI iri && iri.getIRIString().startsWith(BASE)) {
            return Optional.of(iri.getIRIString().substring(BASE.length()));
        }
        return Optional.empty();
    }
}
