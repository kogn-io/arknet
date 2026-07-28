// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.CodeAssignment;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.application.port.in.AddTerm;
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
 * workspace). Keeping identity separate from both the code and the {@code skos:prefLabel} means
 * relabeling never changes identity (a core SKOS principle).</p>
 *
 * <p><strong>Concurrency (issue #144).</strong> {@link #add} recomputes its next code against a
 * fresh read whenever a concurrent {@code term_add} claims the same {@code TERM-N} first, via
 * {@link CodeAssignment#createRetryingOnCodeCollision}; the race is invisible to a well-formed
 * caller. Parallel sessions of one user against one local store are the normal case, not a remote/
 * multi-writer concern (ADR-001).</p>
 *
 * <p><strong>Correction (issue #163).</strong> {@link #update} lets a caller correct a term's
 * preferred label, definition and/or Actor facette after the fact, keeping its identity (and thus
 * every existing link into it) unchanged. Unlike {@link #add}, {@link #update} does not need
 * {@link #add}'s {@code CodeAssignment} retry apparatus - that only guards the code's
 * <em>assignment</em>, and a correction never changes the code.</p>
 *
 * <p><strong>{@link #update} is a pure pass-through (issue #163 follow-up).</strong> An earlier
 * version of this method first read the current term via {@link TermRepository#findByCode} and
 * folded every omitted ({@code null}) argument's current value into a freshly-built {@link Term}
 * before handing that merged object to the repository - which, because {@code findByCode} itself
 * has to collapse a possibly multi-valued {@code skos:prefLabel}/{@code skos:definition} (issues
 * #80/#81) down to a single value for the {@link Term} projection, meant an update that only
 * touched {@code actorKind} silently rewrote {@code prefLabel}/{@code definition} down to
 * whichever one value the read happened to pick - destroying every other language-tagged label or
 * duplicate definition a store-first term legally carried, even though the caller never asked to
 * change either field. {@link #update} therefore no longer reads or merges anything: it passes
 * {@code null}-means-unchanged straight through to {@link TermRepository#update}, which resolves
 * the term and decides, per predicate, what to leave alone entirely at the triple level - the only
 * layer that can do so without first collapsing it through {@link Term}.</p>
 */
public class TermService implements AddTerm, ListTerms, GetTerm, ResolveTerms, UpdateTerm {

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
    public Term add(ProjectId projectId, NewTerm command) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(command, "command");
        // Identity is opaque and stable, so it is minted once, outside the retry: only the
        // business code is recomputed when a concurrent term_add claims the same candidate first
        // (issue #144). See CodeAssignment for why that race exists and why it must retry rather
        // than surface the out-adapter's uniqueness guard as a caller-visible failure.
        TermId id = new TermId(resourceIdFactory.newId());
        return CodeAssignment.createRetryingOnCodeCollision(DuplicateTermCodeException.class, () -> {
            TermCode code = nextCode(projectId);
            Term term = new Term(id, code, command.prefLabel(), command.definition(), command.actorFacet());
            repository.create(projectId, term);
            return term;
        });
    }

    @Override
    public List<Term> list(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");
        return repository.findAll(projectId);
    }

    @Override
    public Optional<Term> get(ProjectId projectId, TermCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.findByCode(projectId, code);
    }

    @Override
    public Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
            ActorFacet actorFacet) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        return repository.update(projectId, code, prefLabel, definition, actorFacet);
    }

    @Override
    public List<ResolvedTerm> getById(ProjectId projectId, ResourceId... ids) {
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
        int next = repository.findAll(projectId).stream()
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
