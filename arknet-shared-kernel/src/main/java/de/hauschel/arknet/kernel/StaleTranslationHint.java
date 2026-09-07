// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.SequencedMap;
import java.util.Set;

/**
 * The signal a single-language write leaves behind (kogn-io/arknet#474): every {@code *_update}
 * writes exactly one language, so correcting the German definition leaves the English one
 * standing - without an error and, before this, without a word. This renders that word, once,
 * for all seven update tools that touch a multilingual field.
 *
 * <p><strong>Never blocks, never guesses.</strong> The result is a block the tool appends to its
 * answer once the write has succeeded, nothing more. A hint is produced only where all four of
 * its inputs actually say something:</p>
 *
 * <ul>
 *   <li>the call wrote a language (a write that resolved to no tag at all - {@code
 *       project_update} without a {@code language} - has nothing to compare against);</li>
 *   <li>the project maintains a language other than the one written (an empty or single-entry
 *       set is no promise, and without a promise incompleteness is undefined - the whole point of
 *       kogn-io/arknet#412);</li>
 *   <li>the field being written already carried the written language <em>before</em> the call.
 *       A field that did not is being <em>translated</em>, not corrected: the other variant is
 *       the source the caller just rendered into a further language, and nothing about it is
 *       out of date. This is the second half of this repository's own two-call workflow, and
 *       for an ADR outside {@code PROPOSED} it is the only write its text fields still accept -
 *       a hint there would recommend the very call the aggregate rejects;</li>
 *   <li>the field actually carries that other language. A field that does not is a
 *       <em>gap</em>, which {@code store_check}'s LANGUAGE check reports; calling it stale here
 *       would report the same thing twice and get it wrong once.</li>
 * </ul>
 *
 * <h2>Before the write, not after</h2>
 *
 * <p>The third rule is only decidable from the state <em>before</em> the write - afterwards the
 * field carries the written language whether the call corrected it or added it. So a tool asks
 * this class before it calls its service and appends the answer after the service returned:
 * one store read more when the write then fails, and a snapshot a concurrent writer may age by
 * the time it is shown, both of which a hint can afford - a concurrent writer can only ever make
 * the snapshot older, never make it describe a field this call itself emptied.</p>
 *
 * <p>What the snapshot rests on is that a write leaves every other language's variant of the
 * field it writes standing. That holds for a <em>direct literal field</em>, and it is the reason
 * the fourth rule may be read off the state before. It does <strong>not</strong> hold for a field
 * pooled over a child edge ({@code acceptanceCriterion}, {@code mainStep}, {@code consequence},
 * {@code consideredOption}): the tags there are those of every child hanging off the edge
 * together, and the same {@code *_update} that writes one child may remove another, taking the
 * last carrier of a language with it. Naming that edge would then describe a state the caller no
 * longer has. Resolving it is each caller's job, not this class's: a tool reports such an edge as
 * written only when its call removes nothing under it (kogn-io/arknet#537 review).</p>
 *
 * <h2>What the store can and cannot say</h2>
 *
 * <p>The wording deliberately stops at "this call did not write it". The shared write funnel
 * records one PROV-O revision <em>per resource</em> per write, never one per literal, so there is
 * no revision to attribute an individual language variant to and no timestamp to call it older
 * by. What is certain is narrower and enough: a write resolves exactly one language tag per field
 * ({@link LanguageTag#resolveWriteLanguage}), so any other tag the field carries afterwards was
 * put there by an earlier write. The hint says that and no more - inventing a per-language
 * revision would be a claim the store cannot back.</p>
 *
 * <p>The one write that is not single-language is {@code term_update}'s rename (a {@code label}
 * without a {@code language} renames the term under every tag at once); its caller leaves {@code
 * prefLabel} out of the fields it reports here, because nothing about it is left behind.</p>
 */
public final class StaleTranslationHint {

    private final FieldLanguageLookup fieldLanguages;

    /**
     * @param fieldLanguages the lookup answering which tags a field already carries; the
     *                       composition root's store-backed implementation in production, a stub
     *                       in a tool-level test
     */
    public StaleTranslationHint(final FieldLanguageLookup fieldLanguages) {
        this.fieldLanguages = Objects.requireNonNull(fieldLanguages, "fieldLanguages");
    }

    /**
     * The hint for a write to a model resource, to be asked <em>before</em> the write and
     * appended to that tool's answer after it succeeded (see the class comment for why).
     *
     * @param projectId           the project the write targets
     * @param code                the resource's business code (e.g. {@code FR-3})
     * @param writtenLanguage     the BCP-47 tag this call writes under, or {@code null} if it
     *                            writes none
     * @param maintainedLanguages the project's declared language set
     *                            ({@link ResolvedProject#maintainedLanguages()})
     * @param fieldsWritten       the multilingual fields this call is about to write, as
     *                            {@link FieldLanguageLookup} keys, in the order they should be
     *                            reported - a child edge under which the same call also removes
     *                            a child belongs left out (see the class comment)
     * @return the block to append - empty when there is nothing to report, otherwise separated
     *         from the answer above it by a blank line
     */
    public String forResource(final ProjectId projectId, final String code, final String writtenLanguage,
            final List<String> maintainedLanguages, final List<String> fieldsWritten) {
        if (nothingCanBeStale(writtenLanguage, maintainedLanguages, fieldsWritten)) {
            return "";
        }
        return section(fieldLanguages.ofResource(projectId, code), writtenLanguage, maintainedLanguages,
                fieldsWritten);
    }

    /**
     * The hint for a write to a project's own registry record ({@code project_update}) - same
     * rules and the same timing, different lookup, because that record lives in the reserved
     * system dataset rather than in the project's own (see
     * {@link FieldLanguageLookup#ofProjectRegistration}).
     *
     * @param projectLabel        the project's label, as its registry record carries it
     * @param writtenLanguage     the BCP-47 tag this call writes under, or {@code null}
     * @param maintainedLanguages the project's declared language set as this call will
     *                            <em>leave</em> it - {@code project_update} can change the set in
     *                            the same breath as the description, and the promise that counts
     *                            is the one in force once it returns
     * @param fieldsWritten       the multilingual fields this call is about to write
     * @return the block to append, as in {@link #forResource}
     */
    public String forProjectRegistration(final String projectLabel, final String writtenLanguage,
            final List<String> maintainedLanguages, final List<String> fieldsWritten) {
        if (nothingCanBeStale(writtenLanguage, maintainedLanguages, fieldsWritten)) {
            return "";
        }
        return section(fieldLanguages.ofProjectRegistration(projectLabel), writtenLanguage, maintainedLanguages,
                fieldsWritten);
    }

    /**
     * Renders the hint from the three facts it needs, with no lookup involved - the whole rule
     * set of this class, and the seam the rules are tested through.
     *
     * @param writtenLanguage     the BCP-47 tag this call writes under, or {@code null}/blank if
     *                            it writes none
     * @param maintainedLanguages the project's declared language set, in the order the project
     *                            declared it (the order the hint lists them in)
     * @param languagesByField    the fields this call writes, in reporting order, each mapped to
     *                            the tags that field carries in the store <em>before</em> the
     *                            write - a field not yet carrying the written language is being
     *                            translated and never reported
     * @return the hint, or the empty string when nothing qualifies
     */
    public static String render(final String writtenLanguage, final List<String> maintainedLanguages,
            final SequencedMap<String, Set<String>> languagesByField) {
        Objects.requireNonNull(maintainedLanguages, "maintainedLanguages");
        Objects.requireNonNull(languagesByField, "languagesByField");
        final List<String> otherLanguages = otherLanguages(writtenLanguage, maintainedLanguages);
        if (otherLanguages.isEmpty() || languagesByField.isEmpty()) {
            return "";
        }
        final SequencedMap<String, List<String>> fieldsByLanguage = new LinkedHashMap<>();
        for (final String language : otherLanguages) {
            final List<String> fields = languagesByField.entrySet().stream()
                    .filter(field -> containsIgnoringCase(field.getValue(), writtenLanguage))
                    .filter(field -> containsIgnoringCase(field.getValue(), language))
                    .map(Map.Entry::getKey)
                    .toList();
            if (!fields.isEmpty()) {
                fieldsByLanguage.put(language, fields);
            }
        }
        if (fieldsByLanguage.isEmpty()) {
            return "";
        }
        final StringBuilder hint = new StringBuilder("Possibly stale translations - this call wrote '")
                .append(writtenLanguage)
                .append("'; these fields also carry a maintained language it did not write:");
        fieldsByLanguage.forEach((language, fields) ->
                hint.append("\n  ").append(language).append(": ").append(String.join(", ", fields)));
        return hint.append("\nRepeat the call under that language to bring them in line.").toString();
    }

    /**
     * Whether the answer is already known to be "no hint" without asking the store - the guard
     * that keeps a single-language project, and a call that touched no multilingual field, from
     * paying for a read whose result cannot matter.
     */
    private static boolean nothingCanBeStale(final String writtenLanguage, final List<String> maintainedLanguages,
            final List<String> fieldsWritten) {
        return fieldsWritten.isEmpty() || otherLanguages(writtenLanguage, maintainedLanguages).isEmpty();
    }

    /** The maintained languages this call did not write, in the order the project declared them. */
    private static List<String> otherLanguages(final String writtenLanguage,
            final List<String> maintainedLanguages) {
        if (writtenLanguage == null || writtenLanguage.isBlank()) {
            return List.of();
        }
        return maintainedLanguages.stream()
                .filter(language -> !language.equalsIgnoreCase(writtenLanguage))
                .distinct()
                .toList();
    }

    /**
     * Restricts the lookup's answer to the fields this call is about to write, in the order the
     * caller named them, and renders the hint over that - a field the lookup knows nothing about
     * contributes an empty tag set and therefore never a line.
     */
    private static String section(final Map<String, Set<String>> inStore, final String writtenLanguage,
            final List<String> maintainedLanguages, final List<String> fieldsWritten) {
        final SequencedMap<String, Set<String>> languagesByField = new LinkedHashMap<>();
        for (final String field : fieldsWritten) {
            languagesByField.put(field, inStore.getOrDefault(field, Set.of()));
        }
        final String hint = render(writtenLanguage, maintainedLanguages, languagesByField);
        return hint.isEmpty() ? "" : "\n\n" + hint;
    }

    /** Tag comparison is case-insensitive, exactly as {@code store_check}'s LANGUAGE check is. */
    private static boolean containsIgnoringCase(final Set<String> tags, final String language) {
        return tags.stream().anyMatch(tag -> tag.equalsIgnoreCase(language));
    }
}
