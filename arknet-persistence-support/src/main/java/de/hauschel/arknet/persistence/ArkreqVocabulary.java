// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * The absolute IRIs of the {@code arkreq:} object properties that carry cross-resource edges in
 * the store, plus the type IRIs the traceability traversal tests those edges' endpoints against,
 * as Java {@code String} constants - the single source of truth shared by the code that
 * <em>writes</em> them (the {@code *-adapter-kogniordf} out-adapters) and the code that
 * <em>traverses</em> them ({@code arknet-mcp}'s traceability read path,
 * {@code de.hauschel.arknet.mcp.trace.TraceabilityGraph}).
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
 * <p>Scope is deliberately narrow: only the object-property predicates and the type IRIs the
 * traceability graph traverses/tests live here. Single-adapter predicates ({@code arkreq:status},
 * {@code arkreq:priority}, ...) are not cross-module-duplicated in the same way and stay with
 * their one owner.</p>
 */
public final class ArkreqVocabulary {

    private static final String NAMESPACE = "https://w3id.org/arknet/requirements#";
    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";

    /** {@code arkreq:usesTerm} - Requirement -&gt; glossary Term. */
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

    /** {@code arkreq:FunctionalRequirement} - the type of a functional requirement. */
    public static final String FUNCTIONAL_REQUIREMENT_TYPE = NAMESPACE + "FunctionalRequirement";

    /** {@code arkreq:NonFunctionalRequirement} - the type of a non-functional requirement. */
    public static final String NON_FUNCTIONAL_REQUIREMENT_TYPE = NAMESPACE + "NonFunctionalRequirement";

    /** {@code arkreq:Step} - the type of a use case's main-flow/extension step. */
    public static final String STEP_TYPE = NAMESPACE + "Step";

    /** {@code skos:Concept} - the type of a glossary term (including actor-facetted ones). */
    public static final String CONCEPT_TYPE = SKOS_NAMESPACE + "Concept";

    private ArkreqVocabulary() {
    }
}
