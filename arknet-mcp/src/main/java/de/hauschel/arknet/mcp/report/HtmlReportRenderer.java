// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreResource;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;

/**
 * Renders the self-contained human HTML report for {@code store_overview}: a single file with
 * inline CSS and JS and no external dependencies.
 *
 * <p><strong>Model-shaped, not triple-shaped.</strong> The report's skeleton is one section per
 * bounded context ({@link ModelViews}), each holding {@link ModelCard}s built from that
 * context's own domain objects: a use case reads as a goal with a numbered flow, a requirement
 * as a statement with acceptance criteria, a term as a definition. The flat
 * {@code SELECT ?s ?p ?o} snapshot is still the report's safety net, but no longer its
 * structure - it supplies the per-card raw triples and the "Other resources" section that
 * catches everything no card was built for (concept schemes, a goal that has no aggregate yet,
 * a resource whose context could not read it back). Nothing in the store can therefore fall out
 * of the report, and nothing has to be guessed from predicates either.</p>
 *
 * <p>The only remaining domain knowledge in this class is structural rather than semantic: it
 * knows that {@code arkreq:Step} resources reached from a use case are already shown inside
 * that use case's flow, and suppresses them from the raw section so a five-step use case does
 * not also litter it with five opaque step cards. A step that no use case references is
 * <em>not</em> suppressed - an orphan is exactly what this report should surface.</p>
 */
public final class HtmlReportRenderer {

    /** {@code arkreq:Step} - inlined into its use case's flow instead of shown as a resource. */
    private static final String STEP_TYPE = "https://w3id.org/arknet/requirements#Step";

    /** The two predicates by which a use case reaches its steps. */
    private static final Set<String> STEP_EDGES = Set.of(
            "https://w3id.org/arknet/requirements#mainStep",
            "https://w3id.org/arknet/requirements#extensionStep");

    private final Prefixes prefixes;

    /**
     * @param prefixes the CURIE resolver used to shorten IRIs for display
     */
    public HtmlReportRenderer(final Prefixes prefixes) {
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
    }

    /**
     * Renders the complete HTML document.
     *
     * @param workspaceId the workspace the snapshot was read from
     * @param snapshot    the flat statement snapshot, used for raw triples and the leftovers
     * @param digest      the agent digest text shown in the top panel
     * @param views       the per-bounded-context sections that make up the report's body
     * @return the self-contained HTML document
     */
    public String render(
            final WorkspaceId workspaceId,
            final StoreSnapshot snapshot,
            final String digest,
            final ModelViews.Views views) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(views, "views");

        final Set<String> subjects = snapshot.resources().stream()
                .map(StoreResource::iri).collect(Collectors.toSet());
        final Map<String, StoreResource> bySubject = snapshot.resources().stream()
                .collect(Collectors.toMap(StoreResource::iri, Function.identity(), (first, second) -> first));
        final Set<String> carded = views.sections().stream()
                .flatMap(section -> section.cards().stream())
                .map(ModelCard::iri)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        final List<StoreResource> leftovers = leftovers(snapshot, carded);

        final StringBuilder html = new StringBuilder();
        html.append("<!doctype html>\n<html lang=\"en\">\n<head>\n")
                .append("<meta charset=\"utf-8\">\n")
                .append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">\n")
                .append("<title>arknet Store Report</title>\n<style>\n")
                .append(CSS)
                .append("\n</style>\n</head>\n<body>\n<div class=\"wrap\">\n");

        appendHeader(html, workspaceId, snapshot, carded.size());
        appendFailures(html, views.failures());
        appendAgentPanel(html, digest);
        appendToolbar(html);

        html.append("  <div class=\"layout\">\n");
        appendIndex(html, views.sections(), leftovers.size());
        html.append("    <main>\n");
        for (final ModelSection section : views.sections()) {
            appendSection(html, section, carded, subjects, bySubject);
        }
        appendLeftovers(html, leftovers, subjects);
        appendEmptyState(html, views.sections(), leftovers);
        html.append("    </main>\n  </div>\n");

        appendFooter(html);
        html.append("</div>\n<script>\n").append(FILTER_JS).append("\n</script>\n</body>\n</html>\n");
        return html.toString();
    }

    // --- structure -------------------------------------------------------------

    /**
     * Every resource no card was built for, minus the use-case steps already shown inside their
     * flow. Order stays the snapshot's own (primary type, then IRI), so the fallback keeps the
     * shape the whole report used to have.
     */
    private static List<StoreResource> leftovers(final StoreSnapshot snapshot, final Set<String> carded) {
        final Set<String> inlinedSteps = snapshot.resources().stream()
                .flatMap(resource -> resource.outgoing().stream())
                .filter(triple -> STEP_EDGES.contains(triple.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(object -> ((RdfNode.Resource) object).iri())
                .collect(Collectors.toSet());
        return snapshot.resources().stream()
                .filter(resource -> !carded.contains(resource.iri()))
                .filter(resource -> !(resource.types().contains(STEP_TYPE) && inlinedSteps.contains(resource.iri())))
                .toList();
    }

    private void appendHeader(
            final StringBuilder html,
            final WorkspaceId workspaceId,
            final StoreSnapshot snapshot,
            final int elements) {
        html.append("  <header class=\"top\">\n")
                .append("    <h1>arknet Store Report</h1>\n")
                .append("    <span class=\"ws\">workspace: ").append(escape(workspaceId.value())).append("</span>\n")
                .append("    <span class=\"meta\"><b>").append(elements)
                .append("</b> model elements &middot; <b>").append(snapshot.resourceCount())
                .append("</b> resources &middot; <b>").append(snapshot.tripleCount())
                .append("</b> triples</span>\n")
                .append("  </header>\n");
    }

    private void appendFailures(final StringBuilder html, final List<String> failures) {
        if (failures.isEmpty()) {
            return;
        }
        html.append("  <div class=\"failures\" role=\"alert\">\n")
                .append("    <p class=\"lbl\">Incomplete report</p>\n    <ul>\n");
        for (final String failure : failures) {
            html.append("      <li>").append(escape(failure)).append("</li>\n");
        }
        html.append("    </ul>\n  </div>\n");
    }

    private void appendAgentPanel(final StringBuilder html, final String digest) {
        html.append("  <details class=\"agent-panel\">\n")
                .append("    <summary>What the agent gets back")
                .append(" <span class=\"hint\">- compact digest as the tool return value (text)</span>")
                .append("</summary>\n<pre>").append(escape(digest)).append("</pre>\n  </details>\n");
    }

    private void appendToolbar(final StringBuilder html) {
        html.append("  <div class=\"toolbar\">\n")
                .append("    <input id=\"filter\" type=\"text\" placeholder=\"Filter: type a code, title,"
                        + " actor or any word ...\" aria-label=\"Filter\" />\n")
                .append("  </div>\n");
    }

    private void appendIndex(final StringBuilder html, final List<ModelSection> sections, final int leftovers) {
        html.append("    <nav class=\"index\" aria-label=\"Section index\">\n")
                .append("      <p class=\"lbl\">Model</p>\n");
        for (final ModelSection section : sections) {
            html.append("      <a href=\"#sec-").append(escape(section.id())).append("\"><span>")
                    .append(escape(section.title())).append("</span><span class=\"cnt\">")
                    .append(section.cards().size()).append("</span></a>\n");
        }
        if (leftovers > 0) {
            html.append("      <a class=\"muted\" href=\"#sec-other\"><span>Other resources</span>")
                    .append("<span class=\"cnt\">").append(leftovers).append("</span></a>\n");
        }
        html.append("    </nav>\n");
    }

    private void appendSection(
            final StringBuilder html,
            final ModelSection section,
            final Set<String> carded,
            final Set<String> subjects,
            final Map<String, StoreResource> bySubject) {
        html.append("      <section class=\"group\" id=\"sec-").append(escape(section.id())).append("\">\n")
                .append("        <h2>").append(escape(section.title()))
                .append(" <span class=\"of\">").append(section.cards().size()).append("</span></h2>\n");
        if (!section.subtitle().isBlank()) {
            html.append("        <p class=\"sub\">").append(escape(section.subtitle())).append("</p>\n");
        }
        html.append("        <div class=\"cards\">\n");
        for (final ModelCard card : section.cards()) {
            appendCard(html, card, carded, subjects, bySubject.get(card.iri()));
        }
        html.append("        </div>\n      </section>\n");
    }

    private void appendLeftovers(
            final StringBuilder html, final List<StoreResource> leftovers, final Set<String> subjects) {
        if (leftovers.isEmpty()) {
            return;
        }
        html.append("      <section class=\"group other\" id=\"sec-other\">\n")
                .append("        <h2>Other resources <span class=\"of\">").append(leftovers.size())
                .append("</span></h2>\n")
                .append("        <p class=\"sub\">everything in the store that no bounded context claims as a"
                        + " model element. Raw triples, so nothing can hide from this report.</p>\n")
                .append("        <details class=\"raw-group\">\n")
                .append("          <summary>show raw resources</summary>\n")
                .append("          <div class=\"cards\">\n");
        for (final StoreResource resource : leftovers) {
            appendRawCard(html, resource, subjects);
        }
        html.append("          </div>\n        </details>\n      </section>\n");
    }

    private void appendEmptyState(
            final StringBuilder html, final List<ModelSection> sections, final List<StoreResource> leftovers) {
        if (!sections.isEmpty() || !leftovers.isEmpty()) {
            return;
        }
        html.append("      <p class=\"empty\">This workspace holds no model yet. Start with"
                + " <code>bc_add</code>, <code>term_add</code>, <code>req_add</code> or"
                + " <code>uc_add</code>.</p>\n");
    }

    // --- cards -----------------------------------------------------------------

    private void appendCard(
            final StringBuilder html,
            final ModelCard card,
            final Set<String> carded,
            final Set<String> subjects,
            final StoreResource raw) {
        final String anchor = resourceAnchor(card.iri());
        html.append("          <article class=\"card\" id=\"").append(anchor).append("\">\n")
                .append("            <div class=\"head\">\n")
                .append("              <span class=\"code\">").append(escape(card.code())).append("</span>\n")
                .append("              <h3>").append(escape(card.title())).append("</h3>\n");
        for (final Badge badge : card.badges()) {
            html.append("              ").append(badgePill(badge)).append('\n');
        }
        html.append("              <a class=\"anchor\" href=\"#").append(anchor).append("\">#</a>\n")
                .append("            </div>\n            <div class=\"body\">\n");
        for (final Block block : card.blocks()) {
            appendBlock(html, block, carded, subjects);
        }
        html.append("            </div>\n");
        appendRawTriples(html, raw, subjects);
        html.append("          </article>\n");
    }

    private void appendBlock(
            final StringBuilder html, final Block block, final Set<String> carded, final Set<String> subjects) {
        html.append("              <div class=\"block\">\n                <span class=\"blabel\">")
                .append(escape(block.label())).append("</span>\n");
        switch (block) {
            case Block.Prose prose -> html.append("                <p class=\"prose\">")
                    .append(escape(prose.text())).append("</p>\n");
            case Block.Bullets bullets -> {
                html.append("                <ul class=\"bullets\">\n");
                for (final String item : bullets.items()) {
                    html.append("                  <li>").append(escape(item)).append("</li>\n");
                }
                html.append("                </ul>\n");
            }
            case Block.Refs refs -> appendChips(html, refs.refs(), carded, subjects);
            case Block.Flow flow -> appendFlow(html, flow, carded, subjects);
        }
        html.append("              </div>\n");
    }

    private void appendFlow(
            final StringBuilder html, final Block.Flow flow, final Set<String> carded, final Set<String> subjects) {
        html.append("                <ol class=\"flow\">\n");
        for (final FlowStep step : flow.steps()) {
            html.append("                  <li><span class=\"num\">").append(step.position())
                    .append("</span><div class=\"step\"><p>").append(escape(step.text())).append("</p>\n");
            if (!step.realises().isEmpty()) {
                html.append("                  <span class=\"realises\">realises</span>\n");
                appendChips(html, step.realises(), carded, subjects);
            }
            html.append("                  </div></li>\n");
        }
        html.append("                </ol>\n");
    }

    /**
     * Renders references as chips. A reference whose target is anywhere in this workspace links
     * to it - to its card if it has one, to its raw card otherwise. A reference to something not
     * in the workspace renders as a dead chip rather than a broken link, so the store's dangling
     * references stay visible instead of being quietly styled away.
     */
    private void appendChips(
            final StringBuilder html, final List<Ref> refs, final Set<String> carded, final Set<String> subjects) {
        html.append("                <div class=\"chips\">");
        for (final Ref ref : refs) {
            if (carded.contains(ref.iri()) || subjects.contains(ref.iri())) {
                html.append("<a class=\"chip\" href=\"#").append(resourceAnchor(ref.iri())).append("\">")
                        .append(escape(ref.code())).append("</a>");
            } else {
                html.append("<span class=\"chip dead\" title=\"not in this workspace\">")
                        .append(escape(ref.code())).append("</span>");
            }
        }
        html.append("</div>\n");
    }

    private void appendRawTriples(final StringBuilder html, final StoreResource raw, final Set<String> subjects) {
        if (raw == null) {
            return;
        }
        html.append("            <details class=\"raw\">\n              <summary>")
                .append(raw.outgoing().size()).append(" raw triples &middot; ")
                .append(escape(displayHandle(raw))).append("</summary>\n");
        appendPropertyTable(html, raw, subjects);
        html.append("            </details>\n");
    }

    private void appendRawCard(final StringBuilder html, final StoreResource resource, final Set<String> subjects) {
        final String anchor = resourceAnchor(resource.iri());
        html.append("            <article class=\"card raw-card\" id=\"").append(anchor).append("\">\n")
                .append("              <div class=\"head\">\n")
                .append("                <span class=\"code mono\">")
                .append(escape(displayHandle(resource))).append("</span>\n");
        resource.label().ifPresent(label ->
                html.append("                <h3>").append(escape(label)).append("</h3>\n"));
        html.append("                <span class=\"pill neutral\">")
                .append(escape(displayType(StoreSnapshot.primaryType(resource)))).append("</span>\n")
                .append("                <a class=\"anchor\" href=\"#").append(anchor).append("\">#</a>\n")
                .append("              </div>\n");
        appendPropertyTable(html, resource, subjects);
        html.append("            </article>\n");
    }

    private void appendPropertyTable(
            final StringBuilder html, final StoreResource resource, final Set<String> subjects) {
        html.append("              <table class=\"props\">\n");
        for (final Triple triple : resource.outgoing()) {
            html.append("                <tr><td class=\"pred\">")
                    .append(escape(prefixes.toCurie(triple.predicate())))
                    .append("</td><td class=\"obj\">").append(renderObject(triple, subjects))
                    .append("</td></tr>\n");
        }
        html.append("              </table>\n");
    }

    // --- leaf rendering --------------------------------------------------------

    private String renderObject(final Triple triple, final Set<String> subjects) {
        if (triple.object() instanceof RdfNode.Resource resource) {
            final String targetCurie = prefixes.toCurie(resource.iri());
            if (subjects.contains(resource.iri())) {
                return "<a href=\"#" + resourceAnchor(resource.iri()) + "\">" + escape(targetCurie) + "</a>";
            }
            return "<span class=\"lit\">" + escape(targetCurie) + "</span>";
        }
        return renderLiteral((RdfNode.Literal) triple.object());
    }

    private String renderLiteral(final RdfNode.Literal literal) {
        final StringBuilder out = new StringBuilder("<span class=\"lit str\">\"")
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

    /**
     * A badge renders with two classes: its family (so every status pill looks alike) and its
     * value (so {@code Accepted} can look different from {@code Proposed}). An unstyled value
     * simply falls back to the family's neutral look - no badge can render invisibly.
     */
    private static String badgePill(final Badge badge) {
        final String cls = "pill " + sanitize(badge.kind()).toLowerCase(Locale.ROOT)
                + " v-" + sanitize(badge.value()).toLowerCase(Locale.ROOT);
        return "<span class=\"" + escape(cls) + "\">" + escape(badge.value()) + "</span>";
    }

    private void appendFooter(final StringBuilder html) {
        html.append("  <footer class=\"foot\">\n")
                .append("    arknet store_overview &middot; model sections read through each bounded context's"
                        + " in-ports &middot; raw view from one SELECT ?s ?p ?o over the kognio-rdf dataset"
                        + " &middot; self-contained HTML, no external dependencies\n")
                .append("  </footer>\n");
    }

    /**
     * The handle a human would type for a raw resource: its CURIE, or - for an opaque,
     * kernel-minted IRI that shortens to nothing - its business id, or its local name.
     */
    private String displayHandle(final StoreResource resource) {
        final String curie = prefixes.toCurie(resource.iri());
        return curie.equals(resource.iri())
                ? resource.identifier().orElseGet(() -> StoreResource.localName(resource.iri()))
                : curie;
    }

    private String displayType(final String typeIri) {
        return typeIri.isEmpty() ? "(untyped)" : prefixes.toCurie(typeIri);
    }

    private String resourceAnchor(final String iri) {
        return "r-" + sanitize(prefixes.toCurie(iri));
    }

    private static String sanitize(final String value) {
        return value.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    static String escape(final String value) {
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
              --info:#315e8a; --info-bg:#e0ebf5; --bad:#a04141; --bad-bg:#f7e6e6;
              --mono:ui-monospace,"SF Mono","JetBrains Mono",Menlo,Consolas,monospace;
              --sans:system-ui,-apple-system,"Segoe UI",Roboto,sans-serif;
            }
            @media (prefers-color-scheme:dark){:root{
              --bg:#14171c; --surface:#1c2027; --surface-2:#232830; --border:#2e353f; --border-strong:#3c4552;
              --ink:#e6e9ee; --ink-soft:#a9b2be; --ink-faint:#6d7783; --accent:#8b98e8; --accent-soft:#262b48;
              --mono-key:#b79be8; --iri:#6faede; --ok:#6fd39c; --ok-bg:#17331f; --warn:#e0b45f; --warn-bg:#35290f;
              --info:#7fb4e6; --info-bg:#142838; --bad:#e79a9a; --bad-bg:#3a1e1e;}}
            *{box-sizing:border-box;}
            body{margin:0;background:var(--bg);color:var(--ink);font-family:var(--sans);font-size:15px;
              line-height:1.55;}
            .wrap{max-width:1180px;margin:0 auto;padding:28px 24px 80px;}
            header.top{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px 16px;
              border-bottom:2px solid var(--border-strong);padding-bottom:16px;margin-bottom:8px;}
            header.top h1{font-size:19px;margin:0;font-weight:650;}
            header.top .ws{font-family:var(--mono);font-size:12.5px;color:var(--iri);}
            header.top .meta{margin-left:auto;color:var(--ink-faint);font-size:12.5px;font-family:var(--mono);}
            header.top .meta b{color:var(--ink-soft);font-weight:600;}
            .failures{background:var(--bad-bg);border:1px solid var(--bad);border-radius:8px;
              padding:12px 16px;margin:18px 0 0;color:var(--ink);}
            .failures .lbl{margin:0 0 6px;font-size:11px;font-weight:700;letter-spacing:0.06em;
              text-transform:uppercase;color:var(--bad);}
            .failures ul{margin:0;padding-left:18px;font-size:13px;}
            .agent-panel{background:var(--surface-2);border:1px solid var(--border);
              border-left:3px solid var(--accent);border-radius:8px;margin:18px 0 24px;overflow:hidden;}
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
              border-radius:6px;text-decoration:none;color:var(--ink-soft);font-size:13px;}
            nav.index a.muted{color:var(--ink-faint);font-family:var(--mono);font-size:12px;}
            nav.index a:hover{background:var(--surface-2);color:var(--ink);}
            nav.index a .cnt{background:var(--surface-2);color:var(--ink-faint);border-radius:20px;padding:1px 8px;
              font-size:11px;font-variant-numeric:tabular-nums;min-width:24px;text-align:center;}
            section.group{margin-bottom:40px;scroll-margin-top:16px;}
            section.group>h2{font-size:15px;font-weight:650;color:var(--ink);margin:0;
              display:flex;align-items:baseline;gap:10px;}
            section.group>h2 .of{color:var(--ink-faint);font-weight:400;font-size:12px;font-family:var(--mono);}
            section.group>.sub{margin:2px 0 14px;color:var(--ink-faint);font-size:12.5px;
              padding-bottom:9px;border-bottom:1px solid var(--border);}
            .cards{display:grid;gap:14px;}
            article.card{background:var(--surface);border:1px solid var(--border);border-radius:9px;
              padding:15px 17px;scroll-margin-top:16px;}
            article.card:target{border-color:var(--accent);box-shadow:0 0 0 2px var(--accent-soft);}
            article.card .head{display:flex;flex-wrap:wrap;align-items:baseline;gap:8px 10px;}
            article.card .head .code{font-family:var(--mono);font-size:12px;font-weight:650;color:var(--accent);
              background:var(--accent-soft);border-radius:5px;padding:2px 7px;}
            article.card .head .code.mono{background:none;color:var(--ink-soft);padding:0;font-weight:600;}
            article.card .head h3{margin:0;font-size:15px;font-weight:600;color:var(--ink);}
            article.card .head .anchor{margin-left:auto;font-family:var(--mono);font-size:12px;
              color:var(--ink-faint);text-decoration:none;}
            article.card .head .anchor:hover{color:var(--accent);}
            article.card .body{margin-top:12px;display:grid;gap:11px;}
            .block .blabel{display:block;text-transform:uppercase;letter-spacing:0.07em;font-size:10px;
              font-weight:700;color:var(--ink-faint);margin-bottom:3px;}
            .block .prose{margin:0;font-size:14px;color:var(--ink-soft);}
            .block .bullets{margin:0;padding-left:18px;font-size:14px;color:var(--ink-soft);}
            .block .bullets li{margin:2px 0;}
            .chips{display:flex;flex-wrap:wrap;gap:5px;}
            .chip{font-family:var(--mono);font-size:11.5px;text-decoration:none;padding:2px 8px;border-radius:5px;
              background:var(--surface-2);color:var(--iri);border:1px solid var(--border);}
            a.chip:hover{border-color:var(--accent);color:var(--accent);}
            .chip.dead{color:var(--bad);border-style:dashed;}
            ol.flow{list-style:none;margin:0;padding:0;}
            ol.flow>li{display:flex;gap:10px;padding:6px 0;border-top:1px solid var(--border);}
            ol.flow>li:first-child{border-top:none;padding-top:2px;}
            ol.flow .num{flex:0 0 22px;height:22px;border-radius:50%;background:var(--accent-soft);
              color:var(--accent);font-family:var(--mono);font-size:11.5px;font-weight:700;
              display:flex;align-items:center;justify-content:center;font-variant-numeric:tabular-nums;}
            ol.flow .step{flex:1;min-width:0;}
            ol.flow .step p{margin:1px 0 0;font-size:14px;color:var(--ink);}
            ol.flow .realises{display:inline-block;margin:5px 0 3px;text-transform:uppercase;
              letter-spacing:0.06em;font-size:9.5px;font-weight:700;color:var(--ink-faint);}
            details.raw{margin-top:12px;border-top:1px solid var(--border);padding-top:8px;}
            details.raw>summary{cursor:pointer;font-family:var(--mono);font-size:11.5px;color:var(--ink-faint);}
            details.raw>summary:hover{color:var(--accent);}
            details.raw-group>summary{cursor:pointer;font-family:var(--mono);font-size:12.5px;
              color:var(--ink-faint);margin-bottom:12px;}
            section.group.other article.card{background:var(--surface-2);}
            .empty{color:var(--ink-faint);font-size:14px;}
            .empty code{font-family:var(--mono);font-size:12.5px;color:var(--accent);}
            table.props{width:100%;border-collapse:collapse;font-size:13px;margin-top:8px;}
            table.props td{padding:4px 0;vertical-align:top;border-top:1px solid var(--border);}
            table.props tr:first-child td{border-top:none;}
            table.props td.pred{font-family:var(--mono);font-size:12px;color:var(--mono-key);width:210px;
              padding-right:16px;white-space:nowrap;}
            table.props td.obj{font-family:var(--mono);font-size:12px;color:var(--ink);word-break:break-word;}
            table.props td.obj a{color:var(--iri);text-decoration:none;border-bottom:1px dotted currentColor;}
            table.props td.obj a:hover{color:var(--accent);}
            table.props td.obj .lit.str{color:var(--ok);}
            table.props td.obj .dt{color:var(--ink-faint);font-size:11px;}
            .pill{display:inline-block;padding:1px 9px;border-radius:20px;font-size:11px;font-weight:600;
              font-family:var(--sans);background:var(--surface-2);color:var(--ink-soft);}
            .pill.neutral{font-family:var(--mono);font-size:10.5px;color:var(--mono-key);}
            .pill.status{background:var(--info-bg);color:var(--info);}
            .pill.status.v-accepted{background:var(--ok-bg);color:var(--ok);}
            .pill.status.v-proposed{background:var(--warn-bg);color:var(--warn);}
            .pill.priority{background:var(--accent-soft);color:var(--accent);}
            .pill.type{background:var(--surface-2);color:var(--ink-soft);border:1px solid var(--border);}
            .pill.actor,.pill.subdomain{background:var(--info-bg);color:var(--info);}
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
                document.querySelectorAll('section.group').forEach(function(sec){
                  var visible = 0;
                  sec.querySelectorAll('article.card').forEach(function(card){
                    var match = q === '' || card.textContent.toLowerCase().indexOf(q) !== -1;
                    card.style.display = match ? '' : 'none';
                    if(match){visible++;}
                  });
                  sec.style.display = visible === 0 ? 'none' : '';
                  // A hit inside the collapsed raw section would otherwise stay invisible.
                  var raw = sec.querySelector('details.raw-group');
                  if(raw && q !== ''){raw.open = visible > 0;}
                });
              });
            })();
            """;
}
