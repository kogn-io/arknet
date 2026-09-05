// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * The absolute IRIs of the {@code arkddd:} strategic-DDD vocabulary as Java {@code String}
 * constants - the single source of truth shared by the code that <em>writes</em> them (the
 * bounded-context out-adapters,
 * {@code de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepository}/
 * {@code KognioRdfContextRelationshipRepository}, and its factory) and the code that
 * <em>reads</em> them - {@code arknet-mcp}'s traceability read path,
 * {@code de.hauschel.arknet.mcp.trace.TraceabilityGraph}, which scans {@link #DOMAIN_VISION} for
 * unlinked glossary mentions and traverses {@link #UPSTREAM}/{@link #DOWNSTREAM} for
 * {@code impact_analysis} (issue #293), {@link #BOUNDED_CONTEXT_TYPE} for its own type-listing
 * and {@link #UBIQUITOUS_LANGUAGE_TERM} for {@code isReferencedTerm}/{@code linkedTerms} - plus
 * two further cross-module readers of {@link #BOUNDED_CONTEXT_TYPE} alone: the ADR out-adapter
 * ({@code KognioRdfAdrRepository}, which asserts it as validation-only context for
 * {@code arkarch:affectsContext}'s {@code sh:class} constraint) and its sibling lookup
 * ({@code KognioRdfBoundedContextLookup}, resolving an ADR's {@code BC-N} reference).
 *
 * <p>Same rationale as {@link ArkreqVocabulary}: these are RDF serialization constants, the
 * literal IRI form of ontology predicates, classes and individuals. The bounded-context core
 * deliberately never sees them - opaque identity keeps IRIs out of the domain - so they are not
 * domain vocabulary in the sense that keeps {@code arknet-shared-kernel} free of such concerns.
 * Being plain {@code String}s they leave this module's RDF4J-freedom untouched.</p>
 *
 * <p><strong>Scope: the whole active module, not just the cross-module subset
 * (kogn-io/arknet#148).</strong> This class used to name only the handful of predicates
 * duplicated across two modules, the same narrow scope {@link ArkreqVocabulary} still keeps - a
 * javadoc claim that {@code arkddd:BoundedContext} and {@code arkddd:ContextRelationship} "stay
 * local" turned out to be false the moment a second, third or fourth private copy of the exact
 * same IRI literal appeared in {@code KognioRdfAdrRepository}, {@code
 * KognioRdfBoundedContextLookup} and {@code KognioRdfContextRelationshipRepositoryFactory} -
 * silent drift the narrow-scope class existed to prevent, on the very predicate it claimed was
 * safe. This class now follows {@link ArkprovVocabulary}/{@link ArkprjVocabulary}/
 * {@link ArkarchVocabulary} instead: it mirrors the whole active {@code arknet-ddd.ttl} module
 * (BoundedContext plus the Domain/Subdomain classification it is placed within, plus the
 * ContextRelationship mapping layer), held against that ontology by a bidirectional architecture
 * test ({@code arknet-architecture-tests}) - the mirror is what makes that test possible in the
 * first place. The tactical-DDD block (Aggregate, Entity, ValueObject, ...) has no active
 * consumer and stays parked in {@code parked/arknet-ddd_parked.ttl}, sharing the namespace but out
 * of this class's scope - {@link #HAS_AGGREGATE_PROPERTY}'s range, {@code arkddd:Aggregate},
 * is one such parked class, named nowhere in this file since no adapter ever writes that IRI as
 * a triple component.</p>
 */
public final class ArkdddVocabulary {

    private static final String NAMESPACE = "https://w3id.org/arknet/ddd#";

    // ------------------------------------------------------------------
    // Domain & Subdomain
    // ------------------------------------------------------------------

    /** {@code arkddd:Domain} - the overarching problem domain a bounded context is placed within. */
    public static final String DOMAIN_TYPE = NAMESPACE + "Domain";

    /**
     * {@code arkddd:SubdomainType} - the class of the three strategic-classification individuals
     * below ({@link #CORE_DOMAIN}/{@link #SUPPORTING_DOMAIN}/{@link #GENERIC_DOMAIN}). Named here
     * for the same completeness reason {@code arkarch:ADRStatus}/{@code ConsequenceType} are named
     * in {@link ArkarchVocabulary}: no adapter writes this class IRI as a triple component itself,
     * only the individuals it classifies and the {@link #SUBDOMAIN_TYPE_PROPERTY} edge that points
     * at them.
     */
    public static final String SUBDOMAIN_TYPE_CLASS = NAMESPACE + "SubdomainType";

    /** {@code arkddd:CoreDomain} - the differentiating heart of the business. */
    public static final String CORE_DOMAIN = NAMESPACE + "CoreDomain";

    /** {@code arkddd:SupportingDomain} - necessary but not differentiating. */
    public static final String SUPPORTING_DOMAIN = NAMESPACE + "SupportingDomain";

    /** {@code arkddd:GenericDomain} - a solved problem, often off-the-shelf software. */
    public static final String GENERIC_DOMAIN = NAMESPACE + "GenericDomain";

    /**
     * {@code arkddd:Subdomain} - a {@link #DOMAIN_TYPE} carrying one of the three strategic
     * classifications above. The bounded-context out-adapter writes a derived subdomain node of
     * this type whenever {@link #SUBDOMAIN_TYPE_PROPERTY} is set.
     */
    public static final String SUBDOMAIN_CLASS = NAMESPACE + "Subdomain";

    /** {@code arkddd:subdomainType} - Subdomain -&gt; one of {@link #CORE_DOMAIN}/{@link #SUPPORTING_DOMAIN}/{@link #GENERIC_DOMAIN}. */
    public static final String SUBDOMAIN_TYPE_PROPERTY = NAMESPACE + "subdomainType";

    /**
     * {@code arkddd:hasSubdomain} - Domain -&gt; a subdomain that is part of it, the inverse of
     * {@link #HAS_CONTEXT}'s sibling {@code arkddd:partOf}. Shipped in the ontology, but no adapter
     * currently writes or reads this direction of the edge - {@link #PART_OF_PROPERTY} is the one
     * direction the store-first lifecycle actually populates.
     */
    public static final String HAS_SUBDOMAIN = NAMESPACE + "hasSubdomain";

    // ------------------------------------------------------------------
    // Bounded Context
    // ------------------------------------------------------------------

    /**
     * {@code arkddd:BoundedContext} - an explicit semantic boundary within which a domain model
     * holds consistently. Read by {@code arknet-mcp}'s traceability graph for its own
     * type-listing and, as validation-only asserted context, by the ADR out-adapter for
     * {@code arkarch:affectsContext}'s {@code sh:class} constraint; resolved by
     * {@code KognioRdfBoundedContextLookup} for an ADR's {@code BC-N} reference and asserted, as
     * validation-only context for {@code arkddd:upstream}/{@code downstream}'s own {@code sh:class}
     * constraint, by {@code KognioRdfContextRelationshipRepository}.
     */
    public static final String BOUNDED_CONTEXT_TYPE = NAMESPACE + "BoundedContext";

    /**
     * {@code arkddd:partOf} - BoundedContext -&gt; the domain (or, since Subdomain is a Domain, the
     * specific subdomain) it is part of.
     */
    public static final String PART_OF_PROPERTY = NAMESPACE + "partOf";

    /**
     * {@code arkddd:hasContext} - Domain -&gt; a bounded context that is part of it,
     * {@code owl:inverseOf} {@link #PART_OF_PROPERTY}. Shipped in the ontology, but no adapter
     * currently writes or reads this direction - the same "documented, not implemented" half
     * {@link #HAS_SUBDOMAIN} is.
     */
    public static final String HAS_CONTEXT = NAMESPACE + "hasContext";

    /**
     * {@code arkddd:domainVision} - BoundedContext -&gt; its vision text, one sentence describing
     * what it delivers and why it exists.
     */
    public static final String DOMAIN_VISION = NAMESPACE + "domainVision";

    /** {@code arkddd:ownedBy} - the team name or person URI responsible for this concept. */
    public static final String OWNED_BY_PROPERTY = NAMESPACE + "ownedBy";

    /**
     * {@code arkddd:ubiquitousLanguageTerm} - BoundedContext -&gt; a glossary term it uses
     * (issue #335: shared once {@code KognioRdfTermRepository#delete}'s reference check became a
     * third reader alongside the writing bc-adapter and {@code TraceabilityGraph}).
     */
    public static final String UBIQUITOUS_LANGUAGE_TERM = NAMESPACE + "ubiquitousLanguageTerm";

    /**
     * {@code arkddd:hasAggregate} - BoundedContext -&gt; an aggregate that belongs to it. Its
     * range, {@code arkddd:Aggregate}, lives in the not-yet-published, parked tactical-DDD block
     * and is out of this class's scope (see the class javadoc).
     */
    public static final String HAS_AGGREGATE_PROPERTY = NAMESPACE + "hasAggregate";

    // ------------------------------------------------------------------
    // Context Mapping (relationship layer)
    // ------------------------------------------------------------------

    /**
     * {@code arkddd:ContextRelationship} - a directed relationship between two bounded contexts
     * with an explicit integration type. Written by {@code KognioRdfContextRelationshipRepository}
     * as every created relationship's own type triple, and typed by its factory's
     * {@code /arknet-shapes.ttl} shape filter.
     */
    public static final String CONTEXT_RELATIONSHIP_TYPE = NAMESPACE + "ContextRelationship";

    /**
     * {@code arkddd:RelationshipType} - the class of the eight relationship-type individuals below.
     * Named here for the same completeness reason {@link #SUBDOMAIN_TYPE_CLASS} is: no adapter
     * writes this class IRI as a triple component itself, only the individuals it classifies and
     * the {@link #RELATIONSHIP_TYPE_PROPERTY} edge that points at them.
     */
    public static final String RELATIONSHIP_TYPE_CLASS = NAMESPACE + "RelationshipType";

    /** {@code arkddd:Partnership} - both teams coordinate closely as equals. */
    public static final String PARTNERSHIP = NAMESPACE + "Partnership";

    /** {@code arkddd:SharedKernel} - a shared subset of code/model requiring close coordination. */
    public static final String SHARED_KERNEL = NAMESPACE + "SharedKernel";

    /** {@code arkddd:CustomerSupplier} - downstream is customer, upstream is supplier. */
    public static final String CUSTOMER_SUPPLIER = NAMESPACE + "CustomerSupplier";

    /** {@code arkddd:Conformist} - downstream adopts upstream's model with no translation layer. */
    public static final String CONFORMIST = NAMESPACE + "Conformist";

    /** {@code arkddd:AnticorruptionLayer} - downstream protects its model behind a translation layer. */
    public static final String ANTICORRUPTION_LAYER = NAMESPACE + "AnticorruptionLayer";

    /** {@code arkddd:OpenHostService} - upstream offers a defined, stable protocol to several consumers. */
    public static final String OPEN_HOST_SERVICE = NAMESPACE + "OpenHostService";

    /** {@code arkddd:PublishedLanguage} - upstream publishes a formal exchange format. */
    public static final String PUBLISHED_LANGUAGE = NAMESPACE + "PublishedLanguage";

    /** {@code arkddd:SeparateWays} - no integration at all, a deliberate decoupling. */
    public static final String SEPARATE_WAYS = NAMESPACE + "SeparateWays";

    /**
     * {@code arkddd:upstream} - ContextRelationship -&gt; the BoundedContext whose model/protocol
     * prevails (issue #293).
     */
    public static final String UPSTREAM = NAMESPACE + "upstream";

    /**
     * {@code arkddd:downstream} - ContextRelationship -&gt; the BoundedContext that consumes
     * {@link #UPSTREAM}'s model/protocol (issue #293).
     */
    public static final String DOWNSTREAM = NAMESPACE + "downstream";

    /** {@code arkddd:relationshipType} - ContextRelationship -&gt; one of the eight individuals above. */
    public static final String RELATIONSHIP_TYPE_PROPERTY = NAMESPACE + "relationshipType";

    private ArkdddVocabulary() {
    }
}
