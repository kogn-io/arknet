package de.hauschel.arknet.ul.adapter.kogniordf;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Out-adapter: {@link TermRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF dataset).
 *
 * <p>Maps a {@link Term} to a W3C SKOS concept with a fixed subject IRI
 * ({@code https://w3id.org/arknet/model/term/<id>}), stored in one named graph
 * shared by all terms of a workspace. Each term is typed {@code skos:Concept},
 * placed into a per-workspace glossary via {@code skos:inScheme}, and carries
 * {@code skos:prefLabel} (the term) and {@code skos:definition} (its meaning); the
 * running identity is additionally kept as {@code dcterms:identifier}. This choice
 * makes the model a native fit for kognio-rdf (SKOS concepts are its model) and for
 * arknet's own dogfood glossary.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports ({@code terms} +
 * {@code dataset}) and {@link SimpleRdf} - it never imports RDF4J or any other
 * backend-specific type. The backend ({@link DatasetLifecycle} implementation) is
 * supplied by the composition root.</p>
 *
 * <p><strong>WorkspaceId (local, single-user).</strong> Each {@link WorkspaceId} is
 * mapped 1:1 to a kognio-rdf {@link DatasetId}, so distinct workspaces are fully
 * isolated datasets - and thus distinct glossaries. For the MVP there is exactly one
 * {@code skos:ConceptScheme} per workspace ({@link #GLOSSARY_SCHEME}); a per-bounded-context
 * scheme is a later refinement (tracked alongside the requirement-to-term linking).</p>
 *
 * <p><strong>SHACL write-gate.</strong> Every {@link #save(WorkspaceId, Term)} call validates
 * the candidate instance graph against the ubiquitous-language SHACL shapes via
 * {@link ShaclWriteGate} before starting the write transaction (symmetric to the requirements
 * adapter); a violation throws {@link WriteConstraintViolationException} and nothing is
 * persisted. The gate itself is technology-neutral - only
 * {@link KognioRdfTermRepositoryFactory} names RDF4J.</p>
 */
public class KognioRdfTermRepository implements TermRepository {

    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    private static final String TERM_INSTANCE_NAMESPACE = "https://w3id.org/arknet/model/term/";
    private static final String TERMS_GRAPH = "https://w3id.org/arknet/model/ubiquitous-language";
    private static final String GLOSSARY_SCHEME = "https://w3id.org/arknet/model/glossary";

    private static final String CONCEPT_TYPE = SKOS_NAMESPACE + "Concept";
    private static final String CONCEPT_SCHEME_TYPE = SKOS_NAMESPACE + "ConceptScheme";
    private static final String IN_SCHEME_PROPERTY = SKOS_NAMESPACE + "inScheme";
    private static final String PREF_LABEL_PROPERTY = SKOS_NAMESPACE + "prefLabel";
    private static final String DEFINITION_PROPERTY = SKOS_NAMESPACE + "definition";
    private static final String IDENTIFIER_PROPERTY = VocabDct.NAMESPACE + "identifier";

    private final DatasetLifecycle lifecycle;
    private final ShaclWriteGate gate;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle the kognio-rdf dataset lifecycle to acquire datasets from (must not be
     *                  {@code null})
     * @param gate      the SHACL write-gate validating candidate graphs before persistence
     *                  (must not be {@code null})
     */
    KognioRdfTermRepository(DatasetLifecycle lifecycle, ShaclWriteGate gate) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    @Override
    public void save(WorkspaceId workspaceId, Term term) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(term, "term");

        IRI subjectIri = rdf.createIRI(termIri(term.id()));
        IRI schemeIri = rdf.createIRI(GLOSSARY_SCHEME);
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_TYPE));
        graph.add(subjectIri, rdf.createIRI(IN_SCHEME_PROPERTY), schemeIri);
        graph.add(subjectIri, rdf.createIRI(IDENTIFIER_PROPERTY), rdf.createLiteral(term.id().value()));
        graph.add(subjectIri, rdf.createIRI(PREF_LABEL_PROPERTY), rdf.createLiteral(term.prefLabel()));
        graph.add(subjectIri, rdf.createIRI(DEFINITION_PROPERTY), rdf.createLiteral(term.definition()));
        // The per-workspace glossary itself, typed once (idempotent - RDF set semantics).
        graph.add(schemeIri, VocabRdf.TYPE, rdf.createIRI(CONCEPT_SCHEME_TYPE));
        gate.enforce(graph);

        String deleteExisting = "DELETE WHERE { GRAPH <" + TERMS_GRAPH + "> { <"
                + subjectIri.getIRIString() + "> ?p ?o } }";
        IRI graphIri = rdf.createIRI(TERMS_GRAPH);

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(deleteExisting);
                tx.add(graphIri, graph);
                return null;
            });
        }
    }

    @Override
    public Optional<Term> findById(WorkspaceId workspaceId, TermId id) {
        Objects.requireNonNull(workspaceId, "workspaceId");
        Objects.requireNonNull(id, "id");

        String query = "SELECT ?prefLabel ?definition WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "<" + termIri(id) + "> a <" + CONCEPT_TYPE + "> ; "
                + "<" + PREF_LABEL_PROPERTY + "> ?prefLabel ; "
                + "<" + DEFINITION_PROPERTY + "> ?definition . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            return handle.sparqlQuery().select(query)
                    .findFirst()
                    .map(row -> new Term(
                            id,
                            literalOf(row, "prefLabel").getLexicalForm(),
                            literalOf(row, "definition").getLexicalForm()));
        }
    }

    @Override
    public List<Term> findAll(WorkspaceId workspaceId) {
        Objects.requireNonNull(workspaceId, "workspaceId");

        String query = "SELECT ?identifier ?prefLabel ?definition WHERE { GRAPH <" + TERMS_GRAPH + "> { "
                + "?s a <" + CONCEPT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "?s <" + PREF_LABEL_PROPERTY + "> ?prefLabel . "
                + "?s <" + DEFINITION_PROPERTY + "> ?definition . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(workspaceId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> new Term(
                            new TermId(literalOf(row, "identifier").getLexicalForm()),
                            literalOf(row, "prefLabel").getLexicalForm(),
                            literalOf(row, "definition").getLexicalForm()))
                    .toList();
        }
    }

    private static String termIri(TermId id) {
        return TERM_INSTANCE_NAMESPACE + id.value();
    }

    private static Literal literalOf(BindingSet row, String name) {
        return (Literal) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
