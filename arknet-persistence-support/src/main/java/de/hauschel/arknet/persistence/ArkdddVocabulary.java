// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * The absolute IRIs of the {@code arkddd:} predicates duplicated across modules - as Java
 * {@code String} constants, the single source of truth shared by the code that <em>writes</em>
 * them (the bounded-context out-adapters,
 * {@code de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepository}/
 * {@code KognioRdfContextRelationshipRepository}) and the code that <em>reads</em> them
 * ({@code arknet-mcp}'s traceability read path, {@code de.hauschel.arknet.mcp.trace.TraceabilityGraph},
 * which scans {@link #DOMAIN_VISION} for unlinked glossary mentions and traverses {@link #UPSTREAM}/
 * {@link #DOWNSTREAM} for {@code impact_analysis}, issue #293).
 *
 * <p>Same rationale as {@link ArkreqVocabulary}: before this class each duplicated predicate was
 * declared as a private copy of the same IRI literal in more than one place, which could drift
 * silently. Scope is just as narrow - this class holds only the {@code arkddd:} predicates that
 * are duplicated across modules, not the whole {@code arkddd:} namespace ({@code
 * arkddd:BoundedContext} is currently only used within {@code TraceabilityGraph} itself and stays
 * local there; {@code arkddd:ContextRelationship}/{@code relationshipType} and the eight
 * {@code arkddd:RelationshipType} individuals stay local to
 * {@code KognioRdfContextRelationshipRepository} for the same reason - nothing outside that
 * adapter reads them). {@link #UBIQUITOUS_LANGUAGE_TERM} joined this class with issue #335: the
 * ubiquitous-language out-adapter's {@code term_delete} needed to recognise the same predicate the
 * bounded-context out-adapter writes and {@code TraceabilityGraph} already read, a third reader
 * that turned the former "stays local" note into exactly the drift risk this class exists to
 * close.</p>
 */
public final class ArkdddVocabulary {

    private static final String NAMESPACE = "https://w3id.org/arknet/ddd#";

    /** {@code arkddd:domainVision} - BoundedContext -&gt; its vision text. */
    public static final String DOMAIN_VISION = NAMESPACE + "domainVision";

    /**
     * {@code arkddd:ubiquitousLanguageTerm} - BoundedContext -&gt; a glossary term it uses
     * (issue #335: shared once {@code KognioRdfTermRepository#delete}'s reference check became a
     * third reader alongside the writing bc-adapter and {@code TraceabilityGraph}).
     */
    public static final String UBIQUITOUS_LANGUAGE_TERM = NAMESPACE + "ubiquitousLanguageTerm";

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

    private ArkdddVocabulary() {
    }
}
