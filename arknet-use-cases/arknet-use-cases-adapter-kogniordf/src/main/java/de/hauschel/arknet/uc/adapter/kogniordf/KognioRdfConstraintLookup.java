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
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.uc.application.port.out.ConstraintLookup;

/**
 * Out-adapter: {@link ConstraintLookup} backed by the kognio-rdf substrate, resolving a
 * constraint's human-typed {@code dcterms:identifier} (e.g. {@code TCON-1}) to its opaque subject
 * {@link ResourceId} within the shared project store (issue #329).
 *
 * <p><strong>Strict cross-BC constraint resolution.</strong> Use-cases and constraints (a second
 * resource type of the requirements bounded context, not a hexagon of its own) share one
 * per-project store. This adapter looks up a code by {@code dcterms:identifier} among the
 * {@code CONSTRAINTS_GRAPH}'s subjects; an unknown or ambiguous code aborts with a didactic
 * {@link UnresolvedReferenceException}. This is called once, from the application service, at the
 * moment a constraint is linked - {@code KognioRdfUseCaseRepository} no longer performs this
 * lookup itself; it just persists the {@link ResourceId} it is handed. Deliberately its own class
 * rather than a shared import of the sibling requirements bounded context's
 * {@code KognioRdfConstraintRepository}: ADR-008 forbids cross-BC adapter imports, only an
 * In-Adapter may consume a neighbour's In-Port.</p>
 *
 * <p><strong>No type filter.</strong> A constraint is typed one of
 * {@code arkreq:TechnicalConstraint}/{@code arkreq:BusinessConstraint}/
 * {@code arkreq:RegulatoryConstraint}; a type filter here would either need all three
 * alternatives (no benefit - {@code dcterms:identifier} within {@code CONSTRAINTS_GRAPH} already
 * scopes the join to constraint subjects) or would arbitrarily exclude two types, mirroring
 * {@code KognioRdfRequirementLookup}'s identical reasoning.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) -
 * it never imports RDF4J or any other backend-specific type. The backend
 * ({@link DatasetLifecycle} implementation) is supplied by the composition root, the same shared
 * lifecycle {@code KognioRdfUseCaseRepository} acquires datasets from.</p>
 */
public final class KognioRdfConstraintLookup implements ConstraintLookup {

    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    // Mirrors the graph IRI the requirements bounded context's constraint out-adapter
    // (KognioRdfConstraintRepository) writes into. The bounded contexts share one project
    // dataset; resolving a constraint means reading across into that sibling graph.
    private static final String CONSTRAINTS_GRAPH = "https://w3id.org/arknet/model/constraints";

    private final DatasetLifecycle lifecycle;

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from (must not be
     *                  {@code null})
     */
    public KognioRdfConstraintLookup(DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public ResourceId resolveByCode(ProjectId projectId, String constraintCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(constraintCode, "constraintCode");

        String query = "SELECT ?constraint WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + "?constraint <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(constraintCode) + "\" } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<IRI> matches = handle.sparqlQuery().select(query)
                    .map(row -> iriOf(row, "constraint"))
                    .distinct()
                    .toList();
            if (matches.isEmpty()) {
                throw new UnresolvedReferenceException("Constraint '" + constraintCode
                        + "' does not exist in project '" + projectId.value()
                        + "'. Create it first with constraint_add before a use case is bound by it.");
            }
            if (matches.size() > 1) {
                throw new UnresolvedReferenceException("Constraint identity '" + constraintCode
                        + "' is ambiguous in project '" + projectId.value() + "' (" + matches.size()
                        + " matches). Reference a constraint by its unique dcterms:identifier.");
            }
            return ResourceId.of(matches.get(0).getIRIString());
        }
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
