// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import de.hauschel.arknet.kernel.DisplayLocale;

/**
 * Renders the project model as a business-reader specification: numbered chapters, prose instead
 * of cards, no technical internals. The second consumer of {@link ModelViews.Views}, next to
 * {@link HtmlReportRenderer}.
 *
 * <p><strong>What it takes from the presentation model.</strong> Cards are picked by their
 * section ({@code Actors}, {@code Roles}, {@code Use Cases}, {@code Requirements},
 * {@code Constraints}, {@code Glossary}); decisions, bounded contexts and everything else are
 * dropped, and so is every {@link ModelCard#iri()} - an IRI is the model's identity, not a
 * reader's information. It is used only as the key that resolves a reference to its anchor.
 * A requirement is functional or a quality requirement by its {@link Badge.Kind.Known#TYPE} badge.
 * Glossary mentions and code references come from the {@link Span}s the card builders already
 * marked up; this class decides only how they look.</p>
 *
 * <p><strong>References.</strong> A reference whose target is not part of this document renders
 * as plain text - a link into nothing would be a defect, and a decision or context the document
 * deliberately omits is exactly that case. In running text a reference shows the target's title
 * when it is short ({@value #INLINE_TITLE_LIMIT} characters at most), else its code; in lists
 * and pop-ups it is always "CODE Title". Glossary terms show their title only: their code is
 * bookkeeping.</p>
 *
 * <p><strong>Self-contained, and readable without script.</strong> Stylesheet and script are
 * classpath resources inlined into the page. Everything the script needs - pop-up titles, term
 * definitions, detail-pane content - it reads from the {@code data-*} attributes and the
 * elements this class emits, so the script knows nothing about any project. Without it the page
 * is a linear document whose anchor links work.</p>
 *
 * <p>Stateless and thread-safe.</p>
 */
public final class SpecificationRenderer {

    /** Longest title shown in place of a code inside running text. */
    static final int INLINE_TITLE_LIMIT = 40;

    private static final int POPUP_DEFINITION_LIMIT = 340;
    private static final String GERMAN = "de";
    private static final String NFR_TYPE = "NFR";
    private static final String CSS = resource("/report/specification.css");
    private static final String JS = resource("/report/specification.js");

    private static final String CH_INTRO = "k1";
    private static final String CH_PARTICIPANTS = "k2";
    private static final String CH_ACTORS = "k21";
    private static final String CH_ROLES = "k22";
    private static final String CH_USE_CASES = "k3";
    private static final String CH_FUNCTIONAL = "k4";
    private static final String CH_QUALITY = "k5";
    private static final String CH_CONSTRAINTS = "k6";
    private static final String CH_GLOSSARY = "k7";

    /**
     * Renders the complete, self-contained HTML document.
     *
     * @param label         the project's registered label, or empty
     * @param description   the project's description already selected for {@code displayLocale},
     *                      or empty
     * @param views         the assembled sections and failures
     * @param displayLocale the effective display language; German selects the German fixed texts,
     *                      anything else the English ones
     * @return the HTML document
     */
    public String render(
            final Optional<String> label,
            final Optional<String> description,
            final ModelViews.Views views,
            final DisplayLocale displayLocale) {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(views, "views");
        Objects.requireNonNull(displayLocale, "displayLocale");
        final boolean german = GERMAN.equals(displayLocale.requested().getLanguage());
        return new Page(german, label.filter(text -> !text.isBlank()), description.filter(text -> !text.isBlank()),
                views).render();
    }

    private static String resource(final String path) {
        try (InputStream in = SpecificationRenderer.class.getResourceAsStream(path)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + path);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static String escape(final String value) {
        return value.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    private enum Kind {
        ACTOR(SpecificationTexts.KIND_ACTOR, true),
        ROLE(SpecificationTexts.KIND_ROLE, true),
        USE_CASE(SpecificationTexts.KIND_USE_CASE, true),
        FUNCTIONAL(SpecificationTexts.KIND_FUNCTIONAL, true),
        QUALITY(SpecificationTexts.KIND_QUALITY, true),
        CONSTRAINT(SpecificationTexts.KIND_CONSTRAINT, true),
        TERM(SpecificationTexts.KIND_TERM, false);

        private final SpecificationTexts label;
        private final boolean showsCode;

        Kind(final SpecificationTexts label, final boolean showsCode) {
            this.label = label;
            this.showsCode = showsCode;
        }
    }

    /** A card that is part of the document, addressable by its anchor. */
    private record Target(String anchor, String code, String title, Kind kind) {

        /** "CODE Title", or the title alone for a kind that shows no code. */
        String listText() {
            return kind.showsCode && !code.isBlank() ? code + " " + title : title;
        }

        /** The title when short, else the code - a kind without code shows its title always. */
        String inlineText() {
            if (title.length() <= INLINE_TITLE_LIMIT || !kind.showsCode || code.isBlank()) {
                return title;
            }
            return code;
        }
    }

    /** One rendering pass: the chosen language, the cards by chapter and the reference index. */
    private static final class Page {

        private final boolean german;
        private final Optional<String> label;
        private final Optional<String> description;
        private final ModelViews.Views views;

        private final List<ModelCard> actors;
        private final List<ModelCard> roles;
        private final List<ModelCard> useCases;
        private final List<ModelCard> functional = new ArrayList<>();
        private final List<ModelCard> quality = new ArrayList<>();
        private final List<ModelCard> constraints;
        private final List<ModelCard> terms;

        private final Map<String, Target> targets = new HashMap<>();
        private final Map<String, List<Target>> useCasesByRequirement = new HashMap<>();
        private final Map<String, List<RoleParticipation>> useCasesByRole = new HashMap<>();
        private final StringBuilder out = new StringBuilder(64 * 1024);
        /** Glossary terms already linked in the current card or chapter; later mentions read as plain text. */
        private final Set<String> linkedTerms = new HashSet<>();

        Page(final boolean german, final Optional<String> label, final Optional<String> description,
                final ModelViews.Views views) {
            this.german = german;
            this.label = label;
            this.description = description;
            this.views = views;
            this.actors = cards(ActorCards.SECTION_TITLE);
            this.roles = cards(RoleCards.SECTION_TITLE);
            this.useCases = cards(UseCaseCards.SECTION_TITLE);
            this.constraints = cards(ConstraintCards.SECTION_TITLE);
            this.terms = cards(TermCards.SECTION_TITLE).stream()
                    .sorted(Comparator.comparing(ModelCard::title, String.CASE_INSENSITIVE_ORDER))
                    .toList();
            for (final ModelCard card : cards(RequirementCards.SECTION_TITLE)) {
                (isQuality(card) ? quality : functional).add(card);
            }
            indexTargets();
            indexUseCases();
        }

        private record RoleParticipation(Target useCase, boolean primary) {
        }

        private List<ModelCard> cards(final String sectionTitle) {
            return views.sections().stream()
                    .filter(section -> section.title().equals(sectionTitle))
                    .flatMap(section -> section.cards().stream())
                    .toList();
        }

        private static boolean isQuality(final ModelCard card) {
            return card.badges().stream()
                    .filter(badge -> badge.kind() == Badge.Kind.Known.TYPE)
                    .anyMatch(badge -> NFR_TYPE.equalsIgnoreCase(badge.value()));
        }

        private String text(final SpecificationTexts key) {
            return key.in(german);
        }

        private String esc(final SpecificationTexts key) {
            return escape(key.in(german));
        }

        // ---- reference index -------------------------------------------------------------------

        private void indexTargets() {
            final Set<String> anchors = new HashSet<>();
            register(anchors, actors, Kind.ACTOR);
            register(anchors, roles, Kind.ROLE);
            register(anchors, useCases, Kind.USE_CASE);
            register(anchors, functional, Kind.FUNCTIONAL);
            register(anchors, quality, Kind.QUALITY);
            register(anchors, constraints, Kind.CONSTRAINT);
            register(anchors, terms, Kind.TERM);
        }

        private void register(final Set<String> anchors, final List<ModelCard> cards, final Kind kind) {
            for (final ModelCard card : cards) {
                final String anchor = uniqueAnchor(anchors, card.code());
                targets.putIfAbsent(card.iri(), new Target(anchor, card.code(), card.title(), kind));
            }
        }

        private static String uniqueAnchor(final Set<String> taken, final String code) {
            final String slug = code.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
            final String base = "a-" + (slug.isEmpty() ? "item" : slug);
            String candidate = base;
            for (int suffix = 2; !taken.add(candidate); suffix++) {
                candidate = base + "-" + suffix;
            }
            return candidate;
        }

        private Target target(final ModelCard card) {
            return targets.get(card.iri());
        }

        private void indexUseCases() {
            for (final ModelCard useCase : useCases) {
                final Target self = target(useCase);
                final Set<String> seenRequirements = new HashSet<>();
                for (final Block block : useCase.blocks()) {
                    if (block instanceof Block.Flow flow) {
                        flow.steps().stream().flatMap(step -> step.realises().stream())
                                .map(Ref::iri)
                                .filter(seenRequirements::add)
                                .forEach(iri -> useCasesByRequirement.computeIfAbsent(iri, key -> new ArrayList<>())
                                        .add(self));
                    }
                    if (block instanceof Block.Refs refs) {
                        final boolean primary = refs.label().equals(CardLabels.PRIMARY_ROLE);
                        if (primary || refs.label().equals(CardLabels.SUPPORTING_ROLES)) {
                            refs.refs().forEach(ref -> useCasesByRole
                                    .computeIfAbsent(ref.iri(), key -> new ArrayList<>())
                                    .add(new RoleParticipation(self, primary)));
                        }
                    }
                }
            }
        }

        // ---- document --------------------------------------------------------------------------

        String render() {
            final String title = text(SpecificationTexts.PAGE_TITLE) + label.map(name -> " " + name).orElse("");
            out.append("<!doctype html><html lang=\"").append(german ? "de" : "en").append("\"><head>")
                    .append("<meta charset=\"utf-8\"><meta name=\"viewport\""
                            + " content=\"width=device-width,initial-scale=1\">")
                    .append("<title>").append(escape(title)).append("</title><style>\n").append(CSS)
                    .append("</style></head><body>\n");
            appendBar(title);
            out.append("<div class=\"layout\" id=\"layout\" data-hint=\"")
                    .append(escape(text(SpecificationTexts.DETAIL_HINT))).append("\" data-open-detail=\"")
                    .append(escape(text(SpecificationTexts.OPEN_DETAIL))).append("\">\n");
            appendToc(true);
            out.append("<div class=\"grip\" id=\"gripL\" role=\"separator\" aria-orientation=\"vertical\""
                    + " aria-label=\"").append(esc(SpecificationTexts.GRIP_TOC)).append("\" tabindex=\"0\"></div>\n");
            out.append("<main><div class=\"doc\">\n<h1>").append(escape(title)).append("</h1>\n");
            appendFailures();
            appendToc(false);
            appendIntroduction();
            appendParticipants();
            appendUseCases();
            appendRequirements(CH_FUNCTIONAL, "4", SpecificationTexts.CH_FUNCTIONAL, functional,
                    SpecificationTexts.NONE_FUNCTIONAL);
            appendRequirements(CH_QUALITY, "5", SpecificationTexts.CH_QUALITY, quality,
                    SpecificationTexts.NONE_QUALITY);
            appendConstraints();
            appendGlossary();
            out.append("</div></main>\n");
            appendDetailPane();
            out.append("</div>\n<div id=\"pop\" role=\"tooltip\" hidden></div>\n");
            appendHelp();
            out.append("<script>\n").append(JS).append("</script></body></html>\n");
            return out.toString();
        }

        private void appendBar(final String title) {
            out.append("<header class=\"bar\"><button id=\"menuBtn\" type=\"button\" aria-label=\"")
                    .append(esc(SpecificationTexts.MENU_OPEN)).append("\">")
                    .append(text(SpecificationTexts.MENU_BUTTON_HTML)).append("</button><span class=\"hdr\">")
                    .append(escape(title)).append("</span><span class=\"sp\"></span>")
                    .append("<button id=\"foldAll\" type=\"button\" data-collapse=\"")
                    .append(esc(SpecificationTexts.COLLAPSE_ALL)).append("\">")
                    .append(esc(SpecificationTexts.EXPAND_ALL)).append("</button>")
                    .append("<button id=\"helpBtn\" type=\"button\" aria-haspopup=\"dialog\">")
                    .append(esc(SpecificationTexts.HELP_BUTTON)).append("</button></header>\n");
        }

        private void appendFailures() {
            if (views.failures().isEmpty()) {
                return;
            }
            out.append("<div class=\"notice\" role=\"alert\"><p>").append(esc(SpecificationTexts.FAILURE_NOTICE))
                    .append("</p><ul>");
            for (final String failure : views.failures()) {
                final int colon = failure.indexOf(':');
                final String part = colon > 0 ? failure.substring(0, colon) : failure;
                out.append("<li>").append(escape(part)).append("</li>");
            }
            out.append("</ul></div>\n");
        }

        // ---- table of contents -----------------------------------------------------------------

        private record TocEntry(String anchor, String text, List<TocEntry> children) {
        }

        private List<TocEntry> toc() {
            final List<TocEntry> entries = new ArrayList<>();
            entries.add(new TocEntry(CH_INTRO, "1 " + text(SpecificationTexts.CH_INTRO), List.of()));
            entries.add(new TocEntry(CH_PARTICIPANTS, "2 " + text(SpecificationTexts.CH_PARTICIPANTS), List.of(
                    new TocEntry(CH_ACTORS, "2.1 " + text(SpecificationTexts.CH_ACTORS), List.of()),
                    new TocEntry(CH_ROLES, "2.2 " + text(SpecificationTexts.CH_ROLES), List.of()))));
            entries.add(chapterEntry(CH_USE_CASES, "3", SpecificationTexts.CH_USE_CASES, useCases));
            entries.add(chapterEntry(CH_FUNCTIONAL, "4", SpecificationTexts.CH_FUNCTIONAL, functional));
            entries.add(chapterEntry(CH_QUALITY, "5", SpecificationTexts.CH_QUALITY, quality));
            entries.add(chapterEntry(CH_CONSTRAINTS, "6", SpecificationTexts.CH_CONSTRAINTS, constraints));
            entries.add(new TocEntry(CH_GLOSSARY, "7 " + text(SpecificationTexts.CH_GLOSSARY), List.of()));
            return entries;
        }

        private TocEntry chapterEntry(
                final String anchor, final String number, final SpecificationTexts title,
                final List<ModelCard> cards) {
            final List<TocEntry> children = new ArrayList<>();
            for (int index = 0; index < cards.size(); index++) {
                final ModelCard card = cards.get(index);
                children.add(new TocEntry(target(card).anchor(), number + "." + (index + 1) + " " + card.title(),
                        List.of()));
            }
            return new TocEntry(anchor, number + " " + text(title), children);
        }

        /** @param interactive the sidebar (with collapse button and rail) rather than the print copy */
        private void appendToc(final boolean interactive) {
            if (interactive) {
                out.append("<nav id=\"toc\" class=\"toc\" aria-label=\"").append(esc(SpecificationTexts.TOC_LABEL))
                        .append("\">\n<div class=\"ph\"><strong class=\"tocttl\">")
                        .append(esc(SpecificationTexts.TOC_TITLE))
                        .append("</strong><button id=\"colL\" class=\"colbtn\" type=\"button\""
                                + " aria-expanded=\"true\" aria-controls=\"toc\" aria-label=\"")
                        .append(esc(SpecificationTexts.TOC_COLLAPSE)).append("\">&lsaquo;</button></div>");
            } else {
                out.append("<nav class=\"ptoc\" aria-label=\"").append(esc(SpecificationTexts.TOC_LABEL))
                        .append("\"><strong class=\"tocttl\">").append(esc(SpecificationTexts.TOC_TITLE))
                        .append("</strong>");
            }
            appendTocList(toc(), true);
            if (interactive) {
                out.append("<button class=\"rail\" type=\"button\" aria-expanded=\"false\" aria-controls=\"toc\""
                        + " aria-label=\"").append(esc(SpecificationTexts.TOC_EXPAND)).append("\">")
                        .append(esc(SpecificationTexts.TOC_TITLE)).append("</button>");
            }
            out.append("</nav>\n");
        }

        private void appendTocList(final List<TocEntry> entries, final boolean ordered) {
            out.append(ordered ? "<ol>" : "<ul>");
            for (final TocEntry entry : entries) {
                out.append("<li><a href=\"#").append(entry.anchor()).append("\">").append(escape(entry.text()))
                        .append("</a>");
                if (!entry.children().isEmpty()) {
                    appendTocList(entry.children(), false);
                }
                out.append("</li>");
            }
            out.append(ordered ? "</ol>" : "</ul>");
        }

        // ---- chapters --------------------------------------------------------------------------

        private void heading(final String anchor, final String number, final SpecificationTexts title,
                final boolean first) {
            linkedTerms.clear();
            out.append("<h2 id=\"").append(anchor).append('"').append(first ? " class=\"first\"" : "")
                    .append("><span class=\"num\">").append(number).append("</span> ").append(esc(title))
                    .append("</h2>\n");
        }

        private void placeholder(final SpecificationTexts message) {
            out.append("<p class=\"empty\">").append(esc(message)).append("</p>\n");
        }

        private void appendIntroduction() {
            heading(CH_INTRO, "1", SpecificationTexts.CH_INTRO, true);
            label.ifPresent(name -> out.append("<p>").append(esc(SpecificationTexts.INTRO_DESCRIBES))
                    .append(" <strong>").append(escape(name)).append("</strong>.</p>\n"));
            description.ifPresent(text -> appendParts(ProseMarkdown.parts(text, RichText::plain)));
            out.append(text(SpecificationTexts.INTRO_HINT_HTML)).append('\n')
                    .append(text(SpecificationTexts.PRIORITY_HINT_HTML)).append('\n');
        }

        private void appendParticipants() {
            heading(CH_PARTICIPANTS, "2", SpecificationTexts.CH_PARTICIPANTS, false);
            out.append("<h3 id=\"").append(CH_ACTORS).append("\"><span class=\"num\">2.1</span> ")
                    .append(esc(SpecificationTexts.CH_ACTORS)).append("</h3>\n");
            if (actors.isEmpty()) {
                placeholder(SpecificationTexts.NONE_ACTORS);
            }
            for (int index = 0; index < actors.size(); index++) {
                appendActor(actors.get(index), "2.1." + (index + 1));
            }
            out.append("<h3 id=\"").append(CH_ROLES).append("\"><span class=\"num\">2.2</span> ")
                    .append(esc(SpecificationTexts.CH_ROLES)).append("</h3>\n");
            if (roles.isEmpty()) {
                placeholder(SpecificationTexts.NONE_ROLES);
            }
            for (int index = 0; index < roles.size(); index++) {
                appendRole(roles.get(index), "2.2." + (index + 1));
            }
        }

        private void appendActor(final ModelCard card, final String number) {
            open("role", "h4", card, number);
            proseOf(card, CardLabels.DESCRIPTION).ifPresent(prose -> appendParts(prose.parts()));
            out.append("</section>\n");
        }

        private void appendRole(final ModelCard card, final String number) {
            open("role", "h4", card, number);
            proseOf(card, CardLabels.DESCRIPTION).ifPresent(prose -> appendParts(prose.parts()));
            proseOf(card, CardLabels.FILLED_BY)
                    .filter(prose -> !prose.source().equals(CardLabels.UNFILLED))
                    .ifPresent(prose -> labelled(SpecificationTexts.FILLED_BY, prose));
            final List<RoleParticipation> participation = useCasesByRole.getOrDefault(card.iri(), List.of());
            if (!participation.isEmpty()) {
                lead(SpecificationTexts.INVOLVED_IN_FLOWS);
                out.append("<ul class=\"refs\">");
                for (final RoleParticipation entry : participation) {
                    out.append("<li>").append(link(entry.useCase(), entry.useCase().listText())).append(" (")
                            .append(esc(entry.primary() ? SpecificationTexts.ROLE_PRIMARY
                                    : SpecificationTexts.ROLE_SUPPORTING))
                            .append(")</li>");
                }
                out.append("</ul>\n");
            }
            out.append("</section>\n");
        }

        private void appendUseCases() {
            heading(CH_USE_CASES, "3", SpecificationTexts.CH_USE_CASES, false);
            if (useCases.isEmpty()) {
                placeholder(SpecificationTexts.NONE_USE_CASES);
            }
            for (int index = 0; index < useCases.size(); index++) {
                appendUseCase(useCases.get(index), "3." + (index + 1));
            }
        }

        private void appendUseCase(final ModelCard card, final String number) {
            open("uc", "h3", card, number);
            proseOf(card, CardLabels.GOAL).ifPresent(prose -> labelled(SpecificationTexts.GOAL, prose));
            proseOf(card, CardLabels.SCOPE).ifPresent(prose -> labelled(SpecificationTexts.SCOPE, prose));
            proseOf(card, CardLabels.TRIGGER).ifPresent(prose -> labelled(SpecificationTexts.TRIGGER, prose));
            proseOf(card, CardLabels.PRECONDITION)
                    .ifPresent(prose -> labelled(SpecificationTexts.PRECONDITION, prose));
            appendUseCaseRoles(card);
            card.blocks().stream().filter(Block.Flow.class::isInstance).map(Block.Flow.class::cast).findFirst()
                    .ifPresent(this::appendFlow);
            bulletsOf(card, UseCaseCards.EXTENSIONS_LABEL).ifPresent(extensions -> {
                openFold(SpecificationTexts.EXTENSIONS, Optional.of(extensions.items().size()),
                        target(card).anchor() + "-extensions");
                appendBulletList(extensions);
                out.append("</details>\n");
            });
            proseOf(card, CardLabels.POSTCONDITION).ifPresent(prose -> labelled(SpecificationTexts.RESULT, prose));
            appendRealisedBy(card);
            out.append("</section>\n");
        }

        private void appendUseCaseRoles(final ModelCard card) {
            final List<String> items = new ArrayList<>();
            for (final Block block : card.blocks()) {
                if (!(block instanceof Block.Refs refs)) {
                    continue;
                }
                final boolean primary = refs.label().equals(CardLabels.PRIMARY_ROLE);
                if (!primary && !refs.label().equals(CardLabels.SUPPORTING_ROLES)) {
                    continue;
                }
                for (final Ref ref : refs.refs()) {
                    referenceText(ref).ifPresent(html -> items.add(html + " ("
                            + esc(primary ? SpecificationTexts.ROLE_PRIMARY : SpecificationTexts.ROLE_SUPPORTING)
                            + ")"));
                }
            }
            if (items.isEmpty()) {
                return;
            }
            lead(SpecificationTexts.ROLES_INVOLVED);
            out.append("<ul class=\"refs\">");
            items.forEach(item -> out.append("<li>").append(item).append("</li>"));
            out.append("</ul>\n");
        }

        private void appendFlow(final Block.Flow flow) {
            if (flow.steps().isEmpty()) {
                return;
            }
            lead(SpecificationTexts.FLOW);
            out.append("<ol>");
            flow.steps().stream().sorted(Comparator.comparingInt(FlowStep::position))
                    .forEach(step -> {
                        out.append("<li>").append(inline(step.text()));
                        appendStepRequirements(step);
                        out.append("</li>");
                    });
            out.append("</ol>\n");
        }

        /** The requirements a step realises, as small linked codes under the step; nothing for a step without edge. */
        private void appendStepRequirements(final FlowStep step) {
            final List<String> markers = new ArrayList<>();
            for (final Ref ref : step.realises()) {
                final Target target = targets.get(ref.iri());
                if (target != null) {
                    markers.add("<a class=\"ref\" href=\"#" + target.anchor() + "\" data-ref=\"" + target.anchor()
                            + "\" title=\"" + escape(target.title()) + "\">" + escape(target.code()) + "</a>");
                }
            }
            if (!markers.isEmpty()) {
                out.append("<span class=\"stepreqs\">").append(String.join(", ", markers)).append("</span>");
            }
        }

        private void appendRealisedBy(final ModelCard card) {
            final List<String> items = new ArrayList<>();
            final Set<String> seen = new HashSet<>();
            for (final Block block : card.blocks()) {
                if (block instanceof Block.Flow flow) {
                    flow.steps().stream().flatMap(step -> step.realises().stream())
                            .filter(ref -> seen.add(ref.iri()))
                            .forEach(ref -> referenceText(ref).ifPresent(items::add));
                }
            }
            appendReferenceList(SpecificationTexts.REALISED_BY, items);
        }

        private void appendRequirements(
                final String anchor, final String number, final SpecificationTexts title,
                final List<ModelCard> cards, final SpecificationTexts none) {
            heading(anchor, number, title, false);
            if (cards.isEmpty()) {
                placeholder(none);
            }
            for (int index = 0; index < cards.size(); index++) {
                appendRequirement(cards.get(index), number + "." + (index + 1));
            }
        }

        private void appendRequirement(final ModelCard card, final String number) {
            open("req", "h3", card, number);
            appendMeta(card);
            proseOf(card, CardLabels.DESCRIPTION).ifPresent(prose -> appendParts(prose.parts()));
            proseOf(card, CardLabels.RATIONALE).ifPresent(prose -> {
                openFold(SpecificationTexts.RATIONALE, Optional.empty(), target(card).anchor() + "-rationale");
                appendParts(prose.parts());
                out.append("</details>\n");
            });
            proseOf(card, CardLabels.QUALITY_CATEGORY)
                    .ifPresent(prose -> labelled(SpecificationTexts.QUALITY_CATEGORY, prose));
            bulletsOf(card, RequirementCards.ACCEPTANCE_CRITERIA_LABEL).ifPresent(criteria -> {
                final String anchor = target(card).anchor();
                openFold(SpecificationTexts.ACCEPTANCE, Optional.of(criteria.items().size()), anchor + "-criteria");
                appendCriteria(criteria, anchor, card.code());
                out.append("</details>\n");
            });
            final List<String> usedIn = useCasesByRequirement.getOrDefault(card.iri(), List.of()).stream()
                    .map(useCase -> link(useCase, useCase.listText()))
                    .toList();
            appendReferenceList(SpecificationTexts.USED_IN_FLOWS, usedIn);
            out.append("</section>\n");
        }

        /** One plain line, "Priority: Must - Status: Proposed"; the type badge is not shown. */
        private void appendMeta(final ModelCard card) {
            final List<String> bits = new ArrayList<>();
            badge(card, Badge.Kind.Known.PRIORITY).ifPresent(value ->
                    bits.add(text(SpecificationTexts.PRIORITY) + ": " + priorityText(value)));
            badge(card, Badge.Kind.Known.STATUS).ifPresent(value ->
                    bits.add(text(SpecificationTexts.STATUS) + ": " + statusText(value)));
            if (!bits.isEmpty()) {
                out.append("<p class=\"meta\">").append(escape(String.join(" · ", bits))).append("</p>\n");
            }
        }

        private static Optional<String> badge(final ModelCard card, final Badge.Kind kind) {
            return card.badges().stream().filter(badge -> badge.kind() == kind).map(Badge::value).findFirst();
        }

        private String priorityText(final String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "must have" -> text(SpecificationTexts.PRIORITY_MUST);
                case "should have" -> text(SpecificationTexts.PRIORITY_SHOULD);
                case "could have" -> text(SpecificationTexts.PRIORITY_COULD);
                case "wont have", "won't have" -> text(SpecificationTexts.PRIORITY_WONT);
                default -> value;
            };
        }

        private String statusText(final String value) {
            return switch (value.toLowerCase(Locale.ROOT)) {
                case "proposed" -> text(SpecificationTexts.STATUS_PROPOSED);
                case "accepted" -> text(SpecificationTexts.STATUS_ACCEPTED);
                default -> value;
            };
        }

        private void appendConstraints() {
            heading(CH_CONSTRAINTS, "6", SpecificationTexts.CH_CONSTRAINTS, false);
            if (constraints.isEmpty()) {
                placeholder(SpecificationTexts.NONE_CONSTRAINTS);
            }
            for (int index = 0; index < constraints.size(); index++) {
                final ModelCard card = constraints.get(index);
                open("req", "h3", card, "6." + (index + 1));
                proseOf(card, CardLabels.STATEMENT).ifPresent(prose -> appendParts(prose.parts()));
                out.append("</section>\n");
            }
        }

        private void appendGlossary() {
            heading(CH_GLOSSARY, "7", SpecificationTexts.CH_GLOSSARY, false);
            if (terms.isEmpty()) {
                placeholder(SpecificationTexts.NONE_GLOSSARY);
                return;
            }
            out.append("<dl>\n");
            for (final ModelCard card : terms) {
                final Target target = target(card);
                final Optional<Block.Prose> definition = proseOf(card, CardLabels.DEFINITION);
                linkedTerms.clear();
                out.append("<dt id=\"").append(target.anchor()).append("\" data-kind=\"")
                        .append(esc(SpecificationTexts.KIND_TERM)).append("\" data-title=\"")
                        .append(escape(card.title())).append("\" data-def=\"")
                        .append(escape(definition.map(Page::popupDefinition).orElse(""))).append("\">")
                        .append(escape(card.title())).append("</dt><dd>");
                definition.ifPresent(prose -> appendParts(prose.parts()));
                out.append("</dd>\n");
            }
            out.append("</dl>\n");
        }

        /** The definition as one line, cut at a word boundary - the pop-up is a preview. */
        private static String popupDefinition(final Block.Prose prose) {
            final List<String> pieces = new ArrayList<>();
            for (final ProsePart part : prose.parts()) {
                switch (part) {
                    case ProsePart.Paragraph paragraph -> pieces.add(paragraph.text().text());
                    case ProsePart.Bullets bullets -> bullets.items().forEach(item -> pieces.add(item.text()));
                }
            }
            final String flat = String.join(" ", pieces).replaceAll("\\s+", " ").strip();
            if (flat.length() <= POPUP_DEFINITION_LIMIT) {
                return flat;
            }
            final String cut = flat.substring(0, POPUP_DEFINITION_LIMIT);
            final int space = cut.lastIndexOf(' ');
            return (space > 0 ? cut.substring(0, space) : cut) + " …";
        }

        private void appendDetailPane() {
            out.append("<div class=\"grip\" id=\"gripR\" role=\"separator\" aria-orientation=\"vertical\""
                    + " aria-label=\"").append(esc(SpecificationTexts.GRIP_DETAIL)).append("\" tabindex=\"0\"></div>\n");
            out.append("<aside id=\"detail\" aria-label=\"").append(esc(SpecificationTexts.DETAIL_LABEL))
                    .append("\"><div class=\"dhead\"><button id=\"backBtn\" type=\"button\">")
                    .append(text(SpecificationTexts.BACK_HTML)).append("</button><a id=\"inDoc\" href=\"#\">")
                    .append(esc(SpecificationTexts.SHOW_IN_DOCUMENT)).append("</a><span class=\"sp\"></span>")
                    .append("<button id=\"colR\" class=\"colbtn\" type=\"button\" aria-expanded=\"true\""
                            + " aria-controls=\"detail\" aria-label=\"")
                    .append(esc(SpecificationTexts.DETAIL_COLLAPSE)).append("\">&rsaquo;</button>")
                    .append("<button id=\"closeDetail\" type=\"button\" aria-label=\"")
                    .append(esc(SpecificationTexts.DETAIL_CLOSE)).append("\">&#10005;</button></div>")
                    .append("<div id=\"dbody\" aria-live=\"polite\"></div>")
                    .append("<button class=\"rail\" type=\"button\" aria-expanded=\"false\""
                            + " aria-controls=\"detail\" aria-label=\"")
                    .append(esc(SpecificationTexts.DETAIL_EXPAND)).append("\">")
                    .append(esc(SpecificationTexts.DETAIL_RAIL)).append("</button></aside>\n");
        }

        private void appendHelp() {
            out.append("<div id=\"helpWrap\" hidden><div role=\"dialog\" aria-modal=\"true\""
                    + " aria-labelledby=\"helpTitle\" tabindex=\"-1\"><button id=\"helpClose\" type=\"button\">")
                    .append(esc(SpecificationTexts.CLOSE)).append("</button><h2 id=\"helpTitle\">")
                    .append(esc(SpecificationTexts.HELP_TITLE)).append("</h2>")
                    .append(text(SpecificationTexts.HELP_BODY_HTML)).append("</div></div>\n");
        }

        // ---- building blocks -------------------------------------------------------------------

        /** Opens a card's element: anchor, the data the script builds pop-ups and the detail pane from, heading. */
        private void open(final String cssClass, final String headingTag, final ModelCard card, final String number) {
            final Target target = target(card);
            linkedTerms.clear();
            out.append("<section class=\"").append(cssClass).append("\" id=\"").append(target.anchor())
                    .append("\" data-kind=\"").append(esc(target.kind().label))
                    .append("\" data-code=\"").append(target.kind().showsCode ? escape(card.code()) : "")
                    .append("\" data-title=\"").append(escape(card.title())).append("\"><")
                    .append(headingTag).append("><span class=\"num\">").append(number).append("</span> ")
                    .append(escape(card.title()));
            if (target.kind().showsCode && !card.code().isBlank()) {
                out.append(" <span class=\"code\">").append(escape(card.code())).append("</span>");
            }
            out.append("</").append(headingTag).append(">\n");
        }

        private static Optional<Block.Prose> proseOf(final ModelCard card, final String label) {
            return card.blocks().stream()
                    .filter(Block.Prose.class::isInstance).map(Block.Prose.class::cast)
                    .filter(prose -> prose.label().equals(label) && !prose.parts().isEmpty())
                    .findFirst();
        }

        private static Optional<Block.Bullets> bulletsOf(final ModelCard card, final String label) {
            return card.blocks().stream()
                    .filter(Block.Bullets.class::isInstance).map(Block.Bullets.class::cast)
                    .filter(bullets -> bullets.label().equals(label) && !bullets.items().isEmpty())
                    .findFirst();
        }

        private void lead(final SpecificationTexts label) {
            out.append("<p class=\"lead\"><strong>").append(esc(label)).append("</strong></p>");
        }

        /** Opens a closed {@code <details>} block; the caller appends the content and the closing tag. */
        private void openFold(final SpecificationTexts label, final Optional<Integer> count, final String id) {
            out.append("<details class=\"fold\" id=\"").append(id).append("\"><summary>").append(esc(label))
                    .append(count.map(number -> " (" + number + ")").orElse("")).append("</summary>");
        }

        /** "Label: text" on one line for a single paragraph, else the label above the parts. */
        private void labelled(final SpecificationTexts label, final Block.Prose prose) {
            if (prose.parts().size() == 1 && prose.parts().getFirst() instanceof ProsePart.Paragraph paragraph) {
                out.append("<p><strong>").append(esc(label)).append(":</strong> ")
                        .append(inline(paragraph.text())).append("</p>\n");
                return;
            }
            out.append("<p class=\"lead\"><strong>").append(esc(label)).append(":</strong></p>");
            appendParts(prose.parts());
        }

        private void appendParts(final List<ProsePart> parts) {
            for (final ProsePart part : parts) {
                switch (part) {
                    case ProsePart.Paragraph paragraph ->
                        out.append("<p>").append(inline(paragraph.text())).append("</p>\n");
                    case ProsePart.Bullets bullets -> {
                        out.append("<ul>");
                        bullets.items().forEach(item -> out.append("<li>").append(inline(item)).append("</li>"));
                        out.append("</ul>\n");
                    }
                }
            }
        }

        private void appendBulletList(final Block.Bullets bullets) {
            out.append("<ul>");
            bullets.items().stream().sorted(Comparator.comparingInt(BulletItem::position))
                    .forEach(item -> out.append("<li>").append(inline(item.text())).append("</li>"));
            out.append("</ul>\n");
        }

        /**
         * Acceptance criteria as a numbered list: the number is "CODE.position", the position being the
         * stored one, so it stays the same between runs on an unchanged store. A criterion whose text starts
         * with "Beispiel:" or "Example:" is an example and goes into its own closed group.
         */
        private void appendCriteria(final Block.Bullets criteria, final String anchor, final String code) {
            final List<BulletItem> sorted = criteria.items().stream()
                    .sorted(Comparator.comparingInt(BulletItem::position)).toList();
            final List<BulletItem> rules = sorted.stream().filter(item -> !isExample(item)).toList();
            final List<BulletItem> examples = sorted.stream().filter(Page::isExample).toList();
            appendCriteriaList(rules, anchor, code, false);
            if (!examples.isEmpty()) {
                openFold(SpecificationTexts.EXAMPLES, Optional.of(examples.size()), anchor + "-examples");
                appendCriteriaList(examples, anchor, code, true);
                out.append("</details>");
            }
        }

        private void appendCriteriaList(final List<BulletItem> items, final String anchor, final String code,
                final boolean example) {
            if (items.isEmpty()) {
                return;
            }
            out.append("<ol class=\"criteria\">");
            for (final BulletItem item : items) {
                final String id = anchor + "-c" + item.position();
                final String number = (code.isBlank() ? "" : code + ".") + item.position();
                out.append("<li id=\"").append(id).append('"').append(example ? " class=\"example\"" : "")
                        .append("><a class=\"cid\" href=\"#")
                        .append(id).append("\">").append(escape(number)).append("</a> ")
                        .append(inline(item.text())).append("</li>");
            }
            out.append("</ol>\n");
        }

        private static boolean isExample(final BulletItem item) {
            return item.text().spans().stream().findFirst()
                    .filter(Span.Plain.class::isInstance).map(span -> ((Span.Plain) span).text().stripLeading())
                    .map(text -> startsWithIgnoreCase(text, "Beispiel:") || startsWithIgnoreCase(text, "Example:"))
                    .orElse(false);
        }

        private static boolean startsWithIgnoreCase(final String text, final String prefix) {
            return text.regionMatches(true, 0, prefix, 0, prefix.length());
        }

        private void appendReferenceList(final SpecificationTexts label, final List<String> items) {
            if (items.isEmpty()) {
                return;
            }
            lead(label);
            out.append("<ul class=\"refs\">");
            items.forEach(item -> out.append("<li>").append(item).append("</li>"));
            out.append("</ul>\n");
        }

        // ---- text and references ---------------------------------------------------------------

        private String link(final Target target, final String text) {
            return "<a class=\"ref\" href=\"#" + target.anchor() + "\" data-ref=\"" + target.anchor() + "\">"
                    + escape(text) + "</a>";
        }

        /**
         * A list-position reference: "CODE Title" as a link when the document holds the target,
         * its label as plain text when it does not, and nothing when the label is nothing but the
         * target's IRI.
         */
        private Optional<String> referenceText(final Ref ref) {
            final Target target = targets.get(ref.iri());
            if (target != null) {
                return Optional.of(link(target, target.listText()));
            }
            if (ref.label().equals(ref.iri())) {
                return Optional.empty();
            }
            return Optional.of(escape(ref.label()));
        }

        private String inline(final RichText richText) {
            final StringBuilder html = new StringBuilder();
            for (final Span span : richText.spans()) {
                switch (span) {
                    case Span.Plain plain -> html.append(escape(plain.text()));
                    case Span.TermLink term -> html.append(termMention(term.text(), term.iri()));
                    case Span.TermGap term -> html.append(termMention(term.text(), term.iri()));
                    case Span.CodeRef reference -> {
                        final Target target = targets.get(reference.iri());
                        html.append(target == null ? escape(reference.text())
                                : link(target, target.inlineText()));
                    }
                    case Span.Emphasis emphasis -> {
                        final String tag = emphasis.style() == Span.Style.STRONG ? "strong" : "em";
                        html.append('<').append(tag).append('>').append(inline(emphasis.content()))
                                .append("</").append(tag).append('>');
                    }
                    case Span.Code code -> html.append("<code>").append(escape(code.text())).append("</code>");
                }
            }
            return html.toString();
        }

        /**
         * A glossary mention, linked when the glossary chapter holds the term. An edge-less mention
         * ({@link Span.TermGap}) reads like a linked one here: a missing modelling edge is a
         * finding for the developer report, not for this document's reader. Only the first mention of
         * a term within a card (or chapter) is a link; later ones read as plain text.
         */
        private String termMention(final String text, final String iri) {
            final Target target = targets.get(iri);
            if (target == null || target.kind() != Kind.TERM || !linkedTerms.add(iri)) {
                return escape(text);
            }
            return "<a class=\"term\" href=\"#" + target.anchor() + "\" data-ref=\"" + target.anchor() + "\">"
                    + escape(text) + "</a>";
        }
    }
}
