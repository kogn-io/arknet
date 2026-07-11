package de.hauschel.arknet.core;

import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.ModelBuilder;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.SHACL;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValidationReportTest {

    private static final ValueFactory VF = SimpleValueFactory.getInstance();

    /**
     * A SHACL report that contains only warnings must still count as conforming:
     * warnings are hints, not violations. Guards against conforms() == results.isEmpty().
     */
    @Test
    void warningsOnly_conformsIsTrue() {
        Model report = shaclResult(SHACL.WARNING, "Entity has no data category annotation.");

        ValidationReport result = ValidationReport.fromShaclReport(report);

        assertTrue(result.conforms(), "a report with only warnings must conform");
        assertEquals(1, result.results().size(), "the warning must still be reported");
        assertFalse(result.results().getFirst().isViolation());
    }

    @Test
    void violation_conformsIsFalse() {
        Model report = shaclResult(SHACL.VIOLATION, "BoundedContext must have a name.");

        ValidationReport result = ValidationReport.fromShaclReport(report);

        assertFalse(result.conforms(), "a violation breaks conformance");
        assertTrue(result.results().getFirst().isViolation());
    }

    @Test
    void mixedWarningAndViolation_conformsIsFalse() {
        Model report = new ModelBuilder(shaclResult(SHACL.WARNING, "just a hint"))
                .build();
        addShaclResult(report, SHACL.VIOLATION, "hard error");

        ValidationReport result = ValidationReport.fromShaclReport(report);

        assertFalse(result.conforms(), "any violation breaks conformance, even alongside warnings");
        assertEquals(2, result.results().size());
    }

    @Test
    void valid_conformsWithNoResults() {
        ValidationReport result = ValidationReport.valid();

        assertTrue(result.conforms());
        assertTrue(result.results().isEmpty());
    }

    private static Model shaclResult(org.eclipse.rdf4j.model.IRI severity, String message) {
        Model model = new ModelBuilder().build();
        addShaclResult(model, severity, message);
        return model;
    }

    private static void addShaclResult(Model model, org.eclipse.rdf4j.model.IRI severity, String message) {
        var node = VF.createBNode();
        model.add(node, RDF.TYPE, SHACL.VALIDATION_RESULT);
        model.add(node, SHACL.RESULT_SEVERITY, severity);
        model.add(node, SHACL.FOCUS_NODE, VF.createIRI("https://example.org/node"));
        model.add(node, SHACL.RESULT_PATH, VF.createIRI("https://example.org/path"));
        model.add(node, SHACL.RESULT_MESSAGE, VF.createLiteral(message));
    }
}
