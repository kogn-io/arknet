// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

/**
 * Strategic DDD classification of a {@link BoundedContext}'s subdomain.
 *
 * <p>Maps to the {@code arkddd:SubdomainType} individuals the ontology enumerates
 * ({@code arkddd:CoreDomain}/{@code arkddd:SupportingDomain}/{@code arkddd:GenericDomain}), one of
 * which the out-adapter attaches via a derived {@code arkddd:Subdomain} node's
 * {@code arkddd:subdomainType} - this enum lists exactly those three values, so a
 * caller can never be offered a subdomain the ontology does not enumerate, though nothing in the
 * current SHACL shapes constrains {@code arkddd:subdomainType} itself. Optional on a bounded
 * context: {@code shapes:BoundedContext-partOf} places no {@code sh:minCount} on
 * {@code arkddd:partOf} (a {@code sh:Warning}-only property), consistent with the store-first
 * lifecycle (ADR-005) in which the strategic classification may be decided after the context
 * itself is minted.</p>
 */
public enum Subdomain {

    /** The differentiating heart of the business - worth the most modelling effort. */
    CORE_DOMAIN,

    /** Necessary but not differentiating - supports the core. */
    SUPPORTING_DOMAIN,

    /** A solved problem, often off-the-shelf software. */
    GENERIC_DOMAIN
}
