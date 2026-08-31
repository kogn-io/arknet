// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.Objects;

/**
 * One run of a {@link RichText}: either plain text, or a stretch of text recognised as a
 * glossary term.
 *
 * <p><strong>Why the text is not just a string.</strong> A requirement's description talks
 * about the ubiquitous language in prose ("the customer places an order"), while the model
 * carries that same relationship as an {@code arkreq:usesTerm} edge. Rendering the two apart -
 * prose here, a chip list there - leaves the reader to match them up by eye. Splitting the
 * prose into spans lets the renderer show, in the sentence itself, which words the model
 * actually knows about.</p>
 *
 * <p><strong>Two kinds of recognised term, deliberately distinct.</strong> {@link TermLink} is
 * a mention the model backs with an edge; {@link TermGap} is a mention of a term that exists in
 * the glossary while no edge records the relationship. Nothing enforces that edge - it is only
 * ever created by an explicit {@code req_link_term}/{@code bc_link_term} call - so the two
 * drift apart silently. Collapsing them into one span type would make the report claim
 * relationships the store does not hold; keeping them apart is what lets a reader see the
 * missing edge instead of being told a comfortable lie.</p>
 *
 * <p><strong>Author markup is a third kind of run.</strong> {@link Emphasis} and {@link Code}
 * come from the narrow Markdown subset the prose fields accept (issue #388), not from the model:
 * they say how the author meant a run to read, where {@link TermLink}/{@link TermGap} say what
 * the model knows about it. Keeping them as spans rather than letting the renderer re-scan the
 * text is what lets a second renderer (the Vaadin review UI of ADR-010) show the same structure
 * without a second text analysis.</p>
 *
 * <p>Sealed, so a new kind of span is a compile error in every renderer rather than silently
 * unrendered text.</p>
 */
public sealed interface Span {

    /** @return the text of this run, exactly as it appears in the source text. */
    String text();

    /**
     * Text that is not a glossary mention.
     *
     * @param text the run of text; never {@code null}
     */
    record Plain(String text) implements Span {
        public Plain {
            Objects.requireNonNull(text, "text");
        }
    }

    /**
     * A glossary term mentioned in the text <em>and</em> linked to by the model element that
     * owns the text - the healthy case, rendered as a link into the glossary.
     *
     * @param text the matched text as written (its case is the author's, not the term's)
     * @param iri  the term's subject IRI, so the renderer can link the mention to its card
     * @param code the term's business code (e.g. {@code TERM-1}), shown as a tooltip
     */
    record TermLink(String text, String iri, String code) implements Span {
        public TermLink {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(iri, "iri");
            Objects.requireNonNull(code, "code");
        }
    }

    /**
     * A business code written into the prose ({@code "see ADR-3"}) that names another carded
     * resource - rendered as a link to that card, with the card's title as its tooltip.
     *
     * <p>Unlike {@link TermLink}, this says nothing about an edge: a sentence naming a decision
     * is prose, not a relationship the model was supposed to record, and there is accordingly no
     * gap counterpart. A code that names nothing in this project is left as plain text - see
     * {@link CodeReferences}.</p>
     *
     * @param text  the matched text as written
     * @param iri   the referenced card's subject IRI
     * @param code  the referenced card's business code (e.g. {@code ADR-3})
     * @param title the referenced card's title, shown as a tooltip
     */
    record CodeRef(String text, String iri, String code, String title) implements Span {
        public CodeRef {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(iri, "iri");
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(title, "title");
        }
    }

    /**
     * A glossary term mentioned in the text with <em>no</em> edge from the owning model element
     * to it - a gap between what the prose says and what the model records.
     *
     * <p>Carries the term's identity as well, even though the renderer shows it as a dead
     * marker rather than a link: what makes this actionable is knowing <em>which</em> term
     * should have been linked.</p>
     *
     * @param text the matched text as written
     * @param iri  the term's subject IRI
     * @param code the term's business code (e.g. {@code TERM-1})
     */
    record TermGap(String text, String iri, String code) implements Span {
        public TermGap {
            Objects.requireNonNull(text, "text");
            Objects.requireNonNull(iri, "iri");
            Objects.requireNonNull(code, "code");
        }
    }

    /**
     * A run the author marked up as emphasised - {@code *italic*} or {@code **bold**} in the
     * accepted Markdown subset (issue #388).
     *
     * <p>Carries a nested {@link RichText} rather than a string, so that a term inside an
     * emphasised run is still recognised as a term: {@code **the Customer places an order**} keeps
     * its glossary link. {@link #text()} is the emphasised text <em>without</em> its markers -
     * the markers are syntax, not content, and no renderer should ever print them.</p>
     *
     * @param style   whether the author wrote one marker or two
     * @param content the emphasised text, itself marked up
     */
    record Emphasis(Style style, RichText content) implements Span {
        public Emphasis {
            Objects.requireNonNull(style, "style");
            Objects.requireNonNull(content, "content");
        }

        @Override
        public String text() {
            return content.text();
        }
    }

    /** How strongly an {@link Emphasis} run is emphasised. */
    enum Style {
        /** {@code *one marker*} - shown in italics. */
        ITALIC,
        /** {@code **two markers**} - shown in bold. */
        STRONG
    }

    /**
     * A run the author marked up as code - {@code `arkreq:usesTerm`} in the accepted Markdown
     * subset (issue #388).
     *
     * <p>Deliberately holds a plain string and no nested {@link RichText}: code is literal. A
     * predicate, a type name or a port written in backticks is an identifier, and marking it up as
     * a glossary mention would claim the model knows a relationship where the author only named a
     * symbol.</p>
     *
     * @param text the code text, without its backticks
     */
    record Code(String text) implements Span {
        public Code {
            Objects.requireNonNull(text, "text");
        }
    }
}
