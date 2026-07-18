package de.hauschel.arknet.mcp.store;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Renders the self-contained human HTML report for {@code store_overview}: a single file
 * with inline CSS and JS and no external dependencies. Structure follows the design mockup
 * {@code docs/mockups/store-report-sample.html} - an agent-digest panel on top, then a
 * type index (left) and one card per resource grouped by {@code rdf:type} (main), with
 * clickable IRI objects, status/priority pills and a filter box.
 *
 * <p>Structurally domain-agnostic: consumes only a {@link StoreSnapshot}, the digest text and a
 * {@link Prefixes} resolver, and renders every resource/triple from every bounded context the
 * same generic way (CURIE, label, cross-links). This does <b>not</b> extend to
 * {@link #STATUS_PREDICATE}/{@link #PRIORITY_PREDICATE} below: those two are hardcoded
 * requirements-BC IRIs, a deliberate, bounded exception kept for "pill" styling rather than a
 * SHACL-driven generic mechanism (issue #111 - not worth the machinery while only one BC has an
 * enum-like field; a future BC's analogous field renders as a plain value/literal until this list
 * is extended).</p>
 */
public final class HtmlReportRenderer {

    private final Prefixes prefixes;

    /**
     * @param prefixes the CURIE resolver used to shorten IRIs for display
     */
    public HtmlReportRenderer(Prefixes prefixes) {
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
    }

    private static final String STATUS_PREDICATE = "https://w3id.org/arknet/requirements#status";
    private static final String PRIORITY_PREDICATE = "https://w3id.org/arknet/requirements#priority";

    /**
     * Renders the complete HTML document.
     *
     * @param workspaceId the workspace the snapshot was read from
     * @param snapshot    the snapshot to render
     * @param digest      the agent digest text shown in the top panel
     * @return the self-contained HTML document
     */
    public String render(WorkspaceId workspaceId, StoreSnapshot snapshot, String digest) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(digest, "digest");

        Set<String> subjects = snapshot.resources().stream()
                .map(StoreResource::iri).collect(Collectors.toSet());

        StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>arknet Store Report</title>\n<style>\n")
                .append(CSS)
                .append("\n</style>\n</head>\n<body>\n<div class=\"wrap\">\n");

        appendHeader(html, workspaceId, snapshot);
        appendAgentPanel(html, digest);
        appendToolbar(html);

        html.append("  <div class=\"layout\">\n");
        appendIndex(html, snapshot);
        appendMain(html, snapshot, subjects);
        html.append("  </div>\n");

        appendFooter(html);
        html.append("</div>\n<script>\n").append(FILTER_JS).append("\n</script>\n</body>\n</html>\n");
        return html.toString();
    }

    private void appendHeader(StringBuilder html, WorkspaceId workspaceId, StoreSnapshot snapshot) {
        html.append("  <header class=\"top\">\n")
                .append("    <h1>arknet Store Report</h1>\n")
                .append("    <span class=\"ws\">workspace: ").append(escape(workspaceId.value())).append("</span>\n")
                .append("    <span class=\"meta\"><b>").append(snapshot.resourceCount())
                .append("</b> resources &middot; <b>").append(snapshot.tripleCount())
                .append("</b> triples &middot; <b>").append(snapshot.typeCount())
                .append("</b> types</span>\n")
                .append("  </header>\n");
    }

    private void appendAgentPanel(StringBuilder html, String digest) {
        html.append("  <details class=\"agent-panel\" open>\n")
                .append("    <summary>What the agent gets back")
                .append(" <span class=\"hint\">- compact digest as the tool return value (text)</span>")
                .append("</summary>\n<pre>").append(escape(digest)).append("</pre>\n  </details>\n");
    }

    private void appendToolbar(StringBuilder html) {
        html.append("  <div class=\"toolbar\">\n")
                .append("    <input id=\"filter\" type=\"text\" placeholder=\"Filter: type an id, label"
                        + " or predicate ...\" aria-label=\"Filter\" />\n")
                .append("  </div>\n");
    }

    private void appendIndex(StringBuilder html, StoreSnapshot snapshot) {
        html.append("    <nav class=\"index\" aria-label=\"Type index\">\n")
                .append("      <p class=\"lbl\">By type</p>\n");
        snapshot.byPrimaryType().forEach((type, members) ->
                html.append("      <a href=\"#").append(typeAnchor(type)).append("\"><span>")
                        .append(escape(displayType(type))).append("</span><span class=\"cnt\">")
                        .append(members.size()).append("</span></a>\n"));
        html.append("    </nav>\n");
    }

    private void appendMain(StringBuilder html, StoreSnapshot snapshot, Set<String> subjects) {
        html.append("    <main>\n");
        snapshot.byPrimaryType().forEach((type, members) -> {
            html.append("      <section class=\"typegroup\" id=\"").append(typeAnchor(type)).append("\">\n")
                    .append("        <h2><span class=\"type-iri\">").append(escape(displayType(type)))
                    .append("</span> <span class=\"of\">").append(members.size())
                    .append(" resource(s)</span></h2>\n")
                    .append("        <div class=\"cards\">\n");
            for (StoreResource resource : members) {
                appendCard(html, resource, subjects);
            }
            html.append("        </div>\n      </section>\n");
        });
        html.append("    </main>\n");
    }

    private void appendCard(StringBuilder html, StoreResource resource, Set<String> subjects) {
        String anchor = resourceAnchor(resource.iri());
        String curie = prefixes.toCurie(resource.iri());
        html.append("          <article class=\"res\" id=\"").append(anchor).append("\">\n")
                .append("            <div class=\"subj\">\n")
                .append("              <span class=\"id\">").append(escape(curie)).append("</span>\n");
        resource.label().ifPresent(label -> html.append("              <span class=\"label\">")
                .append(escape(label)).append("</span>\n"));
        html.append("              <a class=\"anchor\" href=\"#").append(anchor).append("\">#")
                .append(escape(anchor)).append("</a>\n            </div>\n")
                .append("            <table class=\"props\">\n");
        for (Triple triple : resource.outgoing()) {
            html.append("              <tr><td class=\"pred\">").append(escape(prefixes.toCurie(triple.predicate())))
                    .append("</td><td class=\"obj\">").append(renderObject(triple, subjects))
                    .append("</td></tr>\n");
        }
        html.append("            </table>\n          </article>\n");
    }

    private String renderObject(Triple triple, Set<String> subjects) {
        String predicate = triple.predicate();
        if (triple.object() instanceof RdfNode.Resource resource) {
            // Status/priority are stored as vocabulary IRIs (e.g. arkreq:Proposed); render
            // their local name as a pill, matching the mockup.
            if (STATUS_PREDICATE.equals(predicate)) {
                return statusPill(StoreResource.localName(resource.iri()));
            }
            if (PRIORITY_PREDICATE.equals(predicate)) {
                return prioPill(StoreResource.localName(resource.iri()));
            }
            String targetCurie = prefixes.toCurie(resource.iri());
            if (StoreResource.RDF_TYPE.equals(predicate)) {
                return "<a href=\"#" + typeAnchor(resource.iri()) + "\">" + escape(targetCurie) + "</a>";
            }
            if (subjects.contains(resource.iri())) {
                return "<a href=\"#" + resourceAnchor(resource.iri()) + "\">" + escape(targetCurie) + "</a>";
            }
            return "<span class=\"lit\">" + escape(targetCurie) + "</span>";
        }
        RdfNode.Literal literal = (RdfNode.Literal) triple.object();
        if (STATUS_PREDICATE.equals(predicate)) {
            return statusPill(literal.lexicalForm());
        }
        if (PRIORITY_PREDICATE.equals(predicate)) {
            return prioPill(literal.lexicalForm());
        }
        return renderLiteral(literal);
    }

    private String prioPill(String priority) {
        return "<span class=\"pill prio\">" + escape(priority) + "</span>";
    }

    private String statusPill(String status) {
        String cls = "status-" + status.toLowerCase().replaceAll("[^a-z0-9]+", "-");
        return "<span class=\"pill " + escape(cls) + "\">" + escape(status) + "</span>";
    }

    private String renderLiteral(RdfNode.Literal literal) {
        StringBuilder out = new StringBuilder("<span class=\"lit str\">\"")
                .append(escape(literal.lexicalForm())).append("\"</span>");
        if (literal.languageTag() != null) {
            out.append("<span class=\"dt\">@").append(escape(literal.languageTag())).append("</span>");
        } else if (literal.datatypeIri() != null
                && !"http://www.w3.org/2001/XMLSchema#string".equals(literal.datatypeIri())) {
            out.append("<span class=\"dt\">^^").append(escape(prefixes.toCurie(literal.datatypeIri())))
                    .append("</span>");
        }
        return out.toString();
    }

    private void appendFooter(StringBuilder html) {
        html.append("  <footer class=\"foot\">\n")
                .append("    arknet store_overview &middot; generic: SELECT ?s ?p ?o over the kognio-rdf"
                        + " dataset &middot; self-contained HTML, no external dependencies\n")
                .append("  </footer>\n");
    }

    private String displayType(String typeIri) {
        return typeIri.isEmpty() ? "(untyped)" : prefixes.toCurie(typeIri);
    }

    private String typeAnchor(String typeIri) {
        return "type-" + sanitize(typeIri.isEmpty() ? "untyped" : prefixes.toCurie(typeIri));
    }

    private String resourceAnchor(String iri) {
        return "r-" + sanitize(prefixes.toCurie(iri));
    }

    private static String sanitize(String value) {
        return value.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    static String escape(String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;");
    }

    private static final String CSS = """
            :root {
              --bg:#f4f5f7; --surface:#fff; --surface-2:#eceef1; --border:#d8dce2; --border-strong:#c2c8d1;
              --ink:#1c2128; --ink-soft:#4a5560; --ink-faint:#8892a0; --accent:#4759c4; --accent-soft:#eaecf9;
              --mono-key:#7a4ec0; --iri:#2f6fb0; --ok:#1f7a4d; --ok-bg:#e2f2e9; --warn:#9a6413; --warn-bg:#f6ecd6;
              --info:#315e8a; --info-bg:#e0ebf5;
              --mono:ui-monospace,"SF Mono","JetBrains Mono",Menlo,Consolas,monospace;
              --sans:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
            }
            @media (prefers-color-scheme:dark){:root{
              --bg:#14171c; --surface:#1c2027; --surface-2:#232830; --border:#2e353f; --border-strong:#3c4552;
              --ink:#e6e9ee; --ink-soft:#a9b2be; --ink-faint:#6d7783; --accent:#8b98e8; --accent-soft:#262b48;
              --mono-key:#b79be8; --iri:#6faede; --ok:#6fd39c; --ok-bg:#17331f; --warn:#e0b45f; --warn-bg:#35290f;
              --info:#7fb4e6; --info-bg:#142838;}}
            *{box-sizing:border-box;}
            body{margin:0;background:var(--bg);color:var(--ink);font-family:var(--sans);font-size:15px;line-height:1.5;}
            .wrap{max-width:1180px;margin:0 auto;padding:28px 24px 80px;}
            header.top{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px 16px;
              border-bottom:2px solid var(--border-strong);padding-bottom:16px;margin-bottom:8px;}
            header.top h1{font-size:19px;margin:0;font-weight:650;}
            header.top .ws{font-family:var(--mono);font-size:12.5px;color:var(--iri);}
            header.top .meta{margin-left:auto;color:var(--ink-faint);font-size:12.5px;font-family:var(--mono);}
            header.top .meta b{color:var(--ink-soft);font-weight:600;}
            .agent-panel{background:var(--surface-2);border:1px solid var(--border);border-left:3px solid var(--accent);
              border-radius:8px;margin:18px 0 24px;overflow:hidden;}
            .agent-panel summary{cursor:pointer;padding:11px 16px;font-size:12px;font-weight:600;
              letter-spacing:0.06em;text-transform:uppercase;color:var(--ink-soft);}
            .agent-panel .hint{font-weight:400;text-transform:none;letter-spacing:0;color:var(--ink-faint);}
            .agent-panel pre{margin:0;padding:4px 18px 18px;font-family:var(--mono);font-size:12.5px;
              line-height:1.65;color:var(--ink-soft);overflow-x:auto;white-space:pre;}
            .toolbar{display:flex;gap:12px;align-items:center;margin:4px 0 20px;}
            .toolbar input{flex:1;padding:9px 13px;border:1px solid var(--border-strong);border-radius:7px;
              background:var(--surface);color:var(--ink);font-family:var(--mono);font-size:13px;}
            .toolbar input:focus{outline:2px solid var(--accent);outline-offset:1px;border-color:var(--accent);}
            .layout{display:grid;grid-template-columns:210px 1fr;gap:28px;align-items:start;}
            nav.index{position:sticky;top:20px;font-size:13px;}
            nav.index .lbl{text-transform:uppercase;letter-spacing:0.07em;font-size:10.5px;color:var(--ink-faint);
              font-weight:700;margin:0 0 10px;}
            nav.index a{display:flex;justify-content:space-between;align-items:center;gap:8px;padding:6px 10px;
              border-radius:6px;text-decoration:none;color:var(--ink-soft);font-family:var(--mono);font-size:12.5px;}
            nav.index a:hover{background:var(--surface-2);color:var(--ink);}
            nav.index a .cnt{background:var(--surface-2);color:var(--ink-faint);border-radius:20px;padding:1px 8px;
              font-size:11px;font-variant-numeric:tabular-nums;min-width:24px;text-align:center;}
            section.typegroup{margin-bottom:34px;scroll-margin-top:16px;}
            section.typegroup>h2{font-family:var(--mono);font-size:13px;font-weight:600;color:var(--ink);
              margin:0 0 12px;padding-bottom:7px;border-bottom:1px solid var(--border);
              display:flex;align-items:baseline;gap:10px;}
            section.typegroup>h2 .type-iri{color:var(--mono-key);}
            section.typegroup>h2 .of{color:var(--ink-faint);font-weight:400;font-size:11.5px;}
            .cards{display:grid;gap:12px;}
            article.res{background:var(--surface);border:1px solid var(--border);border-radius:9px;
              padding:14px 16px;scroll-margin-top:16px;}
            article.res:target{border-color:var(--accent);box-shadow:0 0 0 2px var(--accent-soft);}
            article.res .subj{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px 12px;margin-bottom:10px;}
            article.res .subj .id{font-family:var(--mono);font-size:13.5px;font-weight:650;color:var(--ink);}
            article.res .subj .label{font-size:13.5px;color:var(--ink-soft);}
            article.res .subj .anchor{margin-left:auto;font-family:var(--mono);font-size:11px;color:var(--ink-faint);
              text-decoration:none;}
            article.res .subj .anchor:hover{color:var(--accent);}
            table.props{width:100%;border-collapse:collapse;font-size:13px;}
            table.props td{padding:4px 0;vertical-align:top;border-top:1px solid var(--border);}
            table.props tr:first-child td{border-top:none;}
            table.props td.pred{font-family:var(--mono);font-size:12.5px;color:var(--mono-key);width:210px;
              padding-right:16px;white-space:nowrap;}
            table.props td.obj{font-family:var(--mono);font-size:12.5px;color:var(--ink);word-break:break-word;}
            table.props td.obj a{color:var(--iri);text-decoration:none;border-bottom:1px dotted currentColor;}
            table.props td.obj a:hover{color:var(--accent);}
            table.props td.obj .lit.str{color:var(--ok);}
            table.props td.obj .dt{color:var(--ink-faint);font-size:11px;}
            .pill{display:inline-block;padding:1px 9px;border-radius:20px;font-size:11px;font-weight:600;
              font-family:var(--sans);}
            .pill.status-proposed{background:var(--warn-bg);color:var(--warn);}
            .pill.status-accepted{background:var(--ok-bg);color:var(--ok);}
            .pill.status-draft{background:var(--info-bg);color:var(--info);}
            .pill.prio{background:var(--accent-soft);color:var(--accent);}
            footer.foot{margin-top:40px;color:var(--ink-faint);font-size:12px;font-family:var(--mono);
              border-top:1px solid var(--border);padding-top:14px;}
            @media (max-width:760px){.layout{grid-template-columns:1fr;}
              nav.index{position:static;display:flex;flex-wrap:wrap;gap:6px;}
              nav.index .lbl{width:100%;}table.props td.pred{width:150px;}}
            """;

    private static final String FILTER_JS = """
            (function(){
              var input = document.getElementById('filter');
              if(!input){return;}
              input.addEventListener('input', function(){
                var q = input.value.trim().toLowerCase();
                document.querySelectorAll('section.typegroup').forEach(function(sec){
                  var visible = 0;
                  sec.querySelectorAll('article.res').forEach(function(card){
                    var match = q === '' || card.textContent.toLowerCase().indexOf(q) !== -1;
                    card.style.display = match ? '' : 'none';
                    if(match){visible++;}
                  });
                  sec.style.display = visible === 0 ? 'none' : '';
                });
              });
            })();
            """;
}
