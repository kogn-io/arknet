// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.persistence.ChildFragments;
import de.hauschel.arknet.persistence.ChildIdSource;
import de.hauschel.arknet.resource.application.port.out.ResourceBatch;

/**
 * An in-memory batch over one graph holding every stored resource with its fragment children. It
 * evaluates no shape: a condition named in {@link #failing} is violated with the message given
 * there, every other one holds; each check is recorded.
 */
final class FakeBatch implements ResourceBatch {

    private static final RDF RDF_FACTORY = new SimpleRdf();

    private final Graph store = RDF_FACTORY.createGraph();
    final List<IRI> created = new ArrayList<>();
    final Map<IRI, ReadableGraph> createdSlices = new HashMap<>();
    final Map<IRI, ReadableGraph> updated = new HashMap<>();
    final List<IRI> deleted = new ArrayList<>();
    final Map<IRI, String> failing = new HashMap<>();
    final List<Check> checks = new ArrayList<>();

    void add(final IRI subject, final IRI predicate, final RDFTerm object) {
        store.add(subject, predicate, object);
    }

    @Override
    public ReadableGraph slice(final IRI subject) {
        Graph slice = RDF_FACTORY.createGraph();
        store.stream()
                .filter(triple -> triple.getSubject() instanceof IRI iri
                        && (iri.equals(subject) || iri.getIRIString().startsWith(subject.getIRIString() + "#")))
                .forEach(slice::add);
        return slice;
    }

    @Override
    public ReadableGraph pointingAt(final IRI resource) {
        Graph triples = RDF_FACTORY.createGraph();
        store.stream(null, null, resource).forEach(triples::add);
        return triples;
    }

    @Override
    public int highestRunningNumber(final String codePrefix) {
        return 0;
    }

    @Override
    public ChildFragments childFragments(final IRI parent, final ReadableGraph current) {
        return ChildFragments.of(parent, current, ChildIdSource.random());
    }

    @Override
    public List<String> violations(final IRI shape, final IRI focus, final ReadableGraph data) {
        checks.add(new Check(shape, focus, data));
        return failing.containsKey(shape) ? List.of(failing.get(shape)) : List.of();
    }

    @Override
    public void create(final IRI subject, final String code, final ReadableGraph slice,
            final ReadableGraph context) {
        created.add(subject);
        createdSlices.put(subject, slice);
    }

    @Override
    public void update(final IRI subject, final ReadableGraph slice, final ReadableGraph context) {
        updated.put(subject, slice);
    }

    @Override
    public void delete(final IRI subject, final String code) {
        deleted.add(subject);
    }

    /** One condition check the engine asked for. */
    record Check(IRI shape, IRI focus, ReadableGraph data) {
    }
}
