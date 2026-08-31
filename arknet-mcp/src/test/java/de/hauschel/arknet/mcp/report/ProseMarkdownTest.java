// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The accepted Markdown subset of issue #388: what it recognises, and - at least as important -
 * what it leaves alone.
 */
@DisplayName("ProseMarkdown")
class ProseMarkdownTest {

    @Test
    @DisplayName("splits paragraphs at a blank line")
    void splitsParagraphsAtABlankLine() {
        final List<ProsePart> parts = ProseMarkdown.parts("first\n\nsecond", RichText::plain);

        assertThat(parts).containsExactly(
                new ProsePart.Paragraph(RichText.plain("first")),
                new ProsePart.Paragraph(RichText.plain("second")));
    }

    /** A hard-wrapped sentence is one sentence - the same rule Markdown itself applies. */
    @Test
    @DisplayName("collapses a single line break inside a paragraph")
    void collapsesASingleLineBreak() {
        final List<ProsePart> parts = ProseMarkdown.parts("one sentence\nwrapped here", RichText::plain);

        assertThat(parts).containsExactly(new ProsePart.Paragraph(RichText.plain("one sentence wrapped here")));
    }

    @Test
    @DisplayName("reads '- ' lines as a bullet list")
    void readsBulletLines() {
        final List<ProsePart> parts = ProseMarkdown.parts("lead-in:\n- first\n- second", RichText::plain);

        assertThat(parts).containsExactly(
                new ProsePart.Paragraph(RichText.plain("lead-in:")),
                new ProsePart.Bullets(List.of(RichText.plain("first"), RichText.plain("second"))));
    }

    /** A wrapped bullet stays one bullet, for the same reason a wrapped sentence does. */
    @Test
    @DisplayName("joins a bullet's continuation lines into one item")
    void joinsBulletContinuationLines() {
        final List<ProsePart> parts = ProseMarkdown.parts("- first item\n  continued\n- second", RichText::plain);

        assertThat(parts).containsExactly(
                new ProsePart.Bullets(List.of(RichText.plain("first item continued"), RichText.plain("second"))));
    }

    /**
     * The project's own prose uses {@code --} as a dash all the time, and a sentence opening with
     * one must not silently become a one-item list.
     */
    @Test
    @DisplayName("does not read a leading dash used as punctuation as a bullet")
    void doesNotReadALeadingDashAsABullet() {
        final List<ProsePart> parts = ProseMarkdown.parts("-- a dash, not a list", RichText::plain);

        assertThat(parts).containsExactly(new ProsePart.Paragraph(RichText.plain("-- a dash, not a list")));
    }

    @Test
    @DisplayName("recognises bold, italic and code")
    void recognisesTheInlineSubset() {
        final RichText text = ProseMarkdown.inline("a **b** and *c* and `d`", RichText::plain);

        assertThat(text.spans()).containsExactly(
                new Span.Plain("a "),
                new Span.Emphasis(Span.Style.STRONG, RichText.plain("b")),
                new Span.Plain(" and "),
                new Span.Emphasis(Span.Style.ITALIC, RichText.plain("c")),
                new Span.Plain(" and "),
                new Span.Code("d"));
    }

    @Test
    @DisplayName("nests italic inside bold")
    void nestsEmphasis() {
        final RichText text = ProseMarkdown.inline("**a *b* c**", RichText::plain);

        assertThat(text.spans()).containsExactly(new Span.Emphasis(Span.Style.STRONG, new RichText("a *b* c", List.of(
                new Span.Plain("a "),
                new Span.Emphasis(Span.Style.ITALIC, RichText.plain("b")),
                new Span.Plain(" c")))));
    }

    /**
     * Prose full of asterisks is prose, not broken markup. Swallowing an unpaired marker would
     * show the reader a literal the store does not hold.
     */
    @Test
    @DisplayName("leaves an unpaired marker as text")
    void leavesAnUnpairedMarkerAsText() {
        assertThat(ProseMarkdown.inline("2 * 3 * 4 = 24", RichText::plain).spans())
                .containsExactly(new Span.Plain("2 * 3 * 4 = 24"));
        assertThat(ProseMarkdown.inline("an **unclosed run", RichText::plain).spans())
                .containsExactly(new Span.Plain("an **unclosed run"));
        assertThat(ProseMarkdown.inline("a ` lone backtick", RichText::plain).spans())
                .containsExactly(new Span.Plain("a ` lone backtick"));
    }

    @Test
    @DisplayName("lets a backslash escape the subset's own syntax")
    void letsABackslashEscapeTheSyntax() {
        assertThat(ProseMarkdown.inline("a \\*literal\\* asterisk pair", RichText::plain).spans())
                .containsExactly(new Span.Plain("a *literal* asterisk pair"));
    }

    /** Every other backslash is text: a prose field is not a string literal. */
    @Test
    @DisplayName("leaves a backslash before anything else alone")
    void leavesAnOrdinaryBackslashAlone() {
        assertThat(ProseMarkdown.inline("C:\\temp and \\n", RichText::plain).spans())
                .containsExactly(new Span.Plain("C:\\temp and \\n"));
    }

    /**
     * The rejected half of the subset. A link is the one that matters: arknet establishes
     * references through the model and shows a missing edge as a {@link Span.TermGap}, so a
     * hand-written link would put a claimed reference where a checked one belongs.
     */
    @Test
    @DisplayName("leaves links, headings, tables and HTML as plain text")
    void leavesTheRejectedSyntaxAsText() {
        assertThat(ProseMarkdown.parts("# Heading\n\n[Actor](#term-3)\n\n| a | b |\n\n<b>x</b>", RichText::plain))
                .containsExactly(
                        new ProsePart.Paragraph(RichText.plain("# Heading")),
                        new ProsePart.Paragraph(RichText.plain("[Actor](#term-3)")),
                        new ProsePart.Paragraph(RichText.plain("| a | b |")),
                        new ProsePart.Paragraph(RichText.plain("<b>x</b>")));
    }

    /**
     * The store literal survives markup, character for character: the report finds a field's other
     * language variants by matching this text back against the subject's raw triples, and a text
     * that lost its markers would match nothing (issues #270, #388).
     */
    @Test
    @DisplayName("keeps the source literal alongside the parsed spans")
    void keepsTheSourceLiteral() {
        final String source = "a **b** and `c`";

        assertThat(ProseMarkdown.inline(source, RichText::plain).text()).isEqualTo(source);
        assertThat(ProseMarkdown.prose("Decision", source, RichText::plain).source()).isEqualTo(source);
    }

    /** A run inside backticks is a symbol the author quoted, not a sentence to analyse. */
    @Test
    @DisplayName("does not hand code runs to the inline marker")
    void doesNotMarkUpCodeRuns() {
        final List<String> seen = new ArrayList<>();

        ProseMarkdown.inline("see `arkreq:usesTerm` here", text -> {
            seen.add(text);
            return RichText.plain(text);
        });

        assertThat(seen).containsExactly("see ", " here");
    }

    /** An emphasised run is still a sentence, so it is analysed like any other. */
    @Test
    @DisplayName("hands emphasised runs to the inline marker")
    void marksUpEmphasisedRuns() {
        final List<String> seen = new ArrayList<>();

        ProseMarkdown.inline("the **customer** orders", text -> {
            seen.add(text);
            return RichText.plain(text);
        });

        assertThat(seen).containsExactly("the ", "customer", " orders");
    }

    /** A field nobody structured still arrives as one part, never as nothing. */
    @Test
    @DisplayName("yields a single paragraph for a blank literal")
    void yieldsASingleParagraphForABlankLiteral() {
        assertThat(ProseMarkdown.parts("", RichText::plain))
                .containsExactly(new ProsePart.Paragraph(RichText.plain("")));
    }
}
