package de.hauschel.arknet.mcp;

import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;

import de.hauschel.arknet.core.ValidationReport;

/**
 * MCP tools of the arknet engine, declared Spring-AI style via {@link McpTool} /
 * {@link McpToolParam}. Each tool delegates to the stateful {@link ArknetEngine}; the
 * tool name, description and JSON input schema are derived from the annotations and the
 * method signature instead of being hand-written.
 *
 * <p><strong>Error handling.</strong> Spring AI maps any exception thrown from a tool
 * method onto an error {@code CallToolResult}, so the previous hand-rolled try/catch
 * blocks are gone. The descriptive error prefixes ("Error loading model: ...", etc.) are
 * preserved by wrapping the checked failures with a matching message.</p>
 */
public class ArknetTools {

    private final ArknetEngine engine;

    public ArknetTools(final ArknetEngine engine) {
        this.engine = Objects.requireNonNull(engine, "engine");
    }

    @McpTool(name = "arknet_load",
            description = "Load a DDD architecture model (Turtle .ttl file) into the triple store for querying.")
    public String load(
            @McpToolParam(description = "Absolute path to the Turtle model file (.ttl)") final String filePath) {
        try {
            return engine.load(filePath);
        } catch (final Exception e) {
            throw new IllegalStateException("Error loading model: " + e.getMessage(), e);
        }
    }

    @McpTool(name = "arknet_validate",
            description = "Validate a DDD architecture model against SHACL shapes. Returns violations (errors) and warnings.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String validate(
            @McpToolParam(description = "Absolute path to the Turtle model file (.ttl)") final String filePath) {
        try {
            final ValidationReport report = engine.validate(filePath);
            return formatValidationReport(report);
        } catch (final Exception e) {
            throw new IllegalStateException("Error validating model: " + e.getMessage(), e);
        }
    }

    @McpTool(name = "arknet_query",
            description = "Run a SPARQL query against the loaded architecture model. "
                    + "Pass a predefined query name (Q01-Q20) or a full SPARQL SELECT string. "
                    + "The model must be loaded first with arknet_load. "
                    + "Predefined queries: Q01=Processes overview, Q02=State machines, Q03=Event flow, "
                    + "Q04=Inputs/outputs, Q05=Preconditions, Q06=Process steps, Q07=Failure outcomes, "
                    + "Q08=Aggregate fields, Q09-Q15=Gap analysis, Q16-Q20=Reverse engineering.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String query(
            @McpToolParam(description = "SPARQL SELECT query string, or predefined query name (Q01-Q20)") final String query) {
        try {
            return engine.query(query);
        } catch (final Exception e) {
            throw new IllegalStateException("Error executing query: " + e.getMessage(), e);
        }
    }

    @McpTool(name = "arknet_list_queries",
            description = "List all predefined SPARQL queries available for arknet_query.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String listQueries() {
        return "Predefined queries: " + String.join(", ", engine.listQueries());
    }

    @McpTool(name = "arknet_generate",
            description = "Generate documentation from a DDD architecture model. "
                    + "Use arknet_list_projections to see available projection types.")
    public String generate(
            @McpToolParam(description = "Absolute path to the Turtle model file (.ttl)") final String filePath,
            @McpToolParam(required = false,
                    description = "Projection type (default: context-map). Use arknet_list_projections for options.") final String projection,
            @McpToolParam(required = false,
                    description = "Output directory (default: docs)") final String outputDir,
            @McpToolParam(required = false,
                    description = "Output format: html or pdf (default: html)") final String format) {
        final String projectionName = orDefault(projection, "context-map");
        final String targetDir = orDefault(outputDir, "docs");
        final String outputFormat = orDefault(format, "html");
        try {
            return engine.generate(filePath, projectionName, targetDir, outputFormat);
        } catch (final Exception e) {
            throw new IllegalStateException("Error generating documentation: " + e.getMessage(), e);
        }
    }

    @McpTool(name = "arknet_list_projections",
            description = "List all available projection types for arknet_generate.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String listProjections() {
        return "Available projections:\n" + String.join("\n", engine.listProjections());
    }

    private static String orDefault(final String value, final String fallback) {
        return (value == null || value.isBlank()) ? fallback : value;
    }

    private static String formatValidationReport(final ValidationReport report) {
        if (report.conforms()) {
            return "Model is valid. 0 violations, 0 warnings.";
        }

        final var sb = new StringBuilder();
        final long violations = report.results().stream().filter(r -> r.isViolation()).count();
        final long warnings = report.results().stream().filter(r -> !r.isViolation()).count();

        sb.append("Validation result: %d violation(s), %d warning(s)\n\n".formatted(violations, warnings));

        for (final var r : report.results()) {
            final String icon = r.isViolation() ? "ERROR" : "WARN";
            sb.append("%s: %s\n".formatted(icon, r.message()));
            if (!r.focusNode().isEmpty()) {
                sb.append("  Node: %s\n".formatted(r.focusNode()));
            }
            if (!r.path().isEmpty()) {
                sb.append("  Path: %s\n".formatted(r.path()));
            }
            sb.append("\n");
        }

        return sb.toString();
    }
}
