// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;

/**
 * The report renders the model the way it was authored, and still cannot lose anything the
 * store holds.
 */
class HtmlReportRendererTest {

    private static final WorkspaceId WORKSPACE = new WorkspaceId("report-test");
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String ID = "https://w3id.org/arknet/id/";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    private static final String UC_1 = ID + "uc-1";
    private static final String STEP_1 = ID + "step-1";
    private static final String STEP_2 = ID + "step-2";
    private static final String FR_1 = ID + "fr-1";
    private static final String REVISION = ID + "revision-1";

    private final HtmlReportRenderer renderer = new HtmlReportRenderer(Prefixes.defaults());

    /**
     * The whole point of the exercise: a use case reads as a use case - goal, actor, numbered
     * flow - not as the opaque step subjects it is stored as.
     */
    @Test
    void rendersAUseCaseAsAFlowRatherThanAsItsTriples() {
        final String html = renderer.render(WORKSPACE, snapshot(), "digest", views(useCaseSection()));

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
        // The primary actor shows the term itself and links to the actor's own resource; its
        // running number is the tooltip, and the opaque identity stays in the raw triples.
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
        final String html = renderer.render(WORKSPACE, snapshot(), "digest", views(useCaseSection()));

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
        final String html = renderer.render(WORKSPACE, snapshot(), "digest", views(useCaseSection()));

        assertThat(html).contains("id=\"sec-other\"");
        assertThat(html).contains("id=\"r-" + anchorOf(REVISION) + "\"");
        assertThat(html).contains("id=\"r-" + anchorOf(FR_1) + "\"");
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

        final String html = renderer.render(WORKSPACE, snapshot, "digest", views());

        assertThat(html).contains("id=\"r-" + anchorOf(orphan) + "\"");
    }

    /** Every card keeps its raw triples one click away, so the model view never has to be trusted blindly. */
    @Test
    void hangsTheRawTriplesOffEveryCard() {
        final String html = renderer.render(WORKSPACE, snapshot(), "digest", views(useCaseSection()));

        assertThat(html).contains("<details class=\"raw\">");
        assertThat(html).contains("raw triples");
        assertThat(html).contains("arkreq:mainStep");
    }

    /** A section that could not be read is stated, not silently dropped - a missing section reads as "empty store". */
    @Test
    void showsSectionFailuresInsteadOfPretendingTheStoreIsEmpty() {
        final ModelViews.Views views = new ModelViews.Views(
                List.of(), List.of("Use Cases: could not be read (IllegalStateException: store closed)"));

        final String html = renderer.render(WORKSPACE, snapshot(), "digest", views);

        assertThat(html).contains("Incomplete report").contains("store closed");
    }

    /** A reference whose target is not in this workspace stays visible as a dead chip. */
    @Test
    void marksAReferenceThatIsNotInTheWorkspace() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-9", "Gone", FR_1, List.of(),
                        List.of(new Block.Refs("Uses terms",
                                List.of(new Ref("Lieferschein", "TERM-404", ID + "nowhere")))))));

        final String html = renderer.render(WORKSPACE, snapshot(), "digest", views(section));

        assertThat(html).contains(
                "<span class=\"chip dead\" title=\"TERM-404 - not in this workspace\">Lieferschein</span>");
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
                        new Block.Prose("Description", new RichText(List.of(
                                new Span.Plain("Der "),
                                new Span.TermLink("Kunde", ID + "actor-1", "TERM-1"),
                                new Span.Plain(" legt eine "),
                                new Span.TermGap("Bestellung", ID + "term-2", "TERM-2"),
                                new Span.Plain(" an."))))))));

        final String html = renderer.render(WORKSPACE, snapshot(), "digest", views(section));

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
        final String html = renderer.render(WORKSPACE, snapshot(), "digest", views(useCaseSection()));

        assertThat(html).contains("<details class=\"fold\">").doesNotContain("<details class=\"fold\" open>");
        assertThat(html).contains("<summary class=\"head\">");
        assertThat(html).contains("id=\"expand-all\"").contains("id=\"collapse-all\"");
        assertThat(html).contains("window.addEventListener('hashchange', reveal);");
    }

    /** With nothing in the store at all, the report says so and names the way in. */
    @Test
    void tellsTheReaderWhereToStartWhenTheWorkspaceIsEmpty() {
        final String html = renderer.render(WORKSPACE, StoreSnapshot.of(List.of()), "digest", views());

        assertThat(html).contains("holds no model yet").contains("uc_add");
    }

    /** The report must stay openable from a file:// URL with no network. */
    @Test
    void staysSelfContained() {
        final String html = renderer.render(WORKSPACE, snapshot(), "digest", views(useCaseSection()));

        assertThat(html).doesNotContain("<script src").doesNotContain("<link rel=\"stylesheet\"")
                .doesNotContain("http://cdn").doesNotContain("https://cdn");
    }

    /** Free text from the store is escaped, not injected into the document. */
    @Test
    void escapesModelText() {
        final ModelSection section = new ModelSection("Requirements", "requirements", "", List.of(
                new ModelCard("FR-1", "<script>alert(1)</script>", FR_1, List.of(),
                        List.of(Block.Prose.plain("Description", "a & b < c")))));

        final String html = renderer.render(WORKSPACE, snapshot(), "digest", views(section));

        assertThat(html).doesNotContain("<script>alert(1)</script>");
        assertThat(html).contains("&lt;script&gt;alert(1)&lt;/script&gt;").contains("a &amp; b &lt; c");
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
                                new FlowStep(1, "Kunde legt Artikel in den Warenkorb",
                                        List.of(Ref.of("FR-1", FR_1))),
                                new FlowStep(2, "System bestaetigt die Bestellung", List.of())))))));
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

    private static String anchorOf(final String iri) {
        return iri.replaceAll("[^A-Za-z0-9]+", "-").replaceAll("^-|-$", "");
    }

    private static Triple iri(final String subject, final String predicate, final String object) {
        return new Triple(subject, predicate, new RdfNode.Resource(object));
    }

    private static Triple literal(final String subject, final String predicate, final String value) {
        return new Triple(subject, predicate, new RdfNode.Literal(value, null, null));
    }
}
