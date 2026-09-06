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
import io.kogn.rdf.terms.vocab.VocabDct;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.uc.application.port.out.RoleLookup;

/**
 * Out-adapter: {@link RoleLookup} backed by the kognio-rdf substrate, resolving a role's
 * human-typed business code (e.g. {@code ROLE-4}) to its opaque subject {@link ResourceId} within
 * the shared project store (ADR-37/kogn-io/arknet#405 Part C).
 *
 * <p><strong>Register-backed cross-BC role resolution, repointed from actor to role.</strong>
 * Before this class, {@code KognioRdfActorLookup} resolved {@code arkreq:primaryActor}/
 * {@code supportingActor} against {@code arknet-actor}'s actor register (graph
 * {@code .../model/actors}), matching an untagged {@code arknet:name} literal. Since Part C the
 * edges instead target {@code arkproc:Role}, a second, independent resource type of the very same
 * hexagon (ADR-37/Part B), living in its own named graph, {@link #ROLE_GRAPH}
 * ({@code .../model/roles}). This adapter looks a code up by {@code dcterms:identifier} among
 * that graph's {@code arkproc:Role}-typed subjects - <strong>both</strong> the type and the
 * identifier are matched, since the type check of the reference lives here and nowhere else
 * (the shape asserting {@code arkreq:primaryRole}'s {@code sh:class} cannot verify it, see
 * {@code KognioRdfUseCaseRepository}'s class-level note): an unknown code, or a code that names
 * some other resource entirely (e.g. an {@code ACTOR-N} code, which never carries
 * {@code a arkproc:Role}), aborts with a didactic {@link UnresolvedReferenceException}. This is
 * called once, from the application service, at the moment a use case is written -
 * {@code KognioRdfUseCaseRepository} no longer performs this lookup itself; it just persists the
 * {@link ResourceId} it is handed.</p>
 *
 * <p><strong>Resolved by code, not by name - the reason Part C repoints this lookup rather than
 * merely renaming it.</strong> A role's {@code name} is language-tagged (ADR-37 Part B), unlike an
 * actor's untagged {@code arknet:name}: matching against a language-tagged literal would be
 * ambiguous the moment a role carries more than one language variant. Resolution therefore goes
 * via the role's stable, single-valued {@code dcterms:identifier} instead - the same key
 * {@code filledBy} already resolves an occupant actor by (its {@code ACTOR-N} code).</p>
 *
 * <p><strong>No ambiguity branch, unlike its predecessor - a genuine behavioural difference.</strong>
 * {@code KognioRdfActorLookup#resolveByName} could see more than one match, since nothing in the
 * model guarantees an actor's {@code arknet:name} is unique; this adapter's resolution key,
 * {@code dcterms:identifier}, is the very same business code {@code role_add}/{@code role_update}
 * enforce uniqueness on across the whole project (see {@code RoleService}), so two distinct roles
 * legitimately sharing a code cannot occur via the MCP tools. This adapter therefore takes the
 * first match rather than counting and rejecting on more than one - a store-first anomaly
 * (two subjects both typed {@code arkproc:Role} and both carrying the identical
 * {@code dcterms:identifier}) is out of this adapter's scope, not a case it is built to detect.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) -
 * it never imports RDF4J or any other backend-specific type, and it does not depend on
 * {@code arknet-actor-core}: the use-cases component must not depend on a neighbour BC's domain
 * module (see {@link RoleLookup}), so this remains a plain SPARQL read against the shared store,
 * scoped to the graph and predicates the role out-adapter is known to write. The backend
 * ({@link DatasetLifecycle} implementation) is supplied by the composition root, the same shared
 * lifecycle {@code KognioRdfUseCaseRepository} acquires datasets from.</p>
 */
public final class KognioRdfRoleLookup implements RoleLookup {

    private static final String ROLE_TYPE = ArkprocVocabulary.ROLE_TYPE;
    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    // Mirrors the graph IRI the role out-adapter (KognioRdfRoleRepository) writes into. The
    // bounded contexts share one project dataset; resolving a role means reading across into
    // that sibling graph.
    private static final String ROLE_GRAPH = "https://w3id.org/arknet/model/roles";

    private final DatasetLifecycle lifecycle;

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from (must not be
     *                  {@code null})
     */
    public KognioRdfRoleLookup(DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public ResourceId resolveByCode(ProjectId projectId, String roleCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(roleCode, "roleCode");

        String query = "SELECT ?role WHERE { GRAPH <" + ROLE_GRAPH + "> { "
                + "?role a <" + ROLE_TYPE + "> ; "
                + "<" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(roleCode) + "\" } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<IRI> matches = handle.sparqlQuery().select(query)
                    .map(row -> iriOf(row, "role"))
                    .distinct()
                    .toList();
            if (matches.isEmpty()) {
                throw new UnresolvedReferenceException("Role '" + roleCode
                        + "' does not exist in project '" + projectId.value()
                        + "'. Create it first with role_add before a use case references it.");
            }
            return ResourceId.of(matches.get(0).getIRIString());
        }
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
