// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.kogniordf;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
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

import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.DuplicateRoleCodeException;
import de.hauschel.arknet.actor.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.RoleDisplayFallback;
import de.hauschel.arknet.actor.domain.RoleId;
import de.hauschel.arknet.actor.domain.RoleNotFoundException;
import de.hauschel.arknet.actor.domain.RoleReferencedException;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.InvalidLanguageTagException;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.LocalizedLiteral;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Out-adapter: {@link RoleRepository} backed by the kognio-rdf substrate ({@code io.kogn.rdf},
 * embeddable RDF store) - the second resource type of the actor hexagon (ADR-37/
 * kogn-io/arknet#405), sitting alongside {@link KognioRdfActorRepository} in the same package and
 * sharing its {@link ShaclWriteGate}/{@link WriteFunnel} (see
 * {@link KognioRdfRoleRepositoryFactory}): both resource types' shapes and axioms already live in
 * {@code actor-shapes.ttl}/{@code arknet-actor.ttl}. Its own named graph, though - {@link
 * #ROLE_GRAPH} ({@code .../model/roles}) is distinct from {@link KognioRdfActorRepository}'s
 * {@code .../model/actors}, since a shared gate (file-based: shapes + axioms) does not require a
 * shared graph (a {@link WriteFunnel} call is parameterised by graph on every invocation).
 *
 * <p>Maps a {@link Role} to its opaque {@link RoleId} as the subject IRI: {@code arkproc:Role},
 * the mandatory {@code dcterms:identifier} (the business code {@code ROLE-1}), one or more
 * language-tagged {@code arknet:name} literals, zero or more language-tagged
 * {@code arknet:description} literals, and zero or more {@code arkproc:filledBy} edges to whichever
 * actors occupy the role.</p>
 *
 * <p><strong>Multilingual {@code name}/{@code description}, mirroring
 * {@code KognioRdfConstraintRepository} - not {@link KognioRdfActorRepository}.</strong> Both carry
 * one language-tagged literal per language (SHACL {@code sh:uniqueLang}), read as their own
 * follow-up queries ({@link #readNames}/{@link #readDescriptions}) and selected through
 * {@link DisplayLocale}, never joined into the single-row scalar clause {@link #roleWhereClause}
 * builds - a join would multiply one subject into a row per name/description candidate
 * combination. {@link #compareAndUpdate} writes exactly one variant of each per call and preserves
 * every other one across its replace-by-identity write (see {@link #replaceTriplesForUpdate}),
 * including the issue #258 sweep of a stale untagged sibling of a default-language write. See
 * {@link Role}'s own javadoc for why this hexagon's two resource types disagree on tagging.</p>
 *
 * <p><strong>{@code arkproc:filledBy} is validated by {@code sh:nodeKind sh:IRI}, not
 * {@code sh:class arkproc:Actor}.</strong> The obvious mirror of
 * {@code KognioRdfRequirementRepository}'s {@code usesTerm}/{@code sh:class skos:Concept} pattern
 * - asserting an occupant's type as validation-only context - does not work here: this gate reasons
 * over {@code arknet-actor.ttl}'s {@code rdfs:subClassOf} chain so {@code actshapes:ActorShape} can
 * fire through it (see {@link KognioRdfActorRepositoryFactory#buildGate}), and that very shape
 * targets {@link ArkprocVocabulary#ACTOR_TYPE}. Asserting an occupant's type would pull it under
 * {@code ActorShape}'s own {@code sh:minCount 1} on {@code arknet:name} too - a node this write
 * never carries a name for, since only its opaque identity is known here. {@code actshapes:
 * Role-filledBy} therefore only demands an IRI; whether the target actually <em>is</em> an actor is
 * {@code RoleService}'s job (it resolves every {@code filledByActorCodes} entry against
 * {@code ActorRepository} and rejects an unknown one before a {@link Role} is ever built), not this
 * shape's - see {@code actor-shapes.ttl}'s own comment on {@code actshapes:Role-filledBy} for the
 * full reasoning. {@code filledBy} itself is genuinely multi-valued by design (not a
 * row-multiplication artefact), so it is read via its own separate per-subject query
 * ({@link #readFilledBy}), independent of the multilingual name/description reads.</p>
 *
 * <p>This class depends only on the neutral kognio-rdf ports and {@link SimpleRdf} - it never
 * imports RDF4J or any other backend-specific type; the backend is supplied by the composition
 * root via {@link KognioRdfRoleRepositoryFactory}.</p>
 */
public class KognioRdfRoleRepository implements RoleRepository {

    private static final String ARKNET_NAMESPACE = "https://w3id.org/arknet/core#";
    private static final String ROLE_GRAPH = "https://w3id.org/arknet/model/roles";

    private static final String ROLE_TYPE = ArkprocVocabulary.ROLE_TYPE;
    private static final String FILLED_BY_PROPERTY = ArkprocVocabulary.FILLED_BY;

    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();
    private static final String NAME_PROPERTY = ARKNET_NAMESPACE + "name";
    private static final String DESCRIPTION_PROPERTY = ARKNET_NAMESPACE + "description";

    /**
     * The prefix every code this resource type mints carries - own counter, unrelated to
     * {@code ACTOR-}, see {@link RoleCode}'s own javadoc.
     */
    private static final String CODE_PREFIX = "ROLE-";

    /**
     * The predicates that, if found pointing at a role, block its deletion - empty today,
     * deliberately: see {@link RoleReferencedException}'s own javadoc for why this map exists
     * ahead of a real entry, and {@code ReferenceGuardsCoverEveryOntologyEdgeTest#
     * everyPropertyRangingOverARoleBlocksTheRolesDeletion} in {@code arknet-architecture-tests}
     * for the guard that keeps it honest once a property ranging over {@code arkproc:Role} ships.
     */
    private static final Map<String, String> REFERENCING_PREDICATES = Map.of();

    private final DatasetLifecycle lifecycle;
    private final DisplayLocale displayLocale;
    private final WriteFunnel funnel;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the adapter.
     *
     * @param lifecycle     the kognio-rdf dataset lifecycle to acquire datasets from - read paths
     *                      only, the write path goes through {@code funnel} (must not be
     *                      {@code null})
     * @param displayLocale the display-language preference selecting which {@code arknet:name}/
     *                      {@code arknet:description} the read paths surface for a multilingual
     *                      role (must not be {@code null})
     * @param funnel        the shared write funnel - the same instance
     *                      {@link KognioRdfActorRepository} writes through (must not be
     *                      {@code null})
     */
    KognioRdfRoleRepository(DatasetLifecycle lifecycle, DisplayLocale displayLocale, WriteFunnel funnel) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.displayLocale = Objects.requireNonNull(displayLocale, "displayLocale");
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void create(ProjectId projectId, Role role, String language) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(role, "role");
        String tag = LanguageTag.canonicalize(language);

        String subjectIriString = role.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        IRI graphIri = rdf.createIRI(ROLE_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, role, tag, tag);

        funnel.create(new DatasetId(projectId.value()), ROLE_GRAPH, subjectIriString,
                role.code().value(), graph, null,
                () -> new ResourceAlreadyExistsException(projectId, role.id().value()),
                () -> new DuplicateRoleCodeException(projectId, role.code()),
                tx -> tx.add(graphIri, graph));
    }

    /**
     * Compare-and-set update, mirroring {@code KognioRdfConstraintRepository#compareAndUpdate}
     * exactly for the multilingual mechanics, and {@link KognioRdfActorRepository#compareAndUpdate}
     * for its {@link #rejectCodeCollision} check.
     */
    @Override
    public void compareAndUpdate(ProjectId projectId, RevisionToken expectedHead, Role updated,
            String nameLanguage, String descriptionLanguage, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(updated, "updated");
        String nameTag = canonicalizeLenient(nameLanguage);
        String descriptionTag = canonicalizeLenient(descriptionLanguage);
        String defaultTag = canonicalizeLenient(defaultLanguage);

        String subjectIriString = updated.id().value().value();
        IRI subjectIri = rdf.createIRI(subjectIriString);
        String subject = SparqlTerms.iriRef(subjectIriString);
        IRI graphIri = rdf.createIRI(ROLE_GRAPH);
        Graph graph = buildCandidateGraph(subjectIri, updated, nameTag, descriptionTag);

        funnel.compareAndUpdate(new DatasetId(projectId.value()), ROLE_GRAPH, subjectIriString,
                expectedHead == null ? null : expectedHead.value(), graph, null,
                () -> new RoleNotFoundException(projectId, updated.code()),
                () -> new RoleConcurrentlyModifiedException(projectId, updated.code()),
                tx -> {
                    rejectCodeCollision(tx, graphIri, subjectIri, updated.code(), projectId);
                    replaceTriplesForUpdate(tx, graphIri, subjectIri, subject, graph, nameTag, descriptionTag,
                            defaultTag);
                });
    }

    /** Mirrors {@link KognioRdfActorRepository#rejectCodeCollision} exactly, for {@link RoleCode}. */
    private void rejectCodeCollision(DatasetTx tx, IRI graphIri, IRI subjectIri, RoleCode code,
            ProjectId projectId) {
        IRI identifierProperty = rdf.createIRI(IDENTIFIER_PROPERTY);
        Literal codeLiteral = rdf.createLiteral(code.value());
        boolean anySubjectHasCode = tx.contains(graphIri, null, identifierProperty, codeLiteral);
        boolean thisSubjectHasCode = tx.contains(graphIri, subjectIri, identifierProperty, codeLiteral);
        if (anySubjectHasCode && !thisSubjectHasCode) {
            throw new DuplicateRoleCodeException(projectId, code);
        }
    }

    /**
     * Builds the candidate graph for one role's triples: the type, the identifier, the name (tagged
     * {@code nameTag}), the optional description (tagged {@code descriptionTag}) and every
     * {@code arkproc:filledBy} edge. Shared by {@link #create} and {@link #compareAndUpdate} -
     * never more than one {@code name}/{@code description} each, since preserving every other
     * language variant is {@link #replaceTriplesForUpdate}'s job.
     */
    private Graph buildCandidateGraph(IRI subjectIri, Role role, String nameTag, String descriptionTag) {
        Graph graph = rdf.createGraph();
        graph.add(subjectIri, VocabRdf.TYPE, rdf.createIRI(ROLE_TYPE));
        graph.add(subjectIri, VocabDct.IDENTIFIER, rdf.createLiteral(role.code().value()));
        graph.add(subjectIri, rdf.createIRI(NAME_PROPERTY), literalOf(role.name(), nameTag));
        if (role.description() != null) {
            graph.add(subjectIri, rdf.createIRI(DESCRIPTION_PROPERTY), literalOf(role.description(), descriptionTag));
        }
        for (ActorId occupant : role.filledBy()) {
            graph.add(subjectIri, rdf.createIRI(FILLED_BY_PROPERTY), rdf.createIRI(occupant.value().value()));
        }
        return graph;
    }

    /**
     * Replaces {@code subject}'s triples with {@code graph} inside an already-open write
     * transaction - mirrors {@code KognioRdfConstraintRepository#replaceTriplesForUpdate} exactly:
     * every <em>other</em> language variant of {@code name}/{@code description} is captured before
     * the unconditional whole-subject delete and re-attached afterwards, including the issue #258
     * sweep of a stale untagged sibling of a default-language write. {@code filledBy} needs no such
     * preservation - it carries no language tag, so {@code graph} already holds every occupant
     * {@code updated} was built with.
     */
    private void replaceTriplesForUpdate(DatasetTx tx, IRI graphIri, IRI subjectIri, String subject, Graph graph,
            String nameTag, String descriptionTag, String defaultTag) {
        String deleteExisting = "DELETE { GRAPH <" + ROLE_GRAPH + "> { " + subject + " ?p ?o } } WHERE { "
                + "GRAPH <" + ROLE_GRAPH + "> { " + subject + " ?p ?o } }";

        // Captured inside this same transaction, never by a separate read beforehand - that would
        // leave a TOCTOU window the caller's own head comparison deliberately avoids.
        List<Literal> preservedNames = otherLanguageLiterals(tx, subject, NAME_PROPERTY, nameTag, defaultTag);
        List<Literal> preservedDescriptions =
                otherLanguageLiterals(tx, subject, DESCRIPTION_PROPERTY, descriptionTag, defaultTag);
        tx.update(deleteExisting);
        tx.add(graphIri, graph);
        if (!preservedNames.isEmpty() || !preservedDescriptions.isEmpty()) {
            Graph preservedLanguageVariants = rdf.createGraph();
            for (Literal name : preservedNames) {
                preservedLanguageVariants.add(subjectIri, rdf.createIRI(NAME_PROPERTY), name);
            }
            for (Literal description : preservedDescriptions) {
                preservedLanguageVariants.add(subjectIri, rdf.createIRI(DESCRIPTION_PROPERTY), description);
            }
            tx.add(graphIri, preservedLanguageVariants);
        }
    }

    /**
     * Reads every existing literal of {@code subject} on {@code predicateIri} whose language tag
     * differs from {@code writtenTag}, inside the live write transaction - mirrors
     * {@code KognioRdfConstraintRepository#otherLanguageLiterals} exactly, sweep included.
     */
    private List<Literal> otherLanguageLiterals(
            DatasetTx tx, String subject, String predicateIri, String writtenTag, String defaultTag) {
        String query = "SELECT ?o WHERE { GRAPH <" + ROLE_GRAPH + "> { "
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

    /**
     * Deletes the role identified by {@code code}, mirroring
     * {@link KognioRdfActorRepository#delete} exactly.
     */
    @Override
    public void delete(ProjectId projectId, RoleCode code) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");

        DatasetId dataset = new DatasetId(projectId.value());
        String subjectIriString;
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            String query = "SELECT ?s WHERE { GRAPH <" + ROLE_GRAPH + "> { "
                    + roleByCodeWhereClause(code) + "} }";
            subjectIriString = handle.sparqlQuery().select(query).findFirst()
                    .map(row -> iriOf(row, "s").getIRIString())
                    .orElseThrow(() -> new RoleNotFoundException(projectId, code));
        }
        String subject = SparqlTerms.iriRef(subjectIriString);

        funnel.delete(dataset, ROLE_GRAPH, subjectIriString, code.value(),
                () -> new RoleNotFoundException(projectId, code),
                tx -> {
                    rejectIfReferenced(tx, subjectIriString, projectId, code);
                    tx.update("DELETE WHERE { GRAPH <" + ROLE_GRAPH + "> { " + subject + " ?p ?o } }");
                });
    }

    @Override
    public List<RoleCode> findRetainedCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        return funnel.findRetainedCodes(new DatasetId(projectId.value()), CODE_PREFIX).stream()
                .map(RoleCode::new)
                .toList();
    }

    /**
     * Rejects the delete, without touching a single triple, if anything in the project still
     * references {@code subjectIri} via one of {@link #REFERENCING_PREDICATES} - mirrors
     * {@link KognioRdfActorRepository#rejectIfReferenced} exactly. A no-op today, since that map is
     * empty (see its own javadoc).
     */
    private void rejectIfReferenced(DatasetTx tx, String subjectIri, ProjectId projectId, RoleCode code) {
        IRI target = rdf.createIRI(subjectIri);
        List<String> referencing = new ArrayList<>();
        REFERENCING_PREDICATES.forEach((predicateIri, shorthand) -> {
            String query = "ASK { GRAPH ?g { ?s <" + predicateIri + "> ?target } }";
            if (tx.ask(query, Map.of("target", target))) {
                referencing.add(shorthand);
            }
        });
        if (!referencing.isEmpty()) {
            throw new RoleReferencedException(projectId, code, referencing);
        }
    }

    /**
     * The WHERE body shared by {@link #findByCode}/{@link #findCurrentByCode}/{@link #delete} -
     * the mandatory type and identifier joins. {@code name}/{@code description} are read
     * separately, not joined here, since both may carry several language-tagged literals each - see
     * the class-level multilingual note.
     */
    private static String roleByCodeWhereClause(RoleCode code) {
        return "?s a <" + ROLE_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code.value()) + "\" . "
                + "FILTER(isIRI(?s)) ";
    }

    @Override
    public Optional<Role> findByCode(ProjectId projectId, RoleCode code, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s WHERE { GRAPH <" + ROLE_GRAPH + "> { " + roleByCodeWhereClause(code) + "} }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            return sparql.select(query).findFirst()
                    .flatMap(row -> roleOf(row, code, sparql::select, effective));
        }
    }

    @Override
    public Optional<CurrentRole> findCurrentByCode(ProjectId projectId, RoleCode code, String defaultLanguage) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(code, "code");
        // The project's own default language decides which variant this read-modify-write round
        // trip sees (issue #456), mirroring KognioRdfConstraintRepository#findCurrentByCode.
        DisplayLocale effective = this.displayLocale.withRequestedOverride(canonicalizeLenient(defaultLanguage));

        String query = "SELECT ?s ?head WHERE { GRAPH <" + ROLE_GRAPH + "> { " + roleByCodeWhereClause(code) + "} "
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
            Optional<NameDescriptionSelection> selection = selectNameDescription(sparql::select, subject, effective);
            if (selection.isEmpty()) {
                return Optional.empty();
            }
            NameDescriptionSelection selected = selection.get();
            Role role = new Role(new RoleId(ResourceId.of(subjectIriString)), code,
                    selected.name().value(),
                    selected.description() == null ? null : selected.description().value(),
                    readFilledBy(sparql::select, subject));
            RevisionToken head = row.getValue("head")
                    .filter(IRI.class::isInstance)
                    .map(value -> new RevisionToken(((IRI) value).getIRIString()))
                    .orElse(null);
            return Optional.of(new CurrentRole(role, head, selected.name().languageTag(),
                    selected.description() == null ? null : selected.description().languageTag()));
        }
    }

    @Override
    public List<Role> findAll(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + ROLE_GRAPH + "> { "
                + "?s a <" + ROLE_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "FILTER(isIRI(?s)) } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            Map<String, List<LocalizedLiteral>> namesBySubject = literalsBySubject(sparql, NAME_PROPERTY);
            Map<String, List<LocalizedLiteral>> descriptionsBySubject = literalsBySubject(sparql, DESCRIPTION_PROPERTY);
            List<Role> roles = new ArrayList<>();
            sparql.select(query).forEach(row -> {
                String subjectIriString = iriOf(row, "s").getIRIString();
                Optional<LocalizedLiteral> name =
                        effective.select(namesBySubject.getOrDefault(subjectIriString, List.of()));
                if (name.isEmpty()) {
                    // actshapes:Role-name carries sh:minCount 1 at sh:Violation severity, so this
                    // is unreachable via the MCP tools - skip this one store-first role rather
                    // than crash the whole listing, mirroring KognioRdfConstraintRepository#findAll.
                    return;
                }
                Optional<LocalizedLiteral> description =
                        effective.select(descriptionsBySubject.getOrDefault(subjectIriString, List.of()));
                String subject = SparqlTerms.iriRef(subjectIriString);
                roles.add(new Role(new RoleId(ResourceId.of(subjectIriString)),
                        new RoleCode(literalOf(row, "identifier").getLexicalForm()),
                        name.get().value(),
                        description.map(LocalizedLiteral::value).orElse(null),
                        readFilledBy(sparql::select, subject)));
            });
            return List.copyOf(roles);
        }
    }

    /**
     * Companion to {@link #findAll}: not the displayed value, but whether displaying it required
     * falling back past the requested/project-default language tier (kogn-io/arknet#475) - mirrors
     * {@code KognioRdfConstraintRepository#findAllDisplayFallback} exactly.
     */
    @Override
    public Map<RoleCode, RoleDisplayFallback> findAllDisplayFallback(ProjectId projectId, String displayLocale) {
        Objects.requireNonNull(projectId, "projectId");
        DisplayLocale effective = this.displayLocale.withRequestedOverride(displayLocale);

        String query = "SELECT ?s ?identifier WHERE { GRAPH <" + ROLE_GRAPH + "> { "
                + "?s a <" + ROLE_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . "
                + "FILTER(isIRI(?s)) } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            SparqlQuery sparql = handle.sparqlQuery();
            Map<String, List<LocalizedLiteral>> namesBySubject = literalsBySubject(sparql, NAME_PROPERTY);
            Map<String, List<LocalizedLiteral>> descriptionsBySubject = literalsBySubject(sparql, DESCRIPTION_PROPERTY);
            Map<RoleCode, RoleDisplayFallback> result = new LinkedHashMap<>();
            sparql.select(query).forEach(row -> {
                String subject = iriOf(row, "s").getIRIString();
                RoleCode code = new RoleCode(literalOf(row, "identifier").getLexicalForm());
                RoleDisplayFallback fallback = new RoleDisplayFallback(
                        fallbackTag(namesBySubject.getOrDefault(subject, List.of()), effective),
                        fallbackTag(descriptionsBySubject.getOrDefault(subject, List.of()), effective));
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
     * {@code KognioRdfConstraintRepository#fallbackTag} exactly. A subject carrying no candidate at
     * all (an optional {@code description} a role never had) is never a fallback - there is nothing
     * to have shown in a different language.
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
     * Reads every registered role's business code - mirrors
     * {@link KognioRdfActorRepository#findAllCodes} exactly (kogn-io/arknet#360's reasoning, ported
     * to this resource type).
     */
    @Override
    public List<RoleCode> findAllCodes(ProjectId projectId) {
        Objects.requireNonNull(projectId, "projectId");

        String query = "SELECT ?identifier WHERE { GRAPH <" + ROLE_GRAPH + "> { "
                + "?s a <" + ROLE_TYPE + "> . "
                + "?s <" + IDENTIFIER_PROPERTY + "> ?identifier . } }";

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .distinct()
                    .map(RoleCode::new)
                    .toList();
        }
    }

    /**
     * One role's selected {@code name}/optional {@code description} literal, each carrying the
     * {@link LocalizedLiteral#languageTag()} it was chosen under - mirrors
     * {@code KognioRdfConstraintRepository}'s {@code TitleStatementSelection}, except
     * {@code description} may legitimately be {@code null} (it is optional, unlike a constraint's
     * mandatory {@code statement}).
     */
    private record NameDescriptionSelection(LocalizedLiteral name, LocalizedLiteral description) {
    }

    /**
     * Selects the {@code name} candidate via {@code locale}, plus a {@code description} candidate
     * if the role carries one - {@link Optional#empty()} only if this subject carries no
     * {@code name} literal at all (unreachable via the MCP tools; {@code actshapes:Role-name}
     * carries {@code sh:minCount 1} at {@code sh:Violation}), a store-first gap only.
     */
    private Optional<NameDescriptionSelection> selectNameDescription(
            Function<String, Stream<BindingSet>> selectFn, String subject, DisplayLocale locale) {
        Optional<LocalizedLiteral> name = locale.select(readNames(selectFn, subject));
        if (name.isEmpty()) {
            return Optional.empty();
        }
        Optional<LocalizedLiteral> description = locale.select(readDescriptions(selectFn, subject));
        return Optional.of(new NameDescriptionSelection(name.get(), description.orElse(null)));
    }

    private Optional<Role> roleOf(BindingSet row, RoleCode code, Function<String, Stream<BindingSet>> selectFn,
            DisplayLocale locale) {
        String subjectIriString = iriOf(row, "s").getIRIString();
        String subject = SparqlTerms.iriRef(subjectIriString);
        return selectNameDescription(selectFn, subject, locale).map(selection -> new Role(
                new RoleId(ResourceId.of(subjectIriString)),
                code,
                selection.name().value(),
                selection.description() == null ? null : selection.description().value(),
                readFilledBy(selectFn, subject)));
    }

    /** Reads the {@code arknet:name} candidates of one role, tagged for {@link DisplayLocale}. */
    private List<LocalizedLiteral> readNames(Function<String, Stream<BindingSet>> selectFn, String subject) {
        return readLocalizedLiterals(selectFn, subject, NAME_PROPERTY);
    }

    /** {@link #readNames} for {@code arknet:description}. */
    private List<LocalizedLiteral> readDescriptions(Function<String, Stream<BindingSet>> selectFn, String subject) {
        return readLocalizedLiterals(selectFn, subject, DESCRIPTION_PROPERTY);
    }

    private List<LocalizedLiteral> readLocalizedLiterals(
            Function<String, Stream<BindingSet>> selectFn, String subject, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + ROLE_GRAPH + "> { " + subject + " <" + predicateIri + "> ?o } }";
        return selectFn.apply(query).map(row -> localizedLiteralOf(row, "o")).toList();
    }

    /** Bulk variant of {@link #readLocalizedLiterals}: every role's candidates in one query. */
    private Map<String, List<LocalizedLiteral>> literalsBySubject(SparqlQuery query, String predicateIri) {
        String sparql = "SELECT ?s ?o WHERE { GRAPH <" + ROLE_GRAPH + "> { "
                + "?s <" + predicateIri + "> ?o . FILTER(isIRI(?s)) } }";
        Map<String, List<LocalizedLiteral>> bySubject = new LinkedHashMap<>();
        query.select(sparql).forEach(row -> bySubject
                .computeIfAbsent(iriOf(row, "s").getIRIString(), key -> new ArrayList<>())
                .add(localizedLiteralOf(row, "o")));
        return bySubject;
    }

    /**
     * Reads the {@code arkproc:filledBy} edges of one role back as opaque actor identities.
     * {@code FILTER(isIRI(?a))} mirrors {@code KognioRdfUseCaseRepository#readSupportingActors}:
     * the property carries no {@code sh:class}/{@code sh:nodeKind} it does not itself declare
     * beyond IRI-ness, so a store-first edge may in principle target a blank node, which
     * {@link ActorId} cannot represent - excluded here.
     */
    private List<ActorId> readFilledBy(Function<String, Stream<BindingSet>> selectFn, String subject) {
        String query = "SELECT ?a WHERE { GRAPH <" + ROLE_GRAPH + "> { "
                + subject + " <" + FILLED_BY_PROPERTY + "> ?a } FILTER(isIRI(?a)) }";
        return selectFn.apply(query)
                .map(row -> new ActorId(ResourceId.of(iriOf(row, "a").getIRIString())))
                .toList();
    }

    /** Builds a language-tagged literal, or a plain untagged one when {@code tag} is {@code null}. */
    private Literal literalOf(String value, String tag) {
        return tag == null ? rdf.createLiteral(value) : rdf.createLiteral(value, tag);
    }

    // ---- helpers -----------------------------------------------------------------------

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
