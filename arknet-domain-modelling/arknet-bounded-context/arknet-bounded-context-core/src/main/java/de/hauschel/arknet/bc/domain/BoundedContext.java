// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * A single DDD bounded context under management: an explicit semantic boundary within which a
 * domain model is consistent ({@code arkddd:BoundedContext}).
 *
 * <p>Value object of the bounded-context component. All invariants are enforced in the compact
 * constructor; instances are immutable and their collections are defensively copied.</p>
 *
 * @param id           opaque, unchanging identity of this bounded context (never a business
 *                     label); minted once by a {@link de.hauschel.arknet.kernel.ResourceIdFactory}
 *                     and stable across relabelling
 * @param code         human-readable business label (e.g. {@code BC-1}); maps to
 *                     {@code dcterms:identifier}
 * @param name         the context's human-readable name (e.g. {@code OrderManagement}); maps to
 *                     {@code arknet:name} and is required by the bounded-context SHACL shape.
 *                     Multilingual since kogn-io/arknet#520 (SHACL {@code sh:uniqueLang}), without
 *                     an FR-10 label-equality guard: a context is nowhere referenced by its name
 *                     (every edge to it runs over {@link BoundedContextCode}), so a name free to
 *                     differ per language breaks no reference and creates no ambiguous resolution
 * @param domainVision one sentence stating what this context does and why it exists; maps to
 *                     {@code arkddd:domainVision} and is required by the SHACL shape. Multilingual
 *                     since kogn-io/arknet#520, the same mechanism as {@link #name()}
 * @param subdomain    strategic subdomain classification; maps to {@code arkddd:partOf} (a
 *                     derived {@code arkddd:Subdomain} node carrying {@code arkddd:subdomainType})
 *                     - a {@code sh:Warning}-only property. Optional (may be
 *                     {@code null}) and may be decided after the context is minted (store-first)
 * @param ownedBy      the owning team name; maps to {@code arkddd:ownedBy}. Optional (may be
 *                     {@code null}) - also a {@code sh:Warning}-only property
 * @param usesTerms    the glossary terms of the ubiquitous language this context names; maps to
 *                     {@code arkddd:ubiquitousLanguageTerm}, {@code 0..n}, held as the terms'
 *                     opaque shared-kernel identities - not as business codes and not as a type
 *                     of the glossary component, which this component must not depend on. A
 *                     caller types {@code TERM-1}; turning that into an identity (and an identity
 *                     back into a code for display) is the job of the driven
 *                     {@code TermLookup} port, not of this record. Never {@code null}; a
 *                     {@code null} argument is normalised to an empty list. Part of the context's own state rather than a side edge: the
 *                     out-adapter persists a bounded context by replacing it wholesale, so a link
 *                     kept outside this record would be silently dropped by the next write.
 * @param delimitedTerms the glossary terms this context names in its prose only to draw its
 *                     boundary against them; maps to {@code arkddd:delimitsTerm}, {@code 0..n},
 *                     held the same way as {@link #usesTerms()}. A model statement in its own
 *                     right, not a suppression flag: the context knows the term and excludes it
 *                     from its language. Disjoint from {@link #usesTerms()} - a term is either
 *                     part of the language or delimited, never both. Never {@code null}.
 */
public record BoundedContext(
        BoundedContextId id,
        BoundedContextCode code,
        String name,
        String domainVision,
        Subdomain subdomain,
        String ownedBy,
        List<ResourceId> usesTerms,
        List<ResourceId> delimitedTerms) {

    public BoundedContext {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(domainVision, "domainVision");
        usesTerms = usesTerms == null ? List.of() : List.copyOf(usesTerms);
        delimitedTerms = delimitedTerms == null ? List.of() : List.copyOf(delimitedTerms);
        if (!Collections.disjoint(usesTerms, delimitedTerms)) {
            throw new IllegalArgumentException("a term cannot be both a language term and a delimited term");
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
        if (domainVision.isBlank()) {
            throw new IllegalArgumentException("domainVision must not be blank");
        }
        if (ownedBy != null && ownedBy.isBlank()) {
            throw new IllegalArgumentException("ownedBy must not be blank when present");
        }
    }

    /**
     * A bounded context that delimits no term - the shape every context had before
     * {@code arkddd:delimitsTerm} existed, kept so callers that never deal with delimitations need
     * not spell out an empty list.
     */
    public BoundedContext(BoundedContextId id, BoundedContextCode code, String name, String domainVision,
            Subdomain subdomain, String ownedBy, List<ResourceId> usesTerms) {
        this(id, code, name, domainVision, subdomain, ownedBy, usesTerms, List.of());
    }

    /**
     * This context with {@code terms} as the edges of {@code relation}, the other relation's edges
     * unchanged.
     */
    public BoundedContext withTerms(TermRelation relation, List<ResourceId> terms) {
        return switch (Objects.requireNonNull(relation, "relation")) {
            case USES -> new BoundedContext(id, code, name, domainVision, subdomain, ownedBy, terms, delimitedTerms);
            case DELIMITS -> new BoundedContext(id, code, name, domainVision, subdomain, ownedBy, usesTerms, terms);
        };
    }

    /** The terms carried as edges of {@code relation}. */
    public List<ResourceId> terms(TermRelation relation) {
        return switch (Objects.requireNonNull(relation, "relation")) {
            case USES -> usesTerms;
            case DELIMITS -> delimitedTerms;
        };
    }
}
