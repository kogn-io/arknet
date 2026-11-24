// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.kogn.rdf.shacl.ShaclValidation;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;

/**
 * Checks the condition of a status transition: one named shape on one focus node.
 *
 * <p>The shapes are the ones the write gate validates against, with every target declaration taken
 * out, so no shape but the condition fires; the condition gets the focus node as its only target.
 * The data is merged with the same ontology axioms, reasoning is on and the messages are picked in
 * the same display language as in the gate, so a condition reads like any other rule of the
 * shapes.</p>
 */
final class TransitionConditions {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final IRI TARGET_NODE = RDF_FACTORY.createIRI(SHACL + "targetNode");
    private static final Set<IRI> TARGETS = Set.of(RDF_FACTORY.createIRI(SHACL + "target"),
            RDF_FACTORY.createIRI(SHACL + "targetClass"), TARGET_NODE,
            RDF_FACTORY.createIRI(SHACL + "targetSubjectsOf"), RDF_FACTORY.createIRI(SHACL + "targetObjectsOf"));

    private final ShaclValidation validation;
    private final Graph untargeted = RDF_FACTORY.createGraph();
    private final ReadableGraph axioms;
    private final DisplayLocale displayLocale;

    TransitionConditions(final ShaclValidation validation, final ReadableGraph shapes, final ReadableGraph axioms,
            final DisplayLocale displayLocale) {
        this.validation = Objects.requireNonNull(validation, "validation");
        this.axioms = Objects.requireNonNull(axioms, "axioms");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        shapes.stream().filter(triple -> !TARGETS.contains(triple.getPredicate())).forEach(untargeted::add);
    }

    /**
     * The violations of {@code shape} on {@code focus} over {@code data}.
     *
     * @param shape the named condition shape
     * @param focus the node it is checked on
     * @param data  the state after the document
     * @return the gate's message, or nothing if the shape holds
     */
    List<String> violations(final IRI shape, final IRI focus, final ReadableGraph data) {
        Graph targeted = RDF_FACTORY.createGraph();
        untargeted.stream().forEach(targeted::add);
        targeted.add(shape, TARGET_NODE, focus);
        try {
            new ShaclWriteGate(validation, targeted, axioms, new ValidationOptions(true), displayLocale).enforce(data);
            return List.of();
        } catch (WriteConstraintViolationException e) {
            return List.of(e.getMessage());
        }
    }
}
