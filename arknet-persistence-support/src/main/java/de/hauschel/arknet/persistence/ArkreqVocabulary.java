// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * The absolute IRIs of the {@code arkreq:} object properties that carry cross-resource edges in
 * the store, plus the literal-valued properties reached one hop past such an edge
 * ({@code arkreq:criterionText}, {@code arkreq:stepText}, {@code arkreq:position}) that the same
 * readers scan on the resource at its far end, plus the type IRIs the traceability traversal
 * tests those edges' endpoints against, as Java {@code String} constants - the single source of
 * truth shared by the code that <em>writes</em> them (the {@code *-adapter-kogniordf}
 * out-adapters) and the code that <em>reads</em> them ({@code arknet-mcp}'s traceability read
 * path, {@code de.hauschel.arknet.mcp.trace.TraceabilityGraph}, and its report renderer,
 * {@code de.hauschel.arknet.mcp.report.HtmlReportRenderer}).
 *
 * <p><strong>Why here, and why this is technology, not domain vocabulary.</strong> These are RDF
 * serialization constants: the literal IRI form of ontology predicates and classes. The
 * bounded-context cores deliberately never see them - opaque identity keeps IRIs out of the
 * domain - so they are <em>not</em> domain vocabulary in the sense that keeps
 * {@code arknet-shared-kernel} free of such concerns; putting them in the kernel would leak RDF
 * serialization into the domain the cores consume. They belong with the other RDF-serialization
 * support in this module (alongside {@link SparqlTerms}, which serializes arbitrary IRIs), and
 * they are consumed by exactly the three modules that already depend on
 * {@code arknet-persistence-support}: the two out-adapters (for the SHACL write gate) and
 * {@code arknet-mcp} (for {@code SparqlTerms}). Being plain {@code String}s, they keep this
 * module RDF4J-free (ADR-007) untouched.</p>
 *
 * <p>Before this class each of those places declared its own private copy of the same IRI
 * literals. That compiled and tested green even when they drifted: rename a predicate or type in
 * the ontology and one out-adapter, and the traceability traversal would keep compiling but
 * silently stop finding the edge or classifying the resource (emptier traversals, more false
 * "orphans"). Naming each IRI once here removes that failure mode - a rename now touches one Java
 * constant (plus the {@code .ttl}).</p>
 *
 * <p>Scope is deliberately narrow: only the predicates and type IRIs an {@code arknet-mcp} read
 * path traverses, reads or tests - whether traversed as an edge or merely read as a literal -
 * live here. Single-adapter predicates ({@code arkreq:status}, {@code arkreq:priority}, ...) are not
 * cross-module-duplicated in the same way and stay with their one owner.</p>
 */
public final class ArkreqVocabulary {

    private static final String NAMESPACE = "https://w3id.org/arknet/requirements#";
    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    private static final String OSLC_RM_NAMESPACE = "http://open-services.net/ns/rm#";

    /** {@code arkreq:usesTerm} - Requirement/UseCase -&gt; glossary Term (issue #329). */
    public static final String USES_TERM = NAMESPACE + "usesTerm";

    /** {@code arkreq:primaryActor} - UseCase -&gt; its primary actor (an actor-facetted Term). */
    public static final String PRIMARY_ACTOR = NAMESPACE + "primaryActor";

    /** {@code arkreq:supportingActor} - UseCase -&gt; a supporting actor (an actor-facetted Term). */
    public static final String SUPPORTING_ACTOR = NAMESPACE + "supportingActor";

    /** {@code arkreq:mainStep} - UseCase -&gt; a main-flow Step. */
    public static final String MAIN_STEP = NAMESPACE + "mainStep";

    /** {@code arkreq:extensionStep} - UseCase -&gt; an extension (alternative/exception) Step. */
    public static final String EXTENSION_STEP = NAMESPACE + "extensionStep";

    /** {@code arkreq:stepRealises} - Step -&gt; the Requirement it realises. */
    public static final String STEP_REALISES = NAMESPACE + "stepRealises";

    /**
     * {@code arkreq:stepText} - the text of a main-flow Step or of an extension, reached one hop
     * past {@link #MAIN_STEP} or {@link #EXTENSION_STEP}. Shared here since issue #319, when the
     * HTML report became a reader of it: it builds a step's language switch from the literals
     * under this predicate, the same way it does for {@link #CRITERION_TEXT}.
     */
    public static final String STEP_TEXT = NAMESPACE + "stepText";

    /**
     * {@code arkreq:acceptanceCriterion} - Requirement -&gt; one positioned, testable
     * {@code arkreq:AcceptanceCriterion} resource (issue #266; formerly a literal-valued
     * {@code xsd:string} property, now an object property mirroring {@code arkreq:mainStep}).
     */
    public static final String ACCEPTANCE_CRITERION = NAMESPACE + "acceptanceCriterion";

    /**
     * {@code arkreq:criterionText} - AcceptanceCriterion -&gt; its testable "Done when ..." text
     * (issue #266). Shared here because {@code arknet-mcp}'s traceability read path needs it too,
     * to scan a requirement's acceptance-criteria prose for unlinked glossary mentions - the same
     * reason {@link #ACCEPTANCE_CRITERION} itself is already shared.
     */
    public static final String CRITERION_TEXT = NAMESPACE + "criterionText";

    /**
     * {@code arkreq:position} - the 1-based number a Step or an AcceptanceCriterion carries.
     * Shared here since issue #319, when it became the key by which the HTML report pairs a
     * rendered flow step or bullet with the sub-resource it came from - text equality cannot,
     * because two identically worded steps are realistic.
     */
    public static final String POSITION = NAMESPACE + "position";

    /**
     * {@code oslc_rm:constrainedBy} - Requirement/UseCase -&gt; Constraint (issue #329). Reused
     * from OSLC RM (not an {@code arkreq:}-namespaced predicate), same as
     * {@code oslc_rm:satisfies}/{@code decomposedBy} the requirements ontology reuses without a
     * local declaration - see {@code arknet-requirements.ttl}'s own comment on that convention.
     */
    public static final String CONSTRAINED_BY = OSLC_RM_NAMESPACE + "constrainedBy";

    /**
     * {@code arkreq:useCaseGoal} - the goal a use case's primary actor pursues; the closest thing
     * a use case has to a description, so this is what the term-co-occurrence read path
     * (issue #108) scans as its use-case prose text.
     */
    public static final String USE_CASE_GOAL = NAMESPACE + "useCaseGoal";

    /** {@code arkreq:FunctionalRequirement} - the type of a functional requirement. */
    public static final String FUNCTIONAL_REQUIREMENT_TYPE = NAMESPACE + "FunctionalRequirement";

    /** {@code arkreq:NonFunctionalRequirement} - the type of a non-functional requirement. */
    public static final String NON_FUNCTIONAL_REQUIREMENT_TYPE = NAMESPACE + "NonFunctionalRequirement";

    /** {@code arkreq:UseCase} - the type of a flow-oriented use case. */
    public static final String USE_CASE_TYPE = NAMESPACE + "UseCase";

    /** {@code arkreq:Step} - the type of a use case's main-flow/extension step. */
    public static final String STEP_TYPE = NAMESPACE + "Step";

    /** {@code skos:Concept} - the type of a glossary term (including actor-facetted ones). */
    public static final String CONCEPT_TYPE = SKOS_NAMESPACE + "Concept";

    /**
     * {@code skos:broader} - Term -&gt; the broader (superordinate) Term it specializes
     * (issue #252). Only this forward direction is ever asserted as a triple - {@code
     * skos:narrower} is left to a reader, never written a second time by hand.
     */
    public static final String BROADER = SKOS_NAMESPACE + "broader";

    /**
     * {@code skos:definition} - the meaning of a glossary term; scanned as its prose text by the
     * term-mention sweep of {@code orphan_check}'s unlinked-mention check (issue #252).
     */
    public static final String DEFINITION = SKOS_NAMESPACE + "definition";

    /** {@code arkreq:TechnicalConstraint} - the type of a technical constraint. */
    public static final String TECHNICAL_CONSTRAINT_TYPE = NAMESPACE + "TechnicalConstraint";

    /** {@code arkreq:BusinessConstraint} - the type of a business constraint. */
    public static final String BUSINESS_CONSTRAINT_TYPE = NAMESPACE + "BusinessConstraint";

    /** {@code arkreq:RegulatoryConstraint} - the type of a regulatory constraint. */
    public static final String REGULATORY_CONSTRAINT_TYPE = NAMESPACE + "RegulatoryConstraint";

    private ArkreqVocabulary() {
    }
}
