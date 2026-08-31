// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/**
 * Reads the narrow Markdown subset a prose field accepts (issue #388) and turns it into the
 * card layer's own shapes - {@link ProsePart}s and {@link Span}s.
 *
 * <p><strong>The subset, and why it stops where it does.</strong> Accepted: {@code **bold**},
 * {@code *italic*}, {@code `code`}, {@code - } bullet lists, and paragraphs separated by a blank
 * line. Rejected, deliberately: links, headings, tables, images, embedded HTML.</p>
 *
 * <p>The link is the one that matters. arknet establishes references through the model and
 * validates them - a {@link Span.TermLink} is an {@code arkreq:usesTerm} edge the store actually
 * holds, and a {@link Span.TermGap} is the report refusing to hide a mention that has no edge
 * behind it. A hand-written {@code [Actor](#term-3)} would bypass both: a claimed reference in
 * place of a checked one. Headings in a single field say the record decides too much at once
 * (the answer is another record, not bigger markup); tables and images would make this a
 * document tool, which arknet is not.</p>
 *
 * <p><strong>Why here and not in a renderer.</strong> {@link Block} is a closed vocabulary of
 * shapes: the card builder decides the shape, the renderer decides the look. Parsing in
 * {@link HtmlReportRenderer} would put the author's structure behind one output channel, and the
 * Vaadin review UI of ADR-010 would have to re-derive it. The store literal itself stays raw -
 * nothing is rewritten on write, and {@code project_export} is untouched.</p>
 *
 * <p><strong>What this cannot do.</strong> The subset is not validated anywhere: SHACL sees a
 * string, and an unbalanced {@code **} simply stays literal text rather than being reported. That
 * is the accepted price - the alternative would be a write-time gate on prose, which is a much
 * bigger promise than "structure renders".</p>
 */
final class ProseMarkdown {

    private static final char EMPHASIS = '*';
    private static final char CODE = '`';
    private static final char ESCAPE = '\\';

    private ProseMarkdown() {
    }

    /**
     * Builds a prose block from a store literal, structuring it by the accepted subset.
     *
     * @param label  the block heading
     * @param source the store literal, exactly as read
     * @param inline how to mark up a run of plain text - typically {@code text ->
     *               glossary.markUp(text, linked)}, or {@link RichText#plain} for a field with no
     *               edges to compare against
     * @return the block; its {@link Block.Prose#source()} is {@code source} unchanged
     */
    static Block.Prose prose(final String label, final String source, final Function<String, RichText> inline) {
        Objects.requireNonNull(source, "source");
        return new Block.Prose(label, source, parts(source, inline));
    }

    /**
     * Splits a literal into paragraphs and bullet lists.
     *
     * @param source the store literal, exactly as read
     * @param inline how to mark up a run of plain text
     * @return the parts in reading order; never empty for a non-blank {@code source}
     */
    static List<ProsePart> parts(final String source, final Function<String, RichText> inline) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(inline, "inline");
        final List<ProsePart> parts = new ArrayList<>();
        final List<String> paragraph = new ArrayList<>();
        final List<List<String>> bullets = new ArrayList<>();
        for (final String line : source.split("\n", -1)) {
            final String trimmed = line.strip();
            if (trimmed.isEmpty()) {
                flush(parts, paragraph, bullets, inline);
                continue;
            }
            final String item = bulletItem(trimmed);
            if (item != null) {
                if (!paragraph.isEmpty()) {
                    flush(parts, paragraph, bullets, inline);
                }
                bullets.add(new ArrayList<>(List.of(item)));
            } else if (!bullets.isEmpty()) {
                bullets.getLast().add(trimmed);
            } else {
                paragraph.add(trimmed);
            }
        }
        flush(parts, paragraph, bullets, inline);
        return parts.isEmpty() ? List.of(new ProsePart.Paragraph(inline(source, inline))) : List.copyOf(parts);
    }

    /**
     * Marks up one run of text - a bullet item, a flow step, an acceptance criterion - for inline
     * markup only. A line break inside such a run is not structure, so nothing is split here.
     *
     * @param source the store literal, exactly as read
     * @param inline how to mark up a run of plain text
     * @return the marked-up text, carrying {@code source} as its {@link RichText#text()}
     */
    static RichText inline(final String source, final Function<String, RichText> inline) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(inline, "inline");
        return new RichText(source, spans(source, inline));
    }

    /**
     * The text of a {@code - } bullet line, or {@code null} if this is not one.
     *
     * <p>A single {@code -} followed by whitespace, and nothing else: the project's own prose is
     * full of {@code --} used as a dash, and a sentence opening with one must not silently become
     * a list.</p>
     */
    private static String bulletItem(final String trimmed) {
        if (trimmed.length() < 2 || trimmed.charAt(0) != '-' || !Character.isWhitespace(trimmed.charAt(1))) {
            return null;
        }
        final String text = trimmed.substring(1).strip();
        return text.isEmpty() ? null : text;
    }

    private static void flush(final List<ProsePart> parts, final List<String> paragraph,
            final List<List<String>> bullets, final Function<String, RichText> inline) {
        if (!paragraph.isEmpty()) {
            parts.add(new ProsePart.Paragraph(inline(String.join(" ", paragraph), inline)));
            paragraph.clear();
        }
        if (!bullets.isEmpty()) {
            parts.add(new ProsePart.Bullets(
                    bullets.stream().map(lines -> inline(String.join(" ", lines), inline)).toList()));
            bullets.clear();
        }
    }

    /**
     * Splits one run of text into spans, recognising {@code `code`} and {@code *emphasis*} and
     * handing every other stretch to {@code inline}.
     *
     * <p>An opening marker with no partner stays literal text: prose full of asterisks is prose,
     * not broken markup, and a report that swallowed the character would be lying about the
     * literal.</p>
     */
    private static List<Span> spans(final String text, final Function<String, RichText> inline) {
        if (text.indexOf(EMPHASIS) < 0 && text.indexOf(CODE) < 0 && text.indexOf(ESCAPE) < 0) {
            return inline.apply(text).spans();
        }
        final List<Span> spans = new ArrayList<>();
        final StringBuilder pending = new StringBuilder();
        int cursor = 0;
        while (cursor < text.length()) {
            final char current = text.charAt(cursor);
            if (current == ESCAPE && cursor + 1 < text.length() && isEscapable(text.charAt(cursor + 1))) {
                pending.append(text.charAt(cursor + 1));
                cursor += 2;
            } else if (current == CODE) {
                final int close = text.indexOf(CODE, cursor + 1);
                if (close < 0 || close == cursor + 1) {
                    pending.append(current);
                    cursor++;
                } else {
                    flushPending(spans, pending, inline);
                    spans.add(new Span.Code(text.substring(cursor + 1, close)));
                    cursor = close + 1;
                }
            } else if (current == EMPHASIS) {
                final int consumed = emphasis(text, cursor, spans, pending, inline);
                if (consumed == 0) {
                    pending.append(current);
                    cursor++;
                } else {
                    cursor += consumed;
                }
            } else {
                pending.append(current);
                cursor++;
            }
        }
        flushPending(spans, pending, inline);
        return spans;
    }

    /**
     * Tries to read an emphasis run starting at {@code start}.
     *
     * @return how many characters of {@code text} the run occupies, or {@code 0} if there is no
     *         well-formed run here
     */
    private static int emphasis(final String text, final int start, final List<Span> spans,
            final StringBuilder pending, final Function<String, RichText> inline) {
        final int markers = text.startsWith("**", start) ? 2 : 1;
        final int contentStart = start + markers;
        if (contentStart >= text.length() || Character.isWhitespace(text.charAt(contentStart))) {
            return 0;
        }
        final String closer = markers == 2 ? "**" : "*";
        int close = text.indexOf(closer, contentStart);
        while (close > contentStart && (Character.isWhitespace(text.charAt(close - 1))
                || (markers == 1 && isStrongMarker(text, close)))) {
            close = text.indexOf(closer, close + 1);
        }
        if (close <= contentStart) {
            return 0;
        }
        final String content = text.substring(contentStart, close);
        flushPending(spans, pending, inline);
        spans.add(new Span.Emphasis(markers == 2 ? Span.Style.STRONG : Span.Style.ITALIC,
                new RichText(content, spans(content, inline))));
        return close + markers - start;
    }

    /**
     * Whether the single {@code *} at {@code at} is really one half of a nested {@code **} run -
     * in which case it is not this run's closer, and the search moves past it.
     */
    private static boolean isStrongMarker(final String text, final int at) {
        return text.charAt(at - 1) == EMPHASIS
                || (at + 1 < text.length() && text.charAt(at + 1) == EMPHASIS);
    }

    private static void flushPending(final List<Span> spans, final StringBuilder pending,
            final Function<String, RichText> inline) {
        if (pending.isEmpty()) {
            return;
        }
        spans.addAll(inline.apply(pending.toString()).spans());
        pending.setLength(0);
    }

    /** Only the subset's own syntax characters are escapable; every other backslash is text. */
    private static boolean isEscapable(final char character) {
        return character == EMPHASIS || character == CODE || character == ESCAPE;
    }
}
