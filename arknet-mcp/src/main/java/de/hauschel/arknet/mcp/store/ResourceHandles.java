// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The display/drill-down handle for a resource, shared by {@link DigestRenderer} (every listed
 * resource of {@code store_overview}) and {@link StoreReportTools} (an incoming neighbour shown
 * by {@code resource_get}, resolved there and handed to {@link ResourceRenderer} as plain text) -
 * one place for the "how do I name this resource" rule instead of two drifting copies (issue
 * #594).
 *
 * <p>Preference order: (1) a CURIE, if the subject IRI shortens against a {@link Prefixes}
 * namespace, (2) else the resource's {@code dcterms:identifier} (a bare business id, e.g.
 * {@code FR-1}), if it carries one AND no other resource in scope carries the same lexical
 * identifier, (3) else the full IRI. Case (2) is what keeps opaque, kernel-minted identities (a
 * {@link de.hauschel.arknet.kernel.ResourceId} is not bound to any CURIE namespace)
 * human-readable; the uniqueness check matters because {@code resource_get} rejects a bare id
 * that resolves to more than one resource as ambiguous (issue #150) - printing it as a handle in
 * that case would promise a drill-down affordance guaranteed to fail, so callers fall back to the
 * (always unique) full IRI instead.</p>
 */
final class ResourceHandles {

    private ResourceHandles() {
    }

    /**
     * @param prefixes             the CURIE resolver used to shorten {@code iri} for display
     * @param iri                  the subject IRI to name
     * @param identifier           the subject's {@code dcterms:identifier}, if it carries one
     * @param ambiguousIdentifiers every identifier value shared by more than one resource in the
     *                             scope the caller is naming resources within - see the class
     *                             Javadoc for why a shared identifier cannot be used as a handle
     * @return the handle to display for {@code iri}
     */
    static String of(Prefixes prefixes, String iri, Optional<String> identifier, Set<String> ambiguousIdentifiers) {
        Objects.requireNonNull(prefixes, "prefixes");
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(ambiguousIdentifiers, "ambiguousIdentifiers");
        String curie = prefixes.toCurie(iri);
        if (!curie.equals(iri)) {
            return curie;
        }
        return identifier.filter(value -> !ambiguousIdentifiers.contains(value)).orElse(iri);
    }
}
