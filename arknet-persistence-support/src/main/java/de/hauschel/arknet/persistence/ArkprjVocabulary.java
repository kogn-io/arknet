// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * The IRIs of arknet's project vocabulary ({@code arkprj:}, ADR-016) as plain string constants -
 * the single source both the project out-adapter serialises from and every reader that has to
 * recognise project triples without depending on that adapter.
 *
 * <p>Same role and same reasoning as {@link ArkprovVocabulary}: these are RDF serialisation
 * constants, not domain vocabulary. The project core never sees an IRI (its identity is opaque),
 * so they live here next to {@link SparqlTerms} rather than in the shared kernel, and as bare
 * strings they keep this module RDF4J-free. The ontology side is
 * {@code arknet-ontology/src/main/resources/arknet-project.ttl}.</p>
 *
 * <p><strong>Two graphs, two datasets, one vocabulary (ADR-016 decisions 6 and 7).</strong> A
 * project is written twice, deliberately:</p>
 *
 * <ul>
 *   <li>{@link #REGISTRY_GRAPH} in the reserved system dataset holds the registry - the index
 *       every anchor resolution looks up, and the place where a concurrently registered duplicate
 *       anchor collides instead of overwriting.</li>
 *   <li>{@link #IDENTITY_GRAPH} in the project's <em>own</em> dataset holds the same project's
 *       self-description, so the registry stays a rebuildable index rather than a single point of
 *       failure and a restored backup carries its identity with it.</li>
 * </ul>
 *
 * <p><strong>Why the identity graph must be excluded from the generic read path (ADR-006).</strong>
 * Unlike the registry - which lives in a dataset no ordinary call ever addresses and is therefore
 * invisible by construction - the self-description sits inside the very dataset
 * {@code store_overview}/{@code resource_get} read. It is infrastructure, not model: without the
 * exclusion, every project's store report would open with its own routing record. {@code StoreReader}
 * excludes it through the same shared filter that already excludes
 * {@link ArkprovVocabulary#PROVENANCE_GRAPH}.</p>
 *
 * <p>The reserved dataset id itself is deliberately <em>not</em> here but in the project core
 * ({@code ProjectId}): that a value is reserved is a domain invariant the core enforces when it
 * rejects it, not a serialisation detail.</p>
 */
public final class ArkprjVocabulary {

    /** The {@code arkprj:} namespace. */
    public static final String NAMESPACE = "https://w3id.org/arknet/project#";

    /** Named graph of the registry, inside the reserved system dataset. */
    public static final String REGISTRY_GRAPH = "https://w3id.org/arknet/model/project-registry";

    /**
     * Named graph of a project's self-description, inside that project's own dataset. Excluded
     * from the generic read path - see the class javadoc.
     */
    public static final String IDENTITY_GRAPH = "https://w3id.org/arknet/model/project-identity";

    /** {@code arkprj:Project} - the registered project itself. */
    public static final String PROJECT_TYPE = NAMESPACE + "Project";

    /** {@code arkprj:Anchor} - one opaque, typed hold a client identifies its project by. */
    public static final String ANCHOR_CLASS = NAMESPACE + "Anchor";

    /** {@code arkprj:anchor} - project to anchor, {@code 1..n}. */
    public static final String ANCHOR = NAMESPACE + "anchor";

    /** {@code arkprj:anchorValue} - the anchor's opaque string, never interpreted by the server. */
    public static final String ANCHOR_VALUE = NAMESPACE + "anchorValue";

    /** {@code arkprj:anchorType} - anchor to one of the three type individuals below. */
    public static final String ANCHOR_TYPE = NAMESPACE + "anchorType";

    /** {@code arkprj:PathAnchor} - the anchor is a working-directory path. */
    public static final String PATH_ANCHOR = NAMESPACE + "PathAnchor";

    /** {@code arkprj:UrlAnchor} - the anchor is a URL. */
    public static final String URL_ANCHOR = NAMESPACE + "UrlAnchor";

    /** {@code arkprj:UuidAnchor} - the anchor is a client-generated UUID. */
    public static final String UUID_ANCHOR = NAMESPACE + "UuidAnchor";

    private ArkprjVocabulary() {
    }
}
