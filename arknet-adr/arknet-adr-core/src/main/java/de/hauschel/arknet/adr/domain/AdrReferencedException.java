// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code adr_delete} is asked to remove a decision that another decision still points at
 * via {@code arkarch:supersedes} or {@code arkarch:relatedTo}.
 *
 * <p>Rejecting rather than deleting-and-leaving-the-edge-dangling follows the same line the glossary
 * and the actor register already draw ({@code TermReferencedException}/
 * {@code ActorReferencedException}): a dangling reference is never created on purpose here either,
 * so it is never created by deletion. What differs is who the referrers are - both relations point
 * back into this very hexagon, so the rejection can name the offending decisions by the codes a
 * caller typed rather than only the predicates involved.</p>
 *
 * <p>The message is deliberately didactic about the remedy, and about the difference between the two
 * relations: a {@code relatedTo} edge is cleared with {@code adr_update} on the decision that names
 * this one, while a {@code supersedes} edge has no removal tool at all - it is written by
 * {@code adr_supersede} and goes away only with the superseding decision itself.</p>
 */
public class AdrReferencedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** The shorthand for {@code arkarch:supersedes} used in {@link Reference#predicate()}. */
    public static final String SUPERSEDES = "supersedes";

    /** The shorthand for {@code arkarch:relatedTo} used in {@link Reference#predicate()}. */
    public static final String RELATED_TO = "relatedTo";

    private final transient ProjectId projectId;
    private final transient AdrCode code;
    private final transient List<Reference> references;

    /**
     * One decision still pointing at the decision a caller tried to delete.
     *
     * @param code      the referencing decision's business code, e.g. {@code ADR-2}
     * @param predicate the edge it points with, in the shorthand a caller would recognise - one of
     *                  {@link #SUPERSEDES}/{@link #RELATED_TO}
     */
    public record Reference(AdrCode code, String predicate) {

        /** @throws NullPointerException if either component is {@code null} */
        public Reference {
            Objects.requireNonNull(code, "code");
            Objects.requireNonNull(predicate, "predicate");
        }
    }

    /**
     * Creates the exception.
     *
     * @param projectId  the project the decision lives in
     * @param code       the decision the caller tried to delete
     * @param references the decisions found still pointing at it, never empty
     */
    public AdrReferencedException(ProjectId projectId, AdrCode code, List<Reference> references) {
        super(message(projectId, code, references));
        this.projectId = projectId;
        this.code = code;
        this.references = List.copyOf(references);
    }

    /**
     * Renders the rejection: the decision, the decisions still pointing at it with the edge each
     * uses, and the remedy for exactly those edges that were found - naming a tool for an edge the
     * caller does not have would be noise, and claiming one for {@code supersedes} would be untrue.
     */
    private static String message(ProjectId projectId, AdrCode code, List<Reference> references) {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(references, "references");
        if (references.isEmpty()) {
            throw new IllegalArgumentException("references must not be empty - nothing rejected the delete");
        }
        String referrers = references.stream()
                .map(reference -> reference.code().value() + " (" + reference.predicate() + ")")
                .collect(Collectors.joining(", "));
        return "ADR " + code.value() + " in project " + projectId.value()
                + " cannot be deleted: still referenced by " + referrers
                + " - remove those edges first: " + remedies(references);
    }

    /** The per-relation remedy hints, in the order the two relations are named above. */
    private static String remedies(List<Reference> references) {
        StringBuilder hints = new StringBuilder();
        if (references.stream().anyMatch(reference -> RELATED_TO.equals(reference.predicate()))) {
            hints.append("adr_update on the decision that names this one clears its relatedTo edge");
        }
        if (references.stream().anyMatch(reference -> SUPERSEDES.equals(reference.predicate()))) {
            if (hints.length() > 0) {
                hints.append("; ");
            }
            hints.append("a supersedes edge has no removal tool - it goes away only with the "
                    + "superseding decision itself");
        }
        return hints.toString();
    }

    /** @return the project the decision lives in */
    public ProjectId projectId() {
        return projectId;
    }

    /** @return the decision the caller tried to delete */
    public AdrCode adrCode() {
        return code;
    }

    /** @return the decisions found still pointing at it, never empty */
    public List<Reference> references() {
        return references;
    }
}
