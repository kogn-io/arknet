package de.hauschel.arknet.kernel;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;

/**
 * The display language a consumer reads arknet in - which language-tagged literal to show
 * when the same resource carries a label in several languages.
 *
 * <p>RDF literals are language-tag-capable and multilingual glossaries are SKOS textbook:
 * {@code skos:prefLabel "Kunde"@de} and {@code "Customer"@en} on the same concept are both
 * shape-legal. arknet does not guess which one to show - the <em>consumer</em> states the
 * language it wants to read in. This is a <strong>shared kernel</strong> concept exactly like
 * {@link WorkspaceId}: one value per process, configured once in the composition root and
 * injected into the bounded contexts. Unlike {@code WorkspaceId} it does not flow as a method
 * parameter through every port call - the language choice must be made <em>before</em> a domain
 * value object (whose label field is a single {@code String}) is constructed, i.e. inside the
 * out-adapter that reads the store.</p>
 *
 * <h2>Fallback chain, never a hard filter</h2>
 *
 * <p>A hard {@code FILTER(lang(?label) = "de")} would be a regression: a resource that lacks a
 * label in the requested language would bind nothing and vanish silently. {@link #select} instead
 * degrades through a fixed chain, so display may show the wrong language but a record is never
 * swallowed and this method never throws:</p>
 *
 * <ol>
 *   <li>a literal tagged with the {@link #requested} language;</li>
 *   <li>else a literal tagged with the {@link #systemDefault} language;</li>
 *   <li>else a plain, untagged literal (today's normal case - {@code term_add} writes untagged);</li>
 *   <li>else <em>some</em> literal, but chosen <strong>deterministically</strong> - two calls
 *       with the same candidate set return the same value (a stable Java-side ordering over the
 *       collected candidates, never the incidental row order a store returns).</li>
 * </ol>
 *
 * <p>SKOS integrity condition S14 (a resource has at most one {@code skos:prefLabel} per language
 * tag) makes steps 1 and 2 an exact match rather than merely a likely one; the deterministic
 * ordering only matters for store-first data that breaks S14 or for step 4.</p>
 *
 * @param requested     the language the consumer asked to read in (never {@code null})
 * @param systemDefault the system fallback language (never {@code null})
 */
public record DisplayLocale(Locale requested, Locale systemDefault) {

    /**
     * The default preference (English requested, English fallback). A sensible neutral default
     * for a store whose labels are, today, written untagged - step 3 of the chain catches them
     * regardless of this value.
     */
    public static final DisplayLocale DEFAULT = new DisplayLocale(Locale.ENGLISH, Locale.ENGLISH);

    /**
     * Total, stable ordering over candidate literals: untagged first (a {@code null} tag sorts
     * before any tag), then by language tag, then by lexical form. Guarantees a deterministic
     * choice at every step where more than one candidate qualifies - step 4 in particular.
     */
    private static final Comparator<LocalizedLiteral> CANONICAL_ORDER =
            Comparator.comparing(LocalizedLiteral::languageTag,
                            Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(LocalizedLiteral::value);

    public DisplayLocale {
        Objects.requireNonNull(requested, "requested");
        Objects.requireNonNull(systemDefault, "systemDefault");
    }

    /**
     * Selects the literal to display from a set of candidates, per the fallback chain documented
     * on this type. Never throws; returns {@link Optional#empty()} only if {@code candidates} is
     * empty (the caller decides what an empty candidate set means - a required join guarantees at
     * least one).
     *
     * @param candidates the language-tagged (and/or untagged) values found for one resource;
     *                   must not be {@code null}
     * @return the chosen literal, or empty if there were no candidates at all
     */
    public Optional<LocalizedLiteral> select(Collection<LocalizedLiteral> candidates) {
        Objects.requireNonNull(candidates, "candidates");

        Optional<LocalizedLiteral> requestedMatch = matching(candidates, requested);
        if (requestedMatch.isPresent()) {
            return requestedMatch;
        }
        Optional<LocalizedLiteral> defaultMatch = matching(candidates, systemDefault);
        if (defaultMatch.isPresent()) {
            return defaultMatch;
        }
        Optional<LocalizedLiteral> untagged = candidates.stream()
                .filter(LocalizedLiteral::isUntagged)
                .min(CANONICAL_ORDER);
        if (untagged.isPresent()) {
            return untagged;
        }
        return candidates.stream().min(CANONICAL_ORDER);
    }

    /**
     * The best candidate whose language tag matches {@code locale}'s primary language subtag,
     * compared case-insensitively (so {@code "de"} matches {@code "de"} and {@code "de-DE"}).
     * Among those, an exact full-tag match (e.g. {@code "en-US"} for a requested {@code "en-US"})
     * wins first - SKOS S14 binds to the full tag, not merely the primary subtag, so a region- or
     * script-specific candidate must not lose to an unrelated sibling region. Only if none matches
     * exactly does {@link #CANONICAL_ORDER} pick among the primary-language matches. If several
     * qualify at either level (store-first data breaking S14), {@link #CANONICAL_ORDER} still picks
     * one deterministically.
     */
    private static Optional<LocalizedLiteral> matching(Collection<LocalizedLiteral> candidates, Locale locale) {
        String language = locale.getLanguage();
        if (language.isEmpty()) {
            return Optional.empty();
        }
        List<LocalizedLiteral> sameLanguage = candidates.stream()
                .filter(candidate -> !candidate.isUntagged())
                .filter(candidate -> Locale.forLanguageTag(candidate.languageTag())
                        .getLanguage().equalsIgnoreCase(language))
                .toList();

        String requestedTag = locale.toLanguageTag();
        Optional<LocalizedLiteral> exactMatch = sameLanguage.stream()
                .filter(candidate -> Locale.forLanguageTag(candidate.languageTag())
                        .toLanguageTag().equalsIgnoreCase(requestedTag))
                .min(CANONICAL_ORDER);
        if (exactMatch.isPresent()) {
            return exactMatch;
        }
        return sameLanguage.stream().min(CANONICAL_ORDER);
    }
}
