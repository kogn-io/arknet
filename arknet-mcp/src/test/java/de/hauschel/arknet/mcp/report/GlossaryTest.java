// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * The glossary decides two things a reader depends on: what a term chip says, and which words
 * of a text count as a mention of the ubiquitous language. Both are easy to get subtly wrong -
 * a missed umlaut, a stem match that invents a relationship - so they are pinned here.
 */
class GlossaryTest {

    private static final String ID = "https://w3id.org/arknet/id/";
    private static final ResourceId KUNDE = ResourceId.of(ID + "term-1");
    private static final ResourceId BESTELLUNG = ResourceId.of(ID + "term-2");

    private static final Glossary GLOSSARY = Glossary.of(List.of(
            term(KUNDE, "TERM-1", "Kunde"),
            term(BESTELLUNG, "TERM-2", "Bestellung")));

    @Test
    void showsATermByItsLabelWithItsCodeAsTooltip() {
        assertThat(GLOSSARY.ref(KUNDE)).isEqualTo(new Ref("Kunde", "TERM-1", KUNDE.value()));
    }

    /** A dangling edge stays visible as itself rather than being dropped from the report. */
    @Test
    void fallsBackToTheBareIriForAnIdentityItDoesNotKnow() {
        final ResourceId unknown = ResourceId.of(ID + "term-404");

        assertThat(GLOSSARY.ref(unknown)).isEqualTo(Ref.of(unknown.value(), unknown.value()));
    }

    @Test
    void findsALabelRegardlessOfCase() {
        final RichText text = GLOSSARY.markUp("kunde und Kunde", Set.of(KUNDE));

        assertThat(text.spans()).containsExactly(
                new Span.TermLink("kunde", KUNDE.value(), "TERM-1"),
                new Span.Plain(" und "),
                new Span.TermLink("Kunde", KUNDE.value(), "TERM-1"));
    }

    /**
     * The deliberate limit of literal matching: an inflected form is missed. Accepting stems
     * instead would make "Kundendienst" a mention of "Kunde" - a link the model never agreed
     * to, and a wrong edge in an architecture model costs more than a missing one.
     */
    @Test
    void missesInflectedFormsAndDoesNotMatchInsideALongerWord() {
        assertThat(GLOSSARY.markUp("dem Kunden im Kundendienst", Set.of(KUNDE)).spans())
                .containsExactly(new Span.Plain("dem Kunden im Kundendienst"));
    }

    /**
     * {@code \b} only treats an umlaut as a word character under
     * {@link java.util.regex.Pattern#UNICODE_CHARACTER_CLASS} - without it a label starting with
     * one silently never matches, which is the kind of gap nobody notices in a German model.
     */
    @Test
    void matchesALabelThatStartsWithAnUmlaut() {
        final ResourceId id = ResourceId.of(ID + "term-3");
        // "Uebergabe" with a real U-umlaut, escaped to keep every source file ASCII.
        final String label = "\u00dcbergabe";
        final Glossary glossary = Glossary.of(List.of(term(id, "TERM-3", label)));

        assertThat(glossary.markUp("Die " + label + " erfolgt.", Set.of(id)).spans())
                .contains(new Span.TermLink(label, id.value(), "TERM-3"));
    }

    /** Of two terms competing for the same words, the longer label wins. */
    @Test
    void prefersTheLongerLabelWhenTwoTermsOverlap() {
        final ResourceId offene = ResourceId.of(ID + "term-9");
        final Glossary glossary = Glossary.of(List.of(
                term(BESTELLUNG, "TERM-2", "Bestellung"),
                term(offene, "TERM-9", "offene Bestellung")));

        assertThat(glossary.markUp("Eine offene Bestellung wartet.", Set.of(offene)).spans())
                .contains(new Span.TermLink("offene Bestellung", offene.value(), "TERM-9"));
    }

    /**
     * Marking up must never lose or reorder a character - the rendered text has to stay the
     * text the author wrote.
     */
    @Test
    void reproducesTheOriginalTextExactly() {
        final String original = "Der Kunde legt eine Bestellung an, und der Kunde zahlt.";

        assertThat(GLOSSARY.markUp(original, Set.of(KUNDE)).text()).isEqualTo(original);
    }

    /**
     * A mention with an edge behind it is a link; a mention of a term nothing links to is a gap.
     * Rendering both the same way would let the report claim a relationship the store does not
     * hold - and hide the missing edge, which is the actionable half.
     */
    @Test
    void separatesLinkedMentionsFromUnlinkedOnes() {
        final RichText text = GLOSSARY.markUp("Der Kunde legt eine Bestellung an.", Set.of(KUNDE));

        assertThat(text.spans()).containsExactly(
                new Span.Plain("Der "),
                new Span.TermLink("Kunde", KUNDE.value(), "TERM-1"),
                new Span.Plain(" legt eine "),
                new Span.TermGap("Bestellung", BESTELLUNG.value(), "TERM-2"),
                new Span.Plain(" an."));
    }

    @Test
    void reportsWhichTermsATextMentions() {
        assertThat(GLOSSARY.mentionedIn(List.of("Der Kunde wartet.", "Nichts hier.")))
                .containsExactly(KUNDE);
    }

    /** An unreadable glossary must not take the report with it: nothing matches, nothing throws. */
    @Test
    void anEmptyGlossaryMarksUpNothing() {
        assertThat(Glossary.empty().markUp("Der Kunde wartet.", Set.of(KUNDE)).spans())
                .containsExactly(new Span.Plain("Der Kunde wartet."));
    }

    private static Term term(final ResourceId id, final String code, final String label) {
        return new Term(new TermId(id), new TermCode(code), label, "Definition von " + label + ".", null);
    }
}
