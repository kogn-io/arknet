// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.CodeCounter;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.application.port.in.AddTerm;
import de.hauschel.arknet.ul.application.port.in.DeleteTerm;
import de.hauschel.arknet.ul.application.port.in.GetTerm;
import de.hauschel.arknet.ul.application.port.in.ListTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.UpdateTerm;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.DuplicateTermCodeException;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;
import de.hauschel.arknet.ul.domain.TermNotFoundException;

/**
 * Application service implementing the glossary-term use cases.
 *
 * <p>This is the policy seat of the hexagon: it drives the {@link TermRepository}
 * driven port. The component is wired as a plain object (constructor injection) by
 * the composition root; there are deliberately no framework annotations here.</p>
 *
 * <p><strong>Policy.</strong> Identity ({@link TermId}) is opaque and minted once per term via
 * {@link ResourceIdFactory}; it never changes. The human-readable business code
 * ({@link TermCode}, {@code TERM-N}) is assigned independently, where {@code N} is one above the
 * highest running number currently used in the target project (numbering is independent per
 * project). Keeping identity separate from both the code and the {@code skos:prefLabel} means
 * relabeling never changes identity (a core SKOS principle).</p>
 *
 * <p><strong>Concurrency.</strong> {@link #add} recomputes its next code against a
 * fresh read whenever a concurrent {@code term_add} claims the same {@code TERM-N} first, via
 * {@link CodeAssignment#createRetryingOnCodeCollision}; the race is invisible to a well-formed
 * caller. Parallel sessions of one user against one local store are the normal case, not a remote/
 * multi-writer concern (ADR-001).</p>
 *
 * <p><strong>Correction.</strong> {@link #update} lets a caller correct a term's
 * preferred label and/or definition after the fact, keeping its identity (and thus
 * every existing link into it) unchanged. Unlike {@link #add}, {@link #update} does not need
 * {@link #add}'s {@code CodeAssignment} retry apparatus - that only guards the code's
 * <em>assignment</em>, and a correction never changes the code.</p>
 *
 * <p><strong>{@link #update} is a pure pass-through.</strong> An earlier
 * version of this method first read the current term via {@link TermRepository#findByCode} and
 * folded every omitted ({@code null}) argument's current value into a freshly-built {@link Term}
 * before handing that merged object to the repository - which, because {@code findByCode} itself
 * has to collapse a possibly multi-valued {@code skos:prefLabel}/{@code skos:definition}
 * down to a single value for the {@link Term} projection, meant an update that only
 * touched one field silently rewrote {@code prefLabel}/{@code definition} down to
 * whichever one value the read happened to pick - destroying every other language-tagged label or
 * duplicate definition a store-first term legally carried, even though the caller never asked to
 * change either field. {@link #update} therefore no longer reads or merges anything: it passes
 * {@code null}-means-unchanged straight through to {@link TermRepository#update}, which resolves
 * the term and decides, per predicate, what to leave alone entirely at the triple level - the only
 * layer that can do so without first collapsing it through {@link Term}.</p>
 *
 * <p><strong>Language (issue #258).</strong> Because {@link #update} never reads the current term,
 * it cannot compare a mutated value against what was read the way {@code RequirementService}/
 * {@code UseCaseService} do to tell whether a language-tagged field is actually changing - it uses
 * the simpler, equivalent test available here instead: {@code prefLabel}/{@code definition} are the
 * only two language-tagged fields, so "the caller supplied at least one of them" is exactly "this
 * call is changing a language-tagged field". Only then is a {@code null} {@code language} resolved
 * against {@code defaultLanguage} (or rejected, see {@link
 * de.hauschel.arknet.kernel.LanguageTag#resolveWriteLanguage}) - a call that touches neither field
 * never reaches the resolver and can never throw for a missing default.</p>
 */
public class TermService implements AddTerm, ListTerms, GetTerm, ResolveTerms, UpdateTerm, DeleteTerm {

    private static final String ID_PREFIX = "TERM";

    private final TermRepository repository;
    private final ResourceIdFactory resourceIdFactory;

    /**
     * Creates the service.
     *
     * @param repository        the driven persistence port (must not be {@code null})
     * @param resourceIdFactory mints the opaque identity of a newly added term (must not be
     *                          {@code null})
     */
    public TermService(TermRepository repository, ResourceIdFactory resourceIdFactory) {
        this.repository = Objects.requireNonNull(repository, "repository");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
    }

    @Override
    public Term add(ProjectId projectId, NewTerm command, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry: only the
        // business code is recomputed when a concurrent term_add claims the same candidate first.
        // See CodeAssignment for why that race exists and why it must retry rather
        // than surface the out-adapter's uniqueness guard as a caller-visible failure.
        TermId id = new TermId(resourceIdFactory.newId());
        // Resolved once, outside the retry, same as RequirementService#add: a missing default must
        // reject the call before any code is even computed (issue #258).
        String language = LanguageTag.resolveWriteLanguage(command.language(), defaultLanguage);
        return CodeAssignment.createRetryingOnCodeCollision(DuplicateTermCodeException.class, () -> {
            TermCode code = nextCode(projectId);
            Term term = new Term(id, code, command.prefLabel(), command.definition(), command.broader());
            repository.create(projectId, term, language);
            return term;
        });
    }

    @Override
    public List<Term> list(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId, displayLocale);
    }

    @Override
    public Optional<Term> get(ProjectId projectId, TermCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code, displayLocale);
    }

    @Override
    public Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
            String language, String defaultLanguage, Optional<TermCode> broader) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // Unlike RequirementService/UseCaseService, this method never reads the current term
        // first (see the class-level "pure pass-through" note), so it cannot compare a mutated
        // value against what was read to tell whether a language-tagged field is actually
        // changing. The simpler, equivalent test available here: a field is being changed exactly
        // when the caller supplied it non-null - prefLabel/definition are the only two
        // language-tagged fields, so "either is non-null" is precisely "this call is changing a
        // language-tagged field", the same condition that would gate the resolveWriteLanguage call
        // if this method did read-then-compare. A no-op call (both null) never reaches the
        // resolver, so it can never throw MissingDefaultLanguageException.
        String effectiveLanguage = (prefLabel != null || definition != null)
                ? LanguageTag.resolveWriteLanguage(language, defaultLanguage)
                : language;
        return repository.update(projectId, code, prefLabel, definition, effectiveLanguage,
                defaultLanguage, broader);
    }

    @Override
    public void delete(ProjectId projectId, TermCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // The reference check (is anything else in the project still pointing at this term?) is
        // the out-adapter's business - it is the only side that can traverse the store's other
        // named graphs, exactly like the broader-cycle check in update() above.
        repository.delete(projectId, code);
    }

    @Override
    public List<ResolvedTerm> resolve(ProjectId projectId, ResourceId... ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.length == 0) {
            return List.of();
        }
        return repository.findByIds(projectId, List.of(ids));
    }

    /**
     * Derives the next free business code in {@code projectId}: the highest running number the
     * project has ever used, plus one (starting at 1).
     *
     * <p><strong>Ever used, not currently in use.</strong> The maximum runs over the living terms
     * <em>and</em> the codes {@link TermRepository#findRetainedCodes} kept from deleted ones
     * (issue #350). Over the living ones alone, deleting the highest-numbered term would let the
     * maximum fall back and the next {@code term_add} hand out that same number again - and a code
     * that already appeared in a commit message or a note would then name something else
     * entirely.</p>
     *
     * <p><strong>Counted over {@link TermRepository#findAllCodes}, not
     * {@link TermRepository#findAll} (kogn-io/arknet#360).</strong> A term written store-first
     * (ADR-005) without a {@code skos:prefLabel} or without a {@code skos:definition} cannot be
     * materialised into a {@link Term} and is dropped by the listing read - but it exists, and its
     * code stays taken. Counting over the listing read would therefore mint that code again as soon
     * as such a term holds the project's highest number, and {@link #add}'s
     * {@link CodeAssignment#createRetryingOnCodeCollision} could not retry its way out: every
     * attempt re-reads the same store and recomputes the same taken number, so the collision
     * repeats until the attempts run out and {@code term_add} stays broken for that project.
     * {@code findAllCodes} reads the codes themselves rather than the terms behind them, which is
     * why the label a term does or does not carry no longer decides which number comes next.</p>
     */
    private TermCode nextCode(ProjectId projectId) {
        String prefix = ID_PREFIX + "-";
        int highestLiving = CodeCounter.highestRunningNumber(prefix,
                repository.findAllCodes(projectId), TermCode::value);
        int highestRetained = CodeCounter.highestRunningNumber(prefix,
                repository.findRetainedCodes(projectId), TermCode::value);
        return new TermCode(prefix + (Math.max(highestLiving, highestRetained) + 1));
    }
}
