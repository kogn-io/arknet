package de.hauschel.arknet.bc.domain;

/**
 * Strategic DDD classification of a {@link BoundedContext}'s subdomain.
 *
 * <p>Maps to the {@code arknet:SubdomainType} individuals the ontology enumerates
 * ({@code arknet:CoreDomain}/{@code arknet:SupportingDomain}/{@code arknet:GenericDomain}) and
 * that {@code shapes:BoundedContext-subdomain} constrains via {@code sh:in}. This enum lists
 * exactly those three values, so a caller can never be offered a subdomain the write-gate would
 * reject. Optional on a bounded context: the shape places no {@code sh:minCount} on
 * {@code arknet:subdomain} (a {@code sh:Warning}-only property), consistent with the store-first
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
