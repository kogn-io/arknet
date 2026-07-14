package de.hauschel.arknet.uc.adapter.kogniordf;

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
 * the use-case SHACL shapes ({@code UseCaseShape}, {@code StepShape} in
 * {@code requirements-shapes.ttl}).
 *
 * <p>Technology-neutral: depends only on the {@code io.kogn.rdf.shacl} port and
 * {@code io.kogn.rdf.terms}, never on RDF4J - the concrete {@link ShaclValidation} and the
 * loaded shapes/axioms graphs are handed in by
 * {@link KognioRdfUseCaseRepositoryFactory}, the only RDF4J-aware collaborator in this
 * module.</p>
 *
 * <p><strong>Mirror, not reuse.</strong> This class is a byte-for-byte sibling of the
 * requirements adapter's {@code ShaclWriteGate}. That one is package-private and lives in a
 * bounded context this module must not depend on, so it is copied here rather than shared.
 * Extracting a single gate into a shared technical module is a deliberate later step (open
 * point), not part of this scaffolding.</p>
 *
 * <p><strong>RDFS gotcha.</strong> The shapes target the abstract {@code arkreq:UseCase} /
 * {@code arkreq:Step} classes, which is exactly how this adapter types its instances, so
 * (unlike the requirements adapter) no subclass reasoning is strictly required for the shape
 * to fire. Axioms and RDFS reasoning are still supplied for the {@code rdfs:subPropertyOf}
 * relation of {@code arkreq:stepRealises} to {@code oslc_rm:satisfies} and future needs.</p>
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
     * @param axioms     ontology axioms (e.g. {@code rdfs:subClassOf}/{@code subPropertyOf})
     *                   merged into the validated data graph (an empty graph if none needed)
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
