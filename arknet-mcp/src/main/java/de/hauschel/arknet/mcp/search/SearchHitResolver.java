// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.search;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.store.Prefixes;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.ResourceHandles;
import de.hauschel.arknet.mcp.store.StoreReader;
import de.hauschel.arknet.mcp.store.StoreResource;
import de.hauschel.arknet.mcp.store.Triple;

/**
 * Resolves a raw {@link StoreReader#literalContaining} match onto a {@link SearchHit}: the
 * resource it is reported under, the field, and a snippet.
 *
 * <p><strong>Owner resolution is purely structural</strong> (as required by ADR-51/55's
 * type-independent generic read path): a matched subject that carries its own
 * {@code dcterms:identifier} is reported under itself; one that does not - an {@code
 * arkreq:Step}, an {@code arkreq:AcceptanceCriterion} and any similar aggregate-internal value
 * object - is climbed to its nearest owner by following the first incoming edge, repeated up to
 * {@link #MAX_OWNER_HOPS} times, without ever asking what {@code rdf:type} either side carries.
 * A subject with neither an identifier nor an incoming edge (an orphan) is reported under its own
 * raw handle, same as {@link ResourceHandles} already falls back to elsewhere.</p>
 */
final class SearchHitResolver {

    /**
     * Bounds the owner climb so a cyclical or pathologically deep store-first graph cannot loop
     * forever; every arknet-minted aggregate is at most one hop deep (a step/criterion points
     * straight back at its owning use case/requirement), so this is generous headroom, not a
     * tuned limit.
     */
    private static final int MAX_OWNER_HOPS = 5;

    private final StoreReader storeReader;
    private final Prefixes prefixes;
    private final ProjectId projectId;

    SearchHitResolver(StoreReader storeReader, Prefixes prefixes, ProjectId projectId) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
        this.projectId = Objects.requireNonNull(projectId, "projectId");
    }

    SearchHit resolve(Triple match, String needle) {
        RdfNode.Literal literal = (RdfNode.Literal) match.object();
        String field = prefixes.toCurie(match.predicate());
        String snippet = Snippet.around(literal.lexicalForm(), needle);
        Optional<String> languageTag = Optional.ofNullable(literal.languageTag());

        String owner = match.subject();
        Optional<String> ownerIdentifier = identifierOf(owner);
        String viaEdge = null;
        int hop = 0;
        while (ownerIdentifier.isEmpty() && hop < MAX_OWNER_HOPS) {
            List<Triple> incoming = storeReader.incoming(projectId, owner);
            if (incoming.isEmpty()) {
                break;
            }
            Triple ownerEdge = incoming.get(0);
            viaEdge = prefixes.toCurie(ownerEdge.predicate());
            owner = ownerEdge.subject();
            ownerIdentifier = identifierOf(owner);
            hop++;
        }

        Set<String> ambiguous = ownerIdentifier
                .filter(id -> storeReader.findByIdentifier(projectId, id).size() > 1)
                .map(Set::of)
                .orElse(Set.of());
        String ownerHandle = ResourceHandles.of(prefixes, owner, ownerIdentifier, ambiguous);
        return new SearchHit(ownerHandle, field, Optional.ofNullable(viaEdge), languageTag, snippet);
    }

    private Optional<String> identifierOf(String iri) {
        return new StoreResource(iri, storeReader.outgoing(projectId, iri)).identifier();
    }
}
