package de.hauschel.arknet.core;

import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.SHACL;

import java.util.ArrayList;
import java.util.List;

public record ValidationReport(boolean conforms, List<ValidationResult> results) {

    public static ValidationReport valid() {
        return new ValidationReport(true, List.of());
    }

    public static ValidationReport fromShaclReport(Model model) {
        var results = new ArrayList<ValidationResult>();

        for (Resource resultNode : model.filter(null, RDF.TYPE, SHACL.VALIDATION_RESULT).subjects()) {
            String focusNode = extractString(model, resultNode, SHACL.FOCUS_NODE);
            String path = extractString(model, resultNode, SHACL.RESULT_PATH);
            String message = extractString(model, resultNode, SHACL.RESULT_MESSAGE);
            String severity = extractString(model, resultNode, SHACL.RESULT_SEVERITY);

            var level = severity.contains("Warning")
                    ? ValidationResult.Severity.WARNING
                    : ValidationResult.Severity.VIOLATION;

            results.add(new ValidationResult(focusNode, path, message, level));
        }

        return new ValidationReport(results.isEmpty(), results);
    }

    private static String extractString(Model model, Resource subject, IRI predicate) {
        return model.filter(subject, predicate, null)
                .objects().stream()
                .findFirst()
                .map(Value::stringValue)
                .orElse("");
    }
}
