// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.ul.domain.Term;

/**
 * The workspace's glossary, read once per report and used for two jobs: turning an opaque term
 * identity into the word a human agreed on, and finding those words inside other contexts'
 * prose.
 *
 * <p><strong>Why the whole glossary, not the referenced terms.</strong> The card builders used
 * to batch-resolve only the identities they held an edge to ({@code ResolveTerms}). That is
 * enough to label a chip, but it cannot answer the more interesting question - is a term
 * mentioned in a text that nothing links to it? Answering that needs every term, so the report
 * reads the glossary once (it renders it as a section anyway) and hands it to every builder.
 * One store round trip fewer than before, not one more.</p>
 *
 * <p><strong>Matching is deliberately literal.</strong> A mention is the term's
 * {@code skos:prefLabel}, compared case-insensitively and only at word boundaries. German
 * inflections ({@code Kunden} for {@code Kunde}) are therefore missed - the alternative,
 * matching on word stems, silently claims {@code Kundendienst} is a mention of {@code Kunde},
 * and a wrong link in an architecture model costs more than a missed one. A {@link Term} holds
 * exactly one label and no synonyms, so there is nothing else to match against.</p>
 */
public final class Glossary {

    private final Map<ResourceId, Term> byId;
    private final List<Term> all;
    private final List<Entry> entries;

    private Glossary(final List<Term> terms) {
        final Map<ResourceId, Term> ids = new LinkedHashMap<>();
        final List<Entry> matchers = new ArrayList<>();
        for (final Term term : terms) {
            if (ids.putIfAbsent(term.id().value(), term) == null) {
                matchers.add(new Entry(term, pattern(term.prefLabel())));
            }
        }
        matchers.sort(Comparator
                .comparingInt((Entry entry) -> entry.term().prefLabel().length()).reversed()
                .thenComparing(entry -> entry.term().code().value()));
        this.byId = Map.copyOf(ids);
        this.all = List.copyOf(ids.values());
        this.entries = List.copyOf(matchers);
    }

    /**
     * @param terms every term of the workspace, in any order
     * @return the glossary; a duplicate identity keeps its first occurrence, so a malformed
     *         store degrades the report rather than failing it
     */
    public static Glossary of(final List<Term> terms) {
        Objects.requireNonNull(terms, "terms");
        return new Glossary(terms);
    }

    /**
     * The glossary of a workspace whose terms could not be read. Chips then fall back to their
     * identity and no text is marked up - the report says less, but nothing false.
     *
     * @return an empty glossary
     */
    public static Glossary empty() {
        return new Glossary(List.of());
    }

    /**
     * @return every term of the workspace, in the order the glossary was read; the glossary
     *         section renders from this rather than reading the store a second time
     */
    public List<Term> terms() {
        return all;
    }

    /**
     * @param id the opaque term identity, e.g. from an {@code arkreq:usesTerm} edge
     * @return the term it names, or {@code null} if this workspace has no such term - a
     *         dangling edge, which the caller renders as a dead reference rather than dropping
     */
    public Term term(final ResourceId id) {
        return id == null ? null : byId.get(id);
    }

    /**
     * The chip a reader should see for a term identity.
     *
     * @param id the opaque term identity an edge points at
     * @return the term's label with its code as the tooltip; for an identity this workspace
     *         does not know, the bare IRI as its own label - a dangling edge stays visible
     *         instead of being styled away
     */
    public Ref ref(final ResourceId id) {
        final Term term = term(id);
        return term == null
                ? Ref.of(id.value(), id.value())
                : new Ref(term.prefLabel(), term.code().value(), id.value());
    }

    /**
     * Splits {@code text} into plain runs and glossary mentions.
     *
     * @param text   the prose to mark up
     * @param linked the term identities the owning model element actually links to; a mention
     *               of one of these becomes a {@link Span.TermLink}, any other mention a
     *               {@link Span.TermGap}
     * @return the marked-up text; concatenating the spans reproduces {@code text}
     */
    public RichText markUp(final String text, final Set<ResourceId> linked) {
        Objects.requireNonNull(text, "text");
        final Set<ResourceId> edges = linked == null ? Set.of() : linked;
        final List<Mention> mentions = mentions(text);
        if (mentions.isEmpty()) {
            return RichText.plain(text);
        }
        final List<Span> spans = new ArrayList<>();
        int cursor = 0;
        for (final Mention mention : mentions) {
            if (mention.start() > cursor) {
                spans.add(new Span.Plain(text.substring(cursor, mention.start())));
            }
            final String matched = text.substring(mention.start(), mention.end());
            final String iri = mention.term().id().value().value();
            final String code = mention.term().code().value();
            spans.add(edges.contains(mention.term().id().value())
                    ? new Span.TermLink(matched, iri, code)
                    : new Span.TermGap(matched, iri, code));
            cursor = mention.end();
        }
        if (cursor < text.length()) {
            spans.add(new Span.Plain(text.substring(cursor)));
        }
        return new RichText(spans);
    }

    /**
     * Which terms are mentioned anywhere in {@code texts}. Lets a card builder reduce its
     * "uses terms" chip list to the edges the prose does <em>not</em> already show.
     *
     * @param texts the texts to scan; {@code null} entries are skipped
     * @return the mentioned identities, in first-appearance order
     */
    public Set<ResourceId> mentionedIn(final Collection<String> texts) {
        if (texts == null) {
            return Set.of();
        }
        final Set<ResourceId> found = new LinkedHashSet<>();
        for (final String text : texts) {
            if (text != null) {
                for (final Mention mention : mentions(text)) {
                    found.add(mention.term().id().value());
                }
            }
        }
        return found;
    }

    /**
     * Every mention in one text, left to right and non-overlapping.
     *
     * <p>Longer labels are tried first, so a two-word term wins over a one-word term nested
     * inside it; of two mentions that overlap, the one starting earlier survives. Without that
     * an overlapping pair would produce spans whose concatenation no longer reproduces the
     * text - the one property that makes marking up safe.</p>
     */
    private List<Mention> mentions(final String text) {
        final List<Mention> hits = new ArrayList<>();
        for (final Entry entry : entries) {
            final Matcher matcher = entry.pattern().matcher(text);
            while (matcher.find()) {
                hits.add(new Mention(matcher.start(), matcher.end(), entry.term()));
            }
        }
        hits.sort(Comparator.comparingInt(Mention::start)
                .thenComparing(Comparator.comparingInt(Mention::end).reversed()));
        final List<Mention> kept = new ArrayList<>();
        int taken = 0;
        for (final Mention hit : hits) {
            if (hit.start() >= taken) {
                kept.add(hit);
                taken = hit.end();
            }
        }
        return kept;
    }

    /**
     * A whole-word, case-insensitive matcher for one label.
     *
     * <p>{@link Pattern#UNICODE_CHARACTER_CLASS} is what makes {@code \b} treat umlauts as word
     * characters; without it {@code Uebergabe} would have a "word boundary" right after the
     * space before it and never match. The boundary assertion is only added on a side where the
     * label itself starts or ends with a word character - {@code \b} before a label like
     * {@code (draft)} would demand the opposite of what is meant and match nothing.</p>
     */
    private static Pattern pattern(final String label) {
        final String prefix = isWordChar(label.charAt(0)) ? "\\b" : "";
        final String suffix = isWordChar(label.charAt(label.length() - 1)) ? "\\b" : "";
        return Pattern.compile(prefix + Pattern.quote(label) + suffix,
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.UNICODE_CHARACTER_CLASS);
    }

    private static boolean isWordChar(final char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    /** One term plus the compiled matcher for its label - compiled once per report, not per text. */
    private record Entry(Term term, Pattern pattern) {
    }

    /** One occurrence of a term's label in a text, as a half-open character range. */
    private record Mention(int start, int end, Term term) {
    }
}
