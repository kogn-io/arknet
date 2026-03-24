package io.kognio.arknet.projection;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import io.kognio.arknet.core.SparqlExecutor;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.repository.Repository;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public class GlossaryProjection implements Projection {

    private static final String NS = "https://w3id.org/arknet/core#";

    private final SparqlExecutor sparql = new SparqlExecutor();
    private final AsciiDocConverter converter = new AsciiDocConverter();
    private final Mustache template;

    public GlossaryProjection() {
        template = new DefaultMustacheFactory().compile("templates/glossary.mustache");
    }

    @Override
    public String name() {
        return "glossary";
    }

    @Override
    public String description() {
        return "Ubiquitous Language glossary per Bounded Context (HTML/PDF via AsciiDoc)";
    }

    @Override
    public void generate(Repository repo, Path outputDir, String format) throws IOException {
        String adocContent = render(repo);
        converter.convert(adocContent, outputDir, "glossary", format);
    }

    String render(Repository repo) {
        String archName = queryArchitectureName(repo);
        List<Map<String, Object>> contexts = queryGlossary(repo);

        Map<String, Object> model = new HashMap<>();
        model.put("architectureName", archName);
        model.put("contexts", contexts);

        var writer = new StringWriter();
        template.execute(writer, model);
        return writer.toString();
    }

    private String queryArchitectureName(Repository repo) {
        String query = "PREFIX arknet: <%s>\nSELECT ?name WHERE { ?arch a arknet:Architecture ; arknet:name ?name . } LIMIT 1".formatted(NS);
        var results = sparql.select(repo, query);
        return results.isEmpty() ? "Architecture" : str(results.getFirst(), "name");
    }

    private List<Map<String, Object>> queryGlossary(Repository repo) {
        String query = loadSparql("/sparql/glossary.sparql");
        var results = sparql.select(repo, query);

        Map<String, List<String>> termsByContext = new LinkedHashMap<>();
        for (BindingSet bs : results) {
            String context = str(bs, "context");
            String term = str(bs, "term");
            if (!context.isEmpty() && !term.isEmpty()) {
                termsByContext.computeIfAbsent(context, k -> new ArrayList<>()).add(term);
            }
        }

        List<Map<String, Object>> contexts = new ArrayList<>();
        for (var entry : termsByContext.entrySet()) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("name", entry.getKey());
            ctx.put("terms", entry.getValue());
            contexts.add(ctx);
        }
        return contexts;
    }

    private String str(BindingSet bs, String name) {
        Value v = bs.getValue(name);
        return v != null ? v.stringValue() : "";
    }

    private String loadSparql(String resource) {
        try (var is = getClass().getResourceAsStream(resource)) {
            if (is == null) throw new IOException("SPARQL resource not found: " + resource);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load SPARQL query: " + resource, e);
        }
    }
}
