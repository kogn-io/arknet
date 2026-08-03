// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.DuplicateConstraintCodeException;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;

/**
 * Out-adapter: {@link ConstraintRepository} backed by the kognio-rdf substrate, alongside
 * {@link KognioRdfRequirementRepository} in the same package (issue #223) - {@link Constraint}
 * lives inside the same requirements bounded context, not a separate one, so it shares this
 * module rather than getting a hexagon of its own.
 *
 * <p>Maps a {@link Constraint} to its opaque {@link ConstraintId} as the subject IRI, stored in
 * one named graph ({@code CONSTRAINTS_GRAPH}, separate from {@code REQUIREMENTS_GRAPH} - a
 * constraint is its own resource, not a field on a requirement): four mandatory triples
 * (identifier, type, title, statement). Unlike {@link KognioRdfRequirementRepository}, there is
 * no compare-and-set update path here at all: a {@link Constraint} is immutable once created in
 * this scope (no {@code constraint_update}/{@code constraint_set_status} tool exists, and the
 * ontology gives it no status field), so {@link #create} is the only write this class performs.
 * It still runs through the shared {@link WriteFunnel} (the very same instance
 * {@link KognioRdfRequirementRepository} uses, wired by the composition root over
 * {@link KognioRdfRequirementRepositoryFactory#buildFunnel}), so a constraint still gets a
 * PROV-O revision and an {@code arkprov:head} recorded - there is simply no second write to guard
 * with a CAS check.</p>
 *
 * <p><strong>{@code arkreq:constraintStatement} stays adapter-local.</strong> Unlike
 * {@code arkreq:usesTerm}/{@code acceptanceCriterion} (shared via {@link ArkreqVocabulary}
 * because {@code arknet-mcp}'s traceability read path also needs them), the constraint statement
 * text is not scanned by {@code orphan_check}'s unlinked-mention check in this scope - so this
 * predicate is declared once, here, rather than added to the shared vocabulary class.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports and {@link SimpleRdf} - it never
 * imports RDF4J or any other backend-specific type; the backend is supplied by the composition
 * root via {@link KognioRdfConstraintRepositoryFactory}.</p>
 */
public class KognioRdfConstraintRepository implements ConstraintRepository {

    private static final String CONSTRAINTS_GRAPH = "https://w3id.org/arknet/model/constraints";

    /**
     * {@code arkreq:constraintStatement} - adapter-local (see class javadoc), not shared via
     * {@link ArkreqVocabulary}.
     */
    private static final String STATEMENT_PROPERTY = "https://w3id.org/arknet/requirements#constraintStatement";

    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String TITLE_PROPERTY = VocabDct.NAMESPACE + "title";
    private static final String TECHNICAL_CONSTRAINT_TYPE = ArkreqVocabulary.TECHNICAL_CONSTRAINT_TYPE;
    private static final String BUSINESS_CONSTRAINT_TYPE = ArkreqVocabulary.BUSINESS_CONSTRAINT_TYPE;
    private static final String REGULATORY_CONSTRAINT_TYPE = ArkreqVocabulary.REGULATORY_CONSTRAINT_TYPE;

    private final DatasetLifecycle lifecycle;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from - used by the
     *                  read paths (must not be {@code null})
     * @param funnel    the shared write funnel (ADR-013) {@link #create} runs through - the very
     *                  same instance {@link KognioRdfRequirementRepository} uses (must not be
     *                  {@code null})
     */
    KognioRdfConstraintRepository(DatasetLifecycle lifecycle, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, Constraint constraint) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(constraint, "constraint");

        // ResourceId#of validates IRIREF-safety at construction, so constraint.id()'s wrapped
        // IRI is already guaranteed safe to embed here - no separate check needed.
        String subjectIriString = constraint.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);

        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(typeIriFor(constraint.type())));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(constraint.code().value()));
        graph.add(subjectIri, rdf.createIRI(TITLE_PROPERTY), rdf.createLiteral(constraint.title()));
        graph.add(subjectIri, rdf.createIRI(STATEMENT_PROPERTY), rdf.createLiteral(constraint.statement()));

        IRI graphIri = rdf.createIRI(CONSTRAINTS_GRAPH);
        funnel.create(new DatasetId(projectId.value()), CONSTRAINTS_GRAPH, subjectIriString,
                constraint.code().value(), graph, null,
                () -> new ResourceAlreadyExistsException(projectId, constraint.id().value()),
                () -> new DuplicateConstraintCodeException(projectId, constraint.code()),
                tx -> tx.add(graphIri, graph));
    }

    /**
     * Builds the WHERE-clause body (inside {@code GRAPH <CONSTRAINTS_GRAPH>}) shared by
     * {@link #findByCode} and {@link #findAll} - the mandatory type join (filtered to the three
     * known constraint types, mirroring {@link KognioRdfRequirementRepository}'s
     * {@code requirementWhereClause}), the mandatory title/statement joins, and the caller-supplied
     * identifier join. Extracted so the two read paths cannot drift apart the way two
     * near-identical read paths in {@link KognioRdfRequirementRepository} already did.
     */
    private static String constraintWhereClause(String identifierClause) {
        return "?s a ?type . "
                + "FILTER(?type = <" + TECHNICAL_CONSTRAINT_TYPE + "> || ?type = <" + BUSINESS_CONSTRAINT_TYPE
                + "> || ?type = <" + REGULATORY_CONSTRAINT_TYPE + ">) "
                + identifierClause
                + "?s <" + TITLE_PROPERTY + "> ?title . "
                + "?s <" + STATEMENT_PROPERTY + "> ?statement . ";
    }

    @Override
    public Optional<Constraint> findByCode(ProjectId projectId, ConstraintCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        String query = "SELECT ?s ?type ?title ?statement WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + constraintWhereClause(
                        "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . ")
                + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query).findFirst().map(row -> constraintOf(row, code));
        }
    }

    @Override
    public List<Constraint> findAll(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?s ?identifier ?type ?title ?statement WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + constraintWhereClause("?s <" + IDENTIFIER_PROPERTY + "> ?identifier . ")
                + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> constraintOf(row, new ConstraintCode(literalOf(row, "identifier").getLexicalForm())))
                    .toList();
        }
    }

    /**
     * Finds every constraint in a project whose identity is among {@code ids}, in one store
     * round-trip - backs {@link ResolveConstraints}. Mirrors
     * {@code KognioRdfRequirementRepository#findByIds}: no type filter, since
     * {@code dcterms:identifier} already scopes the join to subjects that carry a code.
     */
    @Override
    public List<ResolveConstraints.ResolvedConstraint> findByIds(ProjectId projectId, List<ResourceId> ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return List.of();
        }

        String values = ids.stream()
                .map(id -> SparqlTerms.iriRef(id.value()))
                .collect(Collectors.joining(" "));

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + "VALUES ?s { " + values + " } "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Map<String, ResolveConstraints.ResolvedConstraint> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                String subjectIri = iriOf(row, "s").getIRIString();
                bySubject.putIfAbsent(subjectIri, new ResolveConstraints.ResolvedConstraint(
                        ResourceId.of(subjectIri), new ConstraintCode(literalOf(row, "identifier").getLexicalForm())));
            });
            return List.copyOf(bySubject.values());
        }
    }

    /** Builds one {@link Constraint} from a row of {@link #constraintWhereClause}'s projection. */
    private static Constraint constraintOf(BindingSet row, ConstraintCode code) {
        String subjectIriString = iriOf(row, "s").getIRIString();
        return new Constraint(
                new ConstraintId(ResourceId.of(subjectIriString)),
                code,
                literalOf(row, "title").getLexicalForm(),
                literalOf(row, "statement").getLexicalForm(),
                typeFromIri(iriOf(row, "type").getIRIString()));
    }

    private static String typeIriFor(ConstraintType type) {
        return switch (type) {
            case TECHNICAL -> TECHNICAL_CONSTRAINT_TYPE;
            case BUSINESS -> BUSINESS_CONSTRAINT_TYPE;
            case REGULATORY -> REGULATORY_CONSTRAINT_TYPE;
        };
    }

    private static ConstraintType typeFromIri(String iri) {
        if (TECHNICAL_CONSTRAINT_TYPE.equals(iri)) {
            return ConstraintType.TECHNICAL;
        }
        if (BUSINESS_CONSTRAINT_TYPE.equals(iri)) {
            return ConstraintType.BUSINESS;
        }
        if (REGULATORY_CONSTRAINT_TYPE.equals(iri)) {
            return ConstraintType.REGULATORY;
        }
        throw new IllegalStateException("unexpected constraint type " + iri);
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
