// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreResource;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;
import de.hauschel.arknet.persistence.ArkreqVocabulary;

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
 * <em>not</em> suppressed - an orphan is exactly what this report should surface. Since issue
 * #297, {@code arkreq:AcceptanceCriterion} resources reached from a requirement's own
 * {@code arkreq:acceptanceCriterion} edge get the same treatment, for the same reason: every
 * requirement carries at least one, and {@link RequirementCards} already renders their text as
 * bullets inside the requirement's own card.</p>
 */
public final class HtmlReportRenderer {

    /** {@code arkreq:Step} - inlined into its use case's flow instead of shown as a resource. */
    private static final String STEP_TYPE = "https://w3id.org/arknet/requirements#Step";

    // The edges this renderer follows from a card to its positioned sub-resources, and the two
    // predicates carrying those sub-resources' text and position, come from the single shared
    // source of truth (arknet-persistence-support) - the very same constants the requirements and
    // use-cases out-adapters serialize them with, and the same ones the traceability read path
    // (de.hauschel.arknet.mcp.trace.TraceabilityGraph) traverses. A rename in
    // arknet-requirements.ttl therefore cannot leave this renderer compiling while the language
    // switch silently disappears from the report.

    /** {@code arkreq:mainStep} - a use case's edge to one numbered step of its main flow. */
    private static final String MAIN_STEP_EDGE = ArkreqVocabulary.MAIN_STEP;

    /** {@code arkreq:extensionStep} - a use case's edge to one of its extension flows. */
    private static final String EXTENSION_STEP_EDGE = ArkreqVocabulary.EXTENSION_STEP;

    /** The two predicates by which a use case reaches its steps. */
    private static final Set<String> STEP_EDGES = Set.of(MAIN_STEP_EDGE, EXTENSION_STEP_EDGE);

    /** {@code arkreq:stepText} - the text of a main-flow step or of an extension. */
    private static final String STEP_TEXT = ArkreqVocabulary.STEP_TEXT;

    /**
     * {@code arkreq:position} - the 1-based number a step or acceptance criterion carries, and
     * the key by which {@link #langSources} pairs one with the card item that shows it.
     */
    private static final String POSITION = ArkreqVocabulary.POSITION;

    /**
     * {@code arkreq:AcceptanceCriterion} - inlined into its requirement's card instead of shown
     * as a raw resource (issue #297). Every requirement has at least one by domain invariant, so
     * without this suppression the "Other resources" section would fill with one opaque card per
     * acceptance criterion in the store - exactly the "litters the report" problem {@link
     * #STEP_TYPE}'s suppression already solves for use-case steps.
     */
    private static final String ACCEPTANCE_CRITERION_TYPE =
            "https://w3id.org/arknet/requirements#AcceptanceCriterion";

    /** {@code arkreq:acceptanceCriterion} - a requirement's edge to one of its criteria. */
    private static final String ACCEPTANCE_CRITERION_EDGE = ArkreqVocabulary.ACCEPTANCE_CRITERION;

    /** The predicate by which a requirement reaches its acceptance criteria. */
    private static final Set<String> ACCEPTANCE_CRITERION_EDGES = Set.of(ACCEPTANCE_CRITERION_EDGE);

    /** {@code arkreq:criterionText} - the text of one acceptance criterion. */
    private static final String CRITERION_TEXT = ArkreqVocabulary.CRITERION_TEXT;

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
     * @param projectId     the project the snapshot was read from
     * @param label         the project's registered label, or {@link Optional#empty()}
     *                      if {@code projectId} is not (or no longer) found in the registry - the
     *                      header then falls back to the raw id, exactly as before this label was
     *                      available
     * @param description   the project's optional free-text description (issue #110), already
     *                      selected for {@code displayLocale} if the project carries it in several
     *                      languages, or {@link Optional#empty()} if it has none - shown in the
     *                      header when present
     * @param snapshot      the flat statement snapshot, used for raw triples and the leftovers
     * @param digest        the agent digest text shown in the top panel
     * @param views         the per-bounded-context sections that make up the report's body
     * @param displayLocale the display language to select among a resource's language-tagged
     *                      labels, resolved per call rather than baked into this renderer, so the
     *                      caller can merge in the target project's own default language first
     *                      (issue #276)
     * @return the self-contained HTML document
     */
    public String render(
            final ProjectId projectId,
            final Optional<String> label,
            final Optional<String> description,
            final StoreSnapshot snapshot,
            final String digest,
            final ModelViews.Views views,
            final DisplayLocale displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(digest, "digest");
        Objects.requireNonNull(views, "views");
        Objects.requireNonNull(displayLocale, "displayLocale");

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

        appendHeader(html, projectId, label, description, snapshot, carded.size());
        appendFailures(html, views.failures());
        appendAgentPanel(html, digest);
        appendToolbar(html);

        html.append("  <div class=\"layout\">\n");
        appendIndex(html, views.sections(), leftovers.size());
        html.append("    <main>\n");
        for (final ModelSection section : views.sections()) {
            appendSection(html, section, carded, subjects, bySubject, displayLocale);
        }
        appendLeftovers(html, leftovers, subjects, displayLocale);
        appendEmptyState(html, views.sections(), leftovers);
        html.append("    </main>\n  </div>\n");

        appendFooter(html);
        html.append("</div>\n<script>\n").append(FILTER_JS).append("\n</script>\n</body>\n</html>\n");
        return html.toString();
    }

    // --- structure -------------------------------------------------------------

    /**
     * Every resource no card was built for, minus the use-case steps and requirement acceptance
     * criteria already shown inside their owning card. Order stays the snapshot's own (primary
     * type, then IRI), so the fallback keeps the shape the whole report used to have.
     *
     * <p>Only a <em>carded</em> use case's {@code mainStep}/{@code extensionStep} edges - or a
     * <em>carded</em> requirement's {@code acceptanceCriterion} edge - count as "already shown" -
     * if the owning section itself failed to build, no use case/requirement is carded, so its
     * steps/acceptance criteria stay uninlined and fall through to this same leftovers list
     * instead of disappearing from the whole document (issue #142, extended to acceptance
     * criteria by issue #297).</p>
     */
    private static List<StoreResource> leftovers(final StoreSnapshot snapshot, final Set<String> carded) {
        final Set<String> inlinedSteps = inlinedTargets(snapshot, carded, STEP_EDGES);
        final Set<String> inlinedAcceptanceCriteria = inlinedTargets(snapshot, carded, ACCEPTANCE_CRITERION_EDGES);
        return snapshot.resources().stream()
                .filter(resource -> !carded.contains(resource.iri()))
                .filter(resource -> !(resource.types().contains(STEP_TYPE) && inlinedSteps.contains(resource.iri())))
                .filter(resource -> !(resource.types().contains(ACCEPTANCE_CRITERION_TYPE)
                        && inlinedAcceptanceCriteria.contains(resource.iri())))
                .toList();
    }

    /**
     * The targets of {@code edges} reached from a <em>carded</em> resource - shared by {@link
     * #leftovers}'s two suppressions (use-case steps, requirement acceptance criteria), which
     * differ only in which edge predicates and which type they inline.
     */
    private static Set<String> inlinedTargets(
            final StoreSnapshot snapshot, final Set<String> carded, final Set<String> edges) {
        return snapshot.resources().stream()
                .filter(resource -> carded.contains(resource.iri()))
                .flatMap(resource -> resource.outgoing().stream())
                .filter(triple -> edges.contains(triple.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(object -> ((RdfNode.Resource) object).iri())
                .collect(Collectors.toSet());
    }

    private void appendHeader(
            final StringBuilder html,
            final ProjectId projectId,
            final Optional<String> label,
            final Optional<String> description,
            final StoreSnapshot snapshot,
            final int elements) {
        html.append("  <header class=\"top\">\n")
                .append("    <h1>arknet Store Report</h1>\n")
                .append("    <span class=\"ws\">project: ").append(escape(headerName(projectId, label)))
                .append("</span>\n")
                .append("    <span class=\"meta\"><b>").append(elements)
                .append("</b> model elements &middot; <b>").append(snapshot.resourceCount())
                .append("</b> resources &middot; <b>").append(snapshot.tripleCount())
                .append("</b> triples</span>\n")
                .append("  </header>\n");
        description.ifPresent(value -> html.append("  <p class=\"project-desc\">")
                .append(escape(value)).append("</p>\n"));
    }

    /**
     * The project name shown in the header: the registered label with its id alongside (matching
     * {@code project_list}'s own {@code "label (id: ...)"} rendering), or the raw id alone when no
     * label is available.
     */
    private static String headerName(final ProjectId projectId, final Optional<String> label) {
        return label.map(value -> value + " (id: " + projectId.value() + ")").orElseGet(projectId::value);
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
                .append("    <button type=\"button\" id=\"expand-all\">Expand all</button>\n")
                .append("    <button type=\"button\" id=\"collapse-all\">Collapse all</button>\n")
                .append("    <select id=\"lang-switch\" aria-label=\"Language\">\n")
                .append("      <option value=\"\">Original</option>\n")
                .append("    </select>\n")
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
            final Map<String, StoreResource> bySubject,
            final DisplayLocale displayLocale) {
        html.append("      <section class=\"group\" id=\"sec-").append(escape(section.id())).append("\">\n")
                .append("        <h2>").append(escape(section.title()))
                .append(" <span class=\"of\">").append(section.cards().size()).append("</span></h2>\n");
        if (!section.subtitle().isBlank()) {
            html.append("        <p class=\"sub\">").append(escape(section.subtitle())).append("</p>\n");
        }
        html.append("        <div class=\"cards\">\n");
        for (final ModelCard card : section.cards()) {
            appendCard(html, card, carded, subjects, bySubject, displayLocale);
        }
        html.append("        </div>\n      </section>\n");
    }

    private void appendLeftovers(
            final StringBuilder html, final List<StoreResource> leftovers, final Set<String> subjects,
            final DisplayLocale displayLocale) {
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
            appendRawCard(html, resource, subjects, displayLocale);
        }
        html.append("          </div>\n        </details>\n      </section>\n");
    }

    private void appendEmptyState(
            final StringBuilder html, final List<ModelSection> sections, final List<StoreResource> leftovers) {
        if (!sections.isEmpty() || !leftovers.isEmpty()) {
            return;
        }
        html.append("      <p class=\"empty\">This project holds no model yet. Start with"
                + " <code>bc_add</code>, <code>term_add</code>, <code>req_add</code> or"
                + " <code>uc_add</code>.</p>\n");
    }

    // --- cards -----------------------------------------------------------------

    private void appendCard(
            final StringBuilder html,
            final ModelCard card,
            final Set<String> carded,
            final Set<String> subjects,
            final Map<String, StoreResource> bySubject,
            final DisplayLocale displayLocale) {
        final StoreResource raw = bySubject.get(card.iri());
        final LangSources sources = langSources(raw, bySubject);
        final String anchor = resourceAnchor(card.iri());
        html.append("          <article class=\"card\" id=\"").append(anchor).append("\">\n")
                .append("            <details class=\"fold\">\n")
                .append("              <summary class=\"head\">\n")
                .append("                <span class=\"code\">").append(escape(card.code())).append("</span>\n")
                .append("                <h3>")
                .append(langSwitchable(escape(card.title()), languageVariants(raw, card.title(), displayLocale)))
                .append("</h3>\n");
        for (final Badge badge : card.badges()) {
            html.append("                ").append(badgePill(badge)).append('\n');
        }
        html.append("                <a class=\"anchor\" href=\"#").append(anchor).append("\">#</a>\n")
                .append("              </summary>\n              <div class=\"body\">\n");
        for (final Block block : card.blocks()) {
            appendBlock(html, block, carded, subjects, raw, sources, displayLocale);
        }
        html.append("              </div>\n");
        appendRawTriples(html, raw, subjects);
        html.append("            </details>\n          </article>\n");
    }

    private void appendBlock(
            final StringBuilder html, final Block block, final Set<String> carded, final Set<String> subjects,
            final StoreResource raw, final LangSources sources, final DisplayLocale displayLocale) {
        html.append("              <div class=\"block\">\n                <span class=\"blabel\">")
                .append(escape(block.label())).append("</span>\n");
        switch (block) {
            case Block.Prose prose -> {
                final String rendered = renderText(prose.text(), carded, subjects);
                final Optional<LangVariants> variants =
                        languageVariants(raw, prose.text().text(), displayLocale);
                html.append("                <p class=\"prose\">")
                        .append(langSwitchable(rendered, variants)).append("</p>\n");
            }
            case Block.Bullets bullets -> {
                html.append("                <ul class=\"bullets\">\n");
                final Map<Integer, SubResource> bulletSources =
                        sources.bullets().getOrDefault(bullets.label(), Map.of());
                for (final BulletItem item : bullets.items()) {
                    final String rendered = renderText(item.text(), carded, subjects);
                    final Optional<LangVariants> variants =
                            itemVariants(bulletSources.get(item.position()), item.text().text(), displayLocale);
                    html.append("                  <li>").append(langSwitchable(rendered, variants))
                            .append("</li>\n");
                }
                html.append("                </ul>\n");
            }
            case Block.Refs refs -> appendChips(html, refs.refs(), carded, subjects);
            case Block.Flow flow -> appendFlow(html, flow, carded, subjects, sources, displayLocale);
        }
        html.append("              </div>\n");
    }

    /**
     * The language variants of a card's title or a {@link Block.Prose} text - found among the
     * same subject's own raw triples, which the raw view a level down already shows in full. No
     * new store access and no domain/port plumbing: the store read already carried every
     * literal, this only stops throwing the alternates away before the report can offer a
     * client-side switch between them (issue #270, part 2 of #248).
     *
     * <p>The match back from {@code displayed} to a predicate is by text equality alone - a card's
     * title and its prose fields are plain strings by the time they reach the renderer, with no
     * predicate alongside them. If more than one predicate on {@code subject} carries a literal
     * with that exact text, which one is meant is ambiguous; this returns {@link Optional#empty()}
     * rather than guessing and risking a switch that shows an unrelated field's text once the
     * reader picks another language. A positioned item does not take this path: it reaches its own
     * sub-resource through the model's edges and names its predicate outright (issue #319).</p>
     *
     * @param subject       the card's own raw resource, or {@code null} if the snapshot holds none
     * @param displayed     the text currently shown, already selected by {@code displayLocale}
     * @param displayLocale the same locale {@code displayed} was selected under, used to key an
     *                      untagged literal's variant by its system-default language
     * @return the active language together with every other language sharing {@code displayed}'s
     *         predicate, or {@link Optional#empty()} if {@code displayed} cannot be matched back
     *         to exactly one predicate, or only one language exists for it
     */
    private Optional<LangVariants> languageVariants(
            final StoreResource subject, final String displayed, final DisplayLocale displayLocale) {
        if (subject == null) {
            return Optional.empty();
        }
        final Set<String> matchingPredicates = new LinkedHashSet<>();
        for (final Triple triple : subject.outgoing()) {
            if (triple.object() instanceof RdfNode.Literal literal && literal.lexicalForm().equals(displayed)) {
                matchingPredicates.add(triple.predicate());
            }
        }
        if (matchingPredicates.size() != 1) {
            return Optional.empty();
        }
        return languageVariants(subject, matchingPredicates.iterator().next(), displayed, displayLocale);
    }

    /**
     * {@link #languageVariants(StoreResource, String, DisplayLocale)} for a caller that knows
     * which predicate carries the text, so nothing has to be guessed back from what is displayed
     * (issue #319). This is the path a positioned item takes: a step's text is
     * {@code arkreq:stepText} on the {@code arkreq:Step} the flow's position points at, a
     * criterion's is {@code arkreq:criterionText} on its {@code arkreq:AcceptanceCriterion} -
     * both facts the caller establishes from the model's own edges rather than from text
     * equality, which two identically worded steps would make ambiguous.
     *
     * <p>An untagged literal (a store-first edit, or an older resource written before issue #258)
     * is a candidate variant too - {@link DisplayLocale#select} can return one per step 3 of its
     * fallback chain, so a switch built only from tagged literals would silently omit exactly
     * that field. It is keyed by the call's {@code displayLocale}'s {@code systemDefault} language,
     * the same language an untagged literal would be shown under if the store held no other
     * candidate - but only if no literal is genuinely tagged with that same language: a tagged
     * literal always wins its key over an untagged one, mirroring {@link DisplayLocale#select}'s
     * own precedence (step 2 before step 3). Without that precedence here, {@code subject}'s own
     * triple order - not guaranteed stable by {@link StoreResource#types()}'s javadoc - would
     * decide which of the two survives the switch, and the reachable, correctly tagged literal
     * could lose to a stale untagged one (issue #301).</p>
     *
     * @param subject       the resource carrying the text - a card's own resource, or one of its
     *                      positioned sub-resources
     * @param predicate     the predicate whose literals are the variants of one another
     * @param displayed     the text currently shown, already selected by {@code displayLocale}
     * @param displayLocale the same locale {@code displayed} was selected under
     * @return the active language together with every other language under {@code predicate}, or
     *         {@link Optional#empty()} if {@code displayed} is not one of that predicate's
     *         literals, or only one language exists for it
     */
    private Optional<LangVariants> languageVariants(
            final StoreResource subject, final String predicate, final String displayed,
            final DisplayLocale displayLocale) {
        String activeLang = null;
        final Map<String, String> byLang = new LinkedHashMap<>();
        // Tagged literals first, so a genuinely tagged systemDefault-language literal always
        // claims its key - an untagged literal (added in the second pass below) only fills a key
        // no tagged literal already holds. Splitting into two passes, rather than one pass that
        // prefers tagged on a collision, keeps the same precedence regardless of subject.outgoing()'s
        // encounter order, which StoreResource#types()' javadoc documents as not stable (issue #301).
        for (final Triple triple : subject.outgoing()) {
            if (predicate.equals(triple.predicate()) && triple.object() instanceof RdfNode.Literal literal
                    && literal.languageTag() != null) {
                if (activeLang == null && literal.lexicalForm().equals(displayed)) {
                    activeLang = literal.languageTag();
                }
                byLang.putIfAbsent(literal.languageTag(), literal.lexicalForm());
            }
        }
        for (final Triple triple : subject.outgoing()) {
            if (predicate.equals(triple.predicate()) && triple.object() instanceof RdfNode.Literal literal
                    && literal.languageTag() == null) {
                if (activeLang == null && literal.lexicalForm().equals(displayed)) {
                    activeLang = displayLocale.systemDefault().toLanguageTag();
                }
                byLang.putIfAbsent(displayLocale.systemDefault().toLanguageTag(), literal.lexicalForm());
            }
        }
        return activeLang != null && byLang.size() > 1
                ? Optional.of(new LangVariants(activeLang, byLang))
                : Optional.empty();
    }

    /** {@link #languageVariants} for a positioned item, or empty if no source was found for it. */
    private Optional<LangVariants> itemVariants(
            final SubResource source, final String displayed, final DisplayLocale displayLocale) {
        return source == null
                ? Optional.empty()
                : languageVariants(source.resource(), source.textPredicate(), displayed, displayLocale);
    }

    /**
     * Wraps already-rendered HTML in the language-switch markup the report's script toggles,
     * or returns it unchanged when there is nothing to switch between.
     *
     * <p>Only the active language carries {@code activeHtml} (which may itself hold glossary
     * markup); every other language shows its own literal escaped as plain text - a description's
     * German variant is not re-run through {@link Glossary#markUp}, since that would need that
     * language's own term labels, a related but separate concern this issue does not cover.</p>
     */
    private static String langSwitchable(final String activeHtml, final Optional<LangVariants> variants) {
        if (variants.isEmpty()) {
            return activeHtml;
        }
        final LangVariants langVariants = variants.get();
        final StringBuilder out = new StringBuilder("<span class=\"lang-group\" data-default-lang=\"")
                .append(escape(langVariants.activeLang())).append("\">");
        for (final Map.Entry<String, String> entry : langVariants.byLang().entrySet()) {
            final boolean active = entry.getKey().equals(langVariants.activeLang());
            out.append("<span class=\"lang-variant\" data-lang=\"").append(escape(entry.getKey())).append('"');
            if (!active) {
                out.append(" hidden");
            }
            out.append('>').append(active ? activeHtml : escape(entry.getValue())).append("</span>");
        }
        return out.append("</span>").toString();
    }

    /**
     * @param activeLang the language tag of the literal currently shown, selected by the call's
     *                   {@code displayLocale}
     * @param byLang     every language sharing that literal's predicate, keyed by language tag
     */
    private record LangVariants(String activeLang, Map<String, String> byLang) {
    }

    /**
     * The store sub-resources behind a card's positioned items: the {@code arkreq:Step}s of a
     * use case's main flow, keyed by position, and every {@link Block.Bullets} list the card may
     * show, keyed first by that block's own {@link Block#label() label} and then by position.
     *
     * <p>Keying {@code bullets} by label rather than sharing one flat position table is what
     * keeps two {@link Block.Bullets} lists on the same card - the extensions of a use case, the
     * acceptance criteria of a requirement, or any future pair sharing one card - from being
     * matched against each other's positions (issue #358: before this, a second bullet list
     * would either collide with the first's positions or, if the two happened not to collide,
     * silently borrow the wrong sub-resource). The label is a fixed, shared constant between a
     * card builder ({@link RequirementCards#ACCEPTANCE_CRITERIA_LABEL}, {@link
     * UseCaseCards#EXTENSIONS_LABEL}) and this renderer, not a guess back from the rendered text -
     * the same "known key beats a text-equality guess" choice {@link #itemVariants} already makes
     * over {@link #languageVariants} for a single item. The card's own type still never reaches
     * this renderer: which edges feed which label is this class's own, already-hardcoded
     * knowledge (see the class-level note on {@link #MAIN_STEP_EDGE} and friends), and {@link
     * Block}'s shape-only vocabulary is untouched (issue #319).</p>
     *
     * @param flow    main-flow steps by their 1-based position
     * @param bullets every bullets list's sub-resources, keyed by the list's own label and then
     *                by the item's 1-based position; a label with no known source (e.g. a plain
     *                {@link Block.Bullets#plain} list with no positioned sub-resources of its own)
     *                is simply absent, and a lookup miss falls back to an empty map
     */
    private record LangSources(Map<Integer, SubResource> flow, Map<String, Map<Integer, SubResource>> bullets) {

        /** For a card the snapshot holds no resource for, or one with no positioned items. */
        private static final LangSources NONE = new LangSources(Map.of(), Map.of());
    }

    /**
     * @param resource      the sub-resource carrying one item's text
     * @param textPredicate the predicate its text lives under
     */
    private record SubResource(StoreResource resource, String textPredicate) {
    }

    /**
     * Collects {@code card}'s positioned sub-resources from the snapshot it was rendered
     * alongside. No extra store access: {@code bySubject} already holds every resource the
     * overview read, sub-resources included, and the edges are the same ones {@link #leftovers}
     * follows to suppress those resources from the raw section.
     */
    private static LangSources langSources(final StoreResource card, final Map<String, StoreResource> bySubject) {
        if (card == null) {
            return LangSources.NONE;
        }
        final Map<Integer, List<SubResource>> flow = new LinkedHashMap<>();
        collectPositioned(card, bySubject, MAIN_STEP_EDGE, STEP_TEXT, flow);
        final Map<Integer, List<SubResource>> extensions = new LinkedHashMap<>();
        collectPositioned(card, bySubject, EXTENSION_STEP_EDGE, STEP_TEXT, extensions);
        final Map<Integer, List<SubResource>> acceptanceCriteria = new LinkedHashMap<>();
        collectPositioned(card, bySubject, ACCEPTANCE_CRITERION_EDGE, CRITERION_TEXT, acceptanceCriteria);
        final Map<String, Map<Integer, SubResource>> bullets = new LinkedHashMap<>();
        bullets.put(UseCaseCards.EXTENSIONS_LABEL, unambiguous(extensions));
        bullets.put(RequirementCards.ACCEPTANCE_CRITERIA_LABEL, unambiguous(acceptanceCriteria));
        return new LangSources(unambiguous(flow), bullets);
    }

    /** Adds every sub-resource {@code card} reaches through {@code edge} under its own position. */
    private static void collectPositioned(
            final StoreResource card,
            final Map<String, StoreResource> bySubject,
            final String edge,
            final String textPredicate,
            final Map<Integer, List<SubResource>> collected) {
        for (final Triple triple : card.outgoing()) {
            if (!edge.equals(triple.predicate()) || !(triple.object() instanceof RdfNode.Resource target)) {
                continue;
            }
            final StoreResource sub = bySubject.get(target.iri());
            if (sub == null) {
                continue;
            }
            position(sub).ifPresent(position -> collected
                    .computeIfAbsent(position, key -> new ArrayList<>())
                    .add(new SubResource(sub, textPredicate)));
        }
    }

    /** {@code sub}'s {@code arkreq:position}, or empty if it carries none or an unparsable one. */
    private static Optional<Integer> position(final StoreResource sub) {
        for (final Triple triple : sub.outgoing()) {
            if (POSITION.equals(triple.predicate()) && triple.object() instanceof RdfNode.Literal literal) {
                try {
                    return Optional.of(Integer.valueOf(literal.lexicalForm().trim()));
                } catch (final NumberFormatException notANumber) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Keeps only the positions exactly one sub-resource claims. Two resources under the same
     * position mean the store cannot say which one an item shows, and a language switch built on
     * a guess would show the reader another item's text - the same "empty rather than wrong"
     * rule the text match a few lines up already follows.
     */
    private static Map<Integer, SubResource> unambiguous(final Map<Integer, List<SubResource>> collected) {
        final Map<Integer, SubResource> sources = new LinkedHashMap<>();
        collected.forEach((position, candidates) -> {
            if (candidates.size() == 1) {
                sources.put(position, candidates.getFirst());
            }
        });
        return sources;
    }

    private void appendFlow(
            final StringBuilder html, final Block.Flow flow, final Set<String> carded, final Set<String> subjects,
            final LangSources sources, final DisplayLocale displayLocale) {
        html.append("                <ol class=\"flow\">\n");
        for (final FlowStep step : flow.steps()) {
            final String rendered = renderText(step.text(), carded, subjects);
            final Optional<LangVariants> variants =
                    itemVariants(sources.flow().get(step.position()), step.text().text(), displayLocale);
            html.append("                  <li><span class=\"num\">").append(step.position())
                    .append("</span><div class=\"step\"><p>")
                    .append(langSwitchable(rendered, variants)).append("</p>\n");
            if (!step.realises().isEmpty()) {
                html.append("                  <span class=\"realises\">realises</span>\n");
                appendChips(html, step.realises(), carded, subjects);
            }
            html.append("                  </div></li>\n");
        }
        html.append("                </ol>\n");
    }

    /**
     * Renders references as chips. A reference whose target is anywhere in this project links
     * to it - to its card if it has one, to its raw card otherwise. A reference to something not
     * in the project renders as a dead chip rather than a broken link, so the store's dangling
     * references stay visible instead of being quietly styled away.
     *
     * <p>The chip shows the target's label; its business code, which is what a human types into
     * {@code term_get}, moves into the tooltip instead of taking the reader's attention.</p>
     */
    private void appendChips(
            final StringBuilder html, final List<Ref> refs, final Set<String> carded, final Set<String> subjects) {
        html.append("                <div class=\"chips\">");
        for (final Ref ref : refs) {
            if (carded.contains(ref.iri()) || subjects.contains(ref.iri())) {
                html.append("<a class=\"chip\" href=\"#").append(resourceAnchor(ref.iri())).append('"')
                        .append(titleAttribute(ref.code())).append('>')
                        .append(escape(ref.label())).append("</a>");
            } else {
                final String hint = ref.code() == null
                        ? "not in this project"
                        : ref.code() + " - not in this project";
                html.append("<span class=\"chip dead\"").append(titleAttribute(hint)).append('>')
                        .append(escape(ref.label())).append("</span>");
            }
        }
        html.append("</div>\n");
    }

    /**
     * Renders a text's spans: plain runs escaped as they are, glossary mentions marked up.
     *
     * <p>A mention the model backs with an edge becomes a link into the glossary. A mention of a
     * term with no such edge becomes a marked but unlinked run - it is not a reference, it is
     * the <em>absence</em> of one, and rendering it as a working link would tell the reader the
     * model holds a relationship that nobody ever recorded.</p>
     */
    private String renderText(final RichText text, final Set<String> carded, final Set<String> subjects) {
        final StringBuilder out = new StringBuilder();
        for (final Span span : text.spans()) {
            switch (span) {
                case Span.Plain plain -> out.append(escape(plain.text()));
                case Span.TermLink link -> {
                    if (carded.contains(link.iri()) || subjects.contains(link.iri())) {
                        out.append("<a class=\"term\" href=\"#").append(resourceAnchor(link.iri())).append('"')
                                .append(titleAttribute(link.code())).append('>')
                                .append(escape(link.text())).append("</a>");
                    } else {
                        out.append("<span class=\"term\"").append(titleAttribute(link.code())).append('>')
                                .append(escape(link.text())).append("</span>");
                    }
                }
                case Span.TermGap gap -> out.append("<span class=\"term gap\"")
                        .append(titleAttribute(gap.code() + " - in the glossary, but this element does not"
                                + " link to it"))
                        .append('>').append(escape(gap.text())).append("</span>");
            }
        }
        return out.toString();
    }

    /** @return a {@code title="..."} attribute, or nothing at all when there is no tooltip to show. */
    private static String titleAttribute(final String tooltip) {
        return tooltip == null ? "" : " title=\"" + escape(tooltip) + "\"";
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

    private void appendRawCard(final StringBuilder html, final StoreResource resource, final Set<String> subjects,
            final DisplayLocale displayLocale) {
        final String anchor = resourceAnchor(resource.iri());
        html.append("            <article class=\"card raw-card\" id=\"").append(anchor).append("\">\n")
                .append("              <details class=\"fold\">\n")
                .append("                <summary class=\"head\">\n")
                .append("                  <span class=\"code mono\">")
                .append(escape(displayHandle(resource))).append("</span>\n");
        resource.label(displayLocale).ifPresent(label ->
                html.append("                  <h3>").append(escape(label)).append("</h3>\n"));
        html.append("                  <span class=\"pill neutral\">")
                .append(escape(displayType(StoreSnapshot.primaryType(resource)))).append("</span>\n")
                .append("                  <a class=\"anchor\" href=\"#").append(anchor).append("\">#</a>\n")
                .append("                </summary>\n");
        appendPropertyTable(html, resource, subjects);
        html.append("              </details>\n            </article>\n");
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
        final String cls = "pill " + sanitize(badge.kind().cssClass()).toLowerCase(Locale.ROOT)
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

    /**
     * The DOM id one resource's card - and every link to it - uses.
     *
     * <p>{@link #sanitize} collapses any run of non-alphanumeric characters to a single
     * {@code -}, so two different IRIs can collapse to the same sanitized text (e.g.
     * {@code .../a.b} and {@code .../a-b} both become {@code a-b}). Model resources are
     * UUID-minted and never collide this way, but the "Other resources" section renders
     * arbitrary store IRIs by design (issue #150) - appending a short hash of the untouched,
     * full IRI is what makes two sanitized-collapsed IRIs diverge again with overwhelming
     * probability, while the sanitized CURIE in front keeps it readable in a browser's address
     * bar. {@link #shortHash} is a SHA-256 digest, not merely {@link String#hashCode()}
     * (issue #305 part 2): a 32-bit {@code hashCode} is trivially collidable by construction, so
     * it never actually delivered the injectivity this method used to claim - the same collision
     * risk {@link de.hauschel.arknet.mcp.store.FileNameSanitizer#uniqueSegment} closes for
     * on-disk path segments with the identical SHA-256-suffix design, only duplicated here rather
     * than shared because {@code FileNameSanitizer} is package-private to {@code mcp.store}.</p>
     */
    private String resourceAnchor(final String iri) {
        return "r-" + sanitize(prefixes.toCurie(iri)) + "-" + shortHash(iri);
    }

    private static String sanitize(final String value) {
        return value.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    /**
     * @param iri the full, unsanitized IRI - never the CURIE, so two IRIs that collapse to the
     *            same sanitized text still hash differently
     * @return a 16-hex-digit prefix of {@code iri}'s SHA-256 digest, deterministic across calls
     */
    private static String shortHash(final String iri) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is a JDK-mandated algorithm", impossible);
        }
        final byte[] hashed = digest.digest(iri.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hashed, 0, 8);
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
            .project-desc{margin:0 0 8px;color:var(--ink-soft);font-size:13.5px;}
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
            .toolbar button{padding:8px 13px;border:1px solid var(--border-strong);border-radius:7px;
              background:var(--surface);color:var(--ink-soft);font-family:var(--sans);font-size:12.5px;
              cursor:pointer;white-space:nowrap;}
            .toolbar button:hover{border-color:var(--accent);color:var(--accent);}
            .toolbar select{padding:8px 13px;border:1px solid var(--border-strong);border-radius:7px;
              background:var(--surface);color:var(--ink-soft);font-family:var(--sans);font-size:12.5px;
              cursor:pointer;}
            .toolbar select:hover{border-color:var(--accent);color:var(--accent);}
            .lang-variant[hidden]{display:none;}
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
            article.card>details.fold>summary.head{cursor:pointer;list-style:none;}
            article.card>details.fold>summary.head::-webkit-details-marker{display:none;}
            article.card>details.fold>summary.head::before{content:"";flex:0 0 auto;width:0;height:0;
              border-left:5px solid var(--ink-faint);border-top:4px solid transparent;
              border-bottom:4px solid transparent;transition:transform 120ms ease;}
            article.card>details.fold[open]>summary.head::before{transform:rotate(90deg);}
            article.card>details.fold>summary.head:hover::before{border-left-color:var(--accent);}
            article.card .body{margin-top:12px;display:grid;gap:11px;}
            .block .blabel{display:block;text-transform:uppercase;letter-spacing:0.07em;font-size:10px;
              font-weight:700;color:var(--ink-faint);margin-bottom:3px;}
            .block .prose{margin:0;font-size:14px;color:var(--ink-soft);}
            .block .bullets{margin:0;padding-left:18px;font-size:14px;color:var(--ink-soft);}
            .block .bullets li{margin:2px 0;}
            .term{color:var(--iri);text-decoration:none;border-bottom:1px solid var(--border-strong);}
            a.term:hover{color:var(--accent);border-bottom-color:var(--accent);}
            .term.gap{color:var(--warn);border-bottom:1px dashed var(--warn);cursor:help;}
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
              var folds = function(){return document.querySelectorAll('article.card>details.fold');};
              var setAll = function(open){folds().forEach(function(f){f.open = open;});};

              var expand = document.getElementById('expand-all');
              var collapse = document.getElementById('collapse-all');
              if(expand){expand.addEventListener('click', function(){setAll(true);});}
              if(collapse){collapse.addEventListener('click', function(){setAll(false);});}

              // The anchor link sits inside the summary; without this every attempt to copy a
              // card's link would also toggle the card.
              document.querySelectorAll('summary a').forEach(function(a){
                a.addEventListener('click', function(e){e.stopPropagation();});
              });

              // Cards start collapsed, so following a reference has to open its target - and
              // every details around it, or the target stays hidden inside the raw section.
              var reveal = function(){
                var id = decodeURIComponent(location.hash.replace('#',''));
                if(!id){return;}
                var target = document.getElementById(id);
                if(!target){return;}
                var node = target;
                while(node){
                  if(node.tagName === 'DETAILS'){node.open = true;}
                  node = node.parentElement;
                }
                var fold = target.querySelector('details.fold');
                if(fold){fold.open = true;}
                target.scrollIntoView();
              };
              window.addEventListener('hashchange', reveal);
              reveal();

              // Language switch: every switchable field is a .lang-group of .lang-variant spans,
              // one per language, only the active one visible. The languages on offer are
              // whatever the document actually contains - discovered here, not hardcoded, since
              // the report never knows in advance which projects hold which languages.
              var langSelect = document.getElementById('lang-switch');
              if(langSelect){
                var langs = [];
                document.querySelectorAll('.lang-variant[data-lang]').forEach(function(v){
                  var lang = v.getAttribute('data-lang');
                  if(langs.indexOf(lang) === -1){langs.push(lang);}
                });
                if(langs.length === 0){
                  langSelect.style.display = 'none';
                } else {
                  langs.sort().forEach(function(lang){
                    var opt = document.createElement('option');
                    opt.value = lang;
                    opt.textContent = lang;
                    langSelect.appendChild(opt);
                  });
                  langSelect.addEventListener('change', function(){
                    var chosen = langSelect.value;
                    document.querySelectorAll('.lang-group').forEach(function(group){
                      var fallback = group.getAttribute('data-default-lang');
                      var wanted = chosen === '' ? fallback : chosen;
                      var variants = group.querySelectorAll('.lang-variant');
                      var hasWanted = false;
                      variants.forEach(function(v){
                        if(v.getAttribute('data-lang') === wanted){hasWanted = true;}
                      });
                      // No variant in the chosen language for this field: leave its own default
                      // shown rather than hiding the field entirely.
                      var target = hasWanted ? wanted : fallback;
                      variants.forEach(function(v){v.hidden = v.getAttribute('data-lang') !== target;});
                    });
                  });
                }
              }

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
                    // A hit in a collapsed card would otherwise be a card that matches while
                    // showing nothing of why; clearing the filter folds them away again.
                    var fold = card.querySelector('details.fold');
                    if(fold){fold.open = q !== '' && match;}
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
