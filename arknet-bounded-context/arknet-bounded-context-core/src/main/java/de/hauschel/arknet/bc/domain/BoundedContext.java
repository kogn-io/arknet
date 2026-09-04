// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

import java.util.List;
import java.util.Objects;

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
 *                     {@code arknet:name} and is required by the bounded-context SHACL shape
 * @param domainVision one sentence stating what this context does and why it exists; maps to
 *                     {@code arkddd:domainVision} and is required by the SHACL shape
 * @param subdomain    strategic subdomain classification; maps to {@code arkddd:partOf} (a
 *                     derived {@code arkddd:Subdomain} node carrying {@code arkddd:subdomainType})
 *                     - a {@code sh:Warning}-only property. Optional (may be
 *                     {@code null}) and may be decided after the context is minted (store-first)
 * @param ownedBy      the owning team name; maps to {@code arkddd:ownedBy}. Optional (may be
 *                     {@code null}) - also a {@code sh:Warning}-only property
 * @param usesTerms    the glossary terms of the ubiquitous language this context names; maps to
 *                     {@code arkddd:ubiquitousLanguageTerm}, {@code 0..n}, held as bare identity
 *                     references (never {@code null}; a {@code null} argument is normalised to an
 *                     empty list). Part of the context's own state rather than a side edge: the
 *                     out-adapter persists a bounded context by replacing it wholesale, so a link
 *                     kept outside this record would be silently dropped by the next write.
 */
public record BoundedContext(
        BoundedContextId id,
        BoundedContextCode code,
        String name,
        String domainVision,
        Subdomain subdomain,
        String ownedBy,
        List<TermRef> usesTerms) {

    public BoundedContext {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(domainVision, "domainVision");
        usesTerms = usesTerms == null ? List.of() : List.copyOf(usesTerms);
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
}
