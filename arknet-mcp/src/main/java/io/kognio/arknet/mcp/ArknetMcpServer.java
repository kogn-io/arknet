package io.kognio.arknet.mcp;

import io.kognio.arknet.core.ValidationReport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.StdioServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.JsonSchema;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import java.util.List;
import java.util.Map;

public class ArknetMcpServer {

    public static void main(String[] args) {
        var engine = new ArknetEngine();
        var transport = new StdioServerTransportProvider(McpJsonDefaults.getMapper());

        McpSyncServer server = McpServer.sync(transport)
                .serverInfo("arknet", "0.2.0")
                .capabilities(McpSchema.ServerCapabilities.builder()
                        .tools(true)
                        .build())
                .toolCall(loadTool(), (exchange, request) -> {
                    String filePath = (String) request.arguments().get("filePath");
                    try {
                        String result = engine.load(filePath);
                        return CallToolResult.builder().addTextContent(result).build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .addTextContent("Error loading model: " + e.getMessage())
                                .isError(true).build();
                    }
                })
                .toolCall(validateTool(), (exchange, request) -> {
                    String filePath = (String) request.arguments().get("filePath");
                    try {
                        ValidationReport report = engine.validate(filePath);
                        return CallToolResult.builder()
                                .addTextContent(formatValidationReport(report))
                                .build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .addTextContent("Error validating model: " + e.getMessage())
                                .isError(true).build();
                    }
                })
                .toolCall(queryTool(), (exchange, request) -> {
                    String query = (String) request.arguments().get("query");
                    try {
                        String result = engine.query(query);
                        return CallToolResult.builder().addTextContent(result).build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .addTextContent("Error executing query: " + e.getMessage())
                                .isError(true).build();
                    }
                })
                .toolCall(listQueriesTool(), (exchange, request) -> {
                    var queries = engine.listQueries();
                    return CallToolResult.builder()
                            .addTextContent("Predefined queries: " + String.join(", ", queries))
                            .build();
                })
                .toolCall(generateTool(), (exchange, request) -> {
                    String filePath = (String) request.arguments().get("filePath");
                    String outputDir = (String) request.arguments().getOrDefault("outputDir", "docs");
                    String format = (String) request.arguments().getOrDefault("format", "html");
                    try {
                        String result = engine.generate(filePath, outputDir, format);
                        return CallToolResult.builder().addTextContent(result).build();
                    } catch (Exception e) {
                        return CallToolResult.builder()
                                .addTextContent("Error generating documentation: " + e.getMessage())
                                .isError(true).build();
                    }
                })
                .build();
    }

    private static Tool loadTool() {
        return Tool.builder()
                .name("arknet_load")
                .description("Load a DDD architecture model (Turtle .ttl file) into the triple store for querying.")
                .inputSchema(new JsonSchema(
                        "object",
                        Map.of("filePath", Map.of(
                                "type", "string",
                                "description", "Absolute path to the Turtle model file (.ttl)")),
                        List.of("filePath"),
                        null, null, null))
                .build();
    }

    private static Tool validateTool() {
        return Tool.builder()
                .name("arknet_validate")
                .description("Validate a DDD architecture model against SHACL shapes. Returns violations (errors) and warnings.")
                .inputSchema(new JsonSchema(
                        "object",
                        Map.of("filePath", Map.of(
                                "type", "string",
                                "description", "Absolute path to the Turtle model file (.ttl)")),
                        List.of("filePath"),
                        null, null, null))
                .build();
    }

    private static Tool queryTool() {
        return Tool.builder()
                .name("arknet_query")
                .description("Run a SPARQL query against the loaded architecture model. "
                        + "Pass a predefined query name (Q01-Q20) or a full SPARQL SELECT string. "
                        + "The model must be loaded first with arknet_load. "
                        + "Predefined queries: Q01=Processes overview, Q02=State machines, Q03=Event flow, "
                        + "Q04=Inputs/outputs, Q05=Preconditions, Q06=Process steps, Q07=Failure outcomes, "
                        + "Q08=Aggregate fields, Q09-Q15=Gap analysis, Q16-Q20=Reverse engineering.")
                .inputSchema(new JsonSchema(
                        "object",
                        Map.of("query", Map.of(
                                "type", "string",
                                "description", "SPARQL SELECT query string, or predefined query name (Q01-Q20)")),
                        List.of("query"),
                        null, null, null))
                .build();
    }

    private static Tool listQueriesTool() {
        return Tool.builder()
                .name("arknet_list_queries")
                .description("List all predefined SPARQL queries available for arknet_query.")
                .inputSchema(new JsonSchema(
                        "object",
                        Map.of(),
                        List.of(),
                        null, null, null))
                .build();
    }

    private static Tool generateTool() {
        return Tool.builder()
                .name("arknet_generate")
                .description("Generate documentation (Context Map as HTML or PDF) from a DDD architecture model.")
                .inputSchema(new JsonSchema(
                        "object",
                        Map.of(
                                "filePath", Map.of(
                                        "type", "string",
                                        "description", "Absolute path to the Turtle model file (.ttl)"),
                                "outputDir", Map.of(
                                        "type", "string",
                                        "description", "Output directory (default: docs)"),
                                "format", Map.of(
                                        "type", "string",
                                        "description", "Output format: html or pdf (default: html)")),
                        List.of("filePath"),
                        null, null, null))
                .build();
    }

    private static String formatValidationReport(ValidationReport report) {
        if (report.conforms()) {
            return "Model is valid. 0 violations, 0 warnings.";
        }

        var sb = new StringBuilder();
        long violations = report.results().stream().filter(r -> r.isViolation()).count();
        long warnings = report.results().stream().filter(r -> !r.isViolation()).count();

        sb.append("Validation result: %d violation(s), %d warning(s)\n\n".formatted(violations, warnings));

        for (var r : report.results()) {
            String icon = r.isViolation() ? "ERROR" : "WARN";
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
