// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

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
import de.hauschel.arknet.req.application.port.out.TermLookup;

/**
 * Out-adapter: {@link TermLookup} backed by the kognio-rdf substrate, resolving a glossary
 * term's human-typed {@code dcterms:identifier} (e.g. {@code TERM-1}) to its opaque subject
 * {@link ResourceId} within the shared workspace store.
 *
 * <p><strong>Strict cross-BC term resolution (issue #36, extracted into its own port for
 * #77).</strong> Requirements and ubiquitous-language terms share one per-project store. This
 * adapter looks up a code by {@code dcterms:identifier} among the {@code skos:Concept}s of the
 * glossary graph; an unknown or ambiguous code aborts with a didactic
 * {@link UnresolvedReferenceException}. Resolution goes via the identifier, never the
 * {@code skos:prefLabel}, so a link survives relabelling a term. This is called once, from the
 * application service, at the moment a term is linked - {@code KognioRdfRequirementRepository}
 * no longer performs this lookup itself; it just persists the {@link ResourceId} it is
 * handed.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) -
 * it never imports RDF4J or any other backend-specific type. The backend
 * ({@link DatasetLifecycle} implementation) is supplied by the composition root, the same shared
 * lifecycle {@code KognioRdfRequirementRepository} acquires datasets from.</p>
 */
public final class KognioRdfTermLookup implements TermLookup {

    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    private static final String CONCEPT_TYPE = SKOS_NAMESPACE + "Concept";
    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    // Mirrors the graph IRI the ubiquitous-language out-adapter writes into. The bounded
    // contexts share one project dataset; resolving a term means reading across into that
    // sibling graph.
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";

    private final DatasetLifecycle lifecycle;

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from (must not be
     *                  {@code null})
     */
    public KognioRdfTermLookup(DatasetLifecycle lifecycle) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    public ResourceId resolveByCode(ProjectId projectId, String termCode) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(termCode, "termCode");

        String query = "SELECT ?term WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "?term a <" + CONCEPT_TYPE + "> ; "
                + "<" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(termCode) + "\" } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<IRI> matches = handle.sparqlQuery().select(query)
                    .map(row -> iriOf(row, "term"))
                    .distinct()
                    .toList();
            if (matches.isEmpty()) {
                throw new UnresolvedReferenceException("Term '" + termCode
                        + "' does not exist in project '" + projectId.value()
                        + "'. Create it first with term_add before a requirement uses it.");
            }
            if (matches.size() > 1) {
                throw new UnresolvedReferenceException("Term identity '" + termCode
                        + "' is ambiguous in project '" + projectId.value() + "' (" + matches.size()
                        + " matches). Reference a term by its unique dcterms:identifier.");
            }
            return ResourceId.of(matches.get(0).getIRIString());
        }
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
