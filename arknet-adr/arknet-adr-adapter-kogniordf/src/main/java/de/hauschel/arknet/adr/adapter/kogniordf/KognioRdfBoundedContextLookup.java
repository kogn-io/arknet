// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import java.util.List;
import java.util.Objects;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.vocab.VocabDct;

import de.hauschel.arknet.adr.application.port.out.BoundedContextLookup;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;

/**
 * Out-adapter: {@link BoundedContextLookup} backed by the kognio-rdf substrate, resolving a bounded
 * context's human-typed {@code dcterms:identifier} (e.g. {@code BC-1}) to its opaque subject
 * {@link ResourceId} within the shared project store.
 *
 * <p><strong>Strict cross-BC resolution.</strong> Architecture decisions and bounded contexts share
 * one per-project store. This adapter looks up a code by {@code dcterms:identifier} among the
 * {@code arkddd:BoundedContext}s of the bounded-context graph; an unknown or ambiguous code aborts
 * with a didactic {@link UnresolvedReferenceException}. Resolution goes via the identifier, never the
 * {@code arknet:name}, so a link survives renaming the context. Called once, from the application
 * service, at the moment a decision is recorded. Structurally 1:1 to the bounded-context adapter's
 * own {@code KognioRdfTermLookup}.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) - it
 * never imports RDF4J or any other backend-specific type. The backend ({@link DatasetLifecycle}
 * implementation) is supplied by the composition root, the same shared lifecycle
 * {@link KognioRdfAdrRepository} acquires datasets from.</p>
 */
public final class KognioRdfBoundedContextLookup implements BoundedContextLookup {

    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String BOUNDED_CONTEXT_TYPE = "https://w3id.org/arknet/ddd#BoundedContext";
    // Mirrors the graph IRI the bounded-context out-adapter writes into.
    private static final String BOUNDED_CONTEXT_GRAPH = "https://w3id.org/arknet/model/bounded-context";

    private final DatasetLifecycle lifecycle;

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from (must not be
     *                  {@code null})
     */
    public KognioRdfBoundedContextLookup(DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public ResourceId resolveByCode(ProjectId projectId, String boundedContextCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(boundedContextCode, "boundedContextCode");

        String query = "SELECT ?bc WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?bc a <" + BOUNDED_CONTEXT_TYPE + "> ; "
                + "<" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(boundedContextCode) + "\" } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<IRI> matches = handle.sparqlQuery().select(query)
                    .map(row -> iriOf(row, "bc"))
                    .distinct()
                    .toList();
            if (matches.isEmpty()) {
                throw new UnresolvedReferenceException("Bounded context '" + boundedContextCode
                        + "' does not exist in project '" + projectId.value()
                        + "'. Create it first with bc_add before an architecture decision affects it.");
            }
            if (matches.size() > 1) {
                throw new UnresolvedReferenceException("Bounded context identity '" + boundedContextCode
                        + "' is ambiguous in project '" + projectId.value() + "' (" + matches.size()
                        + " matches). Reference a bounded context by its unique dcterms:identifier.");
            }
            return ResourceId.of(matches.get(0).getIRIString());
        }
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
