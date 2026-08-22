// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code term_delete} is asked to remove a term that something else in the project
 * still points at - a requirement's or use case's {@code arkreq:usesTerm}, a bounded context's
 * {@code arkddd:ubiquitousLanguageTerm}, another term's {@code skos:broader}, or - only where a
 * store still holds pre-#336 data, back when an actor was a facette of a glossary term - a use
 * case's {@code arkreq:primaryActor}/{@code supportingActor} (issue #335).
 *
 * <p>Rejecting rather than deleting-and-leaving-the-edge-dangling follows the same line strict
 * cross-BC reference resolution already draws elsewhere ({@code UnresolvedReferenceException}):
 * a dangling reference is never created on purpose here either, so it is never created by
 * deletion. The message is deliberately didactic: it names every predicate found still pointing
 * at the term, so the caller knows which edge(s) to remove first - e.g. via {@code req_update}/
 * {@code uc_update} (drop {@code usesTerm}), {@code bc_link_term} (re-link a different term) or
 * {@code term_update} (clear {@code broader}) - before retrying {@code term_delete}.</p>
 */
public class TermReferencedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final transient ProjectId projectId;
    private final transient TermCode code;
    private final transient List<String> referencingPredicates;

    /**
     * Creates the exception.
     *
     * @param projectId              the project the term lives in
     * @param code                   the term the caller tried to delete
     * @param referencingPredicates  the predicate(s) found still pointing at the term, in the
     *                               human-readable shorthand a caller would recognise (e.g.
     *                               {@code "usesTerm"}), never empty
     */
    public TermReferencedException(ProjectId projectId, TermCode code, List<String> referencingPredicates) {
        super("term " + Objects.requireNonNull(code, "code").value() + " in project "
                + Objects.requireNonNull(projectId, "projectId").value()
                + " cannot be deleted: still referenced via "
                + String.join(", ", Objects.requireNonNull(referencingPredicates, "referencingPredicates"))
                + " - remove those edges first");
        this.projectId = projectId;
        this.code = code;
        this.referencingPredicates = List.copyOf(referencingPredicates);
    }

    /** @return the project the term lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the term the caller tried to delete */
    public TermCode code() {
        return code;
    }

    /** @return the predicate(s) found still pointing at the term, never empty */
    public List<String> referencingPredicates() {
        return referencingPredicates;
    }
}
