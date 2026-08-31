// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;

/**
 * The report renders the model the way it was authored, and still cannot lose anything the
 * store holds.
 */
class HtmlReportRendererTest {

    private static final ProjectId PROJECT = new ProjectId("report-test");
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String ID = "https://w3id.org/arknet/id/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final String UC_1 = ID + "uc-1";
    private static final String STEP_1 = ID + "step-1";
    private static final String STEP_2 = ID + "step-2";
    private static final String FR_1 = ID + "fr-1";
    private static final String CRITERION_1 = ID + "criterion-1";
    private static final String REVISION = ID + "revision-1";

    private final HtmlReportRenderer renderer = new HtmlReportRenderer(Prefixes.defaults());

    /**
     * The whole point of the exercise: a use case reads as a use case - goal, actor, numbered
     * flow - not as the opaque step subjects it is stored as.
     */
    @Test
    void rendersAUseCaseAsAFlowRatherThanAsItsTriples() {
        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).contains("id=\"sec-use-cases\"");
        assertThat(html).contains(">UC1<").contains(">Bestellung aufgeben<");
        assertThat(html).contains("<span class=\"blabel\">Goal</span>");
        assertThat(html).contains("<ol class=\"flow\">");
        assertThat(html).contains("<span class=\"num\">1</span>")
                .contains("Kunde legt Artikel in den Warenkorb")
                .contains("<span class=\"num\">2</span>")
                .contains("System bestaetigt die Bestellung");
        // The realised requirement is a chip linking to the requirement's own anchor.
        assertThat(html).contains("<a class=\"chip\" href=\"#r-" + anchorOf(FR_1) + "\">FR-1</a>");
    }

    /**
     * The primary actor shows the term itself and links to the actor's own resource; its running
     * number is the tooltip, and the opaque identity stays in the raw triples. Split out from
     * {@link #rendersAUseCaseAsAFlowRatherThanAsItsTriples()} (issue #118): the actor chip is not
     * part of the flow, it is a card-level reference like any other.
     */
    @Test
    void linksThePrimaryActorAsAChipToItsOwnResource() {
        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).contains("<span class=\"blabel\">Primary actor</span>");
        assertThat(html).contains("<a class=\"chip\" href=\"#r-" + anchorOf(ID + "actor-1")
                + "\" title=\"TERM-1\">Kunde</a>");
    }

    /**
     * A step is part of its use case's flow, not a resource of its own - it must not also show
     * up as a card in the raw section, or a five-step use case litters the report with five
     * opaque cards.
     */
    @Test
    void suppressesUseCaseStepsFromTheRawSection() {
        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).doesNotContain("id=\"r-" + anchorOf(STEP_1) + "\"");
        assertThat(html).doesNotContain("id=\"r-" + anchorOf(STEP_2) + "\"");
    }

    /**
     * The counterpart: the report must never become a filter that hides parts of the store.
     * Anything no bounded context claimed - here a provenance-style resource and the use case's
     * own requirement - stays reachable under "Other resources".
     */
    @Test
    void keepsEverythingNoSectionClaimedInTheRawSection() {
        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).contains("id=\"sec-other\"");
        assertThat(html).contains("id=\"r-" + anchorOf(REVISION) + "\"");
        assertThat(html).contains("id=\"r-" + anchorOf(FR_1) + "\"");
    }

    /**
     * Issue #297: since #266 every acceptance criterion is its own {@code
     * arkreq:AcceptanceCriterion} resource, already rendered as a bullet inside its requirement's
     * card ({@link RequirementCards}). Without this suppression it also showed up as its own raw
     * card in "Other resources" - the exact "litters the report" problem the step suppression
     * above already solves for use-case steps, just for a resource every requirement carries by
     * domain invariant.
     */
    @Test
    void suppressesAcceptanceCriteriaFromTheRawSectionWhenTheirRequirementIsCarded() {
        final String ac = ID + "ac-1";
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "Bestellen", FR_1, List.of(), List.of())));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(FR_1, RDF_TYPE, ARKREQ + "Requirement"),
                iri(FR_1, ARKREQ + "acceptanceCriterion", ac),
                iri(ac, RDF_TYPE, ARKREQ + "AcceptanceCriterion"),
                literal(ac, ARKREQ + "criterionText", "Bestellung ist abgeschlossen")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).doesNotContain("id=\"r-" + anchorOf(ac) + "\"");
    }

    /**
     * Counterpart to {@link #suppressesAcceptanceCriteriaFromTheRawSectionWhenTheirRequirementIsCarded()},
     * mirroring the #142 protection already in place for use-case steps: if the Requirements
     * section itself failed to build, no requirement is carded, so its acceptance criteria must
     * not be swallowed as if a requirement card had already shown them.
     */
    @Test
    void keepsAcceptanceCriteriaInOtherResourcesWhenTheRequirementsSectionItselfFailed() {
        final String ac = ID + "ac-1";
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(FR_1, RDF_TYPE, ARKREQ + "Requirement"),
                iri(FR_1, ARKREQ + "acceptanceCriterion", ac),
                iri(ac, RDF_TYPE, ARKREQ + "AcceptanceCriterion"),
                literal(ac, ARKREQ + "criterionText", "Bestellung ist abgeschlossen")));
        final ModelViews.Views views = new ModelViews.Views(
                List.of(), List.of("Requirements: could not be read (IllegalStateException: store closed)"));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views, DisplayLocale.DEFAULT);

        assertThat(html).contains("id=\"r-" + anchorOf(ac) + "\"");
    }

    /**
     * An orphan step belongs to no flow, so suppressing it would make it invisible - exactly the
     * kind of leftover this report exists to surface.
     */
    @Test
    void keepsAStepNoUseCaseReferences() {
        final String orphan = ID + "step-orphan";
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(orphan, RDF_TYPE, ARKREQ + "Step"),
                literal(orphan, ARKREQ + "stepText", "Ein Schritt ohne Use Case")));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(), DisplayLocale.DEFAULT);

        assertThat(html).contains("id=\"r-" + anchorOf(orphan) + "\"");
    }

    /**
     * Issue #150, finding 6: two different IRIs whose non-alphanumeric runs collapse to the same
     * sanitized text (a {@code .} and a {@code -} both become {@code -}) used to produce the
     * exact same DOM id, so the second card's anchor - and every link to it - silently pointed at
     * the first card instead. Appending a hash of the untouched, full IRI restores injectivity.
     */
    @Test
    void givesTwoIrisThatSanitizeToTheSameTextDifferentAnchors() {
        final String dotted = ID + "a.b";
        final String dashed = ID + "a-b";
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(dotted, RDF_TYPE, ARKREQ + "Step"),
                literal(dotted, ARKREQ + "stepText", "Erster"),
                iri(dashed, RDF_TYPE, ARKREQ + "Step"),
                literal(dashed, ARKREQ + "stepText", "Zweiter")));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(), DisplayLocale.DEFAULT);

        assertThat(anchorOf(dotted)).isNotEqualTo(anchorOf(dashed));
        assertThat(html).contains("id=\"r-" + anchorOf(dotted) + "\"");
        assertThat(html).contains("id=\"r-" + anchorOf(dashed) + "\"");
    }

    /**
     * Issue #305 part 2: {@link String#hashCode()} is a 32-bit hash, trivially collidable by
     * construction - unlike the {@code .}-vs-{@code -} pair above (which merely collapse to the
     * same *sanitized text*), these two IRIs are chosen to also collide under raw
     * {@code String#hashCode()} ({@code "a.!b".hashCode() == "a-@b".hashCode()}), so the previous
     * {@code shortHash} gave them the identical anchor too. A SHA-256-based hash must still tell
     * them apart.
     */
    @Test
    void givesTwoIrisThatCollideUnderRaw32BitHashCodeDifferentAnchors() {
        final String first = ID + "a.!b";
        final String second = ID + "a-@b";
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(first, RDF_TYPE, ARKREQ + "Step"),
                literal(first, ARKREQ + "stepText", "Erster"),
                iri(second, RDF_TYPE, ARKREQ + "Step"),
                literal(second, ARKREQ + "stepText", "Zweiter")));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(), DisplayLocale.DEFAULT);

        assertThat(anchorOf(first)).isNotEqualTo(anchorOf(second));
        assertThat(html).contains("id=\"r-" + anchorOf(first) + "\"");
        assertThat(html).contains("id=\"r-" + anchorOf(second) + "\"");
    }

    /** Every card keeps its raw triples one click away, so the model view never has to be trusted blindly. */
    @Test
    void hangsTheRawTriplesOffEveryCard() {
        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).contains("<details class=\"raw\">");
        assertThat(html).contains("raw triples");
        assertThat(html).contains("arkreq:mainStep");
    }

    /** A section that could not be read is stated, not silently dropped - a missing section reads as "empty store". */
    @Test
    void showsSectionFailuresInsteadOfPretendingTheStoreIsEmpty() {
        final ModelViews.Views views = new ModelViews.Views(
                List.of(), List.of("Use Cases: could not be read (IllegalStateException: store closed)"));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views, DisplayLocale.DEFAULT);

        assertThat(html).contains("Incomplete report").contains("store closed");
    }

    /**
     * Regression test for issue #142: when the Use Cases section itself failed to build, no use
     * case is carded, so its steps must not be suppressed as if a use-case flow had already shown
     * them - they fall through to "Other resources" like any other uncarded resource instead of
     * disappearing from the document entirely.
     */
    @Test
    void keepsUseCaseStepsInOtherResourcesWhenTheUseCasesSectionItselfFailed() {
        final ModelViews.Views views = new ModelViews.Views(
                List.of(), List.of("Use Cases: could not be read (IllegalStateException: store closed)"));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views, DisplayLocale.DEFAULT);

        assertThat(html).contains("id=\"r-" + anchorOf(STEP_1) + "\"");
        assertThat(html).contains("id=\"r-" + anchorOf(STEP_2) + "\"");
        assertThat(html).contains("Kunde legt Artikel in den Warenkorb");
    }

    /** A reference whose target is not in this project stays visible as a dead chip. */
    @Test
    void marksAReferenceThatIsNotInTheProject() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-9", "Gone", FR_1, List.of(),
                        List.of(new Block.Refs("Uses terms",
                                List.of(new Ref("Lieferschein", "TERM-404", ID + "nowhere")))))));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains(
                "<span class=\"chip dead\" title=\"TERM-404 - not in this project\">Lieferschein</span>");
    }

    /**
     * A mention the model backs with an edge links into the glossary; a mention of a term
     * nothing links to is marked but deliberately not clickable, because it is the absence of a
     * reference, not one.
     */
    @Test
    void rendersALinkedMentionAsALinkAndAnUnlinkedOneAsAGap() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "Bestellen", FR_1, List.of(), List.of(
                        Block.Prose.paragraph("Description", new RichText(List.of(
                                new Span.Plain("Der "),
                                new Span.TermLink("Kunde", ID + "actor-1", "TERM-1"),
                                new Span.Plain(" legt eine "),
                                new Span.TermGap("Bestellung", ID + "term-2", "TERM-2"),
                                new Span.Plain(" an."))))))));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<a class=\"term\" href=\"#r-" + anchorOf(ID + "actor-1")
                + "\" title=\"TERM-1\">Kunde</a>");
        assertThat(html).contains("<span class=\"term gap\" title=\"TERM-2 - in the glossary, but this"
                + " element does not link to it\">Bestellung</span>");
        assertThat(html).doesNotContain("href=\"#r-" + anchorOf(ID + "term-2"));
    }

    /**
     * Cards start folded so a large model is readable at all; the toolbar can open them in bulk,
     * and following a reference has to open its target - see the report's own script.
     */
    @Test
    void foldsEveryCardAndOffersBulkControls() {
        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).contains("<details class=\"fold\">").doesNotContain("<details class=\"fold\" open>");
        assertThat(html).contains("<summary class=\"head\">");
        assertThat(html).contains("id=\"expand-all\"").contains("id=\"collapse-all\"");
        assertThat(html).contains("window.addEventListener('hashchange', reveal);");
    }

    /** With nothing in the store at all, the report says so and names the way in. */
    @Test
    void tellsTheReaderWhereToStartWhenTheProjectIsEmpty() {
        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), StoreSnapshot.of(List.of()), "digest", views(), DisplayLocale.DEFAULT);

        assertThat(html).contains("holds no model yet").contains("uc_add");
    }

    /** The report must stay openable from a file:// URL with no network. */
    @Test
    void staysSelfContained() {
        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).doesNotContain("<script src").doesNotContain("<link rel=\"stylesheet\"")
                .doesNotContain("http://cdn").doesNotContain("https://cdn");
    }

    /**
     * A block carries a class derived from its own label, so a stylesheet can lift the one block
     * a record exists for - an ADR's decision - without the renderer growing a special case.
     */
    @Test
    void tagsEveryBlockWithAClassTakenFromItsLabel() {
        final ModelSection section = new ModelSection("Architecture decisions", "architecture-decisions", "",
                List.of(new ModelCard("ADR-1", "A decision", FR_1, List.of(), List.of(
                        Block.Prose.plain("Context", "the situation"),
                        Block.Prose.plain("Decision", "the decision"),
                        Block.Prose.plain("Decision date", "2026-08-26")))));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest",
                views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<div class=\"block b-context\">")
                .contains("<div class=\"block b-decision\">")
                .contains("<div class=\"block b-decision-date\">");
    }

    /**
     * The structure an author put into a store literal survives into the report as structure:
     * a blank line becomes a new paragraph, a {@code - } line becomes a list item (issue #388).
     *
     * <p>This replaces the {@code white-space:pre-line} of issue #385, which kept the author's
     * line breaks by printing them rather than by reading them - it could not tell an
     * enumeration from a hard-wrapped sentence, so it preserved both and structured neither.</p>
     */
    @Test
    void rendersTheStructureAnAuthorPutIntoProse() {
        final ModelSection section = new ModelSection("Architecture decisions", "architecture-decisions", "",
                List.of(new ModelCard("ADR-1", "A decision", FR_1, List.of(),
                        List.of(ProseMarkdown.prose("Decision", "lead-in:\n\n- first\n- second",
                                RichText::plain)))));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest",
                views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<p class=\"prose\">lead-in:</p>")
                .contains("<ul class=\"prose-bullets\"><li>first</li><li>second</li></ul>");
    }

    /**
     * A hard-wrapped sentence is one sentence: a single line break inside a paragraph collapses
     * to a space, as it does in Markdown, instead of being printed as a break the author never
     * meant (issue #388).
     */
    @Test
    void collapsesASingleLineBreakInsideAParagraph() {
        final ModelSection section = new ModelSection("Architecture decisions", "architecture-decisions", "",
                List.of(new ModelCard("ADR-1", "A decision", FR_1, List.of(),
                        List.of(ProseMarkdown.prose("Decision", "one sentence\nwrapped by an editor",
                                RichText::plain)))));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest",
                views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<p class=\"prose\">one sentence wrapped by an editor</p>");
    }

    /**
     * Emphasis and code from the accepted subset become elements; everything else in the literal
     * still goes through escaping, so a text containing markup-looking characters stays text.
     */
    @Test
    void rendersEmphasisAndCodeFromTheAcceptedSubset() {
        final ModelSection section = new ModelSection("Architecture decisions", "architecture-decisions", "",
                List.of(new ModelCard("ADR-1", "A decision", FR_1, List.of(),
                        List.of(ProseMarkdown.prose("Decision",
                                "the **head** is `arkprov:head`, not <b>bold</b>", RichText::plain)))));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest",
                views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("the <strong>head</strong> is"
                + " <code class=\"md-code\">arkprov:head</code>, not &lt;b&gt;bold&lt;/b&gt;");
    }

    /**
     * A code reference renders as a link to the card it names, with that card's title in the
     * tooltip - the reader learns what ADR-3 is without leaving the sentence.
     */
    @Test
    void rendersACodeReferenceAsALinkCarryingTheTargetsTitle() {
        final ModelSection section = new ModelSection("Architecture decisions", "architecture-decisions", "",
                List.of(new ModelCard("ADR-1", "Scope frame", FR_1, List.of(),
                        List.of(Block.Prose.paragraph("Decision", new RichText(List.of(
                                new Span.Plain("cross-cutting is "),
                                new Span.CodeRef("ADR-3", ID + "actor-1", "ADR-3", "Actor identity"))))))));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest",
                views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<a class=\"code-ref\" href=\"#r-" + anchorOf(ID + "actor-1")
                + "\" title=\"ADR-3 - Actor identity\">ADR-3</a>");
    }

    /** Free text from the store is escaped, not injected into the document. */
    @Test
    void escapesModelText() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "<script>alert(1)</script>", FR_1, List.of(),
                        List.of(Block.Prose.plain("Description", "a & b < c")))));

        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("a &amp; b &lt; c");
    }

    /**
     * A project registered with a human-readable label must show that label in the
     * header, with the raw id kept alongside rather than replaced.
     */
    @Test
    void headerNamesTheRegisteredLabelAlongsideTheId() {
        final String html = renderer.render(
                new ProjectId("ff92cedd-a76a-4f1d-acc5-7aad9ccb1ac8"), Optional.of("arknet-demo"),
                Optional.empty(), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).contains(
                "<span class=\"ws\">project: arknet-demo (id: ff92cedd-a76a-4f1d-acc5-7aad9ccb1ac8)</span>");
    }

    /**
     * No label means no registry entry for this id - the header falls back to the raw id exactly
     * as it did before this lookup existed.
     */
    @Test
    void headerFallsBackToTheRawIdWhenNoLabelIsAvailable() {
        final String html = renderer.render(PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).contains("<span class=\"ws\">project: report-test</span>");
    }

    /** issue #110: a project's optional description renders right below the header when present. */
    @Test
    void headerShowsTheProjectDescriptionWhenPresent() {
        final String html = renderer.render(PROJECT, Optional.of("arknet-demo"),
                Optional.of("A demo <project> for arknet."), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).contains("<p class=\"project-desc\">A demo &lt;project&gt; for arknet.</p>");
    }

    /** No description is simply omitted - unchanged from before issue #110. */
    @Test
    void headerOmitsTheDescriptionParagraphWhenAbsent() {
        final String html = renderer.render(PROJECT, Optional.of("arknet-demo"), Optional.empty(), snapshot(),
                "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).doesNotContain("<p class=\"project-desc\">");
    }

    /**
     * issue #270 (part 2 of #248): when a card's title came from a literal that has more than one
     * language variant among the resource's own raw triples, the report embeds every variant -
     * the active one visible, the rest hidden - for the toolbar's client-side language switch to
     * toggle between. No new store read: the raw triples the report already carries are the only
     * source.
     */
    @Test
    void offersEveryLanguageVariantOfACardTitle() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "Bestellen", FR_1, List.of(), List.of())));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literalLang(FR_1, "http://purl.org/dc/terms/title", "Bestellen", "de"),
                literalLang(FR_1, "http://purl.org/dc/terms/title", "Order", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<span class=\"lang-group\" data-default-lang=\"de\">");
        assertThat(html).contains("<span class=\"lang-variant\" data-lang=\"de\">Bestellen</span>");
        assertThat(html).contains("<span class=\"lang-variant\" data-lang=\"en\" hidden>Order</span>");
    }

    /**
     * Same mechanism for a {@link Block.Prose} field - here a requirement's description. A prose
     * field renders as block elements of its own, so its switch wrapper is a {@code div}: a
     * {@code span} around a {@code p} would be markup no browser is obliged to keep together.
     */
    @Test
    void offersEveryLanguageVariantOfAProseBlock() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "Bestellen", FR_1, List.of(),
                        List.of(Block.Prose.plain("Description", "Der Kunde bestellt.")))));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literalLang(FR_1, "http://purl.org/dc/terms/description", "Der Kunde bestellt.", "de"),
                literalLang(FR_1, "http://purl.org/dc/terms/description", "The customer orders.", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<div class=\"lang-group\" data-default-lang=\"de\">");
        assertThat(html).contains("<div class=\"lang-variant\" data-lang=\"de\"><div class=\"prose-body\">"
                + "<p class=\"prose\">Der Kunde bestellt.</p></div></div>");
        assertThat(html).contains("<div class=\"lang-variant\" data-lang=\"en\" hidden><div class=\"prose-body\">"
                + "<p class=\"prose\">The customer orders.</p></div></div>");
    }

    /**
     * A language variant is structured by the same subset the active one is: the markup an author
     * wrote is language-independent, and showing raw asterisks after a switch would read as the
     * switch having broken the text (issue #388).
     */
    @Test
    void structuresTheInactiveLanguageVariantToo() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "Bestellen", FR_1, List.of(),
                        List.of(ProseMarkdown.prose("Description", "Der **Kunde** bestellt.", RichText::plain)))));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literalLang(FR_1, "http://purl.org/dc/terms/description", "Der **Kunde** bestellt.", "de"),
                literalLang(FR_1, "http://purl.org/dc/terms/description", "The **customer** orders.", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<p class=\"prose\">Der <strong>Kunde</strong> bestellt.</p>")
                .contains("<p class=\"prose\">The <strong>customer</strong> orders.</p>");
    }

    /** A field with only one language on offer stays exactly as it rendered before this issue. */
    @Test
    void leavesATitleUnwrappedWhenOnlyOneLanguageExists() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "Bestellen", FR_1, List.of(), List.of())));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literalLang(FR_1, "http://purl.org/dc/terms/title", "Bestellen", "de")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<h3>Bestellen</h3>");
        assertThat(html).doesNotContain("<span class=\"lang-group\"");
    }

    /**
     * A pre-#258 (2026-08-04) resource may carry an untagged literal alongside a tagged one for
     * the same predicate - {@link DisplayLocale#select} can pick the untagged one (fallback step
     * 3). It must still offer a switch, keyed under the {@link DisplayLocale#systemDefault()}
     * language, rather than silently dropping the field from the toolbar (issue #273).
     */
    @Test
    void includesAnUntaggedLiteralAsALanguageVariant() {
        final ModelSection section = new ModelSection("Glossary", "glossary", "", List.of(
                new ModelCard("TERM-1", "Customer", FR_1, List.of(), List.of())));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(FR_1, "http://purl.org/dc/terms/title", "Customer"),
                literalLang(FR_1, "http://purl.org/dc/terms/title", "Kunde", "de")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<span class=\"lang-group\" data-default-lang=\"en\">");
        assertThat(html).contains("<span class=\"lang-variant\" data-lang=\"en\">Customer</span>");
        assertThat(html).contains("<span class=\"lang-variant\" data-lang=\"de\" hidden>Kunde</span>");
    }

    /**
     * Two different predicates on the same subject may coincidentally carry a literal with the
     * same text (here: a requirement's title and its acceptance criterion both happen to read
     * "Bestellen" in German). Matching {@code displayed} back to a predicate by text alone cannot
     * tell them apart, so the switch must not attach to whichever one is found first - that would
     * show the other field's text once the reader picks another language (issue #273).
     */
    @Test
    void doesNotMixLanguageVariantsWhenAnotherFieldSharesTheDisplayedText() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "Bestellen", FR_1, List.of(), List.of())));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literalLang(FR_1, ARKREQ + "acceptanceCriterion", "Bestellen", "de"),
                literalLang(FR_1, ARKREQ + "acceptanceCriterion", "Zustimmung", "en"),
                literalLang(FR_1, "http://purl.org/dc/terms/title", "Bestellen", "de"),
                literalLang(FR_1, "http://purl.org/dc/terms/title", "Order", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<h3>Bestellen</h3>");
        assertThat(html).doesNotContain("<span class=\"lang-group\"");
    }

    /**
     * Issue #301: a predicate can carry both an untagged legacy literal (pre-#258) and a
     * genuinely {@code systemDefault}-tagged literal at the same time. The untagged one must not
     * win the {@code systemDefault} key just because {@code StoreResource#outgoing()}'s encounter
     * order happened to see it first - a tagged literal always wins its own key, mirroring
     * {@link DisplayLocale#select}'s own precedence (a tagged match beats an untagged one).
     */
    @Test
    void prefersATaggedSystemDefaultLiteralOverAnUntaggedLegacyOneInTheLanguageSwitch() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "Hallo", FR_1, List.of(), List.of())));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                literal(FR_1, "http://purl.org/dc/terms/title", "Legacy title"),
                literalLang(FR_1, "http://purl.org/dc/terms/title", "Hello", "en"),
                literalLang(FR_1, "http://purl.org/dc/terms/title", "Hallo", "de")));
        final DisplayLocale displayLocale = new DisplayLocale(Locale.GERMAN, Locale.ENGLISH);

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), displayLocale);

        // Exactly two variants - the tagged "en" one, never the untagged legacy literal - and the
        // active "de" one selected by displayLocale.requested().
        assertThat(html).contains("<span class=\"lang-group\" data-default-lang=\"de\">"
                + "<span class=\"lang-variant\" data-lang=\"en\" hidden>Hello</span>"
                + "<span class=\"lang-variant\" data-lang=\"de\">Hallo</span></span>");
        // The untagged literal is still visible in the card's own raw-triples view (its safety
        // net, never hidden) - just not smuggled into the language switch under the "en" key.
        assertThat(html).contains("<span class=\"lit str\">\"Legacy title\"</span>");
    }

    /**
     * Issue #319: a use case's flow steps are sub-resources of their own, so the text-match path
     * above never saw their literals - the switch stopped at the card's title and prose while the
     * flow stayed in whatever language the render picked. The step's own {@code arkreq:position},
     * which the report model already carries on {@link FlowStep}, is what pairs the two up.
     */
    @Test
    void offersEveryLanguageVariantOfAFlowStep() {
        final ModelSection section = new ModelSection("Use Cases", "use-cases", "", List.of(
                new ModelCard("UC1", "Bestellen", UC_1, List.of(), List.of(
                        new Block.Flow("Main flow", List.of(
                                new FlowStep(1, RichText.plain("Der Kunde legt Artikel in den Warenkorb."), List.of())))))));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(UC_1, ARKREQ + "mainStep", STEP_1),
                iri(STEP_1, RDF_TYPE, ARKREQ + "Step"),
                literal(STEP_1, ARKREQ + "position", "1"),
                literalLang(STEP_1, ARKREQ + "stepText", "Der Kunde legt Artikel in den Warenkorb.", "de"),
                literalLang(STEP_1, ARKREQ + "stepText", "The customer adds items to the cart.", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<span class=\"lang-group\" data-default-lang=\"de\">"
                + "<span class=\"lang-variant\" data-lang=\"de\">Der Kunde legt Artikel in den Warenkorb.</span>"
                + "<span class=\"lang-variant\" data-lang=\"en\" hidden>The customer adds items to the cart.</span>"
                + "</span>");
    }

    /**
     * Two steps of one flow may read exactly the same - "Der Caller beendet seinen Zugriff." is a
     * realistic sentence to repeat. Matching a step back to its resource by text would have to
     * give up here (or, worse, pair both with the first one's translations); the position is
     * unambiguous by construction, so each step keeps its own variants (issue #319).
     */
    @Test
    void keepsTwoIdenticallyWordedStepsApartInTheLanguageSwitch() {
        final ModelSection section = new ModelSection("Use Cases", "use-cases", "", List.of(
                new ModelCard("UC1", "Bestellen", UC_1, List.of(), List.of(
                        new Block.Flow("Main flow", List.of(
                                new FlowStep(1, RichText.plain("Der Caller beendet seinen Zugriff."), List.of()),
                                new FlowStep(2, RichText.plain("Der Caller beendet seinen Zugriff."), List.of())))))));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(UC_1, ARKREQ + "mainStep", STEP_1),
                iri(UC_1, ARKREQ + "mainStep", STEP_2),
                literal(STEP_1, ARKREQ + "position", "1"),
                literalLang(STEP_1, ARKREQ + "stepText", "Der Caller beendet seinen Zugriff.", "de"),
                literalLang(STEP_1, ARKREQ + "stepText", "The caller closes its access.", "en"),
                literal(STEP_2, ARKREQ + "position", "2"),
                literalLang(STEP_2, ARKREQ + "stepText", "Der Caller beendet seinen Zugriff.", "de"),
                literalLang(STEP_2, ARKREQ + "stepText", "The caller ends its access.", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<span class=\"num\">1</span><div class=\"step\"><p>"
                + "<span class=\"lang-group\" data-default-lang=\"de\">"
                + "<span class=\"lang-variant\" data-lang=\"de\">Der Caller beendet seinen Zugriff.</span>"
                + "<span class=\"lang-variant\" data-lang=\"en\" hidden>The caller closes its access.</span>"
                + "</span>");
        assertThat(html).contains("<span class=\"num\">2</span><div class=\"step\"><p>"
                + "<span class=\"lang-group\" data-default-lang=\"de\">"
                + "<span class=\"lang-variant\" data-lang=\"de\">Der Caller beendet seinen Zugriff.</span>"
                + "<span class=\"lang-variant\" data-lang=\"en\" hidden>The caller ends its access.</span>"
                + "</span>");
    }

    /** An extension bullet reaches its {@code arkreq:extensionStep} resource the same way. */
    @Test
    void offersEveryLanguageVariantOfAnExtensionBullet() {
        final ModelSection section = new ModelSection("Use Cases", "use-cases", "", List.of(
                new ModelCard("UC1", "Bestellen", UC_1, List.of(), List.of(
                        Block.Bullets.plain("Extensions", List.of("Der Kunde bricht ab."))))));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(UC_1, ARKREQ + "extensionStep", STEP_1),
                literal(STEP_1, ARKREQ + "position", "1"),
                literalLang(STEP_1, ARKREQ + "stepText", "Der Kunde bricht ab.", "de"),
                literalLang(STEP_1, ARKREQ + "stepText", "The customer cancels.", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<li><span class=\"lang-group\" data-default-lang=\"de\">"
                + "<span class=\"lang-variant\" data-lang=\"de\">Der Kunde bricht ab.</span>"
                + "<span class=\"lang-variant\" data-lang=\"en\" hidden>The customer cancels.</span>"
                + "</span></li>");
    }

    /**
     * The same bullet path serves a requirement's acceptance criteria - reached by another edge
     * ({@code arkreq:acceptanceCriterion}) and carrying their text under another predicate
     * ({@code arkreq:criterionText}). Which of the two a bullet list shows follows from the
     * card resource's own edges, so the renderer never has to know it is looking at a
     * requirement rather than a use case.
     */
    @Test
    void offersEveryLanguageVariantOfAnAcceptanceCriterion() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "Bestellen", FR_1, List.of(), List.of(
                        new Block.Bullets("Acceptance criteria",
                                List.of(new BulletItem(1, RichText.plain("Die Bestellung ist gespeichert."))))))));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(FR_1, ARKREQ + "acceptanceCriterion", CRITERION_1),
                literal(CRITERION_1, ARKREQ + "position", "1"),
                literalLang(CRITERION_1, ARKREQ + "criterionText", "Die Bestellung ist gespeichert.", "de"),
                literalLang(CRITERION_1, ARKREQ + "criterionText", "The order is stored.", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<li><span class=\"lang-group\" data-default-lang=\"de\">"
                + "<span class=\"lang-variant\" data-lang=\"de\">Die Bestellung ist gespeichert.</span>"
                + "<span class=\"lang-variant\" data-lang=\"en\" hidden>The order is stored.</span>"
                + "</span></li>");
    }

    /**
     * Issue #358: before this fix, every {@link Block.Bullets} list on one card shared a single
     * flat position table. Two lists on the same card both starting at position 1 - unrealistic
     * for today's {@link RequirementCards}/{@link UseCaseCards} (each emits at most one), but
     * exactly what a card combining both shapes would produce - collided into "ambiguous, so no
     * switch for either item" rather than each list keeping its own variants. Keying the source
     * table by the block's own label (shared between the card builders and this renderer) keeps
     * the two apart even though both items sit at position 1.
     */
    @Test
    void keepsTwoBulletListsOnTheSameCardApart() {
        final ModelSection section = new ModelSection("Use Cases", "use-cases", "", List.of(
                new ModelCard("UC1", "Bestellen", UC_1, List.of(), List.of(
                        new Block.Bullets(UseCaseCards.EXTENSIONS_LABEL,
                                List.of(new BulletItem(1, RichText.plain("Der Kunde bricht ab.")))),
                        new Block.Bullets(RequirementCards.ACCEPTANCE_CRITERIA_LABEL,
                                List.of(new BulletItem(1, RichText.plain("Die Bestellung ist gespeichert."))))))));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(UC_1, ARKREQ + "extensionStep", STEP_1),
                literal(STEP_1, ARKREQ + "position", "1"),
                literalLang(STEP_1, ARKREQ + "stepText", "Der Kunde bricht ab.", "de"),
                literalLang(STEP_1, ARKREQ + "stepText", "The customer cancels.", "en"),
                iri(UC_1, ARKREQ + "acceptanceCriterion", CRITERION_1),
                literal(CRITERION_1, ARKREQ + "position", "1"),
                literalLang(CRITERION_1, ARKREQ + "criterionText", "Die Bestellung ist gespeichert.", "de"),
                literalLang(CRITERION_1, ARKREQ + "criterionText", "The order is stored.", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<li><span class=\"lang-group\" data-default-lang=\"de\">"
                + "<span class=\"lang-variant\" data-lang=\"de\">Der Kunde bricht ab.</span>"
                + "<span class=\"lang-variant\" data-lang=\"en\" hidden>The customer cancels.</span>"
                + "</span></li>");
        assertThat(html).contains("<li><span class=\"lang-group\" data-default-lang=\"de\">"
                + "<span class=\"lang-variant\" data-lang=\"de\">Die Bestellung ist gespeichert.</span>"
                + "<span class=\"lang-variant\" data-lang=\"en\" hidden>The order is stored.</span>"
                + "</span></li>");
    }

    /**
     * Two sub-resources claiming the same position leave the store unable to say which one an
     * item shows. The item then renders exactly as it did before this issue rather than being
     * switched to a guess - the same "empty rather than wrong" rule the text match follows when
     * two predicates share a text.
     */
    @Test
    void leavesAStepUnwrappedWhenTwoSubResourcesShareItsPosition() {
        final ModelSection section = new ModelSection("Use Cases", "use-cases", "", List.of(
                new ModelCard("UC1", "Bestellen", UC_1, List.of(), List.of(
                        new Block.Flow("Main flow", List.of(
                                new FlowStep(1, RichText.plain("Der Kunde bestellt."), List.of())))))));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(UC_1, ARKREQ + "mainStep", STEP_1),
                iri(UC_1, ARKREQ + "mainStep", STEP_2),
                literal(STEP_1, ARKREQ + "position", "1"),
                literalLang(STEP_1, ARKREQ + "stepText", "Der Kunde bestellt.", "de"),
                literalLang(STEP_1, ARKREQ + "stepText", "The customer orders.", "en"),
                literal(STEP_2, ARKREQ + "position", "1"),
                literalLang(STEP_2, ARKREQ + "stepText", "Der Kunde bestellt.", "de"),
                literalLang(STEP_2, ARKREQ + "stepText", "The customer buys.", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<div class=\"step\"><p>Der Kunde bestellt.</p>");
        assertThat(html).doesNotContain("<span class=\"lang-group\"");
    }

    /**
     * A position pairs an item with a sub-resource, but it does not prove the two show the same
     * text: an extension bullet is numbered by its list order, not from the store, so a store
     * whose positions do not run 1..n hands the renderer a resource that belongs to another item.
     * The switch then has no active language to anchor on and the item stays unwrapped, rather
     * than offering the reader a foreign item's translation under the label of this one.
     */
    @Test
    void leavesABulletUnwrappedWhenItsPositionPointsAtAnotherItemsText() {
        final ModelSection section = new ModelSection("Use Cases", "use-cases", "", List.of(
                new ModelCard("UC1", "Bestellen", UC_1, List.of(), List.of(
                        Block.Bullets.plain("Extensions", List.of("Der Kunde bricht ab."))))));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(UC_1, ARKREQ + "extensionStep", STEP_1),
                literal(STEP_1, ARKREQ + "position", "1"),
                literalLang(STEP_1, ARKREQ + "stepText", "Die Zahlung schlaegt fehl.", "de"),
                literalLang(STEP_1, ARKREQ + "stepText", "The payment fails.", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<li>Der Kunde bricht ab.</li>");
        assertThat(html).doesNotContain("<span class=\"lang-group\"");
    }

    /**
     * A {@link BulletItem}'s optional {@code badge} and {@code caption} (issue #382) render
     * before its text - the badge as the usual pill, the caption as a plain {@code <strong>} run,
     * neither one bracket-formatted or glued into the text itself the way an ADR consequence/
     * considered option used to render.
     */
    @Test
    void rendersABulletItemsBadgeAndCaptionBeforeItsText() {
        final ModelSection section = new ModelSection("Architecture Decisions", "architecture-decisions", "",
                List.of(new ModelCard(
                        "ADR-1", "Use kognio-rdf", FR_1, List.of(),
                        List.of(new Block.Bullets(AdrCards.CONSIDERED_OPTIONS_LABEL, List.of(
                                new BulletItem(1, RichText.plain("Options that were considered."),
                                        new Badge(Badge.Kind.Known.OUTCOME, "Rejected"), "Option A")))))));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), StoreSnapshot.of(List.of()), "digest", views(section),
                DisplayLocale.DEFAULT);

        assertThat(html).contains("<li><span class=\"pill outcome v-rejected\">Rejected</span> "
                + "<strong class=\"bullet-caption\">Option A</strong> Options that were considered.</li>");
    }

    /**
     * The bullets language switch (issue #319/#358) also serves an ADR's own {@code
     * arkarch:Consequence}/{@code arkarch:ConsideredOption} bullets (issue #382) - reached by
     * another edge again ({@code arkarch:consequence}) and positioned under {@code
     * arknet:position} (kogn-io/arknet#357's core-namespace property for new child resources,
     * not the legacy {@code arkreq:position} the use-case/requirement bullets above carry).
     */
    @Test
    void offersEveryLanguageVariantOfAConsequence() {
        final String adr1 = ID + "adr-1";
        final String consequence1 = ID + "consequence-1";
        final String arkarch = "https://w3id.org/arknet/architecture#";
        final String arknetCore = "https://w3id.org/arknet/core#";
        final ModelSection section = new ModelSection("Architecture Decisions", "architecture-decisions", "",
                List.of(new ModelCard(
                        "ADR-1", "Use kognio-rdf", adr1, List.of(),
                        List.of(new Block.Bullets(AdrCards.CONSEQUENCES_LABEL, List.of(
                                new BulletItem(1, RichText.plain("Weniger Boilerplate."),
                                        new Badge(Badge.Kind.Known.CONSEQUENCE, "Positive"), null)))))));
        final StoreSnapshot snapshot = StoreSnapshot.of(List.of(
                iri(adr1, arkarch + "consequence", consequence1),
                literal(consequence1, arknetCore + "position", "1"),
                literalLang(consequence1, arkarch + "consequenceStatement", "Weniger Boilerplate.", "de"),
                literalLang(consequence1, arkarch + "consequenceStatement", "Less boilerplate.", "en")));

        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot, "digest", views(section), DisplayLocale.DEFAULT);

        assertThat(html).contains("<span class=\"lang-group\" data-default-lang=\"de\">"
                + "<span class=\"lang-variant\" data-lang=\"de\">Weniger Boilerplate.</span>"
                + "<span class=\"lang-variant\" data-lang=\"en\" hidden>Less boilerplate.</span>"
                + "</span>");
    }

    /** The toolbar always offers the control; the script hides it when no field has variants. */
    @Test
    void addsALanguageSwitchToTheToolbar() {
        final String html = renderer.render(
                PROJECT, Optional.empty(), Optional.empty(), snapshot(), "digest", views(useCaseSection()), DisplayLocale.DEFAULT);

        assertThat(html).contains("id=\"lang-switch\"").contains("<option value=\"\">Original</option>");
    }

    // --- fixtures --------------------------------------------------------------

    private static ModelViews.Views views(final ModelSection... sections) {
        return new ModelViews.Views(List.of(sections), List.of());
    }

    private static ModelSection useCaseSection() {
        return new ModelSection("Use Cases", "use-cases", "goal, actors and the ordered main flow", List.of(
                new ModelCard("UC1", "Bestellung aufgeben", UC_1, List.of(), List.of(
                        Block.Prose.plain("Goal", "Der Kunde bestellt Artikel."),
                        new Block.Refs("Primary actor",
                                List.of(new Ref("Kunde", "TERM-1", ID + "actor-1"))),
                        new Block.Flow("Main flow", List.of(
                                new FlowStep(1, RichText.plain("Kunde legt Artikel in den Warenkorb"),
                                        List.of(Ref.of("FR-1", FR_1))),
                                new FlowStep(2, RichText.plain("System bestaetigt die Bestellung"), List.of())))))));
    }

    /** A use case with two steps, one requirement, one actor and one unclaimed resource. */
    private static StoreSnapshot snapshot() {
        return StoreSnapshot.of(List.of(
                iri(UC_1, RDF_TYPE, ARKREQ + "UseCase"),
                literal(UC_1, "http://purl.org/dc/terms/identifier", "UC1"),
                iri(UC_1, ARKREQ + "mainStep", STEP_1),
                iri(UC_1, ARKREQ + "mainStep", STEP_2),
                iri(UC_1, ARKREQ + "primaryActor", ID + "actor-1"),
                iri(STEP_1, RDF_TYPE, ARKREQ + "Step"),
                literal(STEP_1, ARKREQ + "stepText", "Kunde legt Artikel in den Warenkorb"),
                iri(STEP_2, RDF_TYPE, ARKREQ + "Step"),
                literal(STEP_2, ARKREQ + "stepText", "System bestaetigt die Bestellung"),
                iri(FR_1, RDF_TYPE, ARKREQ + "Requirement"),
                literal(FR_1, "http://purl.org/dc/terms/identifier", "FR-1"),
                iri(ID + "actor-1", RDF_TYPE, "http://www.w3.org/2004/02/skos/core#Concept"),
                iri(REVISION, RDF_TYPE, "https://w3id.org/arknet/provenance#Revision")));
    }

    /** Mirrors {@code HtmlReportRenderer#resourceAnchor} for a namespace with no CURIE binding. */
    private static String anchorOf(final String iri) {
        final String sanitized = iri.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
        return sanitized + "-" + shortHash(iri);
    }

    /** Mirrors {@code HtmlReportRenderer#shortHash} (issue #305 part 2: SHA-256, not {@code String#hashCode()}). */
    private static String shortHash(final String iri) {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (final NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        final byte[] hashed = digest.digest(iri.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(hashed, 0, 8);
    }

    private static Triple iri(final String subject, final String predicate, final String object) {
        return new Triple(subject, predicate, new RdfNode.Resource(object));
    }

    private static Triple literal(final String subject, final String predicate, final String value) {
        return new Triple(subject, predicate, new RdfNode.Literal(value, null, null));
    }

    private static Triple literalLang(
            final String subject, final String predicate, final String value, final String lang) {
        return new Triple(subject, predicate, new RdfNode.Literal(value, null, lang));
    }
}
