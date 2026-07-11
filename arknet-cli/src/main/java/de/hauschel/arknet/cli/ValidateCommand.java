package de.hauschel.arknet.cli;

import de.hauschel.arknet.core.ModelLoader;
import de.hauschel.arknet.core.ValidationReport;
import picocli.CommandLine.Command;
import picocli.CommandLine.Parameters;

import java.nio.file.Path;
import java.util.concurrent.Callable;

@Command(name = "validate", description = "Validate an architecture model against SHACL shapes")
public class ValidateCommand implements Callable<Integer> {

    @Parameters(index = "0", description = "Path to the Turtle model file (.ttl)")
    private Path modelFile;

    @Override
    public Integer call() {
        try {
            var loader = new ModelLoader();
            ValidationReport report = loader.validateModel(modelFile);

            if (report.conforms()) {
                System.out.println("Model is valid.");
                return 0;
            }

            System.out.println("Validation failed:\n");
            report.results().forEach(r -> {
                String icon = r.isViolation() ? "  ERROR" : "  WARN ";
                System.out.printf("%s %s%n", icon, r.message());
                if (!r.focusNode().isEmpty()) {
                    System.out.printf("        Node: %s%n", r.focusNode());
                }
                if (!r.path().isEmpty()) {
                    System.out.printf("        Path: %s%n", r.path());
                }
                System.out.println();
            });

            long violations = report.results().stream().filter(r -> r.isViolation()).count();
            long warnings = report.results().stream().filter(r -> !r.isViolation()).count();
            System.out.printf("%d violation(s), %d warning(s)%n", violations, warnings);

            return violations > 0 ? 1 : 0;
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            return 2;
        }
    }
}
