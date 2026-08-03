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
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.mcp.mention.LabelMentions;
import de.hauschel.arknet.ul.domain.Term;

/**
 * The project's glossary, read once per report and used for two jobs: turning an opaque term
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
 * <p>A mention is a term's {@code skos:prefLabel} found in prose; the matching rules themselves
 * (case-insensitive, word-boundary, longest-label-first) are not repeated here - they live once,
 * for every caller, at {@link LabelMentions}. A {@link Term} holds exactly one label and no
 * synonyms, so there is nothing else to match against.</p>
 */
public final class Glossary {

    private final Map<ResourceId, Term> byId;
    private final List<Term> all;
    private final LabelMentions<Term> mentions;

    private Glossary(final List<Term> terms) {
        final Map<ResourceId, Term> ids = new LinkedHashMap<>();
        for (final Term term : terms) {
            ids.putIfAbsent(term.id().value(), term);
        }
        this.byId = Map.copyOf(ids);
        this.all = List.copyOf(ids.values());
        // Sorted by code so that two same-length labels break their matching tie
        // deterministically - LabelMentions.of only guarantees a stable sort of what it is given.
        final List<Term> forMatching = new ArrayList<>(ids.values());
        forMatching.sort(Comparator.comparing(term -> term.code().value()));
        this.mentions = LabelMentions.of(forMatching, Term::prefLabel);
    }

    /**
     * @param terms every term of the project, in any order
     * @return the glossary; a duplicate identity keeps its first occurrence, so a malformed
     *         store degrades the report rather than failing it
     */
    public static Glossary of(final List<Term> terms) {
        Objects.requireNonNull(terms, "terms");
        return new Glossary(terms);
    }

    /**
     * The glossary of a project whose terms could not be read. Chips then fall back to their
     * identity and no text is marked up - the report says less, but nothing false.
     *
     * @return an empty glossary
     */
    public static Glossary empty() {
        return new Glossary(List.of());
    }

    /**
     * @return every term of the project, in the order the glossary was read; the glossary
     *         section renders from this rather than reading the store a second time
     */
    public List<Term> terms() {
        return all;
    }

    /**
     * Null-tolerant on {@code id} itself, unlike {@link #ref(ResourceId)}: a caller that merely
     * checks whether a term is known (before deciding whether to look up a {@link Ref} at all)
     * should not have to null-check first.
     *
     * @param id the opaque term identity, e.g. from an {@code arkreq:usesTerm} edge, or
     *           {@code null}
     * @return the term it names, or {@code null} if {@code id} is {@code null} or this project
     *         has no such term - a dangling edge, which the caller renders as a dead reference
     *         rather than dropping
     */
    public Term term(final ResourceId id) {
        return id == null ? null : byId.get(id);
    }

    /**
     * The chip a reader should see for a term identity.
     *
     * <p>Unlike {@link #term(ResourceId)}, {@code id} must not be {@code null} here: a
     * {@link Ref} always needs an IRI to point at, so there is no honest fallback for "no
     * identity at all" the way there is for "an identity this project does not know". A caller
     * that may have no identity to resolve should not call this method with one.</p>
     *
     * @param id the opaque term identity an edge points at; never {@code null}
     * @return the term's label with its code as the tooltip; for an identity this project
     *         does not know, the bare IRI as its own label - a dangling edge stays visible
     *         instead of being styled away
     * @throws NullPointerException if {@code id} is {@code null}
     */
    public Ref ref(final ResourceId id) {
        Objects.requireNonNull(id, "id");
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
        final List<LabelMentions.Mention<Term>> found = mentions.in(text);
        if (found.isEmpty()) {
            return RichText.plain(text);
        }
        final List<Span> spans = new ArrayList<>();
        int cursor = 0;
        for (final LabelMentions.Mention<Term> mention : found) {
            if (mention.start() > cursor) {
                spans.add(new Span.Plain(text.substring(cursor, mention.start())));
            }
            final String matched = text.substring(mention.start(), mention.end());
            final String iri = mention.item().id().value().value();
            final String code = mention.item().code().value();
            spans.add(edges.contains(mention.item().id().value())
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
        return mentions.mentionedIn(texts).stream()
                .map(term -> term.id().value())
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
