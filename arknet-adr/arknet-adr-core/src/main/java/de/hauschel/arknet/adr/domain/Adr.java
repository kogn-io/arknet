// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;

/**
 * A single architecture decision under management: a documented decision with its context, the
 * decision itself, its consequences and the options that were considered
 * ({@code arkarch:ArchitectureDecisionRecord}, Nygard/MADR style).
 *
 * <p>Value object of the ADR component. All invariants are enforced in the compact constructor;
 * instances are immutable and their collections are defensively copied.</p>
 *
 * <p><strong>Every edge lives inside the record.</strong> All three relations
 * ({@code addressesRequirement}, {@code affectsContext}, {@code supersedes}) are part of the
 * decision's own state rather than side edges: the out-adapter persists a decision by replacing its
 * triples wholesale, so an edge kept outside this record would be silently dropped by the next
 * write - the lesson the requirements and bounded-context contexts already paid for.</p>
 *
 * @param id                    opaque, unchanging identity of this decision (never a business
 *                              label); minted once by a
 *                              {@link de.hauschel.arknet.kernel.ResourceIdFactory} and stable
 *                              across relabelling
 * @param code                  human-readable business label (e.g. {@code ADR-1}); maps to
 *                              {@code dcterms:identifier}
 * @param name                  the decision's title; maps to {@code arknet:name} and is required by
 *                              the ADR SHACL shape
 * @param status                lifecycle status; maps to {@code arkarch:adrStatus} and is required
 *                              by the shape. Never {@code null}
 * @param context               why the decision was necessary - forces and constraints; maps to
 *                              {@code arkarch:adrContext}, required by the shape
 * @param decision              what was decided; maps to {@code arkarch:adrDecision}, required by
 *                              the shape
 * @param consequences          the decision's positive and negative consequences; maps to
 *                              {@code arkarch:adrConsequences}, a {@code sh:Warning}-only (best
 *                              practice) property. Optional, may be {@code null}
 * @param alternatives          the considered but rejected options (MADR "Considered Options");
 *                              maps to {@code arkarch:adrAlternatives}, also
 *                              {@code sh:Warning}-only. Optional, may be {@code null}
 * @param decisionDate          the day the decision was made; maps to
 *                              {@code arkarch:decisionDate}. Optional, may be {@code null} - the
 *                              shape places no constraint on it at all, and a proposed decision has
 *                              no date yet
 * @param addressesRequirements the requirements this decision addresses; maps to
 *                              {@code arkarch:addressesRequirement}, {@code 0..n}, held as bare
 *                              identity references (never {@code null} or containing duplicates; a
 *                              {@code null} argument is normalised to an empty list)
 * @param affectsContexts       the bounded contexts this decision affects; maps to
 *                              {@code arkarch:affectsContext}, {@code 0..n}, same rules as
 *                              {@code addressesRequirements}
 * @param supersedes            the older decisions this one replaces; maps to
 *                              {@code arkarch:supersedes}, {@code 0..n}. Only this direction is
 *                              ever asserted as a triple - the ontology's {@code owl:inverseOf}
 *                              partner {@code arkarch:supersededBy} is left to a reader (or a
 *                              reasoner), never written a second time by hand
 */
public record Adr(
        AdrId id,
        AdrCode code,
        String name,
        AdrStatus status,
        String context,
        String decision,
        String consequences,
        String alternatives,
        LocalDate decisionDate,
        List<RequirementRef> addressesRequirements,
        List<BoundedContextRef> affectsContexts,
        List<AdrId> supersedes) {

    public Adr {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(decision, "decision");
        addressesRequirements = addressesRequirements == null ? List.of() : List.copyOf(addressesRequirements);
        affectsContexts = affectsContexts == null ? List.of() : List.copyOf(affectsContexts);
        supersedes = supersedes == null ? List.of() : List.copyOf(supersedes);
        requireNotBlank(name, "name");
        requireNotBlank(context, "context");
        requireNotBlank(decision, "decision");
        if (consequences != null && consequences.isBlank()) {
            throw new IllegalArgumentException("consequences must not be blank when present");
        }
        if (alternatives != null && alternatives.isBlank()) {
            throw new IllegalArgumentException("alternatives must not be blank when present");
        }
        requireNoDuplicates(addressesRequirements, "addressesRequirements");
        requireNoDuplicates(affectsContexts, "affectsContexts");
        requireNoDuplicates(supersedes, "supersedes");
        if (supersedes.contains(id)) {
            throw new IllegalArgumentException("an ADR must not supersede itself");
        }
    }

    /**
     * Advances this decision to {@link AdrStatus#ACCEPTED} - the only status transition the
     * implemented lifecycle permits. Calling this on an already {@link AdrStatus#ACCEPTED} decision
     * is a no-op, returning {@code this} unchanged, so a caller never has to check the current
     * status first. This is the rule itself, not a generic setter: a richer lifecycle (rejected,
     * deprecated, superseded) would extend this method rather than reintroduce a caller-supplied
     * target status - the same shape {@code Requirement#accept()} settled on.
     *
     * @return the accepted decision, or {@code this} if it was already accepted
     */
    public Adr accept() {
        if (status == AdrStatus.ACCEPTED) {
            return this;
        }
        return new Adr(id, code, name, AdrStatus.ACCEPTED, context, decision, consequences, alternatives,
                decisionDate, addressesRequirements, affectsContexts, supersedes);
    }

    /**
     * Records that this decision supersedes {@code superseded}. Superseding the same decision twice
     * is an idempotent no-op, returning {@code this} unchanged.
     *
     * <p>Only the forward edge is recorded. The superseded decision's own {@link #status} is
     * deliberately left untouched: {@code arkarch:Superseded} is one of the three lifecycle values
     * {@link AdrStatus} does not implement yet, and inventing a transition the enum cannot express
     * would be worse than stating the relation once and letting a reader derive the rest.</p>
     *
     * @param superseded the identity of the decision this one replaces
     * @return the decision including the edge, or {@code this} if it was already recorded
     * @throws IllegalArgumentException if {@code superseded} is this decision's own identity
     */
    public Adr supersede(AdrId superseded) {
        Objects.requireNonNull(superseded, "superseded");
        if (supersedes.contains(superseded)) {
            return this;
        }
        List<AdrId> extended = new ArrayList<>(supersedes);
        extended.add(superseded);
        return new Adr(id, code, name, status, context, decision, consequences, alternatives, decisionDate,
                addressesRequirements, affectsContexts, extended);
    }

    private static void requireNotBlank(String value, String field) {
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
    }

    private static void requireNoDuplicates(List<?> values, String field) {
        if (new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " must not contain duplicate entries");
        }
    }
}
