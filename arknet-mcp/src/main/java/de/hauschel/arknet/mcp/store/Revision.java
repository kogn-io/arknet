// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.Objects;

/**
 * One PROV-O revision of a resource, as read back for {@code resource_history} (issue #251):
 * its own opaque IRI, its {@code prov:generatedAtTime} instant (the lexical form of the
 * {@code xsd:dateTime} literal the shared write funnel wrote, ADR-014), and whether it is the
 * resource's current {@code arkprov:head}. Backend-neutral, like {@link Triple}/{@link RdfNode} -
 * {@link StoreReader} is the only class here that reads the provenance graph these come from.
 *
 * @param iri             the revision's own IRI (under {@code ArkprovVocabulary#REVISION_IRI_BASE})
 * @param generatedAtTime the lexical form of its {@code prov:generatedAtTime} literal
 * @param current         {@code true} if this is the resource's {@code arkprov:head} revision
 */
public record Revision(String iri, String generatedAtTime, boolean current) {

    public Revision {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(generatedAtTime, "generatedAtTime");
    }
}
