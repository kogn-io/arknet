// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.in;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Driving port: correct the preferred label and/or definition of an already-created term,
 * keeping its identity (and thus every existing {@code arkreq:usesTerm}/{@code
 * arkddd:ubiquitousLanguageTerm}/{@code skos:broader} link into it) intact.
 *
 * <p>Backs the MVP tool {@code term_update}. Before this port existed, correcting a
 * term's wording meant registering a fresh one via {@link AddTerm} - which mints a new identity
 * and orphans every existing link to the old one. As with the requirements bounded context's
 * sibling {@code UpdateRequirement} port, every field here is optional: {@code null}
 * leaves that field unchanged, so a caller can correct only the definition without having to
 * restate the label.</p>
 *
 * <p>Every argument here passes straight through to {@link
 * de.hauschel.arknet.ul.application.port.out.TermRepository#update} unmerged - this port's
 * implementation must not pre-read the current term and fold an omitted field's old value into a
 * fresh {@link Term} before writing it, which would round-trip that field through {@link Term}'s
 * single-{@code String} projection and silently collapse a multi-valued {@code skos:prefLabel}/
 * {@code skos:definition} down to one value even though the caller never asked
 * to change it. "Not provided" and "provided" must stay distinguishable all the way to the
 * out-adapter, which is the only place that knows how to leave an untouched predicate's triples
 * alone.</p>
 *
 * <p><strong>Broader (issue #252).</strong> Unlike every other field on this port, {@code
 * broader} genuinely needs three states, not two - "leave unchanged" and "clear an already-set
 * broader term" are different signals, and this port's usual {@code null}-means-unchanged
 * sentinel can only ever mean one of them. {@code broader} is therefore a {@code null}-or-{@link
 * Optional} argument: {@code null} (the outer reference itself) leaves an already-set broader
 * term untouched, exactly like every other field here; a non-{@code null} {@link
 * Optional#empty()} explicitly clears it; a non-{@code null} {@link Optional#of} sets/replaces
 * it. Setting a new broader term is resolved and cycle-checked against the target project's own
 * glossary - a term must not become its own (direct or transitive) broader term.</p>
 *
 * <p><strong>Related (kogn-io/arknet#420).</strong> {@code related} needs the same three states,
 * but being multi-valued it can express them without an extra wrapper, exactly as the ADR bounded
 * context's own reference lists do: {@code null} leaves the existing peers untouched, an empty list
 * clears them, a non-empty list replaces them wholesale. Only the forward direction is written -
 * {@code skos:related} is an {@code owl:SymmetricProperty}, so the returned {@link Term} carries
 * the merged view of both directions, and clearing this term's own peers does not remove an edge
 * another term asserts towards it (clear that one with a {@code term_update} on the other term).</p>
 */
public interface UpdateTerm {

    /**
     * Updates the term identified by {@code code} within a project, leaving any {@code null}
     * argument unchanged.
     *
     * @param projectId       the project (architecture model) the term lives in
     * @param code            the term code, e.g. {@code TERM-1}
     * @param prefLabel       the new preferred label, or {@code null} to leave it unchanged
     * @param definition      the new definition, or {@code null} to leave it unchanged
     * @param language        the BCP-47 language tag the new {@code prefLabel}/{@code definition}
     *                        is written in (e.g. {@code "de"}), or {@code null} to fall back to
     *                        {@code defaultLanguage} if either field is actually being corrected
     *                        (issue #258). Only the existing literal carrying the tag actually
     *                        written is replaced - every other language-tagged variant of a field
     *                        being corrected survives untouched, exactly like every field this
     *                        method does not touch at all, except an existing untagged one that a
     *                        fallback to {@code defaultLanguage} sweeps away (see {@code
     *                        TermRepository#update}'s own {@code defaultLanguage} parameter)
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - only consulted when {@code
     *                        prefLabel}/{@code definition} is actually non-{@code null} here
     * @param broader         {@code null} to leave an already-set broader term untouched, {@link
     *                        Optional#empty()} to clear it, or {@link Optional#of} the code of an
     *                        already-existing term to set/replace it (see the class-level
     *                        "Broader" note)
     * @param related         {@code null} to leave the existing {@code skos:related} peers
     *                        untouched, an empty list to clear them, or the codes of
     *                        already-existing terms to replace them wholesale (see the class-level
     *                        "Related" note)
     * @return the updated term, its {@code related} list carrying the merged view of both
     *         directions
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if {@code prefLabel} or
     *                        {@code definition} is non-{@code null}, {@code language} is
     *                        {@code null} and {@code defaultLanguage} is {@code null} too
     * @throws IllegalArgumentException if {@code related} names {@code code} itself or names the
     *                        same term twice
     * @throws de.hauschel.arknet.ul.domain.TermNotFoundException if {@code broader} or any entry of
     *                        {@code related} carries a code that does not resolve to an existing
     *                        term in the target project
     * @throws de.hauschel.arknet.ul.domain.TermCycleException if setting {@code broader} would
     *                        make {@code code} its own (direct or transitive) broader term
     */
    Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
            String language, String defaultLanguage, Optional<TermCode> broader, List<TermCode> related);
}
