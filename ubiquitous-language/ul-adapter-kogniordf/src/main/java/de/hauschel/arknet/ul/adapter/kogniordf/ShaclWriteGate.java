package de.hauschel.arknet.ul.adapter.kogniordf;

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
 * SHACL write-gate: rejects a candidate instance graph before persistence if it violates
 * the ubiquitous-language SHACL shapes.
 *
 * <p>Technology-neutral: depends only on the {@code io.kogn.rdf.shacl} port and
 * {@code io.kogn.rdf.terms}, never on RDF4J - the concrete {@link ShaclValidation} and the
 * loaded shapes/axioms graphs are handed in by {@link KognioRdfTermRepositoryFactory}, the
 * only RDF4J-aware collaborator in this module.</p>
 *
 * <p>{@code TermShape} targets {@code skos:Concept} directly, which the adapter already
 * assigns to every term instance, so no RDFS reasoning or ontology axioms are needed here
 * (unlike the sibling requirements adapter) - the factory passes an empty {@code axioms}
 * graph and {@link ValidationOptions#defaults()}.</p>
 */
final class ShaclWriteGate {

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
     * @param axioms     ontology axioms merged into the validated data graph (an empty
     *                   graph if no axioms are needed)
     * @param options    validation options (e.g. whether to reason over {@code axioms})
     */
    ShaclWriteGate(ShaclValidation validation, ReadableGraph shapes, ReadableGraph axioms,
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
    void enforce(ReadableGraph candidate) {
        Objects.requireNonNull(candidate, "candidate");

        Graph data = rdf.createGraph();
        candidate.stream().forEach(data::add);
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
