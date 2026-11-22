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
import java.util.List;
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
    /** A requirement whose criteria are plain untagged literals, as v0.3.8 to v0.6.0 wrote them. */
    static final String LITERAL_CRITERION = LEGACY_CHILD_BASE + "6f0c1d2e-0000-4000-8000-000000000011";
    /** A requirement whose {@code usesTerm} object is a literal, a store-first edit the earlier adapter kept. */
    static final String LITERAL_TERM_EDGE = LEGACY_CHILD_BASE + "6f0c1d2e-0000-4000-8000-000000000012";
    /** A requirement with a language-tagged literal criterion, a store-first edit of the literal form. */
    static final String TAGGED_LITERAL_CRITERION = LEGACY_CHILD_BASE + "6f0c1d2e-0000-4000-8000-000000000013";
    /** A use case as v0.3.8 to v0.7.0 wrote it: {@code arkreq:primaryActor}, no {@code arkreq:primaryRole}. */
    static final String ACTOR_USE_CASE = LEGACY_CHILD_BASE + "6f0c1d2e-0000-4000-8000-000000000020";
    /** The actor {@link #ACTOR_USE_CASE} names as its primary and supporting actor. */
    static final String LEGACY_ACTOR = LEGACY_CHILD_BASE + "6f0c1d2e-0000-4000-8000-000000000021";

    /** The two literal criteria of FR-11, in the lexical order the earlier read path showed them in. */
    static final List<String> LITERAL_CRITERIA = List.of("Approved by the owner", "Done when it is shown");
    /** The literal criterion of FR-13, stored under {@code @de}. */
    static final String TAGGED_CRITERION_TEXT = "Wird angezeigt";

    /**
     * The record forms an earlier version wrote or kept past its own gate and the current gate
     * rejects or the migration has to reshape, frozen as the triples the earlier adapters stored
     * them and not derived from this version's write paths, which never produce them:
     * <ul>
     *   <li>FR-10 without any criterion (carried past the gate);</li>
     *   <li>FR-11 with two untagged literal criteria, as {@code KognioRdfRequirementRepository}
     *       wrote them from v0.3.8 to v0.6.0 ({@code rdf.createLiteral(criterion)}), and FR-13 with
     *       one {@code @de} literal criterion, a store-first edit of that form (both reshaped into
     *       criterion resources);</li>
     *   <li>FR-12 with a proper criterion resource but a literal {@code usesTerm} object (the edge
     *       is dropped, the record passes the gate);</li>
     *   <li>UC9 as {@code KognioRdfUseCaseRepository} wrote it from v0.3.8 to v0.7.0: untagged
     *       title, goal and step text, {@code arkreq:primaryActor} and {@code arkreq:supportingActor}
     *       pointing at an actor of the actor register, no {@code arkreq:primaryRole} (both actor
     *       edges are dropped, the record is carried past the gate), with ACTOR-9 as v0.7.0's actor
     *       register stored it.</li>
     * </ul>
     */
    static String legacyOnlyForms() {
        String req = "https://w3id.org/arknet/requirements#";
        String dct = "http://purl.org/dc/terms/";
        String integer = "^^<http://www.w3.org/2001/XMLSchema#integer>";
        String head = " a <" + req + "FunctionalRequirement> ; <" + req + "status> <" + req + "Proposed> ; ";
        return "INSERT DATA { GRAPH <" + BASE + "requirements> { "
                + "<" + WITHOUT_CRITERION + ">" + head + "<" + dct + "identifier> \"FR-10\" ; <" + dct
                + "title> \"Legacy without criterion\" ; <" + dct + "description> \"Written before criteria.\" . "
                + "<" + LITERAL_CRITERION + ">" + head + "<" + dct + "identifier> \"FR-11\" ; <" + dct
                + "title> \"Legacy literal criterion\" ; <" + dct + "description> \"Criterion as a literal.\" ; <"
                + req + "acceptanceCriterion> \"" + LITERAL_CRITERIA.get(1) + "\" , \"" + LITERAL_CRITERIA.get(0)
                + "\" . "
                + "<" + LITERAL_TERM_EDGE + ">" + head + "<" + dct + "identifier> \"FR-12\" ; <" + dct
                + "title> \"Legacy literal term edge\" ; <" + dct + "description> \"Term edge as a literal.\" ; <"
                + req + "usesTerm> \"Kunde\"@de ; <" + req + "acceptanceCriterion> <" + LITERAL_TERM_EDGE + "-ac> . "
                + "<" + LITERAL_TERM_EDGE + "-ac> a <" + req + "AcceptanceCriterion> ; <" + req
                + "position> \"1\"" + integer + " ; <" + req + "criterionText> \"It is linked\"@en . "
                + "<" + TAGGED_LITERAL_CRITERION + ">" + head + "<" + dct + "identifier> \"FR-13\" ; <" + dct
                + "title> \"Legacy tagged literal criterion\" ; <" + dct + "description> \"Tagged literal.\" ; <"
                + req + "acceptanceCriterion> \"" + TAGGED_CRITERION_TEXT + "\"@de . } "
                + "GRAPH <" + BASE + "use-cases> { "
                + "<" + ACTOR_USE_CASE + "> a <" + req + "UseCase> ; <" + dct + "identifier> \"UC9\" ; <" + dct
                + "title> \"Legacy actor use case\" ; <" + req + "useCaseGoal> \"Clerk files an order\" ; <" + req
                + "primaryActor> <" + LEGACY_ACTOR + "> ; <" + req + "supportingActor> <" + LEGACY_ACTOR + "> ; <"
                + req + "mainStep> <" + ACTOR_USE_CASE + "-s1> . "
                + "<" + ACTOR_USE_CASE + "-s1> a <" + req + "Step> ; <" + req + "position> \"1\"" + integer
                + " ; <" + req + "stepText> \"Clerk files the order\" . } "
                + "GRAPH <" + BASE + "actors> { "
                + "<" + LEGACY_ACTOR + "> a <https://w3id.org/arknet/process#HumanActor> ; <" + dct
                + "identifier> \"ACTOR-9\" ; <https://w3id.org/arknet/core#name> \"Legacy clerk\" . } }";
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
