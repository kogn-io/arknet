// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Resolves a resource handle - CURIE, full IRI, or bare business id - to an absolute IRI.
 *
 * <p>Extracted out of {@link StoreReportTools} so {@code impact_analysis} - one of the five
 * traceability tools in package {@code de.hauschel.arknet.mcp.trace} ({@code trace_matrix},
 * {@code orphan_check}, {@code impact_analysis}, {@code role_usecase_matrix},
 * {@code term_cooccurrence}), and the only one of them that takes a resource handle - shares
 * the exact same handle contract {@code resource_get} uses, instead of a second, drifting
 * implementation growing next to it.</p>
 *
 * <p>Resolution order: (1) a blank-node reference ({@code "_:" + label}, as {@link StoreReader}
 * renders a store-first resource with no minted IRI - into {@code store_overview}'s
 * digest) resolves to itself, it already <em>is</em> the handle {@link StoreReader#outgoing}/
 * {@link StoreReader#incoming} expect; (2) a full IRI, or a CURIE against a known
 * {@link Prefixes} binding, is authoritative; (3) anything else is a bare business id, resolved
 * via {@link StoreReader#findByIdentifier} ({@code dcterms:identifier}), rejecting ambiguity
 * across bounded contexts with a didactic message instead of guessing. Domain-agnostic, like
 * {@link StoreReader}/{@link Prefixes}: it knows nothing about requirements, terms or use
 * cases.</p>
 */
public final class HandleResolver {

    private final StoreReader storeReader;
    private final Prefixes prefixes;

    /**
     * @param storeReader the generic store read path, used for the bare-id fallback lookup
     * @param prefixes    the CURIE / IRI resolver
     */
    public HandleResolver(StoreReader storeReader, Prefixes prefixes) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
    }

    /**
     * Resolves a handle to an absolute IRI, following the contract described in the class-level
     * Javadoc.
     *
     * @param projectId the project to resolve a bare business id against
     * @param id          the handle: a CURIE (e.g. {@code req:FR-1}), a full IRI, a blank-node
     *                    reference (e.g. {@code _:b1}, as shown by {@code store_overview} for a
     *                    resource with no minted IRI), or a bare business id (e.g. {@code FR-1})
     * @return the resolved absolute IRI, or the blank-node reference unchanged
     * @throws IllegalArgumentException if the handle is empty, uses an unknown prefix, or a
     *                                  bare id resolves to zero or more than one resource
     */
    public String resolve(ProjectId projectId, String id) {
        Objects.requireNonNull(projectId, "projectId");
        final String handle = Objects.requireNonNull(id, "id").strip();
        if (handle.isEmpty()) {
            throw new IllegalArgumentException("Empty resource handle. Pass a CURIE (req:FR-1),"
                    + " a full IRI, or a bare business id (FR-1).");
        }
        if (handle.startsWith("_:")) {
            return handle;
        }

        final Optional<String> resolved = prefixes.toIri(handle);
        if (resolved.isPresent()) {
            return resolved.get();
        }

        // A colon whose prefix is neither a known CURIE prefix nor a syntactically valid URI
        // scheme (see Prefixes#toIri, issue #305 - urn:.../mailto:... resolve above, before this
        // point is ever reached) means a CURIE with an unknown prefix - do not guess, explain
        // (the handle contract is CURIE/IRI first).
        if (handle.contains(":") && !handle.contains("://")) {
            final String known = prefixes.bindings().stream()
                    .map(Prefixes.Prefix::prefix).sorted().reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException("Unknown prefix in handle '" + handle + "'."
                    + " Known prefixes: " + known + ". Pass a full IRI instead, or a bare business id.");
        }

        // Bare business id: resolve via dcterms:identifier; reject ambiguity across contexts.
        final List<String> matches = storeReader.findByIdentifier(projectId, handle);
        if (matches.isEmpty()) {
            throw new IllegalArgumentException("No resource found for id '" + handle + "'."
                    + " Use a CURIE (req:FR-1) or full IRI, or check the id via store_overview.");
        }
        if (matches.size() > 1) {
            final String candidates = matches.stream()
                    .map(prefixes::toCurie).reduce((a, b) -> a + ", " + b).orElse("");
            throw new IllegalArgumentException("Ambiguous id '" + handle + "' matches several resources"
                    + " across bounded contexts: " + candidates + ". Re-call with the exact CURIE or IRI"
                    + " of the one you mean.");
        }
        return matches.get(0);
    }
}
