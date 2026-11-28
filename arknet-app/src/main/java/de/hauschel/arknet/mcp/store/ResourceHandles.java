// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The drill-down handle for a resource, used by {@link DigestRenderer} (every listed resource of
 * {@code store_overview}) - one place for the "how do I name this resource" rule instead of
 * drifting copies (issue #594).
 *
 * <p>The handle is one {@code resource_get} reads: (1) the resource's {@code dcterms:identifier}
 * (its business code, e.g. {@code FR-1}), if it carries one AND no other resource in scope carries
 * the same lexical identifier, (2) else the full IRI. The uniqueness check matters because
 * {@code resource_get} answers a code naming more than one resource as unresolved (issue #150) -
 * printing it as a handle in that case would promise a drill-down guaranteed to fail, so callers
 * fall back to the always unique full IRI instead. A prefixed name is never chosen: the instance
 * prefixes of earlier layouts ({@code req:}, {@code term:}, {@code act:}) are no prefixes
 * {@code resource_get} expands, and every resource carrying a code is reached by it.</p>
 */
public final class ResourceHandles {

    private ResourceHandles() {
    }

    /**
     * @param iri                  the subject IRI to name, or a blank-node reference
     * @param identifier           the subject's {@code dcterms:identifier}, if it carries one
     * @param ambiguousIdentifiers every identifier value shared by more than one resource in the
     *                             scope the caller is naming resources within - see the class
     *                             Javadoc for why a shared identifier cannot be used as a handle
     * @return the handle to display for {@code iri}
     */
    public static String of(String iri, Optional<String> identifier, Set<String> ambiguousIdentifiers) {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(identifier, "identifier");
        Objects.requireNonNull(ambiguousIdentifiers, "ambiguousIdentifiers");
        return identifier.filter(value -> !ambiguousIdentifiers.contains(value)).orElse(iri);
    }
}
