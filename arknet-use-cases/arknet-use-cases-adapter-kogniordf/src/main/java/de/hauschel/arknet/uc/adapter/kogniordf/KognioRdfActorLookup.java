// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import java.util.List;
import java.util.Objects;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.IRI;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.uc.application.port.out.ActorLookup;

/**
 * Out-adapter: {@link ActorLookup} backed by the kognio-rdf substrate, resolving an actor's
 * human-typed {@code skos:prefLabel} (e.g. {@code Customer}) to its opaque subject
 * {@link ResourceId} within the shared workspace store.
 *
 * <p><strong>Strict cross-BC actor resolution (issue #89).</strong> Use-cases and
 * ubiquitous-language actors share one per-workspace store. This adapter looks up a name by
 * {@code skos:prefLabel} among concepts carrying an actor type
 * ({@code arkproc:HumanActor}/{@code arkproc:SystemActor}); an unknown or ambiguous name aborts
 * with a didactic {@link UnresolvedReferenceException}. This is called once, from the application
 * service, at the moment a use case is written - {@code KognioRdfUseCaseRepository} no longer
 * performs this lookup itself; it just persists the {@link ResourceId} it is handed.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) -
 * it never imports RDF4J or any other backend-specific type. The backend
 * ({@link DatasetLifecycle} implementation) is supplied by the composition root, the same shared
 * lifecycle {@code KognioRdfUseCaseRepository} acquires datasets from.</p>
 */
public final class KognioRdfActorLookup implements ActorLookup {

    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    private static final String ARKPROC_NAMESPACE = "https://w3id.org/arknet/process#";
    private static final String PREF_LABEL_PROPERTY = SKOS_NAMESPACE + "prefLabel";
    private static final String HUMAN_ACTOR_TYPE = ARKPROC_NAMESPACE + "HumanActor";
    private static final String SYSTEM_ACTOR_TYPE = ARKPROC_NAMESPACE + "SystemActor";
    // Mirrors the graph IRI the ubiquitous-language out-adapter writes into. The bounded contexts
    // share one workspace dataset; resolving an actor means reading across into that sibling graph.
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";

    private final DatasetLifecycle lifecycle;

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from (must not be
     *                  {@code null})
     */
    public KognioRdfActorLookup(DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public ResourceId resolveByName(ProjectId projectId, String actorName) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(actorName, "actorName");

        String query = "SELECT ?actor WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "?actor <" + PREF_LABEL_PROPERTY + "> \"" + SparqlTerms.escape(actorName) + "\" . "
                + "{ ?actor a <" + HUMAN_ACTOR_TYPE + "> } UNION { ?actor a <" + SYSTEM_ACTOR_TYPE + "> } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<IRI> matches = handle.sparqlQuery().select(query)
                    .map(row -> iriOf(row, "actor"))
                    .distinct()
                    .toList();
            if (matches.isEmpty()) {
                throw new UnresolvedReferenceException("Actor '" + actorName
                        + "' does not exist in workspace '" + projectId.value()
                        + "'. Create it first with term_add (actorKind human|system) before a use case "
                        + "references it.");
            }
            if (matches.size() > 1) {
                throw new UnresolvedReferenceException("Actor label '" + actorName
                        + "' is ambiguous in workspace '" + projectId.value() + "' (" + matches.size()
                        + " matches). Give the actor term a unique skos:prefLabel.");
            }
            return ResourceId.of(matches.get(0).getIRIString());
        }
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
