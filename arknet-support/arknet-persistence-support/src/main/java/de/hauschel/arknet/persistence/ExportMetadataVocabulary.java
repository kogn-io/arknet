// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * The IRIs of the export-metadata graph: the envelope {@code project_export} writes around a
 * backup dump so a reader can tell which server, and which ontology modules, produced it
 * (issue #194).
 *
 * <p><strong>Output only, never persisted.</strong> Unlike every other vocabulary class in this
 * module, nothing here is ever written into a dataset. {@link #EXPORT_METADATA_GRAPH} exists only
 * in the serialised {@code .trig} text, appended once per exported project after the dataset's own
 * graphs. Keeping it a named graph of its own is the point: a reader can drop it wholesale and be
 * left with exactly the dataset, and no model or provenance graph gains a triple it did not have
 * in the store.</p>
 *
 * <p><strong>Nothing new is invented.</strong> arknet declares no terms of its own for this: the
 * statements are plain PROV-O ({@code prov:Activity}, {@code prov:SoftwareAgent},
 * {@code prov:endedAtTime}, {@code prov:wasAssociatedWith}, {@code prov:used}), {@code owl}
 * ({@code owl:Ontology}, {@code owl:versionInfo} - the very property the shipped
 * {@code arknet-*.ttl} modules already carry) and {@code dcterms:created}. That is the same
 * reuse-before-declare rule {@link ArkprjVocabulary}'s {@code DESCRIPTION} follows, and it means
 * this class needs no matching {@code .ttl} of its own - there is no arknet term here to
 * document.</p>
 *
 * <p>The PROV terms are spelled out here rather than borrowed from {@link ArkprovVocabulary}: that
 * class is the vocabulary of the <em>model's</em> revision trail, written into every project
 * dataset by the write funnel, and an export envelope that never enters a dataset has no business
 * widening its scope. Two IRI constants are the whole overlap.</p>
 */
public final class ExportMetadataVocabulary {

    private static final String PROV_NAMESPACE = "http://www.w3.org/ns/prov#";
    private static final String OWL_NAMESPACE = "http://www.w3.org/2002/07/owl#";
    private static final String DCTERMS_NAMESPACE = "http://purl.org/dc/terms/";

    /**
     * The named graph the export envelope lives in, following the same
     * {@code https://w3id.org/arknet/model/...} shape as
     * {@link ArkprovVocabulary#PROVENANCE_GRAPH} and {@link ArkprjVocabulary#IDENTITY_GRAPH},
     * even though - unlike those two - it is never stored.
     */
    public static final String EXPORT_METADATA_GRAPH = "https://w3id.org/arknet/model/export-metadata";

    /**
     * Base of the opaque IRI minted for each export activity, mirroring
     * {@link ArkprovVocabulary#REVISION_IRI_BASE}: every export is its own event, so two dumps
     * never claim to be the same one.
     */
    public static final String EXPORT_IRI_BASE = "https://w3id.org/arknet/export/";

    /**
     * The exporting software itself - one stable IRI, not minted per export: the agent is arknet,
     * and which build of it ran is said by its {@link #OWL_VERSION_INFO}, not by a fresh identity.
     */
    public static final String SERVER_AGENT = "https://w3id.org/arknet/agent/arknet";

    /** {@code rdf:type}. */
    public static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";

    /** {@code prov:Activity} - the type of the export event. */
    public static final String PROV_ACTIVITY = PROV_NAMESPACE + "Activity";

    /** {@code prov:SoftwareAgent} - the type of {@link #SERVER_AGENT}. */
    public static final String PROV_SOFTWARE_AGENT = PROV_NAMESPACE + "SoftwareAgent";

    /** {@code prov:endedAtTime} - when the export ran. */
    public static final String PROV_ENDED_AT_TIME = PROV_NAMESPACE + "endedAtTime";

    /** {@code prov:wasAssociatedWith} - links the export event to {@link #SERVER_AGENT}. */
    public static final String PROV_WAS_ASSOCIATED_WITH = PROV_NAMESPACE + "wasAssociatedWith";

    /**
     * {@code prov:used} - links the export event to each ontology module the exporting server
     * shipped, which is what makes the listed versions more than a loose footnote.
     */
    public static final String PROV_USED = PROV_NAMESPACE + "used";

    /** {@code owl:Ontology} - the type each listed ontology module is given. */
    public static final String OWL_ONTOLOGY = OWL_NAMESPACE + "Ontology";

    /**
     * {@code owl:versionInfo} - carries the server's own version on {@link #SERVER_AGENT} and each
     * ontology module's version on the module, read straight off the shipped {@code arknet-*.ttl}.
     */
    public static final String OWL_VERSION_INFO = OWL_NAMESPACE + "versionInfo";

    /**
     * {@code dcterms:created} - when the exporting server was built. Omitted rather than guessed
     * when no build information is on the classpath.
     */
    public static final String DCTERMS_CREATED = DCTERMS_NAMESPACE + "created";

    /** {@code xsd:dateTime}, the datatype of both timestamps above. */
    public static final String XSD_DATE_TIME = "http://www.w3.org/2001/XMLSchema#dateTime";

    private ExportMetadataVocabulary() {
    }
}
