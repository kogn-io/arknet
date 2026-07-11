package de.hauschel.arknet.projection;

import com.fasterxml.jackson.databind.ObjectMapper;
import de.hauschel.arknet.core.SparqlExecutor;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.repository.Repository;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public class ExplorerProjection implements Projection {

    private static final String NS = "https://w3id.org/arknet/core#";
    private static final String MODEL_PLACEHOLDER = "/* ARKNET_MODEL_JSON */";

    private final SparqlExecutor sparql = new SparqlExecutor();
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public String name() {
        return "explorer";
    }

    @Override
    public String description() {
        return "3D Architecture Explorer with Three.js visualization (HTML)";
    }

    @Override
    public void generate(Repository repo, Path outputDir, String format) throws IOException {
        Map<String, Object> model = buildModel(repo);
        String json = objectMapper.writeValueAsString(model);
        String template = loadTemplate();
        String html = template.replace(MODEL_PLACEHOLDER, json);
        Files.createDirectories(outputDir);
        Files.writeString(outputDir.resolve("architecture-explorer.html"), html, StandardCharsets.UTF_8);
    }

    private Map<String, Object> buildModel(Repository repo) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("architecture", queryArchitecture(repo));
        model.put("stakeholders", List.of());
        model.put("adrs", List.of());
        model.put("contexts", queryContexts(repo));
        model.put("contextRelationships", queryRelationships(repo));
        return model;
    }

    private Map<String, String> queryArchitecture(Repository repo) {
        String query = """
                PREFIX arknet: <%s>
                SELECT ?name ?description WHERE {
                  ?arch a arknet:Architecture ; arknet:name ?name .
                  OPTIONAL { ?arch arknet:description ?description . }
                } LIMIT 1
                """.formatted(NS);
        List<BindingSet> results = sparql.select(repo, query);
        Map<String, String> arch = new LinkedHashMap<>();
        if (results.isEmpty()) {
            arch.put("name", "Architecture");
            arch.put("description", "");
        } else {
            arch.put("name", str(results.getFirst(), "name"));
            arch.put("description", str(results.getFirst(), "description"));
        }
        return arch;
    }

    private List<Map<String, Object>> queryContexts(Repository repo) {
        String ctxQuery = """
                PREFIX arknet: <%s>
                SELECT ?ctxUri ?ctxName ?domainVision ?subdomain ?ownedBy ?responsibilities
                       ?aggUri ?aggName ?rootName
                WHERE {
                  ?ctxUri a arknet:BoundedContext ;
                          arknet:name ?ctxName ;
                          arknet:domainVision ?domainVision .
                  OPTIONAL { ?ctxUri arknet:subdomain ?sub . BIND(STRAFTER(STR(?sub), "#") AS ?subdomain) }
                  OPTIONAL { ?ctxUri arknet:ownedBy ?ownedBy . }
                  OPTIONAL { ?ctxUri arknet:responsibilities ?responsibilities . }
                  OPTIONAL {
                    ?ctxUri arknet:hasAggregate ?aggUri .
                    ?aggUri arknet:name ?aggName .
                    OPTIONAL { ?aggUri arknet:aggregateRoot ?root . ?root arknet:name ?rootName . }
                  }
                }
                ORDER BY ?ctxName ?aggName
                """.formatted(NS);

        String aggDetailQuery = """
                PREFIX arknet: <%s>
                SELECT ?aggUri ?entityName ?voName ?cmdName ?eventName ?invariantExpr WHERE {
                  ?aggUri a arknet:Aggregate .
                  OPTIONAL { ?aggUri arknet:hasEntity ?ent . ?ent arknet:name ?entityName . }
                  OPTIONAL { ?aggUri arknet:hasValueObject ?vo . ?vo arknet:name ?voName . }
                  OPTIONAL { ?aggUri arknet:hasCommand ?cmd . ?cmd arknet:name ?cmdName . }
                  OPTIONAL { ?aggUri arknet:hasEvent ?evt . ?evt arknet:name ?eventName . }
                  OPTIONAL { ?aggUri arknet:hasInvariant ?inv . ?inv arknet:invariantExpression ?invariantExpr . }
                }
                """.formatted(NS);

        List<BindingSet> ctxResults = sparql.select(repo, ctxQuery);
        List<BindingSet> aggDetailResults = sparql.select(repo, aggDetailQuery);

        Map<String, Set<String>> aggEntities = new LinkedHashMap<>();
        Map<String, Set<String>> aggValueObjects = new LinkedHashMap<>();
        Map<String, Set<String>> aggCommands = new LinkedHashMap<>();
        Map<String, Set<String>> aggEvents = new LinkedHashMap<>();
        Map<String, Set<String>> aggInvariants = new LinkedHashMap<>();

        for (BindingSet bs : aggDetailResults) {
            String aggUri = str(bs, "aggUri");
            if (aggUri.isEmpty()) {
                continue;
            }
            addToSet(aggEntities, aggUri, str(bs, "entityName"));
            addToSet(aggValueObjects, aggUri, str(bs, "voName"));
            addToSet(aggCommands, aggUri, str(bs, "cmdName"));
            addToSet(aggEvents, aggUri, str(bs, "eventName"));
            addToSet(aggInvariants, aggUri, str(bs, "invariantExpr"));
        }

        Map<String, Map<String, Object>> contextMap = new LinkedHashMap<>();
        Map<String, Map<String, Map<String, Object>>> contextAggregates = new LinkedHashMap<>();

        for (BindingSet bs : ctxResults) {
            String ctxUri = str(bs, "ctxUri");
            String ctxName = str(bs, "ctxName");

            if (!contextMap.containsKey(ctxUri)) {
                Map<String, Object> ctx = new LinkedHashMap<>();
                ctx.put("id", slugify(ctxName));
                ctx.put("name", ctxName);
                ctx.put("subdomain", strOrDefault(bs, "subdomain", "CoreDomain"));
                ctx.put("ownedBy", str(bs, "ownedBy"));
                ctx.put("domainVision", str(bs, "domainVision"));
                ctx.put("responsibilities", str(bs, "responsibilities"));
                ctx.put("aggregates", new ArrayList<>());
                contextMap.put(ctxUri, ctx);
                contextAggregates.put(ctxUri, new LinkedHashMap<>());
            }

            String aggUri = str(bs, "aggUri");
            String aggName = str(bs, "aggName");
            if (!aggUri.isEmpty() && !aggName.isEmpty()) {
                Map<String, Map<String, Object>> aggs = contextAggregates.get(ctxUri);
                if (!aggs.containsKey(aggUri)) {
                    Map<String, Object> agg = new LinkedHashMap<>();
                    agg.put("id", slugify(aggName));
                    agg.put("name", aggName);
                    agg.put("invariants", new ArrayList<>(aggInvariants.getOrDefault(aggUri, Set.of())));
                    agg.put("root", strOrDefault(bs, "rootName", aggName));
                    agg.put("entities", new ArrayList<>(aggEntities.getOrDefault(aggUri, Set.of())));
                    agg.put("valueObjects", new ArrayList<>(aggValueObjects.getOrDefault(aggUri, Set.of())));
                    agg.put("commands", new ArrayList<>(aggCommands.getOrDefault(aggUri, Set.of())));
                    agg.put("events", new ArrayList<>(aggEvents.getOrDefault(aggUri, Set.of())));
                    aggs.put(aggUri, agg);
                }
            }
        }

        for (Map.Entry<String, Map<String, Object>> entry : contextMap.entrySet()) {
            String ctxUri = entry.getKey();
            Map<String, Object> ctx = entry.getValue();
            Map<String, Map<String, Object>> aggs = contextAggregates.get(ctxUri);
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> aggList = (List<Map<String, Object>>) ctx.get("aggregates");
            aggList.addAll(aggs.values());
        }

        return new ArrayList<>(contextMap.values());
    }

    private List<Map<String, Object>> queryRelationships(Repository repo) {
        String query = """
                PREFIX arknet: <%s>
                SELECT ?relUri ?upCtxUri ?downCtxUri ?relType WHERE {
                  ?relUri a arknet:ContextRelationship ;
                          arknet:upstream ?upCtxUri ;
                          arknet:downstream ?downCtxUri ;
                          arknet:relationshipType ?relTypeUri .
                  BIND(STRAFTER(STR(?relTypeUri), "#") AS ?relType)
                }
                """.formatted(NS);

        String ctxIdQuery = """
                PREFIX arknet: <%s>
                SELECT ?ctxUri ?ctxName WHERE {
                  ?ctxUri a arknet:BoundedContext ; arknet:name ?ctxName .
                }
                """.formatted(NS);

        List<BindingSet> ctxResults = sparql.select(repo, ctxIdQuery);
        Map<String, String> ctxUriToId = new LinkedHashMap<>();
        for (BindingSet bs : ctxResults) {
            ctxUriToId.put(str(bs, "ctxUri"), slugify(str(bs, "ctxName")));
        }

        List<BindingSet> results = sparql.select(repo, query);
        List<Map<String, Object>> relationships = new ArrayList<>();

        for (BindingSet bs : results) {
            String relUri = str(bs, "relUri");
            String upCtxUri = str(bs, "upCtxUri");
            String downCtxUri = str(bs, "downCtxUri");
            String relType = str(bs, "relType");

            Map<String, Object> rel = new LinkedHashMap<>();
            rel.put("id", slugify(relUri.contains("#") ? relUri.substring(relUri.indexOf('#') + 1) : relUri));
            rel.put("upstream", ctxUriToId.getOrDefault(upCtxUri, slugify(upCtxUri)));
            rel.put("downstream", ctxUriToId.getOrDefault(downCtxUri, slugify(downCtxUri)));
            rel.put("type", relType);
            rel.put("description", "");
            relationships.add(rel);
        }

        return relationships;
    }

    private String loadTemplate() throws IOException {
        try (InputStream is = getClass().getResourceAsStream("/templates/explorer.html")) {
            if (is == null) {
                throw new IOException("Template not found: /templates/explorer.html");
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private String str(BindingSet bs, String name) {
        Value v = bs.getValue(name);
        return v != null ? v.stringValue() : "";
    }

    private String strOrDefault(BindingSet bs, String name, String defaultValue) {
        String value = str(bs, name);
        return value.isEmpty() ? defaultValue : value;
    }

    private String slugify(String text) {
        return text.toLowerCase()
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("^-|-$", "");
    }

    private void addToSet(Map<String, Set<String>> map, String key, String value) {
        if (!value.isEmpty()) {
            map.computeIfAbsent(key, k -> new LinkedHashSet<>()).add(value);
        }
    }
}
