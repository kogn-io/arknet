// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.DisplayLocale;

/** Renders hand-built {@link ModelViews.Views} of an invented library-lending domain. */
class SpecificationRendererTest {

    private static final String IRI_PREFIX = "urn:test:";
    private static final DisplayLocale GERMAN = new DisplayLocale(Locale.GERMAN, Locale.ENGLISH);
    private static final DisplayLocale ENGLISH = DisplayLocale.DEFAULT;

    private final SpecificationRenderer renderer = new SpecificationRenderer();

    // ---- fixture ---------------------------------------------------------------------------------

    private static ModelCard card(final String code, final String title, final List<Badge> badges,
            final Block... blocks) {
        return new ModelCard(code, title, IRI_PREFIX + code, badges, List.of(blocks));
    }

    private static ModelSection section(final String title, final ModelCard... cards) {
        return new ModelSection(title, title.toLowerCase(Locale.ROOT).replace(' ', '-'), "subtitle", List.of(cards));
    }

    private static Badge type(final String value) {
        return new Badge(Badge.Kind.Known.TYPE, value);
    }

    private static ModelViews.Views fixture() {
        final ModelCard member = card("ACTOR-1", "Library member", List.of(),
                Block.Prose.plain(CardLabels.DESCRIPTION, "Borrows media."));
        final ModelCard librarian = card("ROLE-1", "Librarian", List.of(),
                Block.Prose.plain(CardLabels.DESCRIPTION, "Hands out media."),
                Block.Prose.plain(CardLabels.FILLED_BY, "ACTOR-1 (Library member)"));
        final ModelCard vacantRole = card("ROLE-2", "Archivist", List.of(),
                Block.Prose.plain(CardLabels.FILLED_BY, CardLabels.UNFILLED));
        final ModelCard lending = card("UC-1", "Lend a book", List.of(),
                new Block.Prose(CardLabels.GOAL, "g", List.of(new ProsePart.Paragraph(new RichText(List.of(
                        new Span.Plain("A "),
                        new Span.TermLink("loan", IRI_PREFIX + "TERM-1", "TERM-1"),
                        new Span.Plain(" is recorded, see "),
                        new Span.CodeRef("FR-1", IRI_PREFIX + "FR-1", "FR-1", "Record loans"),
                        new Span.Plain(" and "),
                        new Span.CodeRef("FR-2", IRI_PREFIX + "FR-2", "FR-2",
                                "A requirement whose title is far longer than forty characters"),
                        new Span.Plain(" and "),
                        new Span.CodeRef("ADR-1", IRI_PREFIX + "ADR-1", "ADR-1", "Use a relational store"),
                        new Span.Plain(".")))))),
                Block.Prose.plain(CardLabels.TRIGGER, "A member asks."),
                new Block.Refs(CardLabels.PRIMARY_ROLE, List.of(new Ref("Librarian", "ROLE-1", IRI_PREFIX + "ROLE-1"))),
                new Block.Flow(CardLabels.MAIN_FLOW, List.of(
                        new FlowStep(1, RichText.plain("Scan the card."),
                                List.of(Ref.of("FR-1", IRI_PREFIX + "FR-1"))),
                        new FlowStep(2, RichText.plain("Hand out the book."), List.of()))),
                new Block.Bullets(UseCaseCards.EXTENSIONS_LABEL, List.of(
                        new BulletItem(1, RichText.plain("The card is expired.")))));
        final ModelCard record = card("FR-1", "Record loans",
                List.of(type("FR"), new Badge(Badge.Kind.Known.STATUS, "Proposed"),
                        new Badge(Badge.Kind.Known.PRIORITY, "Must have")),
                Block.Prose.plain(CardLabels.DESCRIPTION, "Every loan [assumption: one per copy] is stored."),
                Block.Prose.plain(CardLabels.RATIONALE, "Overdue loans must be traceable."),
                new Block.Bullets(RequirementCards.ACCEPTANCE_CRITERIA_LABEL, List.of(
                        new BulletItem(1, RichText.plain("A loan has a due date.")),
                        new BulletItem(2, RichText.plain("A loan names the member.")),
                        new BulletItem(3, RichText.plain("Example: a loan of 14 days ends on day 14.")))));
        final ModelCard longTitle = card("FR-2", "A requirement whose title is far longer than forty characters",
                List.of(type("FR"), new Badge(Badge.Kind.Known.PRIORITY, "Could have")));
        final ModelCard speed = card("NFR-1", "Fast lookups",
                List.of(type("NFR"), new Badge(Badge.Kind.Known.PRIORITY, "Should have")),
                Block.Prose.plain(CardLabels.DESCRIPTION, "A lookup answers within a second."));
        final ModelCard law = card("CON-1", "Data protection", List.of(type("CON")),
                Block.Prose.plain(CardLabels.STATEMENT, "Member data stays in the country."));
        final ModelCard term = card("TERM-1", "Loan", List.of(),
                Block.Prose.plain(CardLabels.DEFINITION, "A member keeps a medium for a limited time."));
        final ModelCard decision = card("ADR-1", "Use a relational store", List.of(),
                Block.Prose.plain("Decision", "We use SQL."));
        final ModelCard context = card("BC-1", "Lending context", List.of(),
                Block.Prose.plain("Domain vision", "Lending."));
        return new ModelViews.Views(List.of(
                section(BoundedContextCards.SECTION_TITLE, context),
                section(RequirementCards.SECTION_TITLE, record, longTitle, speed),
                section(ConstraintCards.SECTION_TITLE, law),
                section(UseCaseCards.SECTION_TITLE, lending),
                section(AdrCards.SECTION_TITLE, decision),
                section(ActorCards.SECTION_TITLE, member),
                section(RoleCards.SECTION_TITLE, librarian, vacantRole),
                section(TermCards.SECTION_TITLE, term)), List.of());
    }

    private String render(final ModelViews.Views views, final DisplayLocale locale) {
        return renderer.render(Optional.of("Town library"), Optional.of("Lends books."), views, locale);
    }

    private static int count(final String text, final String needle) {
        int found = 0;
        for (int at = text.indexOf(needle); at >= 0; at = text.indexOf(needle, at + 1)) {
            found++;
        }
        return found;
    }

    // ---- tests -----------------------------------------------------------------------------------

    @Test
    void chaptersAppearInTheSpecifiedOrder() {
        final String html = render(fixture(), ENGLISH);

        final List<String> headings = List.of("Introduction", "Participants", "Processes",
                "Functional requirements", "Quality requirements", "Constraints", "Glossary");
        int last = -1;
        for (final String heading : headings) {
            final int at = html.indexOf("</span> " + heading + "</h2>");
            assertThat(at).as(heading).isGreaterThan(last);
            last = at;
        }
    }

    @Test
    void architectureDecisionsAndBoundedContextsAreAbsent() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).doesNotContain("Use a relational store").doesNotContain("We use SQL")
                .doesNotContain("Lending context").doesNotContain("Domain vision");
    }

    @Test
    void functionalAndQualityRequirementsAreSplitByTheTypeBadge() {
        final String html = render(fixture(), ENGLISH);

        final int quality = html.indexOf("</span> Quality requirements</h2>");
        final int constraints = html.indexOf("</span> Constraints</h2>");
        assertThat(html.indexOf("id=\"a-fr-1\"")).isLessThan(quality);
        assertThat(html.indexOf("id=\"a-nfr-1\"")).isBetween(quality, constraints);
        assertThat(html).contains("4.1</span> Record loans").contains("5.1</span> Fast lookups");
    }

    @Test
    void emptyChaptersKeepTheirNumberAndShowAPlaceholder() {
        final String html = render(new ModelViews.Views(List.of(), List.of()), ENGLISH);

        assertThat(html).contains("<span class=\"num\">3</span> Processes")
                .contains("<span class=\"num\">5</span> Quality requirements")
                .contains("<span class=\"num\">7</span> Glossary")
                .contains("No processes are recorded for this project.")
                .contains("No quality requirements are recorded for this project.")
                .contains("No actors are recorded for this project.");
    }

    @Test
    void noIriAppearsInTheOutput() {
        assertThat(render(fixture(), ENGLISH)).doesNotContain(IRI_PREFIX);
    }

    @Test
    void contentIsEscapedAndCannotCloseTheScriptBlock() {
        final String hostile = "</script><script>alert(1)</script>";
        final ModelCard evil = card("FR-9", "<img src=x onerror=alert(1)>",
                List.of(type("FR")), Block.Prose.plain(CardLabels.DESCRIPTION, hostile));
        final ModelCard term = card("TERM-9", hostile, List.of(),
                Block.Prose.plain(CardLabels.DEFINITION, hostile + "\"onmouseover=\"x"));
        final String html = renderer.render(Optional.of(hostile), Optional.of(hostile),
                new ModelViews.Views(List.of(section(RequirementCards.SECTION_TITLE, evil),
                        section(TermCards.SECTION_TITLE, term)), List.of()), ENGLISH);

        assertThat(html).doesNotContain("<img src=x").doesNotContain("<script>alert");
        assertThat(count(html, "<script>")).isEqualTo(1);
        assertThat(count(html, "</script>")).isEqualTo(1);
        assertThat(html).contains("&lt;/script&gt;&lt;script&gt;alert(1)&lt;/script&gt;")
                .contains("&lt;img src=x onerror=alert(1)&gt;");
        assertThat(html).doesNotContain("\"onmouseover=\"x");
    }

    @Test
    void fixedTextsFollowTheEffectiveLanguage() {
        final String german = render(fixture(), GERMAN);
        final String english = render(fixture(), ENGLISH);

        assertThat(german).contains("lang=\"de\"").contains("Begriffsverzeichnis").contains("Abläufe")
                .contains("Hilfe zum Lesen").contains("Priorität: Muss · Status: Vorgeschlagen")
                .contains("Qualitätsanforderungen").doesNotContain("Glossary");
        assertThat(english).contains("lang=\"en\"").contains("Glossary").contains("Reading help")
                .contains("Priority: Must · Status: Proposed").doesNotContain("Begriffsverzeichnis");
    }

    @Test
    void allFixedTextsExistInBothLanguages() {
        for (final SpecificationTexts text : SpecificationTexts.values()) {
            assertThat(text.in(true)).as(text.name()).isNotBlank();
            assertThat(text.in(false)).as(text.name()).isNotBlank();
        }
    }

    @Test
    void failuresRenderAsAVisibleNoticeWithoutTheirDetails() {
        final String html = renderer.render(Optional.empty(), Optional.empty(), new ModelViews.Views(List.of(),
                List.of("Glossary: could not be read (IllegalStateException: secret detail)")), ENGLISH);

        assertThat(html).contains("class=\"notice\"").contains("<li>Glossary</li>")
                .doesNotContain("secret detail");
    }

    @Test
    void noFailuresMeansNoNotice() {
        assertThat(render(fixture(), ENGLISH)).doesNotContain("class=\"notice\"");
    }

    @Test
    void referenceToATargetOutsideTheDocumentIsPlainText() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).contains("see <a class=\"ref\" href=\"#a-fr-1\"");
        assertThat(html).doesNotContain("href=\"#a-adr-1\"").contains("and ADR-1.");
    }

    @Test
    void runningTextShowsShortTitlesAndCodesForLongOnes() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).contains("data-ref=\"a-fr-1\">Record loans</a>")
                .contains("data-ref=\"a-fr-2\">FR-2</a>");
    }

    @Test
    void listsShowCodeAndTitleAndLinkToTheAnchor() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).contains("<a class=\"ref\" href=\"#a-fr-1\" data-ref=\"a-fr-1\">FR-1 Record loans</a>")
                .contains("<a class=\"ref\" href=\"#a-uc-1\" data-ref=\"a-uc-1\">UC-1 Lend a book</a>");
    }

    @Test
    void glossaryMentionsLinkToTheTermAndTheTermCarriesItsDefinitionForThePopup() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).contains("<a class=\"term\" href=\"#a-term-1\" data-ref=\"a-term-1\">loan</a>")
                .contains("<dt id=\"a-term-1\"")
                .contains("data-def=\"A member keeps a medium for a limited time.\"");
        assertThat(html).doesNotContain("TERM-1");
    }

    @Test
    void aRoleShowsFilledByOnlyWhenSomeoneFillsIt() {
        final String html = render(fixture(), ENGLISH);
        final int librarian = html.indexOf("id=\"a-role-1\"");
        final int archivist = html.indexOf("id=\"a-role-2\"");

        assertThat(html.substring(librarian, archivist)).contains("Filled by:")
                .contains("ACTOR-1 (Library member)");
        assertThat(html.substring(archivist, html.indexOf("</section>", archivist)))
                .doesNotContain("Filled by").doesNotContain("unfilled");
    }

    @Test
    void statusAndPriorityAreOnePlainLineAndTheTypeBadgeIsNotShown() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).contains("<p class=\"meta\">Priority: Must · Status: Proposed</p>");
        assertThat(html).doesNotContain(">FR<").doesNotContain("Type");
    }

    @Test
    void textsRenderVerbatimIncludingBracketAnnotations() {
        assertThat(render(fixture(), ENGLISH)).contains("Every loan [assumption: one per copy] is stored.");
    }

    @Test
    void acceptanceCriteriaAndFlowStepsRenderAsLists() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).contains("A loan has a due date.</li>")
                .contains("<li>Hand out the book.</li></ol>");
    }

    @Test
    void acceptanceCriteriaAreNumberedByPositionAndEachCarriesAnAnchor() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html)
                .contains("<li id=\"a-fr-1-c1\"><a class=\"cid\" href=\"#a-fr-1-c1\">FR-1.1</a> A loan has a due date.</li>")
                .contains("<li id=\"a-fr-1-c2\"><a class=\"cid\" href=\"#a-fr-1-c2\">FR-1.2</a> A loan names the member.</li>");
    }

    @Test
    void exampleCriteriaAreGroupedApartAndKeepTheirNumber() {
        final String html = render(fixture(), ENGLISH);

        final int examples = html.indexOf("<summary>Examples (1)</summary>");
        assertThat(examples).isPositive();
        assertThat(html.indexOf("id=\"a-fr-1-c3\"")).isGreaterThan(examples);
        assertThat(html.indexOf("id=\"a-fr-1-c2\"")).isLessThan(examples);
        assertThat(html).contains("<li id=\"a-fr-1-c3\" class=\"example\"><a class=\"cid\" href=\"#a-fr-1-c3\">")
                .contains("FR-1.3</a> Example: a loan");
    }

    @Test
    void criterionAnchorsAndTheFoldAreInsideTheirDetailsBlockSoAnAnchorJumpOpensIt() {
        final String html = render(fixture(), ENGLISH);

        final int fold = html.indexOf("<details class=\"fold\" id=\"a-fr-1-criteria\">");
        final int end = html.indexOf("</details>\n", html.indexOf("id=\"a-fr-1-c3\""));
        assertThat(fold).isPositive();
        assertThat(html.indexOf("id=\"a-fr-1-c1\"")).isBetween(fold, end);
        assertThat(html.indexOf("id=\"a-fr-1-c3\"")).isBetween(fold, end);
        assertThat(html).contains("id=\"a-fr-1-rationale\"");
    }

    @Test
    void aGlossaryTermIsLinkedOnlyAtItsFirstMentionWithinACard() {
        final RichText twice = new RichText(List.of(
                new Span.TermLink("loan", IRI_PREFIX + "TERM-1", "TERM-1"), new Span.Plain(" and a "),
                new Span.TermGap("loan", IRI_PREFIX + "TERM-1", "TERM-1")));
        final ModelCard first = card("FR-7", "First", List.of(type("FR")), new Block.Prose(CardLabels.DESCRIPTION, "d",
                List.of(new ProsePart.Paragraph(twice), new ProsePart.Paragraph(twice))));
        final ModelCard second = card("FR-8", "Second", List.of(type("FR")), new Block.Prose(CardLabels.DESCRIPTION,
                "d", List.of(new ProsePart.Paragraph(twice))));
        final ModelCard term = card("TERM-1", "Loan", List.of(),
                Block.Prose.plain(CardLabels.DEFINITION, "A medium kept."));
        final String html = render(new ModelViews.Views(List.of(section(RequirementCards.SECTION_TITLE, first, second),
                section(TermCards.SECTION_TITLE, term)), List.of()), ENGLISH);

        final String card1 = html.substring(html.indexOf("id=\"a-fr-7\""), html.indexOf("id=\"a-fr-8\""));
        final String card2 = html.substring(html.indexOf("id=\"a-fr-8\""));
        assertThat(count(card1, "<a class=\"term\"")).isEqualTo(1);
        assertThat(card1).contains("</a> and a loan</p>");
        assertThat(count(card2.substring(0, card2.indexOf("</section>")), "<a class=\"term\"")).isEqualTo(1);
    }

    @Test
    void aFlowStepShowsTheRequirementsItRealisesDirectlyUnderItsText() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).contains("<li>Scan the card.<span class=\"stepreqs\"><a class=\"ref\" href=\"#a-fr-1\" "
                + "data-ref=\"a-fr-1\" title=\"Record loans\">FR-1</a></span></li>")
                .contains("<li>Hand out the book.</li></ol>");
        assertThat(html).contains("Realised by requirements");
    }

    @Test
    void rationaleCriteriaAndDeviationsAreCollapsedBlocksWithTheirCount() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html)
                .contains("<details class=\"fold\" id=\"a-fr-1-rationale\"><summary>Rationale</summary>")
                .contains("<summary>Acceptance criteria (3)</summary>")
                .contains("<summary>Deviations (1)</summary>")
                .doesNotContain("<details open");
        assertThat(count(html, "<details class=\"fold\"")).isEqualTo(4);
        assertThat(html).contains("<details class=\"fold\" id=\"a-fr-1-rationale\"><summary>Rationale</summary>"
                + "<p>Overdue loans must be traceable.</p>\n</details>");
    }

    @Test
    void theReadingHelpExplainsTheCollapsedBlocks() {
        assertThat(render(fixture(), ENGLISH)).contains("<h3>Collapsed blocks</h3>")
                .contains("&ldquo;Expand all&rdquo;").contains("find function does not search collapsed content");
        assertThat(render(fixture(), GERMAN)).contains("<h3>Aufklappbare Bl\u00f6cke</h3>");
    }

    @Test
    void theRuleTextStaysVisibleOutsideTheFoldedBlocks() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html.indexOf("Every loan [assumption")).isLessThan(html.indexOf("<summary>Rationale"));
        assertThat(html).doesNotContain("<summary>Every loan");
    }

    @Test
    void anchorJumpOpensFoldedBlocksAndPrintOpensAllOfThem() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).contains("closest('details')").contains("beforeprint").contains("afterprint")
                .contains("hashchange").contains("id=\"foldAll\"").contains("Expand all");
        assertThat(html).contains("details::details-content");
    }

    @Test
    void requirementsAndRolesCarryTheirReverseUseCaseLinks() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).contains("Used in processes").contains("Involved in processes")
                .contains("UC-1 Lend a book</a> (primary role)");
    }

    @Test
    void theScriptIsInlinedAndReadsItsDataFromTheDom() {
        final String html = render(fixture(), ENGLISH);

        assertThat(html).contains("document.querySelectorAll('[data-kind]')").contains("data-hint=")
                .doesNotContain("src=\"http").doesNotContain("href=\"http");
        assertThat(html).contains("@page{size:A4");
    }

    @Test
    void tableOfContentsListsEveryCardOnce() {
        final String html = render(fixture(), ENGLISH);
        final List<String> anchors = new ArrayList<>();
        for (final String code : List.of("uc-1", "fr-1", "nfr-1", "con-1")) {
            anchors.add("href=\"#a-" + code + "\"");
        }

        for (final String anchor : anchors) {
            assertThat(count(html, anchor)).as(anchor).isGreaterThanOrEqualTo(2);
        }
        assertThat(html).contains("3.1 Lend a book").contains("4.1 Record loans");
    }
}
