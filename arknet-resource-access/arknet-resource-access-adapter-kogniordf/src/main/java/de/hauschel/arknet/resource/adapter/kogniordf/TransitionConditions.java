// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.ShaclResult;
import io.kogn.rdf.shacl.ShaclValidation;
import io.kogn.rdf.shacl.Severity;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.LocalizedLiteral;

/**
 * Checks the condition of a status transition: one named shape on one focus node.
 *
 * <p>The shapes are the ones the write gate validates against, with every target declaration taken
 * out, so no shape but the condition fires; the condition gets the focus node as its only target.
 * The data is merged with the same ontology axioms, reasoning is on and the messages are picked in
 * the same display language as in the gate, so a condition reads like any other rule of the
 * shapes. Each violation is reported on its own, in the form the gate uses ({@code focusNode=...,
 * path=..., message=...}).</p>
 *
 * <p>One validation per condition and focus node: the target is the focus, and the data is the
 * focus's state after the document, so neither can be shared across the conditions of a document.
 * The copy of the untargeted shapes per call is small next to the validation itself.</p>
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
     * @return one message per violation, empty if the shape holds
     */
    List<String> violations(final IRI shape, final IRI focus, final ReadableGraph data) {
        Graph targeted = RDF_FACTORY.createGraph();
        untargeted.stream().forEach(targeted::add);
        targeted.add(shape, TARGET_NODE, focus);
        Graph merged = RDF_FACTORY.createGraph();
        data.stream().forEach(merged::add);
        axioms.stream().forEach(merged::add);
        ShaclReport report = validation.validate(merged, targeted, new ValidationOptions(true));
        if (report.conforms()) {
            return List.of();
        }
        return report.results().stream()
                .filter(result -> result.severity() == Severity.VIOLATION)
                .map(this::describe)
                .toList();
    }

    private String describe(final ShaclResult result) {
        StringBuilder description = new StringBuilder("focusNode=").append(result.focusNode());
        if (result.path() != null) {
            description.append(", path=").append(result.path());
        }
        displayLocale.select(result.messages().stream()
                        .map(message -> new LocalizedLiteral(message.text(), message.language()))
                        .toList())
                .ifPresent(message -> description.append(", message=").append(message.value()));
        return description.toString();
    }
}
