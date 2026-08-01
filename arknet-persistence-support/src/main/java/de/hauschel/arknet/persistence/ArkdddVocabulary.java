// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * The absolute IRI of {@code arkddd:domainVision} - a BoundedContext's literal-valued vision
 * text - as a Java {@code String} constant, the single source of truth shared by the code that
 * <em>writes</em> it (the bounded-context out-adapter,
 * {@code de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepository}) and the code
 * that <em>reads</em> it ({@code arknet-mcp}'s traceability read path,
 * {@code de.hauschel.arknet.mcp.trace.TraceabilityGraph}, which scans it for unlinked glossary
 * mentions).
 *
 * <p>Same rationale as {@link ArkreqVocabulary}: before this class both places declared their own
 * private copy of the same IRI literal, which could drift silently. Scope is just as narrow -
 * this class holds only the one {@code arkddd:} predicate that is duplicated across those two
 * modules, not the whole {@code arkddd:} namespace (e.g. {@code arkddd:ubiquitousLanguageTerm}
 * and {@code arkddd:BoundedContext} are currently only used within
 * {@code TraceabilityGraph} itself and stay local there).</p>
 */
public final class ArkdddVocabulary {

    private static final String NAMESPACE = "https://w3id.org/arknet/ddd#";

    /** {@code arkddd:domainVision} - BoundedContext -&gt; its vision text. */
    public static final String DOMAIN_VISION = NAMESPACE + "domainVision";

    private ArkdddVocabulary() {
    }
}
