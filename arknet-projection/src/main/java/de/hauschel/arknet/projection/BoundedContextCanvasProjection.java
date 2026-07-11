package de.hauschel.arknet.projection;

import com.github.mustachejava.DefaultMustacheFactory;
import com.github.mustachejava.Mustache;
import de.hauschel.arknet.core.SparqlExecutor;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.repository.Repository;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;

public class BoundedContextCanvasProjection implements Projection {

    private static final String NS = "https://w3id.org/arknet/core#";

    private final SparqlExecutor sparql = new SparqlExecutor();
    private final AsciiDocConverter converter = new AsciiDocConverter();
    private final Mustache template;

    public BoundedContextCanvasProjection() {
        template = new DefaultMustacheFactory().compile("templates/bounded-context-canvas.mustache");
    }

    @Override
    public String name() {
        return "bounded-context-canvas";
    }

    @Override
    public String description() {
        return "Bounded Context Canvas with UL, Aggregates, Commands, Events (HTML/PDF via AsciiDoc)";
    }

    @Override
    public void generate(Repository repo, Path outputDir, String format) throws IOException {
        String adocContent = render(repo);
        converter.convert(adocContent, outputDir, "bounded-context-canvas", format);
    }

    String render(Repository repo) {
        String archName = queryArchitectureName(repo);
        List<Map<String, Object>> contexts = buildContextCanvases(repo);

        Map<String, Object> model = new HashMap<>();
        model.put("architectureName", archName);
        model.put("contexts", contexts);

        var writer = new StringWriter();
        template.execute(writer, model);
        return writer.toString();
    }

    private List<Map<String, Object>> buildContextCanvases(Repository repo) {
        Map<String, Map<String, Object>> contextMap = queryContexts(repo);
        Map<String, List<String>> ulTerms = queryUlTerms(repo);
        Map<String, List<Map<String, Object>>> aggregates = queryAggregates(repo);

        List<Map<String, Object>> result = new ArrayList<>();
        for (var entry : contextMap.entrySet()) {
            String ctxUri = entry.getKey();
            Map<String, Object> ctx = entry.getValue();

            List<String> terms = ulTerms.getOrDefault(ctxUri, List.of());
            ctx.put("terms", terms);
            ctx.put("hasTerms", !terms.isEmpty());

            List<Map<String, Object>> aggs = aggregates.getOrDefault(ctxUri, List.of());
            ctx.put("aggregates", aggs);
            ctx.put("hasAggregates", !aggs.isEmpty());

            result.add(ctx);
        }
        return result;
    }

    private Map<String, Map<String, Object>> queryContexts(Repository repo) {
        String query = loadSparql("/sparql/bc-canvas-contexts.sparql");
        var results = sparql.select(repo, query);

        Map<String, Map<String, Object>> contexts = new LinkedHashMap<>();
        for (BindingSet bs : results) {
            String ctxUri = str(bs, "ctxUri");
            if (contexts.containsKey(ctxUri)) continue;

            var ctx = new LinkedHashMap<String, Object>();
            ctx.put("name", str(bs, "ctxName"));
            ctx.put("domainVision", str(bs, "domainVision"));

            String subdomainUri = str(bs, "subdomainType");
            ctx.put("subdomainLabel", mapSubdomainLabel(subdomainUri));

            String ownedBy = str(bs, "ownedBy");
            if (!ownedBy.isEmpty()) ctx.put("ownedBy", ownedBy);

            String responsibilities = str(bs, "responsibilities");
            if (!responsibilities.isEmpty()) ctx.put("responsibilities", responsibilities);

            contexts.put(ctxUri, ctx);
        }
        return contexts;
    }

    private Map<String, List<String>> queryUlTerms(Repository repo) {
        String query = loadSparql("/sparql/bc-canvas-ul-terms.sparql");
        var results = sparql.select(repo, query);

        Map<String, List<String>> terms = new LinkedHashMap<>();
        for (BindingSet bs : results) {
            String ctxUri = str(bs, "ctxUri");
            String term = str(bs, "term");
            if (!ctxUri.isEmpty() && !term.isEmpty()) {
                terms.computeIfAbsent(ctxUri, k -> new ArrayList<>()).add(term);
            }
        }
        return terms;
    }

    private Map<String, List<Map<String, Object>>> queryAggregates(Repository repo) {
        String query = loadSparql("/sparql/bc-canvas-aggregates.sparql");
        var results = sparql.select(repo, query);

        Map<String, Map<String, AggregateBuilder>> buildersByCtx = new LinkedHashMap<>();

        for (BindingSet bs : results) {
            String ctxUri = str(bs, "ctxUri");
            String aggName = str(bs, "aggName");
            if (ctxUri.isEmpty() || aggName.isEmpty()) continue;

            var builders = buildersByCtx.computeIfAbsent(ctxUri, k -> new LinkedHashMap<>());
            var builder = builders.computeIfAbsent(aggName, k -> new AggregateBuilder(aggName));

            builder.root = strOrDefault(bs, "rootName", aggName);
            builder.addIfPresent(builder.entities, str(bs, "entityName"));
            builder.addIfPresent(builder.valueObjects, str(bs, "voName"));
            builder.addIfPresent(builder.commands, str(bs, "cmdName"));
            builder.addIfPresent(builder.events, str(bs, "eventName"));

            String invName = str(bs, "invariantName");
            String invExpr = str(bs, "invariantExpr");
            if (!invName.isEmpty()) {
                builder.invariants.put(invName, invExpr);
            }
        }

        Map<String, List<Map<String, Object>>> result = new LinkedHashMap<>();
        for (var ctxEntry : buildersByCtx.entrySet()) {
            List<Map<String, Object>> aggs = new ArrayList<>();
            for (var builder : ctxEntry.getValue().values()) {
                aggs.add(builder.build());
            }
            result.put(ctxEntry.getKey(), aggs);
        }
        return result;
    }

    private String queryArchitectureName(Repository repo) {
        String query = "PREFIX arknet: <%s>\nSELECT ?name WHERE { ?arch a arknet:Architecture ; arknet:name ?name . } LIMIT 1".formatted(NS);
        var results = sparql.select(repo, query);
        return results.isEmpty() ? "Architecture" : str(results.getFirst(), "name");
    }

    private String mapSubdomainLabel(String uri) {
        if (uri.endsWith("CoreDomain")) return "Core Domain";
        if (uri.endsWith("SupportingDomain")) return "Supporting Domain";
        if (uri.endsWith("GenericDomain")) return "Generic Domain";
        return "Unknown";
    }

    private String str(BindingSet bs, String name) {
        Value v = bs.getValue(name);
        return v != null ? v.stringValue() : "";
    }

    private String strOrDefault(BindingSet bs, String name, String defaultValue) {
        String value = str(bs, name);
        return value.isEmpty() ? defaultValue : value;
    }

    private String loadSparql(String resource) {
        try (var is = getClass().getResourceAsStream(resource)) {
            if (is == null) throw new IOException("SPARQL resource not found: " + resource);
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to load SPARQL query: " + resource, e);
        }
    }

    private static class AggregateBuilder {
        final String name;
        String root;
        final Set<String> entities = new LinkedHashSet<>();
        final Set<String> valueObjects = new LinkedHashSet<>();
        final Set<String> commands = new LinkedHashSet<>();
        final Set<String> events = new LinkedHashSet<>();
        final Map<String, String> invariants = new LinkedHashMap<>();

        AggregateBuilder(String name) {
            this.name = name;
            this.root = name;
        }

        void addIfPresent(Set<String> set, String value) {
            if (!value.isEmpty()) set.add(value);
        }

        Map<String, Object> build() {
            var agg = new LinkedHashMap<String, Object>();
            agg.put("name", name);
            agg.put("root", root);
            agg.put("entities", new ArrayList<>(entities));
            agg.put("hasEntities", !entities.isEmpty());
            agg.put("valueObjects", new ArrayList<>(valueObjects));
            agg.put("hasValueObjects", !valueObjects.isEmpty());
            agg.put("commands", new ArrayList<>(commands));
            agg.put("hasCommands", !commands.isEmpty());
            agg.put("events", new ArrayList<>(events));
            agg.put("hasEvents", !events.isEmpty());

            List<Map<String, String>> invList = new ArrayList<>();
            for (var entry : invariants.entrySet()) {
                invList.add(Map.of("name", entry.getKey(), "expression", entry.getValue()));
            }
            agg.put("invariants", invList);
            agg.put("hasInvariants", !invList.isEmpty());

            return agg;
        }
    }
}
