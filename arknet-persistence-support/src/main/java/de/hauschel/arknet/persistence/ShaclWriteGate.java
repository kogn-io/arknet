// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import io.kogn.rdf.shacl.ShaclReport;
import io.kogn.rdf.shacl.ShaclResult;
import io.kogn.rdf.shacl.ShaclValidation;
import io.kogn.rdf.shacl.Severity;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;

/**
 * SHACL write-gate: rejects a candidate instance graph before persistence if it violates the
 * shapes it was built with. Shared by every bounded context's kognio-rdf out-adapter.
 *
 * <p>The gate is deliberately ignorant of <em>which</em> context it guards. Everything that
 * differs per context is constructor state, not code:</p>
 * <ul>
 *   <li>{@code shapes} - the context's SHACL shapes (possibly filtered to the node shapes the
 *       writing adapter actually owns).</li>
 *   <li>{@code axioms} + {@code options} - the ontology axioms merged into the validated data
 *       graph and whether to reason over them. This is what makes shapes fire that target a
 *       superclass or a super-property of the type the adapter asserts; an adapter whose
 *       instances already carry the targeted type passes an empty graph and
 *       {@link ValidationOptions#defaults()}.</li>
 * </ul>
 *
 * <p><strong>Technology-neutral.</strong> Depends only on the {@code io.kogn.rdf.shacl} port
 * and {@code io.kogn.rdf.terms}, never on RDF4J: the concrete {@link ShaclValidation} and the
 * loaded shapes/axioms graphs are handed in by each adapter's repository factory, which stays
 * the only RDF4J-aware collaborator. That is why this module carries no RDF4J dependency even
 * though every one of its callers does.</p>
 *
 * <p><strong>Validation-only asserted context (issue #63).</strong>
 * {@link #enforce(ReadableGraph, ReadableGraph)} accepts a second graph of triples that are
 * merged into the validated data alongside {@code candidate} and {@code axioms}, but are never
 * persisted - the gate persists nothing to begin with; persistence remains the calling
 * adapter's job. This lets an adapter satisfy an {@code sh:class} constraint on a node that
 * belongs to a sibling graph it does not own (e.g. a term or actor referenced by IRI) without
 * copying its own candidate graph. It stays context-neutral: {@code assertedContext} is a
 * <em>parameter</em> supplied per call, not context knowledge baked into the gate - which
 * synthetic type triples to assert remains entirely the calling adapter's decision.</p>
 */
public final class ShaclWriteGate {

    private final ShaclValidation validation;
    private final ReadableGraph shapes;
    private final ReadableGraph axioms;
    private final ValidationOptions options;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the gate.
     *
     * @param validation the SHACL validation port implementation
     * @param shapes     the SHACL shapes to validate candidate graphs against
     * @param axioms     ontology axioms (e.g. {@code rdfs:subClassOf} / {@code subPropertyOf})
     *                   merged into the validated data graph so that shapes targeting a
     *                   superclass fire (an empty graph if no axioms are needed)
     * @param options    validation options (e.g. whether to reason over {@code axioms})
     */
    public ShaclWriteGate(ShaclValidation validation, ReadableGraph shapes, ReadableGraph axioms,
            ValidationOptions options) {
        this.validation = Objects.requireNonNull(validation, "validation");
        this.shapes = Objects.requireNonNull(shapes, "shapes");
        this.axioms = Objects.requireNonNull(axioms, "axioms");
        this.options = Objects.requireNonNull(options, "options");
    }

    /**
     * Validates the candidate graph against the SHACL shapes and rejects it if it does not
     * conform.
     *
     * @param candidate the instance graph about to be persisted
     * @throws WriteConstraintViolationException if the candidate violates the shapes
     */
    public void enforce(ReadableGraph candidate) {
        Objects.requireNonNull(candidate, "candidate");
        enforce(candidate, rdf.createGraph());
    }

    /**
     * Validates the candidate graph together with a validation-only asserted context against
     * the SHACL shapes and rejects it if it does not conform.
     *
     * @param candidate       the instance graph about to be persisted
     * @param assertedContext additional triples merged into the validated data for this call
     *                        only - never persisted (e.g. type triples for nodes the candidate
     *                        references but does not itself own)
     * @throws WriteConstraintViolationException if the merged data violates the shapes
     */
    public void enforce(ReadableGraph candidate, ReadableGraph assertedContext) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(assertedContext, "assertedContext");

        Graph data = rdf.createGraph();
        candidate.stream().forEach(data::add);
        assertedContext.stream().forEach(data::add);
        axioms.stream().forEach(data::add);

        ShaclReport report = validation.validate(data, shapes, options);
        if (!report.conforms()) {
            throw new WriteConstraintViolationException(violationMessage(report.results()));
        }
    }

    private static String violationMessage(List<ShaclResult> results) {
        return results.stream()
                .filter(result -> result.severity() == Severity.VIOLATION)
                .map(ShaclWriteGate::describe)
                .collect(Collectors.joining("; "));
    }

    private static String describe(ShaclResult result) {
        StringBuilder description = new StringBuilder("focusNode=").append(result.focusNode());
        if (result.path() != null) {
            description.append(", path=").append(result.path());
        }
        if (result.message() != null) {
            description.append(", message=").append(result.message());
        }
        return description.toString();
    }
}
