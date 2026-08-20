// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.CodeAssignment;
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
import de.hauschel.arknet.ul.domain.ActorFacet;
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
 * preferred label, definition and/or Actor facette after the fact, keeping its identity (and thus
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
 * touched {@code actorKind} silently rewrote {@code prefLabel}/{@code definition} down to
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
            Term term = new Term(id, code, command.prefLabel(), command.definition(), command.actorFacet(),
                    command.broader());
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
            ActorFacet actorFacet, String language, String defaultLanguage, Optional<TermCode> broader) {
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
        return repository.update(projectId, code, prefLabel, definition, actorFacet, effectiveLanguage,
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
     * Derives the next free business code in {@code projectId}: the highest running
     * number currently in use, plus one (starting at 1).
     */
    private TermCode nextCode(ProjectId projectId) {
        // Only each term's TermCode is read here, never a label, so this call has no need for a
        // display language override - null uses the repository's own configured preference, which
        // has no bearing on this method's result either way.
        int next = repository.findAll(projectId, null).stream()
                .mapToInt(t -> runningNumber(t.code()))
                .max()
                .orElse(0) + 1;
        return new TermCode(ID_PREFIX + "-" + next);
    }

    /** Parses the running number from a code such as {@code TERM-7} (0 if not parseable). */
    private static int runningNumber(TermCode code) {
        String value = code.value();
        int dash = value.lastIndexOf('-');
        if (dash < 0 || dash == value.length() - 1) {
            return 0;
        }
        try {
            return Integer.parseInt(value.substring(dash + 1));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
