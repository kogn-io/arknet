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
import java.util.*;

public class ContextMapProjection {

    private static final String NS = "https://doc42.ddd-forge.dev/ontology#";

    private final SparqlExecutor sparql = new SparqlExecutor();
    private final Mustache template;

    public ContextMapProjection() {
        var mf = new DefaultMustacheFactory();
        template = mf.compile("templates/context-map.mustache");
    }

    public String generate(Repository repo) {
        String archName = queryArchitectureName(repo);
        List<Map<String, Object>> contexts = queryContexts(repo);
        List<Map<String, Object>> relationships = queryRelationships(repo);

        Map<String, Object> model = new HashMap<>();
        model.put("architectureName", archName);
        model.put("contexts", contexts);
        model.put("relationships", relationships);

        var writer = new StringWriter();
        template.execute(writer, model);
        return writer.toString();
    }

    private String queryArchitectureName(Repository repo) {
        String query = "PREFIX doc42: <%s>\nSELECT ?name WHERE { ?arch a doc42:Architecture ; doc42:name ?name . } LIMIT 1".formatted(NS);
        var results = sparql.select(repo, query);
        return results.isEmpty() ? "Architecture" : str(results.getFirst(), "name");
    }

    private List<Map<String, Object>> queryContexts(Repository repo) {
        String query = loadSparql("/sparql/architect-context-map-contexts.sparql");
        var results = sparql.select(repo, query);
        var contexts = new ArrayList<Map<String, Object>>();
        var seen = new LinkedHashSet<String>();

        for (BindingSet bs : results) {
            String name = str(bs, "contextName");
            if (!seen.add(name)) continue;

            var ctx = new LinkedHashMap<String, Object>();
            ctx.put("name", name);
            ctx.put("alias", toAlias(name));
            ctx.put("domainVision", str(bs, "domainVision"));

            String subdomainUri = str(bs, "subdomainType");
            ctx.put("stereotype", mapStereotype(subdomainUri));
            ctx.put("subdomainLabel", mapSubdomainLabel(subdomainUri));

            putIfPresent(ctx, "ownedBy", str(bs, "ownedBy"));
            putIfPresent(ctx, "responsibilities", str(bs, "responsibilities"));

            contexts.add(ctx);
        }
        return contexts;
    }

    private List<Map<String, Object>> queryRelationships(Repository repo) {
        String query = loadSparql("/sparql/architect-context-map-relationships.sparql");
        var results = sparql.select(repo, query);
        var relationships = new ArrayList<Map<String, Object>>();

        for (BindingSet bs : results) {
            var rel = new LinkedHashMap<String, Object>();
            rel.put("upstreamAlias", toAlias(str(bs, "upstreamName")));
            rel.put("downstreamAlias", toAlias(str(bs, "downstreamName")));
            rel.put("relTypeLabel", mapRelTypeLabel(str(bs, "relType")));
            relationships.add(rel);
        }
        return relationships;
    }

    private String str(BindingSet bs, String name) {
        Value v = bs.getValue(name);
        return v != null ? v.stringValue() : "";
    }

    private String toAlias(String name) {
        return name.replaceAll("[^a-zA-Z0-9]", "");
    }

    private void putIfPresent(Map<String, Object> map, String key, String value) {
        if (value != null && !value.isEmpty()) {
            map.put(key, value);
        }
    }

    private String mapStereotype(String uri) {
        if (uri.endsWith("CoreDomain")) return "core";
        if (uri.endsWith("SupportingDomain")) return "supporting";
        if (uri.endsWith("GenericDomain")) return "generic";
        return "unknown";
    }

    private String mapSubdomainLabel(String uri) {
        if (uri.endsWith("CoreDomain")) return "Core Domain";
        if (uri.endsWith("SupportingDomain")) return "Supporting Domain";
        if (uri.endsWith("GenericDomain")) return "Generic Domain";
        return "Unknown";
    }

    private String mapRelTypeLabel(String uri) {
        String local = uri.contains("#") ? uri.substring(uri.indexOf('#') + 1) : uri;
        return switch (local) {
            case "OpenHostService" -> "OHS";
            case "CustomerSupplier" -> "Customer/Supplier";
            case "AnticorruptionLayer" -> "ACL";
            case "PublishedLanguage" -> "Published Language";
            case "SharedKernel" -> "Shared Kernel";
            default -> local;
        };
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
