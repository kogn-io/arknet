// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.RevisionToken;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextConcurrentlyModifiedException;
import de.hauschel.arknet.bc.domain.BoundedContextDisplayFallback;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.BoundedContextNotFoundException;
import de.hauschel.arknet.bc.domain.DuplicateBoundedContextCodeException;
import de.hauschel.arknet.bc.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.InvalidLanguageTagException;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Out-adapter: {@link BoundedContextRepository} backed by the kognio-rdf substrate
 * ({@code io.kogn.rdf}, embeddable RDF store).
 *
 * <p>Maps a {@link BoundedContext} to its opaque {@link BoundedContextId} as the subject IRI
 * (minted once by a {@link ResourceIdFactory}, never derived from the business code), stored in
 * one named graph shared by all bounded contexts: the type triple ({@code a
 * arkddd:BoundedContext}), the mandatory {@code dcterms:identifier} (the business code
 * {@code BC-1}), one or more language-tagged {@code arknet:name} literals and one or more
 * language-tagged {@code arkddd:domainVision} literals, an optional {@code arkddd:ownedBy}
 * literal, plus zero or more {@code arkddd:ubiquitousLanguageTerm} edges. This class depends only
 * on the neutral kognio-rdf ports ({@code terms} + {@code dataset}) and {@link SimpleRdf} - it
 * never imports RDF4J. The backend ({@link DatasetLifecycle} implementation) is supplied by the
 * composition root.</p>
 *
 * <p><strong>Multilingual {@code name}/{@code domainVision}, mirroring
 * {@code KognioRdfConstraintRepository} (kogn-io/arknet#520).</strong> Both carry one
 * language-tagged literal per language (SHACL {@code sh:uniqueLang}) and are, unlike a
 * constraint's {@code title}/{@code statement}, BOTH mandatory - a subject missing a candidate for
 * either is treated the same as a store-first gap and skipped (see {@link #selectNameVision}).
 * Read as their own follow-up queries ({@link #readNames}/{@link #readDomainVisions}) and selected
 * through {@link DisplayLocale}, never joined into the single-row scalar clause
 * {@link #boundedContextByCodeWhereClause} builds any more - a join would multiply one subject
 * into a row per name/domainVision candidate combination, on top of the row multiplication
 * {@code subdomain}/{@code ownedBy} already cause for a different reason (see the class-level "Row
 * multiplication" note below). {@link #compareAndUpdate} writes exactly one variant of each per
 * call and preserves every other one across its replace-by-identity write (see
 * {@link #replaceExistingTriples}), including the issue #258 sweep of a stale untagged sibling of
 * a default-language write - layered on top of, not instead of, the pre-existing
 * {@code hasAggregate}/blank-node-term preservation and derived-{@code Subdomain}-node
 * follow-delete described below.</p>
 *
 * <p><strong>Subdomain classification is a derived {@code arkddd:Subdomain} resource, not a flat
 * property.</strong> {@link BoundedContext#subdomain()} is only the strategic
 * classification enum ({@link Subdomain#CORE_DOMAIN}/{@link Subdomain#SUPPORTING_DOMAIN}/
 * {@link Subdomain#GENERIC_DOMAIN}), but the DDD ontology models it as
 * {@code BoundedContext arkddd:partOf Subdomain ; Subdomain arkddd:subdomainType
 * arkddd:CoreDomain|SupportingDomain|GenericDomain} - a class-typed node in between, matching
 * {@code arkddd:Domain}/{@code arkddd:Subdomain}'s class-based modelling. When present, this
 * adapter mints that node's opaque IRI afresh on every write via {@link ResourceIdFactory} - the
 * same "derived value object, minted by the adapter, no stable identity of its own" pattern
 * {@code KognioRdfUseCaseRepository} uses for a use case's steps. {@link #replaceExistingTriples}
 * follows the {@code arkddd:partOf} edge to delete the superseded node's triples on update, exactly as
 * the use-case adapter follows {@code mainStep}/{@code extensionStep} - a plain subject-only
 * delete would otherwise leave a fresh, disconnected {@code arkddd:Subdomain} node behind on
 * every update that touches the classification.</p>
 *
 * <p><strong>Create vs. compare-and-set update (opaque identity).</strong> The
 * transactional mechanics - the in-transaction {@code contains} existence checks, the SHACL gate,
 * the commit-conflict translation, and the head comparison - live in the shared
 * {@link WriteFunnel}, not here. {@link #create} rejects an existing subject
 * with {@link ResourceAlreadyExistsException} and a business-code collision (by
 * {@code dcterms:identifier}) with {@link DuplicateBoundedContextCodeException};
 * {@link #compareAndUpdate} rejects a missing subject with
 * {@link BoundedContextNotFoundException}, a stale {@code expectedHead} with
 * {@link BoundedContextConcurrentlyModifiedException}, and - via its own
 * {@link #rejectCodeCollision} check, since {@link WriteFunnel#compareAndUpdate} runs no such
 * check itself - a business-code collision with {@link DuplicateBoundedContextCodeException} too
 * (issue #164), and otherwise replaces the subject's triples wholesale (see
 * {@link #replaceExistingTriples}). There is no unconditional update: every correction to an
 * already-created bounded context goes through the compare-and-set guard, so two concurrent
 * {@code bc_link_term} calls can no longer silently lose one another's edge.</p>
 *
 * <p><strong>The second interleaving.</strong> {@link WriteFunnel#create} translates
 * a lost {@code SERIALIZABLE} write conflict on {@link #create} into the same
 * {@link DuplicateBoundedContextCodeException} its synchronous code check throws - so
 * {@code CodeAssignment}'s retry (see {@code arknet-shared-kernel}) catches both interleavings the
 * same way. {@link WriteFunnel#compareAndUpdate} translates the same commit-time rejection into
 * its {@code headMismatch} signal instead - on that path a lost conflict is not a code collision
 * but a stale read, which the application service's retry loop absorbs exactly like a synchronous
 * head mismatch.</p>
 *
 * <p><strong>Term references arrive pre-resolved.</strong> {@link TermRef}
 * carries the term's opaque subject {@link ResourceId} directly - resolving a human-typed term
 * code (e.g. {@code TERM-1}) against the shared project store, and rejecting an unknown or
 * ambiguous code, is done once by {@link KognioRdfTermLookup} at the moment a term is linked (in
 * the application service), not here on every write. Unlike the requirements adapter's
 * {@code arkreq:usesTerm}, the {@code shapes:BoundedContextShape} places no {@code sh:class}
 * constraint on {@code arkddd:ubiquitousLanguageTerm}, so this adapter needs no validation-only
 * asserted context for it - the plain {@link ShaclWriteGate#enforce(io.kogn.rdf.terms.ReadableGraph)}
 * suffices.</p>
 *
 * <p><strong>SHACL write-gate.</strong> The gate mechanics - validate the candidate instance graph
 * against the DDD SHACL shapes before the write transaction opens, throw
 * {@link WriteConstraintViolationException} on a violation, persist nothing - live in the shared
 * {@link WriteFunnel}. {@code shapes:BoundedContext-hasAggregate} is {@code sh:Warning},
 * not {@code sh:Violation}: a store-first bounded context minted during analysis,
 * before tactical design, has no aggregates yet, and that must not block the write.</p>
 *
 * <p><strong>Row multiplication.</strong> {@code arkddd:partOf}'s
 * {@code sh:maxCount 1} is {@code sh:Warning}-severity only and {@code arkddd:ownedBy} carries no
 * {@code sh:maxCount} at all, so a store-first bounded context with two triples on
 * either predicate legally multiplies a single-subject query's SPARQL rows too, not only
 * {@link #findAll}'s. {@link #findByCode} and {@link #findCurrentByCode} share
 * {@link #boundedContextByCodeWhereClause}, whose two {@code OPTIONAL} joins (subdomain,
 * ownedBy) bind a cross product of rows for one subject exactly as {@link #findAll}'s do -
 * {@link #reduceSubdomainOwnedBy} therefore consumes every row the query returns, not just the
 * first, and picks the first-seen value per field deterministically via the same
 * {@link #firstDistinctValue} helper {@link #findAll} uses, logging a single {@code WARN} when
 * more than one distinct value was collapsed - a plain {@code .findFirst()} on the joined rows
 * would otherwise pick one arbitrary, unlogged (subdomain, ownedBy) combination, and
 * {@link #compareAndUpdate}'s replace-by-identity write would then silently drop every other
 * value on the very next update (issue #158).</p>
 */
public class KognioRdfBoundedContextRepository implements BoundedContextRepository {

    private static final Logger LOG = LoggerFactory.getLogger(KognioRdfBoundedContextRepository.class);

    private static final String ARKNET_NAMESPACE = "https://w3id.org/arknet/core#";
    private static final String BOUNDED_CONTEXT_GRAPH = "https://w3id.org/arknet/model/bounded-context";

    private static final String BOUNDED_CONTEXT_TYPE = ArkdddVocabulary.BOUNDED_CONTEXT_TYPE;
    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String NAME_PROPERTY = ARKNET_NAMESPACE + "name";
    private static final String DOMAIN_VISION_PROPERTY = ArkdddVocabulary.DOMAIN_VISION;
    private static final String PART_OF_PROPERTY = ArkdddVocabulary.PART_OF_PROPERTY;
    private static final String SUBDOMAIN_TYPE_PROPERTY = ArkdddVocabulary.SUBDOMAIN_TYPE_PROPERTY;
    private static final String SUBDOMAIN_CLASS = ArkdddVocabulary.SUBDOMAIN_CLASS;
    private static final String OWNED_BY_PROPERTY = ArkdddVocabulary.OWNED_BY_PROPERTY;
    private static final String UBIQUITOUS_LANGUAGE_TERM_PROPERTY = ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM;
    private static final String HAS_AGGREGATE_PROPERTY = ArkdddVocabulary.HAS_AGGREGATE_PROPERTY;

    private static final String CORE_DOMAIN = ArkdddVocabulary.CORE_DOMAIN;
    private static final String SUPPORTING_DOMAIN = ArkdddVocabulary.SUPPORTING_DOMAIN;
    private static final String GENERIC_DOMAIN = ArkdddVocabulary.GENERIC_DOMAIN;

    private final DatasetLifecycle lifecycle;
    private final ResourceIdFactory resourceIdFactory;
    private final DisplayLocale displayLocale;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle         the kognio-rdf dataset lifecycle to acquire datasets from - read
     *                          paths only, the write path goes through {@code funnel} (must not
     *                          be {@code null})
     * @param resourceIdFactory mints the opaque IRI of the derived {@code arkddd:Subdomain} node
     *                          when a bounded context carries a subdomain classification (must
     *                          not be {@code null}); the bounded context's own identity is minted
     *                          store-neutrally above the store
     * @param displayLocale     the display-language preference selecting which {@code arknet:name}/
     *                          {@code arkddd:domainVision} the read paths surface for a
     *                          multilingual bounded context (must not be {@code null})
     * @param funnel            the shared write funnel running the SHACL gate, dataset
     *                          acquisition and existence/head checks for every
     *                          {@link #create}/{@link #compareAndUpdate}
     *                          (must not be {@code null})
     */
    KognioRdfBoundedContextRepository(
            DatasetLifecycle lifecycle, ResourceIdFactory resourceIdFactory, DisplayLocale displayLocale,
            WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.resourceIdFactory = Objects.requireNonNull(resourceIdFactory, "resourceIdFactory");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, BoundedContext boundedContext, String language) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(boundedContext, "boundedContext");
        String tag = LanguageTag.canonicalize(language);

        // ResourceId#of validates IRIREF-safety at construction, so the wrapped IRI is already
        // guaranteed safe to embed here - no separate check needed.
        String subjectIriString = boundedContext.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        IRI graphIri = rdf.createIRI(BOUNDED_CONTEXT_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, boundedContext, tag, tag);

        funnel.create(new DatasetId(projectId.value()), BOUNDED_CONTEXT_GRAPH, subjectIriString,
                boundedContext.code().value(), graph, null,
                () -> new ResourceAlreadyExistsException(projectId, boundedContext.id().value()),
                () -> new DuplicateBoundedContextCodeException(projectId, boundedContext.code()),
                tx -> writeNewTriples(tx, graphIri, graph));
    }

    /**
     * Compare-and-set update (the guard requirements got): replaces the bounded context's
     * triples only if its {@code arkprov:head} still equals
     * {@code expectedHead} at the moment the shared {@link WriteFunnel} checks it inside the write
     * transaction - closing the lost-update window a plain read (via {@link #findCurrentByCode})
     * followed by an unconditional replace would otherwise leave open between the read and the
     * write.
     *
     * <p><strong>Business-code uniqueness (issue #164).</strong> Unlike {@link #create},
     * {@link WriteFunnel#compareAndUpdate} runs no {@code dcterms:identifier} collision check of
     * its own - a create's subject is brand-new, but a compare-and-set update's subject already
     * exists and, ordinarily, already carries this very code. So the check this method runs
     * itself, via {@link #rejectCodeCollision}, must exclude the subject being updated rather than
     * simply asking whether {@code updated.code()} exists anywhere - the same synchronous
     * {@code dcterms:identifier} check {@link #create} performs, just scoped to "some
     * <em>other</em> subject" instead of "any subject". Rejects with
     * {@link DuplicateBoundedContextCodeException} before {@code replaceExistingTriples} touches
     * any triple, so a rejected code change writes nothing and records no revision - the same
     * atomicity a stale {@code expectedHead} already gets.</p>
     */
    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, BoundedContext updated,
            String nameLanguage, String domainVisionLanguage, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updated, "updated");
        String nameTag = canonicalizeLenient(nameLanguage);
        String domainVisionTag = canonicalizeLenient(domainVisionLanguage);
        String defaultTag = canonicalizeLenient(defaultLanguage);

        String subjectIriString = updated.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(BOUNDED_CONTEXT_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, updated, nameTag, domainVisionTag);

        funnel.compareAndUpdate(new DatasetId(projectId.value()), BOUNDED_CONTEXT_GRAPH, subjectIriString,
                expectedHead == null ? null : expectedHead.value(), graph, null,
                () -> new BoundedContextNotFoundException(projectId, updated.code()),
                () -> new BoundedContextConcurrentlyModifiedException(projectId, updated.code()),
                tx -> {
                    rejectCodeCollision(tx, graphIri, subjectIri, updated.code(), projectId);
                    replaceExistingTriples(tx, graphIri, subjectIri, subject, graph, nameTag, domainVisionTag,
                            defaultTag);
                });
    }

    /**
     * Rejects the write if {@code code} already labels a bounded context other than
     * {@code subjectIri} - {@link #create}'s business-code uniqueness rule, ported to
     * {@link #compareAndUpdate} (issue #164). Two {@link DatasetTx#contains} checks rather than a
     * {@code SELECT}/{@code ASK}, following the same reasoning {@link #create}'s own code check
     * already relies on ({@code DatasetTx#contains}'s javadoc: a pattern-matched {@code contains}
     * is answered from the backend's own pattern lookup and stays conflict-guarded under
     * {@code SERIALIZABLE}, where a query's rewritten terms are not guaranteed to be). Plain
     * {@code contains(graph, null, identifierProperty, code)} alone cannot exclude
     * {@code subjectIri}: at this point in the transaction {@code subjectIri}'s own,
     * not-yet-deleted {@code dcterms:identifier} triple still carries whatever code it had before
     * the update, so an unscoped check would misreport a no-op code change - the only case every
     * caller in this codebase, {@code linkTerm}, currently exercises - as a collision with itself.
     * A collision is exactly "some subject other than {@code subjectIri} has {@code code}": true
     * when any subject has it but {@code subjectIri} does not (yet).
     */
    private void rejectCodeCollision(DatasetTx tx, IRI graphIri, IRI subjectIri, BoundedContextCode code,
            ProjectId projectId) {
        IRI identifierProperty = rdf.createIRI(IDENTIFIER_PROPERTY);
        Literal codeLiteral = rdf.createLiteral(code.value());
        boolean anySubjectHasCode = tx.contains(graphIri, null, identifierProperty, codeLiteral);
        boolean thisSubjectHasCode = tx.contains(graphIri, subjectIri, identifierProperty, codeLiteral);
        if (anySubjectHasCode && !thisSubjectHasCode) {
            throw new DuplicateBoundedContextCodeException(projectId, code);
        }
    }

    /**
     * Builds the candidate graph for one bounded context's triples: type, identifier, name (tagged
     * {@code nameTag}), domainVision (tagged {@code domainVisionTag}), an optional derived
     * {@code arkddd:Subdomain} node (see the class-level note above) and an optional ownedBy
     * literal, and zero or more {@code arkddd:ubiquitousLanguageTerm} edges to the bounded
     * context's already-resolved term references. Shared by {@link #create} and
     * {@link #compareAndUpdate} so both write paths serialise a {@link BoundedContext} identically
     * - never more than one {@code name}/{@code domainVision} each, since preserving every other
     * language variant is {@link #replaceExistingTriples}'s job.
     */
    private Graph buildCandidateGraph(IRI subjectIri, BoundedContext boundedContext, String nameTag,
            String domainVisionTag) {
        List<IRI> termIris = boundedContext.usesTerms().stream()
                .map(this::termIriFor)
                .toList();
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(BOUNDED_CONTEXT_TYPE));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(boundedContext.code().value()));
        graph.add(subjectIri, rdf.createIRI(NAME_PROPERTY), literalOf(boundedContext.name(), nameTag));
        graph.add(subjectIri, rdf.createIRI(DOMAIN_VISION_PROPERTY),
                literalOf(boundedContext.domainVision(), domainVisionTag));
        if (boundedContext.subdomain() != null) {
            IRI subdomainIri = mintSubdomainIri();
            graph.add(subdomainIri, VocabRdf.TYPE, rdf.createIRI(SUBDOMAIN_CLASS));
            graph.add(subdomainIri, rdf.createIRI(SUBDOMAIN_TYPE_PROPERTY),
                    rdf.createIRI(subdomainIriFor(boundedContext.subdomain())));
            graph.add(subjectIri, rdf.createIRI(PART_OF_PROPERTY), subdomainIri);
        }
        if (boundedContext.ownedBy() != null) {
            graph.add(subjectIri, rdf.createIRI(OWNED_BY_PROPERTY), rdf.createLiteral(boundedContext.ownedBy()));
        }
        for (IRI termIri : termIris) {
            graph.add(subjectIri, rdf.createIRI(UBIQUITOUS_LANGUAGE_TERM_PROPERTY), termIri);
        }
        return graph;
    }

    /**
     * Writes {@code graph} for a freshly minted subject inside an already-open write transaction -
     * the tail of {@link #create}, reached once the funnel's own existence check has decided the
     * write may proceed. Unlike {@link #replaceExistingTriples}, there is nothing under this
     * identity yet: no triples to delete, and consequently no {@code arkddd:ubiquitousLanguageTerm},
     * {@code arkddd:hasAggregate} or other-language {@code name}/{@code domainVision} literal that
     * could need preserving (that concern is specific to replacing an already-existing subject's
     * triples - see {@link #replaceExistingTriples}'s javadoc).
     */
    private void writeNewTriples(DatasetTx tx, IRI graphIri, Graph graph) {
        tx.add(graphIri, graph);
    }

    /**
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write
     * transaction - the tail of {@link #compareAndUpdate}, reached once the funnel's own head
     * comparison has decided the write should proceed. It first captures several kinds of state
     * that {@code graph} (built from the {@link BoundedContext} record) never carries the full
     * extent of, and re-attaches all of it after the rewrite - so a replace-by-identity write of a
     * store-first or multilingual bounded context carries it along instead of erasing it:
     *
     * <ul>
     * <li>{@code arkddd:ubiquitousLanguageTerm} edges whose target is not an IRI
     * ({@link #readUsesTerms} can never read those, since {@link ResourceId} cannot represent a
     * blank node) - the same preservation the requirements adapter does for
     * {@code arkreq:usesTerm}.</li>
     * <li><strong>All</strong> {@code arkddd:hasAggregate} edges, regardless of target kind:
     * {@link BoundedContext} has no field for its aggregates at all (the shape is {@code sh:Warning}
     * precisely so a bounded context minted before tactical design has none yet), so unlike
     * {@code ubiquitousLanguageTerm} there is no IRI-typed round-trip through the domain object to
     * fall back on - every edge, IRI or blank node, would otherwise be lost on the very next
     * {@code bc_link_term} call.</li>
     * <li><strong>Every other language-tagged variant of {@code name}/{@code domainVision}</strong>
     * (kogn-io/arknet#520), mirroring {@code KognioRdfConstraintRepository#replaceTriplesForUpdate}
     * exactly, sweep of a stale untagged sibling of a default-language write included.</li>
     * </ul>
     *
     * <p>{@code deleteExisting} also follows the {@code arkddd:partOf} edge, mirroring
     * {@code KognioRdfUseCaseRepository}'s step-following delete: the derived
     * {@code arkddd:Subdomain} node {@link #buildCandidateGraph} mints is reachable only from the
     * subject, so a subject-only delete would leave the superseded node's triples behind as
     * disconnected, ever-accumulating garbage on every update that touches the classification.</p>
     */
    private void replaceExistingTriples(DatasetTx tx, IRI graphIri, IRI subjectIri, String subject, Graph graph,
            String nameTag, String domainVisionTag, String defaultTag) {
        String selectUnjoinableTerms = "SELECT ?term WHERE { "
                + "GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { " + subject + " <"
                + UBIQUITOUS_LANGUAGE_TERM_PROPERTY + "> ?term } FILTER(!isIRI(?term)) }";
        String selectAggregates = "SELECT ?aggregate WHERE { "
                + "GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { " + subject + " <"
                + HAS_AGGREGATE_PROPERTY + "> ?aggregate } }";
        String deleteExisting = "DELETE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { ?s ?p ?o } } WHERE { "
                + "GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "{ " + subject + " ?p ?o . BIND(" + subject + " AS ?s) } UNION "
                + "{ " + subject + " <" + PART_OF_PROPERTY + "> ?s . ?s ?p ?o } } }";

        List<RDFTerm> unjoinableTerms = tx.select(selectUnjoinableTerms).map(row -> termOf(row, "term")).toList();
        List<RDFTerm> aggregates = tx.select(selectAggregates).map(row -> termOf(row, "aggregate")).toList();
        // Captured inside this same transaction, never by a separate read beforehand - that would
        // leave a TOCTOU window the caller's own head comparison deliberately avoids.
        List<Literal> preservedNames = otherLanguageLiterals(tx, subject, NAME_PROPERTY, nameTag, defaultTag);
        List<Literal> preservedDomainVisions =
                otherLanguageLiterals(tx, subject, DOMAIN_VISION_PROPERTY, domainVisionTag, defaultTag);
        tx.update(deleteExisting);
        tx.add(graphIri, graph);
        if (!unjoinableTerms.isEmpty() || !aggregates.isEmpty()
                || !preservedNames.isEmpty() || !preservedDomainVisions.isEmpty()) {
            Graph preservedEdges = rdf.createGraph();
            for (RDFTerm termNode : unjoinableTerms) {
                preservedEdges.add(subjectIri, rdf.createIRI(UBIQUITOUS_LANGUAGE_TERM_PROPERTY), termNode);
            }
            for (RDFTerm aggregateNode : aggregates) {
                preservedEdges.add(subjectIri, rdf.createIRI(HAS_AGGREGATE_PROPERTY), aggregateNode);
            }
            for (Literal name : preservedNames) {
                preservedEdges.add(subjectIri, rdf.createIRI(NAME_PROPERTY), name);
            }
            for (Literal domainVision : preservedDomainVisions) {
                preservedEdges.add(subjectIri, rdf.createIRI(DOMAIN_VISION_PROPERTY), domainVision);
            }
            tx.add(graphIri, preservedEdges);
        }
    }

    /**
     * Reads every existing literal of {@code subject} on {@code predicateIri} whose language tag
     * differs from {@code writtenTag}, inside the live write transaction - mirrors
     * {@code KognioRdfConstraintRepository#otherLanguageLiterals}/
     * {@code KognioRdfRoleRepository#otherLanguageLiterals} exactly, sweep included.
     */
    private List<Literal> otherLanguageLiterals(
            DatasetTx tx, String subject, String predicateIri, String writtenTag, String defaultTag) {
        String query = "SELECT ?o WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
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
     * of throwing - mirrors {@code KognioRdfConstraintRepository#canonicalizeLenient} exactly.
     */
    private static String canonicalizeLenient(String tag) {
        try {
            return LanguageTag.canonicalize(tag);
        } catch (InvalidLanguageTagException e) {
            return null;
        }
    }

    @Override
    public Optional<BoundedContext> findByCode(ProjectId projectId, BoundedContextCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?subdomain ?ownedBy WHERE { GRAPH <"
                + BOUNDED_CONTEXT_GRAPH + "> { "
                + boundedContextByCodeWhereClause(code)
                + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<BindingSet> rows = handle.sparqlQuery().select(query).toList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            return boundedContextOf(rows, code, handle, effective);
        }
    }

    /**
     * Reads a bounded context's current state together with its concurrency token. The rows built
     * from {@link #boundedContextByCodeWhereClause} (possibly row-multiplied on
     * subdomain/ownedBy - see the class-level "Row multiplication" note) plus the head itself come
     * from this method's one query call - one snapshot, which is the load-bearing
     * guarantee, not an ordering of clauses within that query. {@code head} is single-valued
     * (the queryable-head invariant), so every row carries the same value; only the first
     * row is consulted for it. {@link #boundedContextOf} then issues further, independent
     * queries, via {@link #readUsesTerms}/{@link #selectNameVision}, to fill in {@code usesTerms}
     * and the multilingual selection; those later reads are safe precisely because they can only
     * be fresher, never staler, than the head: a concurrent funnel write landing in between moves
     * the head, so {@link BoundedContextRepository#compareAndUpdate} then fails its comparison and
     * the caller re-reads instead of silently overwriting a state it never actually saw. Builds the
     * {@link BoundedContext} the same way {@link #findByCode} does - both call
     * {@link #boundedContextOf} on their rows, so the two read paths cannot drift apart
     * field-by-field.
     */
    @Override
    public Optional<BoundedContextRepository.CurrentBoundedContext> findCurrentByCode(
            ProjectId projectId, BoundedContextCode code, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // The project's own default language decides which variant this read-modify-write round
        // trip sees (issue #456), mirroring KognioRdfConstraintRepository#findCurrentByCode.
        DisplayLocale effective = this.displayLocale.withRequestedOverride(canonicalizeLenient(defaultLanguage));

        String query = "SELECT ?s ?subdomain ?ownedBy ?head WHERE { GRAPH <"
                + BOUNDED_CONTEXT_GRAPH + "> { "
                + boundedContextByCodeWhereClause(code)
                + "} "
                + "OPTIONAL { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?s <" + ArkprovVocabulary.HEAD + "> ?head } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            List<BindingSet> rows = handle.sparqlQuery().select(query).toList();
            if (rows.isEmpty()) {
                return Optional.empty();
            }
            String subjectIriString = iriOf(rows.get(0), "s").getIRIString();
            String subject = SparqlTerms.iriRef(subjectIriString);
            SparqlQuery sparql = handle.sparqlQuery();
            Optional<NameVisionSelection> selection = selectNameVision(sparql::select, subject, effective);
            if (selection.isEmpty()) {
                return Optional.empty();
            }
            NameVisionSelection selected = selection.get();
            SubdomainOwnedBy reduced = reduceSubdomainOwnedBy(rows, subjectIriString);
            BoundedContext boundedContext = new BoundedContext(
                    new BoundedContextId(ResourceId.of(subjectIriString)), code,
                    selected.name().value(), selected.domainVision().value(),
                    reduced.subdomain(), reduced.ownedBy(),
                    readUsesTerms(sparql::select, subject));
            RevisionToken head = rows.get(0).getValue("head")
                    .filter(IRI.class::isInstance)
                    .map(value -> new RevisionToken(((IRI) value).getIRIString()))
                    .orElse(null);
            return Optional.of(new BoundedContextRepository.CurrentBoundedContext(boundedContext, head,
                    selected.name().languageTag(), selected.domainVision().languageTag()));
        }
    }

    /**
     * The WHERE body shared by {@link #findByCode} and {@link #findCurrentByCode}: the mandatory
     * type/identifier joins plus the two optional joins (subdomain, ownedBy) that scope a
     * single-bounded-context read to one {@code code}. {@code name}/{@code domainVision} are read
     * separately, not joined here, since both may carry several language-tagged literals each -
     * see the class-level multilingual note. The subdomain join
     * follows the derived {@code arkddd:Subdomain} node's {@code arkddd:partOf}/
     * {@code arkddd:subdomainType} hop but still projects a single {@code ?subdomain}
     * binding - the {@code arkddd:CoreDomain}/{@code SupportingDomain}/{@code GenericDomain}
     * individual - so {@link #subdomainOf} reads it exactly as it did the old flat property.
     * Extracted because both callers build a {@link BoundedContext} from the same row shape via
     * {@link #boundedContextOf}/{@link #reduceSubdomainOwnedBy} - drift between two
     * near-identical read paths is what row multiplication cost the requirements adapter, so this
     * text lives in one place. The caller supplies
     * the surrounding {@code SELECT}/{@code GRAPH}/{@code WHERE} wrapping and, in
     * {@link #findCurrentByCode}'s case, the additional provenance-graph join.
     *
     * <p>{@code FILTER(isIRI(?s))} because both callers cast {@code ?s} to an {@link IRI} to name
     * the context's identity (kogn-io/arknet#401), and {@code bounded-context-shapes.ttl}
     * constrains no node kind on the subject: a store-first blank-node context used to make the
     * whole call throw a {@link ClassCastException} rather than read as absent. {@link
     * #findAllCodes} carries no such filter, on purpose - see its own javadoc.</p>
     */
    private static String boundedContextByCodeWhereClause(BoundedContextCode code) {
        return "?s a <" + BOUNDED_CONTEXT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . "
                + "FILTER(isIRI(?s)) "
                + "OPTIONAL { ?s <" + PART_OF_PROPERTY + "> ?subdomainNode . "
                + "?subdomainNode <" + SUBDOMAIN_TYPE_PROPERTY + "> ?subdomain } "
                + "OPTIONAL { ?s <" + OWNED_BY_PROPERTY + "> ?ownedBy } ";
    }

    /**
     * Builds one {@link BoundedContext} from every row of {@link #boundedContextByCodeWhereClause}'s
     * projection ({@code ?s ?subdomain ?ownedBy}) for one subject, including the multilingual
     * {@code name}/{@code domainVision} selection (via {@link #selectNameVision}) and the follow-up
     * read {@link #readUsesTerms} (via {@code handle}). {@code subdomain} and {@code ownedBy} are
     * collected across <strong>all</strong> rows and reduced with {@link #reduceSubdomainOwnedBy} -
     * the same row-multiplication guard {@link #findAll} applies - because {@code rows} can
     * legally hold a cross product of subdomain/ownedBy candidates for this one subject (see the
     * class-level "Row multiplication" note). Shared by {@link #findByCode} and
     * {@link #findCurrentByCode} so both single-bounded-context read paths build the aggregate the
     * same way. {@link Optional#empty()} only if this subject carries no valid {@code name}/
     * {@code domainVision} candidate pair at all (unreachable via the MCP tools; both properties
     * carry {@code sh:minCount 1} at {@code sh:Violation}), a store-first gap only.
     */
    private Optional<BoundedContext> boundedContextOf(List<BindingSet> rows, BoundedContextCode code,
            DatasetHandle handle, DisplayLocale locale) {
        BindingSet firstRow = rows.get(0);
        String subjectIriString = iriOf(firstRow, "s").getIRIString();
        String subject = SparqlTerms.iriRef(subjectIriString);
        SparqlQuery sparql = handle.sparqlQuery();
        return selectNameVision(sparql::select, subject, locale).map(selection -> {
            SubdomainOwnedBy reduced = reduceSubdomainOwnedBy(rows, subjectIriString);
            return new BoundedContext(
                    new BoundedContextId(ResourceId.of(subjectIriString)),
                    code,
                    selection.name().value(),
                    selection.domainVision().value(),
                    reduced.subdomain(),
                    reduced.ownedBy(),
                    readUsesTerms(sparql::select, subject));
        });
    }

    /**
     * Reduces {@code subdomain}/{@code ownedBy} candidates across every row of a single-subject
     * read to one deterministic value each (see the class-level "Row multiplication" note). Shared
     * by {@link #boundedContextOf} and {@link #findCurrentByCode}, so the reduction lives in one
     * place rather than twice.
     */
    private SubdomainOwnedBy reduceSubdomainOwnedBy(List<BindingSet> rows, String subjectIriString) {
        List<Subdomain> subdomains = new ArrayList<>();
        List<String> ownedBys = new ArrayList<>();
        for (BindingSet row : rows) {
            Subdomain subdomain = subdomainOf(row);
            if (subdomain != null) {
                subdomains.add(subdomain);
            }
            String ownedBy = ownedByOf(row);
            if (ownedBy != null) {
                ownedBys.add(ownedBy);
            }
        }
        return new SubdomainOwnedBy(
                firstDistinctValue(subdomains, subjectIriString, "subdomain"),
                firstDistinctValue(ownedBys, subjectIriString, "ownedBy"));
    }

    private record SubdomainOwnedBy(Subdomain subdomain, String ownedBy) {
    }

    /**
     * {@inheritDoc}
     *
     * <p><strong>Store-first skip.</strong> A subject missing a {@code name} or
     * {@code domainVision} candidate under {@code displayLocale}'s selection is simply absent from
     * the listing (see {@link #selectNameVision}). Unreachable through {@code bc_add}, whose write
     * gate enforces {@code shapes:BoundedContext-name}/{@code -domainVision} at
     * {@code sh:Violation} severity - but a context written straight into the store can lack
     * either, and dropping just that one context is still preferable to failing the whole listing
     * over it. Its {@code BC-N} stays taken all the same, which is why {@link #findAllCodes}
     * exists and why the code counter reads that instead of this (kogn-io/arknet#360).</p>
     */
    @Override
    public List<BoundedContext> findAll(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?identifier ?subdomain ?ownedBy WHERE { GRAPH <"
                + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s a <" + BOUNDED_CONTEXT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "FILTER(isIRI(?s)) "
                + "OPTIONAL { ?s <" + PART_OF_PROPERTY + "> ?subdomainNode . "
                + "?subdomainNode <" + SUBDOMAIN_TYPE_PROPERTY + "> ?subdomain } "
                + "OPTIONAL { ?s <" + OWNED_BY_PROPERTY + "> ?ownedBy } } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            Map<String, List<TermRef>> termsBySubject = readUsesTermsBySubject(handle);
            Map<String, List<LocalizedLiteral>> namesBySubject = literalsBySubject(sparql, NAME_PROPERTY);
            Map<String, List<LocalizedLiteral>> domainVisionsBySubject =
                    literalsBySubject(sparql, DOMAIN_VISION_PROPERTY);
            // Grouped by subject: subdomain/ownedBy are OPTIONAL joins without an
            // enforced sh:maxCount, so a store-first bounded context with two triples on either
            // predicate binds a cross-product of rows for the same subject. Mapping each row
            // straight to a BoundedContext would surface that subject more than once.
            Map<String, BoundedContextAssembly> bySubject = new LinkedHashMap<>();
            sparql.select(query).forEach(row -> {
                BoundedContextAssembly assembly = assemblyFor(bySubject, row);
                assembly.addSubdomainCandidate(subdomainOf(row));
                assembly.addOwnedByCandidate(ownedByOf(row));
            });
            List<BoundedContext> results = new ArrayList<>();
            bySubject.forEach((subjectIri, assembly) -> {
                Optional<LocalizedLiteral> name = effective.select(namesBySubject.getOrDefault(subjectIri, List.of()));
                if (name.isEmpty()) {
                    return;
                }
                Optional<LocalizedLiteral> domainVision =
                        effective.select(domainVisionsBySubject.getOrDefault(subjectIri, List.of()));
                if (domainVision.isEmpty()) {
                    return;
                }
                results.add(assembly.toBoundedContext(name.get().value(), domainVision.get().value(),
                        termsBySubject.getOrDefault(subjectIri, List.of())));
            });
            return List.copyOf(results);
        }
    }

    /**
     * Companion to {@link #findAll}: not the displayed value, but whether displaying it required
     * falling back past the requested/project-default language tier (kogn-io/arknet#520) - mirrors
     * {@code KognioRdfConstraintRepository#findAllDisplayFallback}/{@code KognioRdfRoleRepository
     * #findAllDisplayFallback} exactly.
     */
    @Override
    public Map<BoundedContextCode, BoundedContextDisplayFallback> findAllDisplayFallback(
            ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s a <" + BOUNDED_CONTEXT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "FILTER(isIRI(?s)) } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            Map<String, List<LocalizedLiteral>> namesBySubject = literalsBySubject(sparql, NAME_PROPERTY);
            Map<String, List<LocalizedLiteral>> domainVisionsBySubject =
                    literalsBySubject(sparql, DOMAIN_VISION_PROPERTY);
            Map<BoundedContextCode, BoundedContextDisplayFallback> result = new LinkedHashMap<>();
            sparql.select(query).forEach(row -> {
                String subject = iriOf(row, "s").getIRIString();
                BoundedContextCode code = new BoundedContextCode(literalOf(row, "identifier").getLexicalForm());
                BoundedContextDisplayFallback fallback = new BoundedContextDisplayFallback(
                        fallbackTag(namesBySubject.getOrDefault(subject, List.of()), effective),
                        fallbackTag(domainVisionsBySubject.getOrDefault(subject, List.of()), effective));
                if (!fallback.isEmpty()) {
                    result.put(code, fallback);
                }
            });
            return result;
        }
    }

    /**
     * {@code null} if the candidate matching {@code displayLocale}'s requested language was shown;
     * otherwise the tag of whatever was shown instead - mirrors
     * {@code KognioRdfConstraintRepository#fallbackTag}/{@code KognioRdfRoleRepository#fallbackTag}
     * exactly.
     */
    private static String fallbackTag(List<LocalizedLiteral> candidates, DisplayLocale displayLocale) {
        if (candidates.isEmpty()) {
            return null;
        }
        LocalizedLiteral selected = displayLocale.select(candidates)
                .orElseThrow(() -> new IllegalStateException("candidates checked non-empty above"));
        String tag = selected.languageTag();
        String requestedLanguage = displayLocale.requested().getLanguage();
        boolean matchesRequested = tag != null
                && Locale.forLanguageTag(tag).getLanguage().equalsIgnoreCase(requestedLanguage);
        return matchesRequested ? null : (tag == null ? "" : tag);
    }

    /**
     * Reads every recorded bounded context's business code straight off {@code dcterms:identifier},
     * joining neither {@code arknet:name} nor {@code arkddd:domainVision} - the two mandatory
     * fields that make {@link #findAll} drop a store-first context, and the whole point of this
     * method (kogn-io/arknet#360, see {@link BoundedContextRepository#findAllCodes}'s own javadoc).
     * Any further predicate joined in here would re-introduce exactly the skip the code counter
     * must not have.
     *
     * <p>Deduplicated, because nothing stops a store-first subject from carrying two
     * {@code dcterms:identifier} triples: {@code shapes:BoundedContextShape} constrains
     * {@code name}, {@code domainVision}, {@code hasAggregate}, {@code partOf} and {@code ownedBy},
     * and no property shape at all for the identifier - so not even a write-time
     * {@code sh:maxCount 1} stands behind it here. {@code nextCode} only wants the highest running
     * number, so which of two duplicates survives makes no difference.</p>
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
    public List<BoundedContextCode> findAllCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?identifier WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s a <" + BOUNDED_CONTEXT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .distinct()
                    .map(BoundedContextCode::new)
                    .toList();
        }
    }

    /**
     * Batch identity-to-code resolution backing the {@code ResolveBoundedContexts} in-port: one
     * {@code VALUES}-bound query, never one per id.
     *
     * <p>Joins only {@code dcterms:identifier} - not {@code name}/{@code domainVision} - so a
     * store-first context that carries an identity and a code but misses one of the
     * otherwise-mandatory fields still resolves, exactly as {@code KognioRdfTermRepository#findByIds}
     * decided for the glossary. Rows are grouped per subject rather than mapped 1:1 (the row
     * multiplication pattern): {@code dcterms:identifier} carries no enforceable {@code sh:maxCount}, so a
     * store-first context with two identifier triples would otherwise report the same identity
     * twice - the very contract violation {@code ResolveBoundedContexts} ("the contexts", not "one
     * row per predicate combination") exists to rule out. No {@code FILTER(isIRI(?s))} is needed
     * here: the subjects come from a {@code VALUES} clause bound to caller-supplied
     * {@link ResourceId}s, which can never denote a blank node.</p>
     */
    @Override
    public List<ResolveBoundedContexts.ResolvedBoundedContext> findByIds(
            ProjectId projectId, List<ResourceId> ids) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(ids, "ids");
        if (ids.isEmpty()) {
            return List.of();
        }

        // ResourceId#of validates IRIREF-safety at construction, so every id here is already
        // guaranteed safe to embed - which is what keeps ResolveBoundedContexts' "never rejects"
        // contract intact.
        String values = ids.stream()
                .map(id -> SparqlTerms.iriRef(id.value()))
                .distinct()
                .collect(Collectors.joining(" "));

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "VALUES ?s { " + values + " } "
                + "?s a <" + BOUNDED_CONTEXT_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            Map<String, ResolveBoundedContexts.ResolvedBoundedContext> bySubject = new LinkedHashMap<>();
            handle.sparqlQuery().select(query).forEach(row -> {
                String subjectIri = iriOf(row, "s").getIRIString();
                // putIfAbsent, not put: the first row wins if a subject has several identifiers.
                bySubject.putIfAbsent(subjectIri, new ResolveBoundedContexts.ResolvedBoundedContext(
                        ResourceId.of(subjectIri),
                        new BoundedContextCode(literalOf(row, "identifier").getLexicalForm())));
            });
            return List.copyOf(bySubject.values());
        }
    }

    private static BoundedContextAssembly assemblyFor(Map<String, BoundedContextAssembly> bySubject, BindingSet row) {
        String subjectIri = iriOf(row, "s").getIRIString();
        return bySubject.computeIfAbsent(subjectIri, iri -> new BoundedContextAssembly(
                new BoundedContextId(ResourceId.of(iri)),
                new BoundedContextCode(literalOf(row, "identifier").getLexicalForm())));
    }

    /**
     * Picks one value of {@code candidates} deterministically (first-seen), logging a single
     * {@code WARN} naming {@code subjectIri}/{@code fieldName} when more than one distinct value
     * was collapsed. The shared row-multiplication guard behind both {@link #findAll} (via
     * {@link BoundedContextAssembly#toBoundedContext}) and {@link #reduceSubdomainOwnedBy} - {@code
     * arkddd:partOf}/{@code arkddd:ownedBy} carry no enforceable {@code sh:maxCount}, so a
     * store-first bounded context can legally bind more than one row for either field
     * (issue #158).
     */
    private static <T> T firstDistinctValue(List<T> candidates, String subjectIri, String fieldName) {
        if (candidates.isEmpty()) {
            return null;
        }
        long distinctCount = candidates.stream().distinct().count();
        if (distinctCount > 1) {
            LOG.warn("BoundedContext {}: field '{}' had {} distinct values, returning the first",
                    subjectIri, fieldName, distinctCount);
        }
        return candidates.get(0);
    }

    /**
     * Mutable per-subject accumulator collecting a bounded context's {@code subdomain} and
     * {@code ownedBy} candidates across rows, then choosing one of each
     * deterministically (first-seen) when the bounded context is finally materialised with its
     * already-selected {@code name}/{@code domainVision}, logging a
     * {@code WARN} if more than one distinct value was collected for a field.
     */
    private static final class BoundedContextAssembly {

        private final BoundedContextId id;
        private final BoundedContextCode code;
        private final List<Subdomain> subdomains = new ArrayList<>();
        private final List<String> ownedBys = new ArrayList<>();

        private BoundedContextAssembly(BoundedContextId id, BoundedContextCode code) {
            this.id = id;
            this.code = code;
        }

        private void addSubdomainCandidate(Subdomain subdomain) {
            if (subdomain != null) {
                subdomains.add(subdomain);
            }
        }

        private void addOwnedByCandidate(String ownedBy) {
            if (ownedBy != null) {
                ownedBys.add(ownedBy);
            }
        }

        private BoundedContext toBoundedContext(String name, String domainVision, List<TermRef> usesTerms) {
            return new BoundedContext(id, code, name, domainVision,
                    firstDistinctValue(subdomains, id.value().value(), "subdomain"),
                    firstDistinctValue(ownedBys, id.value().value(), "ownedBy"), usesTerms);
        }
    }

    // ---- name/domainVision multilingual reading ----------------------------------------

    /**
     * One bounded context's selected {@code name}/{@code domainVision} literal, each carrying the
     * {@link LocalizedLiteral#languageTag()} it was chosen under - mirrors
     * {@code KognioRdfConstraintRepository}'s {@code TitleStatementSelection} exactly: both fields
     * are mandatory (unlike {@code Role}'s optional {@code description}), so
     * {@link #selectNameVision} is empty unless both selections succeed.
     */
    private record NameVisionSelection(LocalizedLiteral name, LocalizedLiteral domainVision) {
    }

    /**
     * Selects the {@code name} and {@code domainVision} candidates via {@code locale} -
     * {@link Optional#empty()} if this subject carries no candidate for either (unreachable via the
     * MCP tools; both {@code shapes:BoundedContext-name}/{@code -domainVision} carry
     * {@code sh:minCount 1} at {@code sh:Violation}), a store-first gap only.
     */
    private Optional<NameVisionSelection> selectNameVision(
            Function<String, Stream<BindingSet>> selectFn, String subject, DisplayLocale locale) {
        Optional<LocalizedLiteral> name = locale.select(readNames(selectFn, subject));
        if (name.isEmpty()) {
            return Optional.empty();
        }
        Optional<LocalizedLiteral> domainVision = locale.select(readDomainVisions(selectFn, subject));
        if (domainVision.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new NameVisionSelection(name.get(), domainVision.get()));
    }

    /** Reads the {@code arknet:name} candidates of one bounded context, tagged for {@link DisplayLocale}. */
    private List<LocalizedLiteral> readNames(Function<String, Stream<BindingSet>> selectFn, String subject) {
        return readLocalizedLiterals(selectFn, subject, NAME_PROPERTY);
    }

    /** {@link #readNames} for {@code arkddd:domainVision}. */
    private List<LocalizedLiteral> readDomainVisions(Function<String, Stream<BindingSet>> selectFn, String subject) {
        return readLocalizedLiterals(selectFn, subject, DOMAIN_VISION_PROPERTY);
    }

    private List<LocalizedLiteral> readLocalizedLiterals(
            Function<String, Stream<BindingSet>> selectFn, String subject, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + subject + " <" + predicateIri + "> ?o } }";
        return selectFn.apply(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /** Bulk variant of {@link #readLocalizedLiterals}: every bounded context's candidates in one query. */
    private Map<String, List<LocalizedLiteral>> literalsBySubject(SparqlQuery query, String predicateIri) {
        String sparql = "SELECT ?s ?o WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s <" + predicateIri + "> ?o . FILTER(isIRI(?s)) } }";
        Map<String, List<LocalizedLiteral>> bySubject = new LinkedHashMap<>();
        query.select(sparql).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(localizedLiteralOf(row, "o")));
        return bySubject;
    }

    /** Builds a language-tagged literal, or a plain untagged one when {@code tag} is {@code null}. */
    private Literal literalOf(String value, String tag) {
        return tag == null ? rdf.createLiteral(value) : rdf.createLiteral(value, tag);
    }

    // ---- ubiquitousLanguageTerm reading ------------------------------------------------

    /**
     * Reads the {@code arkddd:ubiquitousLanguageTerm} edges of one bounded context back as term
     * references. Ordered by target IRI (RDF has no intrinsic statement order and
     * {@link BoundedContext} compares its {@code usesTerms} list positionally). A store-first
     * blank-node target is excluded by {@code FILTER(isIRI(?term))} - it is preserved across an
     * update by {@link #replaceExistingTriples} but cannot be materialised into a {@link TermRef}.
     */
    private List<TermRef> readUsesTerms(Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?term WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + subject + " <" + UBIQUITOUS_LANGUAGE_TERM_PROPERTY + "> ?term } "
                + "FILTER(isIRI(?term)) } ORDER BY ?term";
        return selectFn.apply(query)
                .map(row -> new TermRef(ResourceId.of(iriOf(row, "term").getIRIString())))
                .toList();
    }

    /** Bulk variant of {@link #readUsesTerms}: all bounded contexts' term references in one query. */
    private Map<String, List<TermRef>> readUsesTermsBySubject(DatasetHandle handle) {
        String query = "SELECT ?s ?term WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { "
                + "?s <" + UBIQUITOUS_LANGUAGE_TERM_PROPERTY + "> ?term } "
                + "FILTER(isIRI(?s) && isIRI(?term)) } ORDER BY ?s ?term";
        Map<String, List<TermRef>> bySubject = new LinkedHashMap<>();
        handle.sparqlQuery().select(query).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(new TermRef(ResourceId.of(iriOf(row, "term").getIRIString()))));
        return bySubject;
    }

    /**
     * Converts an already-resolved {@link TermRef} to an {@link IRI} for writing.
     * {@link ResourceId#of(String)} validates IRIREF-safety at construction, so the wrapped IRI
     * is already guaranteed safe here.
     */
    private IRI termIriFor(TermRef term) {
        return rdf.createIRI(term.value().value());
    }

    /**
     * Mints an opaque IRI for the derived {@code arkddd:Subdomain} node from the same kernel
     * scheme as the bounded context root, mirroring
     * {@code KognioRdfUseCaseRepository#mintStepIri}: the node is a value object with no stable
     * identity of its own.
     */
    private IRI mintSubdomainIri() {
        return rdf.createIRI(resourceIdFactory.newId().value());
    }

    // ---- helpers -----------------------------------------------------------------------

    private static String subdomainIriFor(Subdomain subdomain) {
        return switch (subdomain) {
            case CORE_DOMAIN -> CORE_DOMAIN;
            case SUPPORTING_DOMAIN -> SUPPORTING_DOMAIN;
            case GENERIC_DOMAIN -> GENERIC_DOMAIN;
        };
    }

    private static Subdomain subdomainFromIri(String iri) {
        if (CORE_DOMAIN.equals(iri)) {
            return Subdomain.CORE_DOMAIN;
        }
        if (SUPPORTING_DOMAIN.equals(iri)) {
            return Subdomain.SUPPORTING_DOMAIN;
        }
        if (GENERIC_DOMAIN.equals(iri)) {
            return Subdomain.GENERIC_DOMAIN;
        }
        throw new IllegalStateException("unexpected subdomain " + iri);
    }

    private static Subdomain subdomainOf(BindingSet row) {
        return row.getValue("subdomain")
                .map(value -> subdomainFromIri(((IRI) value).getIRIString()))
                .orElse(null);
    }

    private static String ownedByOf(BindingSet row) {
        return row.getValue("ownedBy")
                .map(value -> ((Literal) value).getLexicalForm())
                .orElse(null);
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

    /**
     * Reads a binding as the bare {@link RDFTerm} it is, without narrowing it to {@link IRI} -
     * used where the binding's kind is not known in advance (an
     * {@code arkddd:ubiquitousLanguageTerm} target may legally be a blank node).
     */
    private static RDFTerm termOf(BindingSet row, String name) {
        return row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
