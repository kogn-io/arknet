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
 * <p>The write paths just before the funnel switch built the same triples per resource as the
 * versioned one - only the graph and the child IRIs differ (compare the adapters on the commit
 * before the funnel switch). That covers what those paths produce, not every form an older version
 * wrote or a store-first edit left behind; those are frozen as hand-written triples in
 * {@link #legacyOnlyForms()}. The rest is written through this version's services and then rewritten: every resource
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

    /** A requirement written before {@code arkreq:acceptanceCriterion} existed (before 2026-07-17). */
    static final String WITHOUT_CRITERION = LEGACY_CHILD_BASE + "6f0c1d2e-0000-4000-8000-000000000010";
    /** A requirement whose criterion is a literal, as written from 2026-07-17 to 2026-08-05. */
    static final String LITERAL_CRITERION = LEGACY_CHILD_BASE + "6f0c1d2e-0000-4000-8000-000000000011";
    /** A requirement whose {@code usesTerm} object is a literal, a store-first edit the earlier adapter kept. */
    static final String LITERAL_TERM_EDGE = LEGACY_CHILD_BASE + "6f0c1d2e-0000-4000-8000-000000000012";

    /**
     * The record forms an earlier version wrote or kept past its own gate and the current gate
     * rejects, frozen as the triples the earlier requirements adapter stored them - typed resource,
     * untagged {@code dcterms:title}/{@code dcterms:description}, {@code arkreq:status} - and not
     * derived from this version's write paths, which never produce them: FR-10 without any
     * criterion, FR-11 with a literal criterion (both carried past the gate), FR-12 with a proper
     * criterion resource but a literal {@code usesTerm} object (the edge is dropped, the record
     * passes the gate).
     */
    static String legacyOnlyForms() {
        String req = "https://w3id.org/arknet/requirements#";
        String dct = "http://purl.org/dc/terms/";
        String head = " a <" + req + "FunctionalRequirement> ; <" + req + "status> <" + req + "Proposed> ; ";
        return "INSERT DATA { GRAPH <" + BASE + "requirements> { "
                + "<" + WITHOUT_CRITERION + ">" + head + "<" + dct + "identifier> \"FR-10\" ; <" + dct
                + "title> \"Legacy without criterion\" ; <" + dct + "description> \"Written before criteria.\" . "
                + "<" + LITERAL_CRITERION + ">" + head + "<" + dct + "identifier> \"FR-11\" ; <" + dct
                + "title> \"Legacy literal criterion\" ; <" + dct + "description> \"Criterion as a literal.\" ; <"
                + req + "acceptanceCriterion> \"Done when it is shown\" . "
                + "<" + LITERAL_TERM_EDGE + ">" + head + "<" + dct + "identifier> \"FR-12\" ; <" + dct
                + "title> \"Legacy literal term edge\" ; <" + dct + "description> \"Term edge as a literal.\" ; <"
                + req + "usesTerm> \"Kunde\"@de ; <" + req + "acceptanceCriterion> <" + LITERAL_TERM_EDGE + "-ac> . "
                + "<" + LITERAL_TERM_EDGE + "-ac> a <" + req + "AcceptanceCriterion> ; <" + req
                + "position> \"1\"^^<http://www.w3.org/2001/XMLSchema#integer> ; <" + req
                + "criterionText> \"It is linked\"@en . } }";
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
