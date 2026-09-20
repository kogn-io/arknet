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

import de.hauschel.arknet.adr.application.port.out.RequirementLookup;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;

/**
 * Out-adapter: {@link RequirementLookup} backed by the kognio-rdf substrate, resolving a
 * requirement's human-typed {@code dcterms:identifier} (e.g. {@code FR-1}) to its opaque subject
 * {@link ResourceId} within the shared project store.
 *
 * <p><strong>Strict cross-BC requirement resolution.</strong> Architecture decisions and
 * requirements share one per-project store. This adapter looks up a code by
 * {@code dcterms:identifier} among the requirements graph's subjects; an unknown or ambiguous code
 * aborts with a didactic {@link UnresolvedReferenceException}. Resolution goes via the identifier,
 * never the {@code dcterms:title}, so a link survives retitling the requirement. This is called
 * once, from the application service, at the moment a decision is recorded -
 * {@link KognioRdfAdrRepository} never performs this lookup itself; it just persists the
 * {@link ResourceId} it is handed. Structurally 1:1 to the use-cases adapter's own
 * {@code KognioRdfRequirementLookup}.</p>
 *
 * <p><strong>No type filter.</strong> Requirements are typed either
 * {@code arkreq:FunctionalRequirement} or {@code arkreq:NonFunctionalRequirement}; a type filter
 * here would either need both alternatives (no benefit - {@code dcterms:identifier} alone already
 * scopes the join to requirements-graph subjects that carry a code) or would arbitrarily exclude one
 * requirement type. An architecture decision may legitimately address either.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) - it
 * never imports RDF4J or any other backend-specific type. The backend ({@link DatasetLifecycle}
 * implementation) is supplied by the composition root, the same shared lifecycle
 * {@link KognioRdfAdrRepository} acquires datasets from.</p>
 */
public final class KognioRdfRequirementLookup implements RequirementLookup {

    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    // Mirrors the graph IRI the requirements out-adapter writes into. The bounded contexts share one
    // project dataset; resolving a requirement means reading across into that sibling graph.
    private static final String REQUIREMENTS_GRAPH = "https://w3id.org/arknet/model/requirements";

    private final DatasetLifecycle lifecycle;

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from (must not be
     *                  {@code null})
     */
    public KognioRdfRequirementLookup(DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public ResourceId resolveByCode(ProjectId projectId, String requirementCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(requirementCode, "requirementCode");

        String query = "SELECT ?req WHERE { GRAPH <" + REQUIREMENTS_GRAPH + "> { "
                + "?req <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(requirementCode) + "\" } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<IRI> matches = handle.sparqlQuery().select(query)
                    .map(row -> iriOf(row, "req"))
                    .distinct()
                    .toList();
            if (matches.isEmpty()) {
                throw new UnresolvedReferenceException("Requirement '" + requirementCode
                        + "' does not exist in project '" + projectId.value()
                        + "'. Create it first with req_add before an architecture decision addresses it.");
            }
            if (matches.size() > 1) {
                throw new UnresolvedReferenceException("Requirement identity '" + requirementCode
                        + "' is ambiguous in project '" + projectId.value() + "' (" + matches.size()
                        + " matches). Reference a requirement by its unique dcterms:identifier.");
            }
            return ResourceId.of(matches.get(0).getIRIString());
        }
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
