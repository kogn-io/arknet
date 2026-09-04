// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.SparqlQuery;
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

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.InvalidLanguageTagException;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintNotFoundException;
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
 * (identifier, type, title, statement). Both writes run through the shared {@link WriteFunnel}
 * (the very same instance {@link KognioRdfRequirementRepository} uses, wired by the composition
 * root over {@link KognioRdfRequirementRepositoryFactory#buildFunnel}), so every constraint gets a
 * PROV-O revision and an {@code arkprov:head} recorded - and, since issue #313, that head is also
 * the concurrency token {@link #compareAndUpdate} compares against.</p>
 *
 * <p><strong>Multilingual title/statement (issue #313).</strong> {@code dcterms:title} and
 * {@code arkreq:constraintStatement} each carry one language-tagged literal per language
 * (SHACL {@code sh:uniqueLang}), exactly like {@code KognioRdfRequirementRepository}'s own
 * {@code title}/{@code description}. They are therefore read as their own follow-up queries
 * ({@link #readTitles}/{@link #readStatements}) and selected through {@link DisplayLocale}, never
 * joined into the single-row scalar clause {@link #constraintWhereClause} builds - a join would
 * multiply one subject into a row per title/statement combination. {@link #compareAndUpdate}
 * writes exactly one variant of each per call and preserves every other one across its
 * replace-by-identity write (see {@link #replaceTriplesForUpdate}).</p>
 *
 * <p><strong>What a write may not change.</strong> {@link #compareAndUpdate} replaces the
 * subject's triples wholesale, so it does write {@code dcterms:identifier} and the constraint's
 * {@code rdf:type} again - but always with the values the caller read back from this same port.
 * Nothing here re-derives a code or a type from a candidate: reassigning either is out of scope
 * for a constraint (see {@code UpdateConstraint}'s own javadoc), and this adapter simply
 * serialises whatever {@link Constraint} it is handed.</p>
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
    private final DisplayLocale displayLocale;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from - used by the
     *                      read paths (must not be {@code null})
     * @param displayLocale the display-language preference selecting which {@code dcterms:title}/
     *                      {@code arkreq:constraintStatement} the read paths surface for a
     *                      multilingual constraint (issue #313; must not be {@code null})
     * @param funnel        the shared write funnel (ADR-013) both writes run through - the very
     *                      same instance {@link KognioRdfRequirementRepository} uses (must not be
     *                      {@code null})
     */
    KognioRdfConstraintRepository(DatasetLifecycle lifecycle, DisplayLocale displayLocale, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, Constraint constraint, String language) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(constraint, "constraint");
        String tag = LanguageTag.canonicalize(language);

        // ResourceId#of validates IRIREF-safety at construction, so constraint.id()'s wrapped
        // IRI is already guaranteed safe to embed here - no separate check needed.
        String subjectIriString = constraint.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);

        Graph graph = buildCandidateGraph(subjectIri, constraint, tag, tag);
        IRI graphIri = rdf.createIRI(CONSTRAINTS_GRAPH);
        funnel.create(new DatasetId(projectId.value()), CONSTRAINTS_GRAPH, subjectIriString,
                constraint.code().value(), graph, null,
                () -> new ResourceAlreadyExistsException(projectId, constraint.id().value()),
                () -> new DuplicateConstraintCodeException(projectId, constraint.code()),
                tx -> tx.add(graphIri, graph));
    }

    /**
     * Compare-and-set update: replaces the constraint's triples only if its
     * {@code arkprov:head} still equals {@code expectedHead} at the moment the shared
     * {@link WriteFunnel} checks it inside the write transaction - closing the lost-update window
     * a plain read (via {@link #findCurrentByCode}) followed by an unconditional replace would
     * otherwise leave open between the read and the write. Mirrors
     * {@code KognioRdfRequirementRepository#compareAndUpdate}, minus that method's term/constraint
     * edges and derived acceptance-criterion resources: a constraint is a flat subject with two
     * multilingual text fields and nothing else.
     */
    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Constraint updated,
            String titleLanguage, String statementLanguage, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updated, "updated");
        String titleTag = canonicalizeLenient(titleLanguage);
        String statementTag = canonicalizeLenient(statementLanguage);
        String defaultTag = canonicalizeLenient(defaultLanguage);

        String subjectIriString = updated.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);

        Graph graph = buildCandidateGraph(subjectIri, updated, titleTag, statementTag);
        IRI graphIri = rdf.createIRI(CONSTRAINTS_GRAPH);

        funnel.compareAndUpdate(new DatasetId(projectId.value()), CONSTRAINTS_GRAPH, subjectIriString,
                expectedHead == null ? null : expectedHead.value(), graph, null,
                () -> new ConstraintNotFoundException(projectId, updated.code()),
                () -> new ConstraintConcurrentlyModifiedException(projectId, updated.code()),
                tx -> replaceTriplesForUpdate(tx, graphIri, subjectIri, subject, graph, titleTag, statementTag,
                        defaultTag));
    }

    /**
     * Builds the candidate graph for one constraint's four triples (type, identifier, title,
     * statement). Shared by {@link #create} and {@link #compareAndUpdate} so both write paths
     * serialise a {@link Constraint} identically. {@code title}/{@code statement} are written as
     * the language-tagged (or, for a {@code null} tag, plain untagged) literal named by
     * {@code titleTag}/{@code statementTag} - never more than one each, since preserving every
     * other language variant a store-first edit or an earlier {@code constraint_update}
     * may have left is {@link #replaceTriplesForUpdate}'s job, run after this candidate has
     * already passed the gate.
     */
    private Graph buildCandidateGraph(IRI subjectIri, Constraint constraint, String titleTag, String statementTag) {
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(typeIriFor(constraint.type())));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(constraint.code().value()));
        graph.add(subjectIri, rdf.createIRI(TITLE_PROPERTY), literalOf(constraint.title(), titleTag));
        graph.add(subjectIri, rdf.createIRI(STATEMENT_PROPERTY), literalOf(constraint.statement(), statementTag));
        return graph;
    }

    /**
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write
     * transaction - the tail of {@link #compareAndUpdate}, reached once the funnel's own head
     * comparison has decided the write should proceed. ({@link #create} has no such tail: a freshly
     * minted identity has nothing to delete or preserve.)
     *
     * <p><strong>Other language variants are captured before the delete and re-attached
     * after.</strong> {@code title}/{@code statement} may each legally carry several
     * language-tagged literals; {@code graph} (from {@link #buildCandidateGraph}) carries exactly
     * one of each, tagged {@code titleTag}/{@code statementTag}. Every <em>other</em> existing
     * variant is read here, before the unconditional whole-subject delete would otherwise wipe it,
     * and written back afterwards - the same capture/delete/reattach shape
     * {@code KognioRdfRequirementRepository#replaceTriplesForUpdate} uses. A variant already
     * carrying {@code titleTag}/{@code statementTag} is <em>not</em> re-attached: it is exactly
     * the one {@code graph} is about to (re)write, so re-attaching it too would duplicate it and
     * break {@code sh:uniqueLang} on the next write.</p>
     *
     * <p><strong>Sweeping a stale untagged sibling of a default-language write (issue
     * #258).</strong> {@code defaultTag} is the target project's configured default language,
     * canonicalized. When {@code titleTag}/{@code statementTag} equals it, the literal
     * {@code graph} is about to write <em>is</em>, by construction, what an omitted
     * {@code language} argument would have resolved to
     * ({@link LanguageTag#resolveWriteLanguage}) - so an existing <em>untagged</em> literal on
     * that same predicate is no longer a genuine other-language variant to preserve, it is a stale
     * duplicate of the very value now being written under its proper tag. That is precisely the
     * shape of the pre-#313 corpus: every constraint written before this change carries untagged
     * title/statement literals. {@link #otherLanguageLiterals} excludes such a literal from what it
     * preserves in exactly that one case, so it is dropped along with everything else - a lazy,
     * incremental normalisation triggered only by the next write that happens to touch this field,
     * not a batch migration. A write under any other tag leaves an existing untagged literal
     * untouched; {@code defaultTag} being {@code null} (no project default configured) never
     * matches a non-{@code null} written tag, so the sweep is unreachable for a project without
     * one.</p>
     */
    private void replaceTriplesForUpdate(DatasetTx tx, IRI graphIri, IRI subjectIri, String subject, Graph graph,
            String titleTag, String statementTag, String defaultTag) {
        String deleteExisting = "DELETE { GRAPH <" + CONSTRAINTS_GRAPH + "> { " + subject + " ?p ?o } } WHERE { "
                + "GRAPH <" + CONSTRAINTS_GRAPH + "> { " + subject + " ?p ?o } }";

        // Captured inside this same transaction, never by a separate read beforehand - that would
        // leave a TOCTOU window the caller's own head comparison deliberately avoids.
        List<Literal> preservedTitles = otherLanguageLiterals(tx, subject, TITLE_PROPERTY, titleTag, defaultTag);
        List<Literal> preservedStatements =
                otherLanguageLiterals(tx, subject, STATEMENT_PROPERTY, statementTag, defaultTag);
        tx.update(deleteExisting);
        tx.add(graphIri, graph);
        if (!preservedTitles.isEmpty() || !preservedStatements.isEmpty()) {
            // Re-attached only after the gate has already run on `graph` and the rewritten graph is
            // committed - never mixed into `graph` beforehand. Safe precisely because nothing new
            // is introduced: each literal already existed in the store and is carried forward
            // untouched.
            Graph preservedLanguageVariants = rdf.createGraph();
            for (Literal title : preservedTitles) {
                preservedLanguageVariants.add(subjectIri, rdf.createIRI(TITLE_PROPERTY), title);
            }
            for (Literal statement : preservedStatements) {
                preservedLanguageVariants.add(subjectIri, rdf.createIRI(STATEMENT_PROPERTY), statement);
            }
            tx.add(graphIri, preservedLanguageVariants);
        }
    }

    /**
     * Reads every existing literal of {@code subject} on {@code predicateIri} whose language tag
     * differs from {@code writtenTag}, inside the live write transaction, before the
     * whole-subject delete would otherwise wipe them. Mirrors
     * {@code KognioRdfRequirementRepository#otherLanguageLiterals} exactly.
     *
     * @param writtenTag the tag of the literal {@code graph} is about to (re)write for this
     *                   predicate, or {@code null} for untagged - excluded here since it is not
     *                   being preserved, it is being replaced
     * @param defaultTag the target project's configured default language, canonicalized, or
     *                   {@code null} if it has none - when it equals {@code writtenTag}, an
     *                   existing <em>untagged</em> literal is excluded here too (issue #258): see
     *                   {@link #replaceTriplesForUpdate}'s "Sweeping a stale untagged sibling"
     *                   note for why that untagged literal is a stale duplicate, not a genuine
     *                   other-language variant, in exactly that case
     */
    private List<Literal> otherLanguageLiterals(
            DatasetTx tx, String subject, String predicateIri, String writtenTag, String defaultTag) {
        String query = "SELECT ?o WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + subject + " <" + predicateIri + "> ?o } }";
        boolean sweepUntagged = defaultTag != null && defaultTag.equals(writtenTag);
        return tx.select(query)
                .map(row -> literalOf(row, "o"))
                .filter(literal -> {
                    String existingTag = canonicalizeLenient(literal.getLanguageTag().orElse(null));
                    if (sweepUntagged && existingTag == null) {
                        return false;
                    }
                    return !Objects.equals(existingTag, writtenTag);
                })
                .toList();
    }

    /**
     * {@link LanguageTag#canonicalize(String)}, but falls back to {@code null} (untagged) instead
     * of throwing {@link InvalidLanguageTagException} - used where the tag may be a verbatim
     * pass-through of whatever is already sitting in the store ({@code ConstraintService} reads
     * {@code current.titleLanguage()}/{@code current.statementLanguage()} straight off
     * {@link LocalizedLiteral#languageTag()}, the raw tag as read, never re-validated), rather
     * than a freshly caller-supplied value already validated before it reached this adapter (as
     * {@link #create}'s {@code language} is - that call site stays on the strict
     * {@link LanguageTag#canonicalize(String)}). Same fallback rationale as
     * {@code KognioRdfRequirementRepository#canonicalizeLenient}: RDF4J's own literal construction
     * rejects exactly the tags {@link LanguageTag#canonicalize(String)} does, so re-embedding an
     * irreparably malformed raw tag would only crash the gate a moment later; writing the value
     * untagged instead keeps such a constraint editable at the cost of that one field's tag.
     */
    private static String canonicalizeLenient(String tag) {
        try {
            return LanguageTag.canonicalize(tag);
        } catch (InvalidLanguageTagException e) {
            return null;
        }
    }

    /**
     * Builds the WHERE-clause body (inside {@code GRAPH <CONSTRAINTS_GRAPH>}) shared by
     * {@link #findByCode}, {@link #findCurrentByCode} and {@link #findAll} - the mandatory type
     * join (filtered to the three known constraint types, mirroring
     * {@link KognioRdfRequirementRepository}'s {@code requirementWhereClause}) and the
     * caller-supplied identifier join. Extracted so the read paths cannot drift apart the way two
     * near-identical read paths in {@link KognioRdfRequirementRepository} already did.
     *
     * <p><strong>{@code title}/{@code statement} are read separately, not joined here</strong>
     * (issue #313): both may now legally carry several language-tagged literals each, so joining
     * them into this single-row scalar clause would multiply a subject into a row per
     * title/statement candidate combination - see {@link #readTitles}/{@link #readStatements}.</p>
     */
    private static String constraintWhereClause(String identifierClause) {
        return "?s a ?type . "
                + "FILTER(?type = <" + TECHNICAL_CONSTRAINT_TYPE + "> || ?type = <" + BUSINESS_CONSTRAINT_TYPE
                + "> || ?type = <" + REGULATORY_CONSTRAINT_TYPE + ">) "
                + identifierClause;
    }

    @Override
    public Optional<Constraint> findByCode(ProjectId projectId, ConstraintCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?type WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + constraintWhereClause(
                        "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . ")
                + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            return sparql.select(query).findFirst()
                    .flatMap(row -> constraintOf(row, code, sparql::select, effective));
        }
    }

    @Override
    public Optional<CurrentConstraint> findCurrentByCode(ProjectId projectId, ConstraintCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        String query = "SELECT ?s ?type ?head WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + constraintWhereClause(
                        "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . ")
                + "} "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?s <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            Optional<BindingSet> found = sparql.select(query).findFirst();
            if (found.isEmpty()) {
                return Optional.empty();
            }
            BindingSet row = found.get();
            String subjectIriString = iriOf(row, "s").getIRIString();
            String subject = SparqlTerms.iriRef(subjectIriString);
            // No per-call display-language override here: an internal read-modify-write round trip
            // is not a caller-facing read, so this adapter's own configured displayLocale is used -
            // exactly as KognioRdfRequirementRepository#findCurrentByCode does.
            Optional<TitleStatementSelection> selection =
                    selectTitleStatement(sparql::select, subject, displayLocale);
            if (selection.isEmpty()) {
                return Optional.empty();
            }
            TitleStatementSelection selected = selection.get();
            Constraint constraint = new Constraint(
                    new ConstraintId(ResourceId.of(subjectIriString)),
                    code,
                    selected.title().value(),
                    selected.statement().value(),
                    typeFromIri(iriOf(row, "type").getIRIString()));
            RevisionToken head = row.getValue("head")
                    .filter(IRI.class::isInstance)
                    .map(value -> new RevisionToken(((IRI) value).getIRIString()))
                    .orElse(null);
            return Optional.of(new CurrentConstraint(constraint, head,
                    selected.title().languageTag(), selected.statement().languageTag()));
        }
    }

    @Override
    public List<Constraint> findAll(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?identifier ?type WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + constraintWhereClause("?s <" + IDENTIFIER_PROPERTY + "> ?identifier . ")
                + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            // One bulk read per multilingual predicate across every constraint in the project,
            // rather than two follow-up queries per subject - mirrors
            // KognioRdfRequirementRepository#findAll's own readTitlesBySubject/
            // readDescriptionsBySubject.
            Map<String, List<LocalizedLiteral>> titlesBySubject = literalsBySubject(sparql, TITLE_PROPERTY);
            Map<String, List<LocalizedLiteral>> statementsBySubject = literalsBySubject(sparql, STATEMENT_PROPERTY);
            return sparql.select(query)
                    .map(row -> {
                        String subjectIriString = iriOf(row, "s").getIRIString();
                        Optional<LocalizedLiteral> title =
                                effective.select(titlesBySubject.getOrDefault(subjectIriString, List.of()));
                        Optional<LocalizedLiteral> statement =
                                effective.select(statementsBySubject.getOrDefault(subjectIriString, List.of()));
                        if (title.isEmpty() || statement.isEmpty()) {
                            // Both shapes carry sh:minCount 1 at sh:Violation severity, so this is
                            // unreachable via the MCP tools - skip this one store-first
                            // constraint rather than crash the whole listing, mirroring
                            // KognioRdfRequirementRepository#findAll's own skip.
                            return null;
                        }
                        return new Constraint(
                                new ConstraintId(ResourceId.of(subjectIriString)),
                                new ConstraintCode(literalOf(row, "identifier").getLexicalForm()),
                                title.get().value(),
                                statement.get().value(),
                                typeFromIri(iriOf(row, "type").getIRIString()));
                    })
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    /**
     * Joins the type - the very filter {@link #constraintWhereClause} builds, all three constraint
     * types alike - and {@code dcterms:identifier}, and stops there. What {@link #findAll} adds on
     * top of that pair is the title/statement selection whose failure makes it skip a subject, so
     * leaving that selection out is precisely what makes a code survive here that the listing
     * loses (kogn-io/arknet#360, and see {@link ConstraintRepository#findAllCodes}).
     *
     * <p>The graph holds constraints only, but the type join stays anyway: it keeps this read
     * shaped like the listing it backstops, so a future third resource type in this graph does not
     * quietly start feeding foreign codes into the constraint counters.</p>
     *
     * <p>Distinct, for the same reason {@link KognioRdfRequirementRepository#findAllCodes} is:
     * {@code sh:maxCount 1} on the identifier only gates writes, and a doubled code would
     * misreport the project even where it would not misplace the counter.</p>
     *
     * <p><strong>No {@code FILTER(isIRI(?s))}, unlike the read paths around it
     * (kogn-io/arknet#360).</strong> {@code WriteFunnel#create}'s uniqueness check is
     * {@code tx.contains(graph, null, dcterms:identifier, code)} - a wildcard subject, so it sees a
     * blank-node subject holding a code just as well as an IRI one and rejects the write either way.
     * A counter that filtered blank nodes out would therefore be blind to a code the write path
     * still refuses, which is this bug over again through a different skip. Counting one number too
     * many costs a number; counting one too few costs the {@code add}.</p>
     */
    @Override
    public List<ConstraintCode> findAllCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?identifier WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + constraintWhereClause("?s <" + IDENTIFIER_PROPERTY + "> ?identifier . ") + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .distinct()
                    .map(ConstraintCode::new)
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

    /**
     * One constraint's selected {@code dcterms:title}/{@code arkreq:constraintStatement} literal,
     * each carrying the {@link LocalizedLiteral#languageTag()} it was chosen under - {@link
     * #constraintOf} needs only the values, but {@link #findCurrentByCode} also needs the tags, to
     * pass through unchanged into {@link CurrentConstraint#titleLanguage()}/
     * {@link CurrentConstraint#statementLanguage()}.
     */
    private record TitleStatementSelection(LocalizedLiteral title, LocalizedLiteral statement) {
    }

    /**
     * Selects one {@code title}/{@code statement} candidate each via {@code locale}, or
     * {@link Optional#empty()} if this subject carries no literal for either - unreachable via the
     * MCP tools (both shapes carry {@code sh:minCount 1} at {@code sh:Violation} severity), but
     * possible for a store-first constraint.
     */
    private Optional<TitleStatementSelection> selectTitleStatement(
            Function<String, Stream<BindingSet>> selectFn, String subject, DisplayLocale locale) {
        Optional<LocalizedLiteral> title = locale.select(readTitles(selectFn, subject));
        Optional<LocalizedLiteral> statement = locale.select(readStatements(selectFn, subject));
        if (title.isEmpty() || statement.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new TitleStatementSelection(title.get(), statement.get()));
    }

    /** Reads the {@code dcterms:title} candidates of one constraint, tagged for {@link DisplayLocale}. */
    private List<LocalizedLiteral> readTitles(Function<String, Stream<BindingSet>> selectFn, String subject) {
        return readLocalizedLiterals(selectFn, subject, TITLE_PROPERTY);
    }

    /** {@link #readTitles} for {@code arkreq:constraintStatement}. */
    private List<LocalizedLiteral> readStatements(Function<String, Stream<BindingSet>> selectFn, String subject) {
        return readLocalizedLiterals(selectFn, subject, STATEMENT_PROPERTY);
    }

    private List<LocalizedLiteral> readLocalizedLiterals(
            Function<String, Stream<BindingSet>> selectFn, String subject, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + subject + " <" + predicateIri + "> ?o } }";
        return selectFn.apply(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /** Bulk variant of {@link #readLocalizedLiterals}: every constraint's candidates in one query. */
    private Map<String, List<LocalizedLiteral>> literalsBySubject(SparqlQuery query, String predicateIri) {
        String sparql = "SELECT ?s ?o WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { "
                + "?s <" + predicateIri + "> ?o } }";
        Map<String, List<LocalizedLiteral>> bySubject = new LinkedHashMap<>();
        query.select(sparql).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(localizedLiteralOf(row, "o")));
        return bySubject;
    }

    /**
     * Builds one {@link Constraint} from a row of {@link #constraintWhereClause}'s projection plus
     * the two follow-up literal reads, or {@link Optional#empty()} if the subject carries neither a
     * title nor a statement literal (see {@link #selectTitleStatement}).
     */
    private Optional<Constraint> constraintOf(BindingSet row, ConstraintCode code,
            Function<String, Stream<BindingSet>> selectFn, DisplayLocale locale) {
        String subjectIriString = iriOf(row, "s").getIRIString();
        String subject = SparqlTerms.iriRef(subjectIriString);
        return selectTitleStatement(selectFn, subject, locale).map(selection -> new Constraint(
                new ConstraintId(ResourceId.of(subjectIriString)),
                code,
                selection.title().value(),
                selection.statement().value(),
                typeFromIri(iriOf(row, "type").getIRIString())));
    }

    /** Builds a language-tagged literal, or a plain untagged one when {@code tag} is {@code null}. */
    private Literal literalOf(String value, String tag) {
        return tag == null ? rdf.createLiteral(value) : rdf.createLiteral(value, tag);
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

    /** Converts a bound literal into the technology-neutral {@link LocalizedLiteral} projection. */
    private static LocalizedLiteral localizedLiteralOf(BindingSet row, String name) {
        Literal literal = literalOf(row, name);
        return new LocalizedLiteral(literal.getLexicalForm(), literal.getLanguageTag().orElse(null));
    }
}
