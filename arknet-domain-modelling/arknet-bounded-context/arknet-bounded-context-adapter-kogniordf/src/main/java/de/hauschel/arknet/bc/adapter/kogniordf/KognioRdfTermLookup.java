// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.vocab.VocabDct;

import de.hauschel.arknet.bc.application.port.out.TermLookup;
import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;

/**
 * Out-adapter: {@link TermLookup} backed by the kognio-rdf substrate, translating between a
 * glossary term's human-typed {@code dcterms:identifier} (e.g. {@code TERM-1}) and its opaque
 * subject {@link ResourceId} within the shared project store, in both directions.
 *
 * <p><strong>The one mechanism for reading the glossary (ADR-49).</strong> Both directions read
 * the glossary's published language - its graph in the shared store - and neither touches a
 * module of the glossary component. The reverse direction ({@link #codesById}) exists so the
 * driving adapter can render {@code TERM-1} instead of a bare IRI without borrowing the glossary
 * component's own in-port.</p>
 *
 * <p><strong>Strict term resolution.</strong> Bounded contexts and
 * ubiquitous-language terms share one per-project store. This adapter looks up a code by
 * {@code dcterms:identifier} among the {@code skos:Concept}s of the glossary graph; an unknown or
 * ambiguous code aborts with a didactic {@link UnresolvedReferenceException}. Resolution goes via
 * the identifier, never the {@code skos:prefLabel}, so a link survives relabelling a term. This
 * is called once, from the application service, at the moment a term is linked -
 * {@link KognioRdfBoundedContextRepository} no longer performs this lookup itself; it just
 * persists the {@link ResourceId} it is handed. Structurally 1:1 to the requirements adapter's
 * {@code KognioRdfTermLookup}.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) -
 * it never imports RDF4J or any other backend-specific type. The backend
 * ({@link DatasetLifecycle} implementation) is supplied by the composition root, the same shared
 * lifecycle {@link KognioRdfBoundedContextRepository} acquires datasets from.</p>
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
                        + "'. Create it first with term_add before a bounded context names it.");
            }
            if (matches.size() > 1) {
                throw new UnresolvedReferenceException("Term identity '" + termCode
                        + "' is ambiguous in project '" + projectId.value() + "' (" + matches.size()
                        + " matches). Reference a term by its unique dcterms:identifier.");
            }
            return ResourceId.of(matches.get(0).getIRIString());
        }
    }

    /**
     * Reads the reverse direction out of the same glossary graph and with the same predicate
     * {@link #resolveByCode} resolves against, in one query for the whole batch: a
     * {@code VALUES} block over the requested identities rather than one round trip per id.
     * Inlining the identities is safe because {@link ResourceId#of(String)} has already rejected
     * anything that is not IRIREF-safe.
     *
     * <p>An id that names no {@code skos:Concept} in the glossary graph, or one whose concept
     * carries no identifier, simply contributes no entry - this answers a display, and a missing
     * entry is the caller's cue to fall back, not an error. A concept carrying more than one
     * identifier (possible store-first: the shape constrains it, but severity decides) keeps the
     * first binding rather than throwing, for the same reason.</p>
     */
    @Override
    public Map<ResourceId, TermCode> codesById(ProjectId projectId, List<ResourceId> ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        List<ResourceId> distinct = ids.stream().distinct().toList();
        if (distinct.isEmpty()) {
            return Map.of();
        }
        StringBuilder values = new StringBuilder();
        for (ResourceId id : distinct) {
            values.append('<').append(id.value()).append("> ");
        }
        String query = "SELECT ?term ?code WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "VALUES ?term { " + values + "} "
                + "?term a <" + CONCEPT_TYPE + "> ; "
                + "<" + IDENTIFIER_PROPERTY + "> ?code } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Map<ResourceId, TermCode> codes = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> codes.putIfAbsent(
                    ResourceId.of(iriOf(row, "term").getIRIString()),
                    new TermCode(literalOf(row, "code").getLexicalForm())));
            return codes;
        }
    }

    private static IRI iriOf(BindingSet row, String name) {
        return (IRI) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }

    private static Literal literalOf(BindingSet row, String name) {
        return (Literal) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
