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
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.uc.application.port.out.ActorLookup;

/**
 * Out-adapter: {@link ActorLookup} backed by the kognio-rdf substrate, resolving an actor's
 * human-typed name (e.g. {@code Customer}) to its opaque subject {@link ResourceId} within the
 * shared project store.
 *
 * <p><strong>Register-backed cross-BC actor resolution.</strong> Since issue #336, actors are no
 * longer a facet on glossary terms; they live in {@code arknet-actor}'s own register, one
 * ungoverned resource type in its own named graph. This adapter looks a name up by the untagged
 * {@code arknet:name} literal among the four concrete actor types
 * ({@code arkproc:HumanActor}/{@code SystemActor}/{@code LegalActor}/{@code GroupActor}); an
 * unknown or ambiguous name aborts with a didactic {@link UnresolvedReferenceException}. This is
 * called once, from the application service, at the moment a use case is written -
 * {@code KognioRdfUseCaseRepository} no longer performs this lookup itself; it just persists the
 * {@link ResourceId} it is handed.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) -
 * it never imports RDF4J or any other backend-specific type, and it does not depend on
 * {@code arknet-actor-core}: the use-cases component must not depend on a neighbour BC's domain
 * module (see {@link ActorLookup}), so this remains a plain SPARQL read against the shared store,
 * scoped to the graph and predicates the actor out-adapter is known to write. The backend
 * ({@link DatasetLifecycle} implementation) is supplied by the composition root, the same shared
 * lifecycle {@code KognioRdfUseCaseRepository} acquires datasets from.</p>
 *
 * <p><strong>Untagged literal, exact match.</strong> Unlike the {@code skos:prefLabel} this
 * adapter used to match against - which a store-first actor concept could legally carry
 * only as a language-tagged literal, forcing a lexical-form comparison - {@code arknet:name} is
 * always written untagged (see {@code KognioRdfActorRepository}). {@code actorName} is likewise
 * what a human typed at the MCP boundary ({@code uc_add}), which never carries a language tag of
 * its own, so a plain RDF-term match on the escaped literal is sufficient; there is no tag-blind
 * comparison to reason about here.</p>
 */
public final class KognioRdfActorLookup implements ActorLookup {

    private static final String ARKNET_NAMESPACE = "https://w3id.org/arknet/core#";
    private static final String NAME_PROPERTY = ARKNET_NAMESPACE + "name";
    // Shared via ArkprocVocabulary (kogn-io/arknet#148): this class used to declare its own private
    // copy of these four IRI literals, duplicated with TraceabilityGraph's and
    // KognioRdfActorRepository's own private copies.
    private static final String HUMAN_ACTOR_TYPE = ArkprocVocabulary.HUMAN_ACTOR_TYPE;
    private static final String SYSTEM_ACTOR_TYPE = ArkprocVocabulary.SYSTEM_ACTOR_TYPE;
    private static final String LEGAL_ACTOR_TYPE = ArkprocVocabulary.LEGAL_ACTOR_TYPE;
    private static final String GROUP_ACTOR_TYPE = ArkprocVocabulary.GROUP_ACTOR_TYPE;
    // Mirrors the graph IRI the actor out-adapter writes into (KognioRdfActorRepository's
    // ACTOR_GRAPH). The bounded contexts share one project dataset; resolving an actor means
    // reading across into that sibling graph.
    private static final String ACTOR_GRAPH = "https://w3id.org/arknet/model/actors";

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

        String query = "SELECT ?actor WHERE { GRAPH <" + ACTOR_GRAPH + "> { "
                + "?actor a ?type . "
                + "FILTER(?type = <" + HUMAN_ACTOR_TYPE + "> || ?type = <" + SYSTEM_ACTOR_TYPE + "> "
                + "|| ?type = <" + LEGAL_ACTOR_TYPE + "> || ?type = <" + GROUP_ACTOR_TYPE + ">) "
                + "?actor <" + NAME_PROPERTY + "> \"" + SparqlTerms.escape(actorName) + "\" } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<IRI> matches = handle.sparqlQuery().select(query)
                    .map(row -> iriOf(row, "actor"))
                    .distinct()
                    .toList();
            if (matches.isEmpty()) {
                throw new UnresolvedReferenceException("Actor '" + actorName
                        + "' does not exist in project '" + projectId.value()
                        + "'. Create it first with actor_add (type human|system|legal|group) before a use case "
                        + "references it.");
            }
            if (matches.size() > 1) {
                throw new UnresolvedReferenceException("Actor name '" + actorName
                        + "' is ambiguous in project '" + projectId.value() + "' (" + matches.size()
                        + " matches). Give the actor a unique name.");
            }
            return ResourceId.of(matches.get(0).getIRIString());
        }
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
