package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

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
import io.kogn.rdf.terms.Triple;

/**
 * Unit test for the shared {@link ShaclWriteGate}.
 *
 * <p>Drives the gate against a recording fake {@link ShaclValidation} rather than a real
 * SHACL engine: what is under test here is the gate's own contract - merge axioms into the
 * validated data, pass shapes/options through untouched, translate a non-conforming report
 * into a {@link WriteConstraintViolationException} carrying only the violations. Whether a
 * given shape actually fires is the business of each adapter's own integration test.</p>
 */
class ShaclWriteGateTest {

    private static final String SUBJECT = "https://example.org/thing";
    private static final String AXIOM_SUBJECT = "https://example.org/Concrete";

    private final RDF rdf = new SimpleRdf();

    @Test
    void passesConformingCandidate() {
        ShaclWriteGate gate = gateReturning(new ShaclReport(true, List.of()));

        assertDoesNotThrow(() -> gate.enforce(graphWith(SUBJECT)));
    }

    @Test
    void rejectsNonConformingCandidate() {
        ShaclReport report = new ShaclReport(false,
                List.of(new ShaclResult(SUBJECT, "https://example.org/label", Severity.VIOLATION,
                        "missing label")));
        ShaclWriteGate gate = gateReturning(report);

        WriteConstraintViolationException thrown = assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(graphWith(SUBJECT)));

        assertEquals("focusNode=" + SUBJECT + ", path=https://example.org/label, message=missing label",
                thrown.getMessage());
    }

    @Test
    void messageOmitsAbsentPathAndMessage() {
        ShaclReport report = new ShaclReport(false,
                List.of(new ShaclResult(SUBJECT, null, Severity.VIOLATION, null)));
        ShaclWriteGate gate = gateReturning(report);

        WriteConstraintViolationException thrown = assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(graphWith(SUBJECT)));

        assertEquals("focusNode=" + SUBJECT, thrown.getMessage());
    }

    @Test
    void messageReportsOnlyViolationsAndJoinsThem() {
        ShaclReport report = new ShaclReport(false, List.of(
                new ShaclResult("https://example.org/warn", null, Severity.WARNING, "just a warning"),
                new ShaclResult("https://example.org/a", null, Severity.VIOLATION, "first"),
                new ShaclResult("https://example.org/info", null, Severity.INFO, "just info"),
                new ShaclResult("https://example.org/b", null, Severity.VIOLATION, "second")));
        ShaclWriteGate gate = gateReturning(report);

        WriteConstraintViolationException thrown = assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(graphWith(SUBJECT)));

        assertEquals("focusNode=https://example.org/a, message=first; "
                + "focusNode=https://example.org/b, message=second", thrown.getMessage());
    }

    /**
     * The RDFS gotcha the requirements/use-cases adapters depend on: shapes targeting a
     * superclass only fire if the axioms travel with the candidate into the validated data.
     */
    @Test
    void mergesAxiomsIntoValidatedData() {
        RecordingValidation validation = new RecordingValidation(new ShaclReport(true, List.of()));
        ReadableGraph shapes = graphWith("https://example.org/shape");
        ReadableGraph axioms = graphWith(AXIOM_SUBJECT);
        ValidationOptions options = new ValidationOptions(true);
        ShaclWriteGate gate = new ShaclWriteGate(validation, shapes, axioms, options);

        gate.enforce(graphWith(SUBJECT));

        assertNotNull(validation.data);
        assertEquals(2L, validation.data.size());
        assertTrue(containsSubject(validation.data, SUBJECT), "candidate triple must be validated");
        assertTrue(containsSubject(validation.data, AXIOM_SUBJECT), "axioms must be merged in");
        assertSame(shapes, validation.shapes);
        assertSame(options, validation.options);
    }

    /**
     * The other half of issue #63's validationGraph workaround: an {@code assertedContext}
     * graph passed to {@link ShaclWriteGate#enforce(ReadableGraph, ReadableGraph)} must be
     * merged into the validated data exactly like {@code axioms} - it is validation-only, never
     * persisted (the gate persists nothing to begin with).
     */
    @Test
    void mergesAssertedContextIntoValidatedData() {
        RecordingValidation validation = new RecordingValidation(new ShaclReport(true, List.of()));
        ShaclWriteGate gate = new ShaclWriteGate(validation, emptyGraph(), emptyGraph(),
                ValidationOptions.defaults());
        String contextSubject = "https://example.org/context";

        gate.enforce(graphWith(SUBJECT), graphWith(contextSubject));

        assertNotNull(validation.data);
        assertEquals(2L, validation.data.size());
        assertTrue(containsSubject(validation.data, SUBJECT), "candidate triple must be validated");
        assertTrue(containsSubject(validation.data, contextSubject), "asserted context must be merged in");
    }

    /**
     * {@link ShaclWriteGate#enforce(ReadableGraph)} must behave exactly like
     * {@link ShaclWriteGate#enforce(ReadableGraph, ReadableGraph)} with an empty asserted
     * context - the one-argument overload is a convenience delegate, not a different code path.
     */
    @Test
    void enforceWithoutContextEqualsEnforceWithEmptyContext() {
        RecordingValidation validation = new RecordingValidation(new ShaclReport(true, List.of()));
        ShaclWriteGate gate = new ShaclWriteGate(validation, emptyGraph(), emptyGraph(),
                ValidationOptions.defaults());

        gate.enforce(graphWith(SUBJECT));

        assertNotNull(validation.data);
        assertEquals(1L, validation.data.size());
        assertTrue(containsSubject(validation.data, SUBJECT));
    }

    /**
     * The gate must not accumulate state across writes - the ubiquitous-language adapter reuses
     * one gate instance for every term it persists.
     */
    @Test
    void doesNotLeakCandidateBetweenCalls() {
        RecordingValidation validation = new RecordingValidation(new ShaclReport(true, List.of()));
        ShaclWriteGate gate = new ShaclWriteGate(validation, emptyGraph(), emptyGraph(),
                ValidationOptions.defaults());

        gate.enforce(graphWith(SUBJECT));
        gate.enforce(graphWith("https://example.org/other"));

        assertEquals(1L, validation.data.size());
        assertTrue(containsSubject(validation.data, "https://example.org/other"));
    }

    @Test
    void rejectsNullArguments() {
        ShaclValidation validation = new RecordingValidation(new ShaclReport(true, List.of()));
        ValidationOptions options = ValidationOptions.defaults();

        assertThrows(NullPointerException.class,
                () -> new ShaclWriteGate(null, emptyGraph(), emptyGraph(), options));
        assertThrows(NullPointerException.class,
                () -> new ShaclWriteGate(validation, null, emptyGraph(), options));
        assertThrows(NullPointerException.class,
                () -> new ShaclWriteGate(validation, emptyGraph(), null, options));
        assertThrows(NullPointerException.class,
                () -> new ShaclWriteGate(validation, emptyGraph(), emptyGraph(), null));

        ShaclWriteGate gate = gateReturning(new ShaclReport(true, List.of()));
        assertThrows(NullPointerException.class, () -> gate.enforce(null));
        assertThrows(NullPointerException.class, () -> gate.enforce(null, emptyGraph()));
        assertThrows(NullPointerException.class, () -> gate.enforce(graphWith(SUBJECT), null));
    }

    private ShaclWriteGate gateReturning(ShaclReport report) {
        return new ShaclWriteGate(new RecordingValidation(report), emptyGraph(), emptyGraph(),
                ValidationOptions.defaults());
    }

    private Graph emptyGraph() {
        return rdf.createGraph();
    }

    private Graph graphWith(String subject) {
        Graph graph = rdf.createGraph();
        IRI type = rdf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type");
        graph.add(rdf.createIRI(subject), type, rdf.createIRI("https://example.org/Type"));
        return graph;
    }

    private static boolean containsSubject(ReadableGraph graph, String subject) {
        return graph.stream()
                .map(Triple::getSubject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .anyMatch(iri -> iri.getIRIString().equals(subject));
    }

    /**
     * Fake {@link ShaclValidation} that records what the gate handed it and returns a
     * canned report.
     */
    private static final class RecordingValidation implements ShaclValidation {

        private final ShaclReport report;

        private ReadableGraph data;
        private ReadableGraph shapes;
        private ValidationOptions options;

        private RecordingValidation(ShaclReport report) {
            this.report = report;
        }

        @Override
        public ShaclReport validate(ReadableGraph data, ReadableGraph shapes, ValidationOptions options) {
            this.data = data;
            this.shapes = shapes;
            this.options = options;
            return report;
        }
    }
}
