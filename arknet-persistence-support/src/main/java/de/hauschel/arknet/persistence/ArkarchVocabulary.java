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
 * {@code addressesRequirement}/{@code affectsContext} backwards and, since kogn-io/arknet#357,
 * {@code supersededBy} forwards for {@code impact_analysis} (the pre-#357
 * {@code supersedes} shape, where store-first data still carries it, stays in the backward set
 * this predicate used to sit in).
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
 * <p><strong>{@link #SUPERSEDED_BY} and {@link #SUPERSEDED} are real, written terms
 * (kogn-io/arknet#357).</strong> {@code arkarch:supersededBy} is written on the
 * <em>superseded</em> decision, together with its status transitioning to {@code Superseded}, in
 * one write ({@code adr_supersede}) - the two are coupled by a bi-implication {@code Adr}'s
 * compact constructor enforces in full; {@code architecture-shapes.ttl}'s
 * {@code ashapes:ADR-supersededByRequiresSupersededStatus} enforces only the
 * {@code supersededBy}-implies-{@code Superseded} half of it a second time at the write gate
 * (kogn-io/arknet#359). {@link #SUPERSEDES} is the
 * pre-#357 shape: nothing writes it any more, but the ADR out-adapter still reads it, so a
 * project with decisions superseded before this issue keeps working - see
 * {@code AdrRepository#findLegacySupersedesEdges}. {@link #RELATED_TO} follows the same
 * one-direction-only rule {@link #SUPERSEDES} used to, although the ontology declares it an
 * {@code owl:SymmetricProperty}: {@code adr_add}/{@code adr_update} write only the forward triple,
 * and a reader sees both directions via a reverse read, never two hand-maintained triples for one
 * fact.</p>
 */
public final class ArkarchVocabulary {

    private static final String NAMESPACE = "https://w3id.org/arknet/architecture#";

    /** {@code arkarch:ArchitectureDecisionRecord} - the type of an architecture decision record. */
    public static final String ADR_TYPE = NAMESPACE + "ArchitectureDecisionRecord";

    /** {@code arkarch:adrContext} - ADR -&gt; why the decision was necessary. */
    public static final String ADR_CONTEXT = NAMESPACE + "adrContext";

    /** {@code arkarch:adrDecision} - ADR -&gt; what was decided. */
    public static final String ADR_DECISION = NAMESPACE + "adrDecision";

    /**
     * {@code arkarch:adrConsequences} - ADR -&gt; the decision's positive and negative consequences,
     * as a single flat string. The pre-#357 shape: no tool writes this predicate any more (see
     * {@link #CONSEQUENCE}), but the ADR out-adapter still reads a store-first record that still
     * carries one, synthesising a single {@code NEUTRAL} {@code arkarch:Consequence} from it (never
     * persisted) rather than losing the information.
     */
    public static final String ADR_CONSEQUENCES = NAMESPACE + "adrConsequences";

    /**
     * {@code arkarch:adrAlternatives} - ADR -&gt; the considered but rejected options (MADR), as a
     * single flat string. The pre-#357 shape: no tool writes this predicate any more (see
     * {@link #CONSIDERED_OPTION}), but the ADR out-adapter still reads a store-first record that
     * still carries one, synthesising a single outcome-less {@code arkarch:ConsideredOption} from it.
     */
    public static final String ADR_ALTERNATIVES = NAMESPACE + "adrAlternatives";

    /**
     * {@code arkarch:Consequence} - a single positive, negative or neutral consequence of a decision,
     * its own resource (kogn-io/arknet#357, replacing the pre-#357 flat {@link #ADR_CONSEQUENCES}
     * string) - mirrors {@code arkreq:AcceptanceCriterion} (issue #266).
     */
    public static final String CONSEQUENCE_TYPE_CLASS = NAMESPACE + "Consequence";

    /** {@code arkarch:consequence} - ADR -&gt; one of its {@link #CONSEQUENCE_TYPE_CLASS} resources. */
    public static final String CONSEQUENCE = NAMESPACE + "consequence";

    /** {@code arkarch:consequenceStatement} - Consequence -&gt; its multilingual text. */
    public static final String CONSEQUENCE_STATEMENT = NAMESPACE + "consequenceStatement";

    /** {@code arkarch:consequenceType} - Consequence -&gt; one of the three {@link #CONSEQUENCE_TYPE} individuals. */
    public static final String CONSEQUENCE_TYPE_PROPERTY = NAMESPACE + "consequenceType";

    /** {@code arkarch:ConsequenceType} - the class of the three consequence-type individuals below. */
    public static final String CONSEQUENCE_TYPE = NAMESPACE + "ConsequenceType";

    /** {@code arkarch:Positive} - a beneficial consequence. */
    public static final String POSITIVE = NAMESPACE + "Positive";

    /** {@code arkarch:Negative} - a detrimental consequence. */
    public static final String NEGATIVE = NAMESPACE + "Negative";

    /**
     * {@code arkarch:Neutral} - neither clearly beneficial nor detrimental; also the type the
     * out-adapter's legacy-literal fallback assigns an unclassified pre-#357 consequence.
     */
    public static final String NEUTRAL = NAMESPACE + "Neutral";

    /**
     * {@code arkarch:ConsideredOption} - a single option considered while making a decision, its own
     * resource (kogn-io/arknet#357, replacing the pre-#357 flat {@link #ADR_ALTERNATIVES} string) -
     * mirrors {@link #CONSEQUENCE_TYPE_CLASS} in shape.
     */
    public static final String CONSIDERED_OPTION_TYPE_CLASS = NAMESPACE + "ConsideredOption";

    /** {@code arkarch:consideredOption} - ADR -&gt; one of its {@link #CONSIDERED_OPTION_TYPE_CLASS} resources. */
    public static final String CONSIDERED_OPTION = NAMESPACE + "consideredOption";

    /** {@code arkarch:optionRationale} - ConsideredOption -&gt; its multilingual reasoning text. */
    public static final String OPTION_RATIONALE = NAMESPACE + "optionRationale";

    /** {@code arkarch:optionOutcome} - ConsideredOption -&gt; one of the two {@link #OPTION_OUTCOME} individuals. */
    public static final String OPTION_OUTCOME_PROPERTY = NAMESPACE + "optionOutcome";

    /** {@code arkarch:OptionOutcome} - the class of the two option-outcome individuals below. */
    public static final String OPTION_OUTCOME = NAMESPACE + "OptionOutcome";

    /** {@code arkarch:Chosen} - the option that was actually picked; at most one per decision. */
    public static final String CHOSEN = NAMESPACE + "Chosen";

    /**
     * {@code arkarch:OptionRejected} - an option that was considered and turned down. Deliberately
     * not named {@code arkarch:Rejected}: that IRI already denotes the unrelated
     * {@link #REJECTED} {@code ADRStatus} individual ("the decision itself was rejected while
     * still proposed") - the issue's literal wording ("Chosen/Rejected") would have collided one
     * individual across two different {@code owl:NamedIndividual} classes, so this name was chosen
     * instead (kogn-io/arknet#357, deviation noted in the PR description).
     */
    public static final String OPTION_REJECTED = NAMESPACE + "OptionRejected";

    /** {@code arkarch:decisionDate} - ADR -&gt; the date the decision was made ({@code xsd:date}). */
    public static final String DECISION_DATE = NAMESPACE + "decisionDate";

    /**
     * {@code arkarch:supersedes} - ADR -&gt; an older ADR this one replaces. The pre-#357 shape: no
     * tool writes this predicate any more (the written edge moved to {@link #SUPERSEDED_BY}, on the
     * <em>superseded</em> decision), but the ADR out-adapter still reads a store-first record that
     * still carries one, rather than dropping data no write ever touched.
     */
    public static final String SUPERSEDES = NAMESPACE + "supersedes";

    /**
     * {@code arkarch:supersededBy} - ADR -&gt; the newer ADR replacing it ({@code owl:inverseOf}
     * {@link #SUPERSEDES}). Written on the <em>superseded</em> decision, together with its
     * {@link #ADR_STATUS} transitioning to {@link #SUPERSEDED}, in one write (kogn-io/arknet#357,
     * {@code adr_supersede}) - the two are coupled by a bi-implication {@code Adr}'s compact
     * constructor enforces in full; {@code architecture-shapes.ttl}'s
     * {@code ashapes:ADR-supersededByRequiresSupersededStatus} enforces only the
     * {@code supersededBy}-implies-{@code Superseded} half of it a second time at the write gate
     * (kogn-io/arknet#359: the converse cannot be a node shape without also firing on the
     * validation-only peer copies a {@code relatedTo}/{@code supersededBy} write asserts).
     */
    public static final String SUPERSEDED_BY = NAMESPACE + "supersededBy";

    /**
     * {@code arkarch:relatedTo} - ADR &lt;-&gt; ADR, a loose symmetric cross-reference. Written by
     * {@code adr_add}/{@code adr_update} in the forward direction only, even though the ontology
     * declares it an {@code owl:SymmetricProperty}: a reader sees both directions because the
     * application service unions the forward edges with a reverse read, not because a second
     * triple was maintained by hand.
     */
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

    /** {@code arkarch:Rejected} - rejected while still proposed. */
    public static final String REJECTED = NAMESPACE + "Rejected";

    /** {@code arkarch:Deprecated} - obsolete without a successor. */
    public static final String DEPRECATED = NAMESPACE + "Deprecated";

    /**
     * {@code arkarch:Superseded} - replaced by a newer decision; a real {@code AdrStatus} value
     * (kogn-io/arknet#357), set together with {@link #SUPERSEDED_BY} in one write.
     */
    public static final String SUPERSEDED = NAMESPACE + "Superseded";

    private ArkarchVocabulary() {
    }
}
