package io.kognio.arknet.mcp;

import io.kognio.arknet.core.ModelLoader;
import io.kognio.arknet.core.SparqlExecutor;
import io.kognio.arknet.core.ValidationReport;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.repository.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Stateful wrapper around arknet-core. Holds the loaded model in memory
 * so multiple queries can run against the same triple store.
 */
public class ArknetEngine {

    private static final Map<String, String> PREDEFINED_QUERIES = loadPredefinedQueries();

    private final ModelLoader modelLoader = new ModelLoader();
    private final SparqlExecutor sparqlExecutor = new SparqlExecutor();
    private Repository repository;

    public String load(String filePath) throws IOException {
        if (repository != null) {
            repository.shutDown();
        }
        repository = modelLoader.loadModel(Path.of(filePath));
        long count;
        try (var conn = repository.getConnection()) {
            count = conn.size();
        }
        return "Model loaded: %s (%d triples)".formatted(filePath, count);
    }

    public ValidationReport validate(String filePath) throws IOException {
        return modelLoader.validateModel(Path.of(filePath));
    }

    public String query(String queryOrName) {
        if (repository == null) {
            return "Error: No model loaded. Use arknet_load first.";
        }

        String sparql;
        if (queryOrName.toUpperCase().matches("Q\\d{2}")) {
            sparql = PREDEFINED_QUERIES.get(queryOrName.toUpperCase());
            if (sparql == null) {
                return "Error: Unknown predefined query '%s'. Available: %s".formatted(
                        queryOrName, String.join(", ", PREDEFINED_QUERIES.keySet()));
            }
        } else {
            sparql = queryOrName;
        }

        List<BindingSet> results = sparqlExecutor.select(repository, sparql);
        return formatResults(results);
    }

    public List<String> listQueries() {
        return List.copyOf(PREDEFINED_QUERIES.keySet());
    }

    private String formatResults(List<BindingSet> results) {
        if (results.isEmpty()) {
            return "(no results)";
        }

        var sb = new StringBuilder();
        var bindingNames = results.getFirst().getBindingNames();

        // Header
        sb.append(String.join(" | ", bindingNames)).append("\n");
        sb.append(bindingNames.stream().map(n -> "-".repeat(Math.max(n.length(), 10))).reduce((a, b) -> a + " | " + b).orElse("")).append("\n");

        // Rows
        for (BindingSet bs : results) {
            var values = bindingNames.stream()
                    .map(name -> {
                        var v = bs.getValue(name);
                        return v != null ? v.stringValue() : "";
                    })
                    .toList();
            sb.append(String.join(" | ", values)).append("\n");
        }

        sb.append("\n(%d rows)".formatted(results.size()));
        return sb.toString();
    }

    private static Map<String, String> loadPredefinedQueries() {
        var queries = new LinkedHashMap<String, String>();
        try (InputStream is = ArknetEngine.class.getResourceAsStream("/sparql/process-queries.sparql")) {
            if (is == null) return queries;
            String content = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            parseQueries(content, queries);
        } catch (IOException e) {
            // Silently ignore — predefined queries will be empty
        }
        return queries;
    }

    private static void parseQueries(String content, Map<String, String> queries) {
        // Split on "## Q" headers, extract query ID and SPARQL
        String[] sections = content.split("(?=## Q\\d{2}:)");
        String prefixes = extractPrefixes(content);

        for (String section : sections) {
            var matcher = java.util.regex.Pattern.compile("## (Q\\d{2}): (.+?)\\n").matcher(section);
            if (matcher.find()) {
                String id = matcher.group(1);
                // Extract the SELECT statement
                int selectIdx = section.indexOf("SELECT");
                if (selectIdx >= 0) {
                    String sparql = prefixes + "\n" + section.substring(selectIdx).trim();
                    // Remove trailing comments from next section
                    int nextComment = sparql.indexOf("\n## ");
                    if (nextComment > 0) {
                        sparql = sparql.substring(0, nextComment).trim();
                    }
                    queries.put(id, sparql);
                }
            }
        }
    }

    private static String extractPrefixes(String content) {
        var sb = new StringBuilder();
        for (String line : content.split("\n")) {
            if (line.startsWith("PREFIX ")) {
                sb.append(line).append("\n");
            }
        }
        return sb.toString();
    }
}
