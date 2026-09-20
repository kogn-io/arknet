// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Thrown when {@code adr_delete} is asked to remove a decision that another decision still points at
 * via {@code arkarch:supersedes} (pre-#357 legacy shape), {@code arkarch:supersededBy}
 * (kogn-io/arknet#357's current write shape) or {@code arkarch:relatedTo}.
 *
 * <p>Rejecting rather than deleting-and-leaving-the-edge-dangling follows the same line the glossary
 * and the actor register already draw ({@code TermReferencedException}/
 * {@code ActorReferencedException}): a dangling reference is never created on purpose here either,
 * so it is never created by deletion. What differs is who the referrers are - all three relations
 * point back into this very hexagon, so the rejection can name the offending decisions by the codes
 * a caller typed rather than only the predicates involved.</p>
 *
 * <p>The message is deliberately didactic about the remedy, and about the difference between the
 * three relations: a {@code relatedTo} edge is cleared with {@code adr_update} on the decision that
 * names this one; a {@code supersededBy} edge - the current write shape - is cleared with
 * {@code adr_unsupersede} (kogn-io/arknet#354) on the <em>superseded</em> decision itself, the one
 * the edge lives on (kogn-io/arknet#357 moved it there; kogn-io/arknet#359 fixed this remedy to name
 * that decision instead of the superseding one it used to, back when the edge lived on the
 * superseding decision's forward-only {@code supersedes} list) - the same call restores that
 * decision to {@code ACCEPTED}; a store-first {@code supersedes} edge, where one still exists,
 * follows the pre-#357 shape and has no removal tool - it goes away only with the
 * <em>superseding</em> decision that carries it.</p>
 */
public class AdrReferencedException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The shorthand for the pre-#357 legacy {@code arkarch:supersedes} edge used in
     * {@link Reference#predicate()} - a store-first record may still carry one, but nothing writes
     * it any more (kogn-io/arknet#357).
     */
    public static final String SUPERSEDES = "supersedes";

    /**
     * The shorthand for {@code arkarch:supersededBy} used in {@link Reference#predicate()} - the
     * current write shape (kogn-io/arknet#357), living on the <em>superseded</em> decision.
     */
    public static final String SUPERSEDED_BY = "supersededBy";

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

    /** The per-relation remedy hints, in the order the three relations are named above. */
    private static String remedies(List<Reference> references) {
        StringBuilder hints = new StringBuilder();
        if (references.stream().anyMatch(reference -> RELATED_TO.equals(reference.predicate()))) {
            hints.append("adr_update on the decision that names this one clears its relatedTo edge");
        }
        if (references.stream().anyMatch(reference -> SUPERSEDED_BY.equals(reference.predicate()))) {
            if (hints.length() > 0) {
                hints.append("; ");
            }
            hints.append("adr_unsupersede on the superseded decision (the one named above) clears its "
                    + "supersededBy edge and restores it to ACCEPTED");
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
