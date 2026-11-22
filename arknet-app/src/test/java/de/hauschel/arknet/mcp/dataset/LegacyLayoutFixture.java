// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.versioning.VersioningGraphs;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Turns a dataset written by this version back into the storage layout of the earlier one, the
 * input of the migration tests.
 *
 * <p>The earlier write paths built the same triples per resource as the versioned one - only the
 * graph and the child IRIs differ (compare the adapters on the commit before the funnel switch).
 * So the fixture is written through this version's services and then rewritten: every resource
 * moves into the named graph of its component, every child fragment {@code <resource>#<kind>-<id>}
 * becomes an opaque {@code urn:uuid:} IRI of its own, the library's version graphs and the
 * retained codes are dropped, and a provenance graph with one revision and head per resource is
 * added, the way the earlier {@code WriteFunnel} recorded it.</p>
 */
final class LegacyLayoutFixture {

    static final String BASE = "https://w3id.org/arknet/model/";
    static final String PROVENANCE = BASE + "provenance";
    static final String PROV = "http://www.w3.org/ns/prov#";
    static final String ARKPROV = "https://w3id.org/arknet/provenance#";

    /** The base the earlier write paths minted child IRIs under, like any resource IRI. */
    static final String LEGACY_CHILD_BASE = "https://w3id.org/arknet/id/";

    private static final Map<String, String> GRAPH_BY_TYPE = graphByType();

    private final RDF rdf = new SimpleRdf();

    /**
     * Rewrites the content graph of {@code dataset} into the earlier layout.
     *
     * @param lifecycle an unguarded lifecycle over the storage root
     * @param dataset   the project dataset
     * @param untagged  resources whose language tags are dropped, as before multilingual fields
     */
    void legacify(DatasetLifecycle lifecycle, DatasetId dataset, Set<String> untagged) {
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            handle.transactor().inTransaction(tx -> {
                rewrite(tx, untagged);
                return null;
            });
        }
    }

    private void rewrite(DatasetTx tx, Set<String> untagged) {
        IRI content = rdf.createIRI(ContentGraph.GRAPH_IRI);
        ReadableGraph current = tx.snapshot(content);
        Map<String, String> graphOfResource = new HashMap<>();
        current.stream(null, VocabRdf.TYPE, null).forEach(typing -> {
            String graph = GRAPH_BY_TYPE.get(((IRI) typing.getObject()).getIRIString());
            String subject = ((IRI) typing.getSubject()).getIRIString();
            if (graph != null && !subject.contains("#")) {
                graphOfResource.put(subject, graph);
            }
        });
        Map<String, IRI> legacyChild = new HashMap<>();
        Map<String, Graph> graphs = new LinkedHashMap<>();
        current.stream().forEach(triple -> {
            String subject = ((IRI) triple.getSubject()).getIRIString();
            String owner = subject.contains("#") ? subject.substring(0, subject.indexOf('#')) : subject;
            String graph = graphOfResource.get(owner);
            RDFTerm object = triple.getObject();
            if (untagged.contains(owner) && object instanceof Literal literal && literal.getLanguageTag().isPresent()) {
                object = rdf.createLiteral(literal.getLexicalForm());
            }
            graphs.computeIfAbsent(graph, key -> rdf.createGraph())
                    .add(child(triple.getSubject(), legacyChild, graphOfResource), triple.getPredicate(),
                            child(object, legacyChild, graphOfResource));
        });
        for (IRI graph : VersioningGraphs.ALL) {
            tx.clear(graph);
        }
        tx.clear(content);
        tx.clear(rdf.createIRI(VersionedWriteFunnel.RETAINED_CODES_GRAPH));
        graphs.forEach((graph, triples) -> tx.add(rdf.createIRI(graph), triples));
        Graph provenance = rdf.createGraph();
        graphOfResource.keySet().forEach(resource -> {
            IRI revision = rdf.createIRI("https://w3id.org/arknet/revision/" + UUID.randomUUID());
            provenance.add(revision, VocabRdf.TYPE, rdf.createIRI(ARKPROV + "Revision"));
            provenance.add(revision, rdf.createIRI(PROV + "specializationOf"), rdf.createIRI(resource));
            provenance.add(revision, rdf.createIRI(PROV + "generatedAtTime"),
                    rdf.createLiteral("2026-10-01T10:00:00Z", rdf.createIRI("http://www.w3.org/2001/XMLSchema#dateTime")));
            provenance.add(rdf.createIRI(resource), rdf.createIRI(ARKPROV + "head"), revision);
        });
        tx.add(rdf.createIRI(PROVENANCE), provenance);
    }

    /** Records, in the earlier funnel's form, that a resource with {@code code} was deleted. */
    static String deletedRevision(String code) {
        String revision = "https://w3id.org/arknet/revision/" + UUID.randomUUID();
        return "INSERT DATA { GRAPH <" + PROVENANCE + "> { <" + revision + "> a <" + ARKPROV + "Revision> ; <"
                + PROV + "specializationOf> <urn:uuid:" + UUID.randomUUID() + "> ; <" + PROV
                + "invalidatedAtTime> \"2026-10-02T10:00:00Z\"^^<http://www.w3.org/2001/XMLSchema#dateTime> ; <"
                + "http://purl.org/dc/terms/identifier> \"" + code + "\" } }";
    }

    private <T extends RDFTerm> T child(T term, Map<String, IRI> legacyChild, Map<String, String> resources) {
        if (term instanceof IRI iri && iri.getIRIString().contains("#")
                && resources.containsKey(iri.getIRIString().substring(0, iri.getIRIString().indexOf('#')))) {
            @SuppressWarnings("unchecked")
            T renamed = (T) legacyChild.computeIfAbsent(iri.getIRIString(),
                    key -> rdf.createIRI(LEGACY_CHILD_BASE + UUID.randomUUID()));
            return renamed;
        }
        return term;
    }

    private BlankNodeOrIRI child(BlankNodeOrIRI term, Map<String, IRI> legacyChild, Map<String, String> resources) {
        return child((RDFTerm) term, legacyChild, resources) instanceof BlankNodeOrIRI renamed ? renamed : term;
    }

    private static Map<String, String> graphByType() {
        Map<String, String> graphs = new HashMap<>();
        graphs.put(ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE, BASE + "requirements");
        graphs.put(ArkreqVocabulary.NON_FUNCTIONAL_REQUIREMENT_TYPE, BASE + "requirements");
        graphs.put(ArkreqVocabulary.TECHNICAL_CONSTRAINT_TYPE, BASE + "constraints");
        graphs.put(ArkreqVocabulary.BUSINESS_CONSTRAINT_TYPE, BASE + "constraints");
        graphs.put(ArkreqVocabulary.REGULATORY_CONSTRAINT_TYPE, BASE + "constraints");
        graphs.put(ArkreqVocabulary.USE_CASE_TYPE, BASE + "use-cases");
        graphs.put(ArkreqVocabulary.CONCEPT_TYPE, BASE + "ubiquitous-language");
        graphs.put(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE, BASE + "bounded-context");
        graphs.put(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE, BASE + "bounded-context");
        graphs.put(ArkarchVocabulary.ADR_TYPE, BASE + "adr");
        graphs.put(ArkprocVocabulary.HUMAN_ACTOR_TYPE, BASE + "actors");
        graphs.put(ArkprocVocabulary.SYSTEM_ACTOR_TYPE, BASE + "actors");
        graphs.put(ArkprocVocabulary.LEGAL_ACTOR_TYPE, BASE + "actors");
        graphs.put(ArkprocVocabulary.GROUP_ACTOR_TYPE, BASE + "actors");
        graphs.put(ArkprocVocabulary.ROLE_TYPE, BASE + "roles");
        return graphs;
    }

}
