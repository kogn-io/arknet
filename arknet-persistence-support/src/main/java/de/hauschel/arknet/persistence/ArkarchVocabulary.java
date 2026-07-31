// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * The absolute IRIs of the {@code arkarch:} architecture-decision vocabulary as Java
 * {@code String} constants - the single source of truth shared by the code that <em>writes</em>
 * them (the ADR out-adapter,
 * {@code de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfAdrRepository}) and the code that
 * <em>reads</em> them ({@code arknet-mcp}'s traceability read path,
 * {@code de.hauschel.arknet.mcp.trace.TraceabilityGraph}, which traverses
 * {@code addressesRequirement}/{@code affectsContext}/{@code supersedes} backwards for
 * {@code impact_analysis}).
 *
 * <p>Same rationale as {@link ArkreqVocabulary} and {@link ArkdddVocabulary}: these are RDF
 * serialization constants, the literal IRI form of ontology predicates, classes and individuals.
 * The ADR bounded context's core deliberately never sees them - opaque identity keeps IRIs out of
 * the domain - so they are not domain vocabulary in the sense that keeps
 * {@code arknet-shared-kernel} free of such concerns. Being plain {@code String}s they leave this
 * module's RDF4J-freedom (ADR-007) untouched.</p>
 *
 * <p><strong>Scope: the whole active module, not just the cross-module subset.</strong> This
 * differs from {@link ArkreqVocabulary}/{@link ArkdddVocabulary}, which name only the handful of
 * predicates duplicated across two modules, and follows {@link ArkprovVocabulary}/
 * {@link ArkprjVocabulary} instead: those mirror their whole ontology module and are held against
 * it by a bidirectional architecture test ({@code arknet-architecture-tests}). The active
 * {@code arknet-architecture.ttl} is ADR-only (the remaining ISO-42010 concepts stay parked), so
 * "the whole module" and "what the ADR context writes" coincide here - and the mirror is what makes
 * the drift test possible in the first place.</p>
 *
 * <p><strong>Why terms nothing writes are still named.</strong> {@link #SUPERSEDED_BY} and
 * {@link #RELATED_TO} are never asserted by any tool: the codebase materialises no
 * {@code owl:inverseOf} pair as a second physical triple, and {@code relatedTo} has no tool at all.
 * The ADR out-adapter nevertheless names both - it must preserve such store-first (ADR-005) edges
 * across its replace-by-identity write instead of erasing them. {@link #REJECTED},
 * {@link #DEPRECATED} and {@link #SUPERSEDED} are named because the shipped
 * {@code ashapes:ADR-status} shape admits all five lifecycle individuals while the Java
 * {@code AdrStatus} enum implements only {@code Proposed}/{@code Accepted} - the same deliberate
 * subset {@code RequirementStatus} takes of the requirements lifecycle. The vocabulary mirrors what
 * arknet ships, not what the tools currently reach.</p>
 */
public final class ArkarchVocabulary {

    private static final String NAMESPACE = "https://w3id.org/arknet/architecture#";

    /** {@code arkarch:ArchitectureDecisionRecord} - the type of an architecture decision record. */
    public static final String ADR_TYPE = NAMESPACE + "ArchitectureDecisionRecord";

    /** {@code arkarch:adrContext} - ADR -&gt; why the decision was necessary. */
    public static final String ADR_CONTEXT = NAMESPACE + "adrContext";

    /** {@code arkarch:adrDecision} - ADR -&gt; what was decided. */
    public static final String ADR_DECISION = NAMESPACE + "adrDecision";

    /** {@code arkarch:adrConsequences} - ADR -&gt; the decision's positive and negative consequences. */
    public static final String ADR_CONSEQUENCES = NAMESPACE + "adrConsequences";

    /** {@code arkarch:adrAlternatives} - ADR -&gt; the considered but rejected options (MADR). */
    public static final String ADR_ALTERNATIVES = NAMESPACE + "adrAlternatives";

    /** {@code arkarch:decisionDate} - ADR -&gt; the date the decision was made ({@code xsd:date}). */
    public static final String DECISION_DATE = NAMESPACE + "decisionDate";

    /** {@code arkarch:supersedes} - ADR -&gt; an older ADR this one replaces. */
    public static final String SUPERSEDES = NAMESPACE + "supersedes";

    /** {@code arkarch:supersededBy} - ADR -&gt; the newer ADR replacing it ({@code owl:inverseOf} supersedes). */
    public static final String SUPERSEDED_BY = NAMESPACE + "supersededBy";

    /** {@code arkarch:relatedTo} - ADR &lt;-&gt; ADR, a loose symmetric cross-reference. */
    public static final String RELATED_TO = NAMESPACE + "relatedTo";

    /** {@code arkarch:addressesRequirement} - ADR -&gt; the {@code arkreq:Requirement} it addresses. */
    public static final String ADDRESSES_REQUIREMENT = NAMESPACE + "addressesRequirement";

    /** {@code arkarch:affectsContext} - ADR -&gt; the {@code arkddd:BoundedContext} it affects. */
    public static final String AFFECTS_CONTEXT = NAMESPACE + "affectsContext";

    /** {@code arkarch:ADRStatus} - the class of the lifecycle status individuals below. */
    public static final String ADR_STATUS_TYPE = NAMESPACE + "ADRStatus";

    /** {@code arkarch:adrStatus} - ADR -&gt; its current lifecycle status individual. */
    public static final String ADR_STATUS = NAMESPACE + "adrStatus";

    /** {@code arkarch:Proposed} - proposed, not yet accepted. */
    public static final String PROPOSED = NAMESPACE + "Proposed";

    /** {@code arkarch:Accepted} - accepted and in force. */
    public static final String ACCEPTED = NAMESPACE + "Accepted";

    /** {@code arkarch:Rejected} - rejected; shipped in the ontology, not implemented in Java yet. */
    public static final String REJECTED = NAMESPACE + "Rejected";

    /** {@code arkarch:Deprecated} - obsolete without a successor; shipped, not implemented yet. */
    public static final String DEPRECATED = NAMESPACE + "Deprecated";

    /** {@code arkarch:Superseded} - replaced by a newer decision; shipped, not implemented yet. */
    public static final String SUPERSEDED = NAMESPACE + "Superseded";

    private ArkarchVocabulary() {
    }
}
