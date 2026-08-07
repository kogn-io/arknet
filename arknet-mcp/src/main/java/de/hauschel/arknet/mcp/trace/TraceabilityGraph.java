// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.mcp.mention.LabelMentions;
import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreResource;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;

/**
 * An in-memory directed multigraph over one project's statements, purpose-built for the
 * cross-bounded-context edges the traceability tools traverse:
 * {@code arkreq:usesTerm} (Requirement -&gt; Term), {@code arkreq:primaryActor}/
 * {@code arkreq:supportingActor} (UseCase -&gt; Term/Actor), {@code arkddd:ubiquitousLanguageTerm}
 * (BoundedContext -&gt; Term), {@code arkddd:upstream}/{@code arkddd:downstream} (ContextRelationship
 * -&gt; BoundedContext, issue #293), {@code oslc_rm:constrainedBy} (Requirement -&gt; Constraint,
 * issue #223), the three ADR edges
 * {@code arkarch:addressesRequirement} (ADR -&gt; Requirement), {@code arkarch:affectsContext}
 * (ADR -&gt; BoundedContext) and {@code arkarch:supersedes} (ADR -&gt; ADR, issue #69), and the
 * two-hop {@code arkreq:mainStep}/{@code arkreq:extensionStep} then {@code arkreq:stepRealises}
 * (UseCase -&gt; Step -&gt; Requirement). It also exposes the requirement/bounded-context prose
 * ({@code dcterms:description}/{@code arkreq:acceptanceCriterion}/{@code arkddd:domainVision}) that
 * {@code orphan_check}'s unlinked-mention check scans for a glossary term nothing links to.
 * {@code actor_usecase_matrix} needs the {@code primaryActor}/{@code
 * supportingActor} edges in the <em>forward</em> direction too ({@link #actorsOf(String)}/{@link
 * #useCasesOf(String)}), and {@code term_cooccurrence} reuses the same prose-scanning idea for a
 * use case's {@code arkreq:useCaseGoal} ({@link #useCaseProseTexts(String)}, issue #108).
 *
 * <p>Built once per read from a {@link StoreSnapshot} - the same generic {@code SELECT ?s ?p
 * ?o} {@link de.hauschel.arknet.mcp.store.StoreReader} already reads for {@code
 * store_overview}/{@code resource_get} (ADR-006) - instead of three bespoke SPARQL
 * property-path queries: at this project's single-user, local-store scale (ADR-001) an
 * in-memory traversal over the full triple set is simpler and just as fast, and it keeps this
 * class, like {@code StoreReader}, free of any RDF4J or further kognio-rdf dependency (it
 * consumes only the neutral {@link Triple}/{@link RdfNode} model).</p>
 *
 * <p>Unlike {@code StoreReader}/{@code Prefixes}, this class is deliberately <em>not</em>
 * domain-agnostic - it knows the {@code arkreq:}/{@code arkddd:}/{@code arkarch:}/{@code skos:}
 * predicate and type IRIs it traverses. That is a bounded exception in the same spirit as {@code
 * StoreResource#status()}/{@code #priority()}: a fully generic "follow every
 * object-typed predicate" traversal would report noise indistinguishable from the specific
 * edges traceability cares about.</p>
 */
public final class TraceabilityGraph {

    private static final String ARKDDD_NAMESPACE = "https://w3id.org/arknet/ddd#";
    private static final String ARKPROC_NAMESPACE = "https://w3id.org/arknet/process#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String DCTERMS_DESCRIPTION = "http://purl.org/dc/terms/description";

    // The traversed arkreq: object-property IRIs, the literal-valued arkreq:acceptanceCriterion/
    // arkddd:domainVision predicates and the type IRIs below come from the single shared source
    // of truth (arknet-persistence-support), the very same constants the requirements/
    // bounded-context/ubiquitous-language/use-cases out-adapters serialize them with - so a
    // predicate or type rename cannot silently desync the write side from this read-side
    // traversal.
    private static final String USES_TERM = ArkreqVocabulary.USES_TERM;
    private static final String PRIMARY_ACTOR = ArkreqVocabulary.PRIMARY_ACTOR;
    private static final String SUPPORTING_ACTOR = ArkreqVocabulary.SUPPORTING_ACTOR;
    private static final String MAIN_STEP = ArkreqVocabulary.MAIN_STEP;
    private static final String EXTENSION_STEP = ArkreqVocabulary.EXTENSION_STEP;
    private static final String STEP_REALISES = ArkreqVocabulary.STEP_REALISES;
    private static final String ACCEPTANCE_CRITERION = ArkreqVocabulary.ACCEPTANCE_CRITERION;
    private static final String CRITERION_TEXT = ArkreqVocabulary.CRITERION_TEXT;
    private static final String USE_CASE_GOAL = ArkreqVocabulary.USE_CASE_GOAL;
    private static final String DOMAIN_VISION = ArkdddVocabulary.DOMAIN_VISION;

    /**
     * {@code arkddd:upstream}/{@code arkddd:downstream} - ContextRelationship -&gt; BoundedContext
     * (issue #293), the same shared constants {@code KognioRdfContextRelationshipRepository}
     * serialises them with.
     */
    private static final String UPSTREAM = ArkdddVocabulary.UPSTREAM;
    private static final String DOWNSTREAM = ArkdddVocabulary.DOWNSTREAM;

    /** {@code skos:broader} - Term -&gt; its broader (superordinate) Term (issue #252). */
    private static final String BROADER = ArkreqVocabulary.BROADER;

    /**
     * {@code skos:definition} - a term's meaning, scanned by {@link #unlinkedMentions()}'s third
     * sweep for a mention of another term the source does not link via {@code broader} (issue
     * #252).
     */
    private static final String DEFINITION = ArkreqVocabulary.DEFINITION;

    /** {@code oslc_rm:constrainedBy} - Requirement -&gt; Constraint (issue #223). */
    private static final String CONSTRAINED_BY = ArkreqVocabulary.CONSTRAINED_BY;

    // The three arkarch: edges an architecture decision owns (issue #69). Unlike ArkreqVocabulary/
    // ArkdddVocabulary, whose scope is deliberately the cross-module subset only, ArkarchVocabulary
    // mirrors its whole (ADR-only) ontology module - so these come from the same shared source the
    // ADR out-adapter serializes them with, and a rename cannot silently desync the two sides.
    private static final String ADDRESSES_REQUIREMENT = ArkarchVocabulary.ADDRESSES_REQUIREMENT;
    private static final String AFFECTS_CONTEXT = ArkarchVocabulary.AFFECTS_CONTEXT;
    private static final String SUPERSEDES = ArkarchVocabulary.SUPERSEDES;

    // arkddd:ubiquitousLanguageTerm (BoundedContext -> Term) and arkddd:BoundedContext below are,
    // unlike arkreq:acceptanceCriterion/arkddd:domainVision above, used only within this class -
    // ArkdddVocabulary's scope is deliberately limited to predicates duplicated across modules
    // (see its javadoc), so these two stay local rather than growing that shared class further.
    private static final String UBIQUITOUS_LANGUAGE_TERM = ARKDDD_NAMESPACE + "ubiquitousLanguageTerm";

    private static final String FUNCTIONAL_REQUIREMENT_TYPE = ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE;
    private static final String NON_FUNCTIONAL_REQUIREMENT_TYPE = ArkreqVocabulary.NON_FUNCTIONAL_REQUIREMENT_TYPE;
    private static final String USE_CASE_TYPE = ArkreqVocabulary.USE_CASE_TYPE;
    private static final String STEP_TYPE = ArkreqVocabulary.STEP_TYPE;
    private static final String CONCEPT_TYPE = ArkreqVocabulary.CONCEPT_TYPE;
    private static final String BOUNDED_CONTEXT_TYPE = ARKDDD_NAMESPACE + "BoundedContext";

    // The three arkreq: Constraint subtypes (issue #223) - unlike arkreq:Requirement's two
    // subtypes, there is no abstract arkreq:Constraint type triple to check against: this
    // adapter's out-adapter always writes one of the three concrete subtypes, mirroring
    // FUNCTIONAL_REQUIREMENT_TYPE/NON_FUNCTIONAL_REQUIREMENT_TYPE above.
    private static final String TECHNICAL_CONSTRAINT_TYPE = ArkreqVocabulary.TECHNICAL_CONSTRAINT_TYPE;
    private static final String BUSINESS_CONSTRAINT_TYPE = ArkreqVocabulary.BUSINESS_CONSTRAINT_TYPE;
    private static final String REGULATORY_CONSTRAINT_TYPE = ArkreqVocabulary.REGULATORY_CONSTRAINT_TYPE;

    // arkproc:HumanActor/SystemActor/LegalActor below are, like UBIQUITOUS_LANGUAGE_TERM above,
    // used only within this class and duplicated rather than shared - the same three type IRIs
    // are already duplicated as adapter-private constants in the ul/uc kogniordf out-adapters, and
    // this class stays free of any dependency on either adapter (see the class javadoc).
    private static final String HUMAN_ACTOR_TYPE = ARKPROC_NAMESPACE + "HumanActor";
    private static final String SYSTEM_ACTOR_TYPE = ARKPROC_NAMESPACE + "SystemActor";
    private static final String LEGAL_ACTOR_TYPE = ARKPROC_NAMESPACE + "LegalActor";

    /**
     * The predicates {@link #dependents(String)} follows backwards ("who references this").
     * {@code mainStep}/{@code extensionStep} are in this set purely to hop a reached
     * {@code arkreq:Step} back to its owning use case - a step itself is never reported (see
     * {@link #dependents(String)}).
     *
     * <p>The three {@code arkarch:} edges are here because an architecture decision is exactly the
     * kind of artifact "what breaks if this changes" is asked about: change a requirement and the
     * decision that addresses it is affected; change a bounded context and the decision affecting it
     * is; supersede a decision and its successor is. Only {@code arkarch:supersedes} is listed for
     * the ADR-to-ADR relation, never its {@code owl:inverseOf} partner {@code supersededBy} - that
     * one is never asserted as a triple, so listing it would traverse an edge no writer produces
     * (issue #69). {@code arkarch:relatedTo} stays out on purpose: a symmetric "see also" cross-link
     * would make every related decision reachable from every other one and turn an impact report
     * into a cluster dump. {@code oslc_rm:constrainedBy} (Requirement -&gt; Constraint, issue #223)
     * joins the set for the same reason as {@code usesTerm}: a changed or removed Constraint should
     * surface the requirements bound by it in {@code impact_analysis}. {@code arkddd:upstream}/
     * {@code arkddd:downstream} (ContextRelationship -&gt; BoundedContext, issue #293) join for the
     * same reason as the three {@code arkarch:} edges: a recorded context-map relationship is
     * exactly the kind of artifact whose classification needs re-checking when either bounded
     * context it names changes, so both directions are listed here - a ContextRelationship is
     * reported as affected whichever of its two bounded contexts changed, unlike {@code
     * arkreq:Step}, it is never filtered out below, since it is (unlike a Step) a first-class
     * resource of its own (see the bounded-context module's CLAUDE.md). Deliberately <em>not</em>
     * traversed further from there: the partner bounded context on the relationship's other end is
     * not itself reached (it carries no backward-pointing edge to the relationship) - doing so
     * would need a two-hop traversal analogous to {@code mainStep}/{@code stepRealises}, left for a
     * follow-up if ever needed.
     */
    private static final Set<String> DEPENDENT_EDGE_PREDICATES = Set.of(
            USES_TERM, PRIMARY_ACTOR, SUPPORTING_ACTOR, STEP_REALISES, MAIN_STEP, EXTENSION_STEP,
            UBIQUITOUS_LANGUAGE_TERM, UPSTREAM, DOWNSTREAM, ADDRESSES_REQUIREMENT, AFFECTS_CONTEXT, SUPERSEDES,
            CONSTRAINED_BY);

    private final Map<String, List<Triple>> outgoingBySubject;
    private final Map<String, List<Triple>> incomingByObject;
    private final Map<String, Set<String>> typesBySubject;
    private final Map<String, String> identifierBySubject;
    private final Map<String, String> labelBySubject;

    private TraceabilityGraph(List<StoreResource> resources, DisplayLocale displayLocale) {
        Map<String, List<Triple>> outgoing = new LinkedHashMap<>();
        Map<String, List<Triple>> incoming = new LinkedHashMap<>();
        Map<String, Set<String>> types = new LinkedHashMap<>();
        Map<String, String> identifiers = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (StoreResource resource : resources) {
            outgoing.put(resource.iri(), resource.outgoing());
            resource.identifier().ifPresent(value -> identifiers.putIfAbsent(resource.iri(), value));
            resource.label(displayLocale).ifPresent(value -> labels.putIfAbsent(resource.iri(), value));
            for (Triple triple : resource.outgoing()) {
                if (triple.object() instanceof RdfNode.Resource resourceObject) {
                    incoming.computeIfAbsent(resourceObject.iri(), s -> new ArrayList<>()).add(triple);
                    if (RDF_TYPE.equals(triple.predicate())) {
                        types.computeIfAbsent(triple.subject(), s -> new LinkedHashSet<>()).add(resourceObject.iri());
                    }
                }
            }
        }
        this.outgoingBySubject = Map.copyOf(outgoing);
        this.incomingByObject = Map.copyOf(incoming);
        Map<String, Set<String>> frozenTypes = new LinkedHashMap<>();
        types.forEach((subject, values) -> frozenTypes.put(subject, Set.copyOf(values)));
        this.typesBySubject = Map.copyOf(frozenTypes);
        this.identifierBySubject = Map.copyOf(identifiers);
        this.labelBySubject = Map.copyOf(labels);
    }

    /**
     * Builds a graph from every statement of a project snapshot.
     *
     * <p>Label/identifier lookups ({@link #labelOf(String)}/{@link #identifierOf(String)}) defer
     * to each subject's already-built {@link StoreResource#label(DisplayLocale)}/{@link
     * StoreResource#identifier()} rather than re-scanning the raw triples, so this graph can never
     * disagree with {@code store_overview}/{@code resource_get} about what a resource's label is
     * (issue #103) - both read paths are handed the very same {@code displayLocale}, so a
     * multi-language {@code skos:prefLabel} resolves to the same word in {@code orphan_check} as
     * in the HTML report (issue #141).</p>
     *
     * @param snapshot      the snapshot to traverse (as read by {@code StoreReader#readSnapshot})
     * @param displayLocale the display language to select among a resource's language-tagged labels
     * @return the assembled graph
     */
    public static TraceabilityGraph of(StoreSnapshot snapshot, DisplayLocale displayLocale) {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(displayLocale, "displayLocale");
        return new TraceabilityGraph(snapshot.resources(), displayLocale);
    }

    /** @return the IRIs of every {@code arkreq:FunctionalRequirement}/{@code NonFunctionalRequirement}, sorted. */
    public List<String> requirementIris() {
        return subjectsOfType(FUNCTIONAL_REQUIREMENT_TYPE, NON_FUNCTIONAL_REQUIREMENT_TYPE);
    }

    /**
     * @return the IRIs of every {@code skos:Concept} (glossary terms, including actor-facetted
     *         ones - an actor remains a {@code skos:Concept}), sorted.
     */
    public List<String> termIris() {
        return subjectsOfType(CONCEPT_TYPE);
    }

    /**
     * @return the IRIs of every {@code arkreq:Constraint} (technical, business and regulatory
     *         alike), sorted - mirrors {@link #requirementIris()} (issue #223).
     */
    public List<String> constraintIris() {
        return subjectsOfType(TECHNICAL_CONSTRAINT_TYPE, BUSINESS_CONSTRAINT_TYPE, REGULATORY_CONSTRAINT_TYPE);
    }

    /** @return the term IRIs a requirement uses via {@code arkreq:usesTerm}, sorted. */
    public List<String> usedTerms(String requirementIri) {
        Objects.requireNonNull(requirementIri, "requirementIri");
        return outgoingBySubject.getOrDefault(requirementIri, List.of()).stream()
                .filter(t -> USES_TERM.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(o -> ((RdfNode.Resource) o).iri())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The use case(s) realising a requirement: hops from the requirement backwards through
     * {@code arkreq:stepRealises} to the realising {@code arkreq:Step}(s), then backwards again
     * through {@code arkreq:mainStep}/{@code arkreq:extensionStep} to the owning use case(s). A
     * step is aggregate-internal (see CLAUDE.md's use-cases module note) and never itself
     * returned - only the use case that owns it.
     *
     * @return the realising use-case IRIs, sorted, deduplicated
     */
    public List<String> realisingUseCases(String requirementIri) {
        Objects.requireNonNull(requirementIri, "requirementIri");
        List<String> steps = incomingByObject.getOrDefault(requirementIri, List.of()).stream()
                .filter(t -> STEP_REALISES.equals(t.predicate()))
                .map(Triple::subject)
                .distinct()
                .toList();
        Set<String> useCases = new TreeSet<>();
        for (String step : steps) {
            incomingByObject.getOrDefault(step, List.of()).stream()
                    .filter(t -> MAIN_STEP.equals(t.predicate()) || EXTENSION_STEP.equals(t.predicate()))
                    .map(Triple::subject)
                    .forEach(useCases::add);
        }
        return List.copyOf(useCases);
    }

    /** @return the IRIs of every {@code arkreq:UseCase}, sorted. */
    public List<String> useCaseIris() {
        return subjectsOfType(USE_CASE_TYPE);
    }

    /**
     * @return the IRIs of every {@code arkproc:HumanActor}/{@code SystemActor}/{@code LegalActor}
     *         in the project, sorted - independent of whether any use case references it via
     *         {@code arkreq:primaryActor}/{@code supportingActor}. {@code actor_usecase_matrix}'s
     *         "Actors" section unions this with {@link #actorsOf(String)}'s results so an actor
     *         nobody's use case references yet still appears, instead of silently disappearing
     *         from a matrix whose own tool description promises "for every actor" (issue #147).
     */
    public List<String> actorIris() {
        return subjectsOfType(HUMAN_ACTOR_TYPE, SYSTEM_ACTOR_TYPE, LEGAL_ACTOR_TYPE);
    }

    /**
     * The actor(s) a use case references: its {@code arkreq:primaryActor} plus every
     * {@code arkreq:supportingActor} - the forward direction of the same two edges
     * {@link #DEPENDENT_EDGE_PREDICATES} already traverses backwards for {@link #dependents(String)}
     * (issue #108).
     *
     * @return the actor term IRIs, sorted, deduplicated
     */
    public List<String> actorsOf(String useCaseIri) {
        Objects.requireNonNull(useCaseIri, "useCaseIri");
        return outgoingBySubject.getOrDefault(useCaseIri, List.of()).stream()
                .filter(t -> PRIMARY_ACTOR.equals(t.predicate()) || SUPPORTING_ACTOR.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(o -> ((RdfNode.Resource) o).iri())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The use case(s) an actor plays a role in, as primary or supporting actor - the reverse
     * lookup of {@link #actorsOf(String)} (issue #108).
     *
     * @return the use-case IRIs, sorted, deduplicated
     */
    public List<String> useCasesOf(String actorIri) {
        Objects.requireNonNull(actorIri, "actorIri");
        return incomingByObject.getOrDefault(actorIri, List.of()).stream()
                .filter(t -> PRIMARY_ACTOR.equals(t.predicate()) || SUPPORTING_ACTOR.equals(t.predicate()))
                .map(Triple::subject)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * @return {@code true} if a term is used by a requirement ({@code arkreq:usesTerm}), plays
     *         an actor role in a use case ({@code arkreq:primaryActor}/
     *         {@code arkreq:supportingActor}), is a bounded context's ubiquitous language
     *         ({@code arkddd:ubiquitousLanguageTerm}), or is another term's broader (superordinate)
     *         term ({@code skos:broader}, issue #252) - an interior/root taxonomy term stops
     *         being reported as an orphan once something is hung under it
     */
    public boolean isReferencedTerm(String termIri) {
        Objects.requireNonNull(termIri, "termIri");
        return incomingByObject.getOrDefault(termIri, List.of()).stream()
                .anyMatch(t -> USES_TERM.equals(t.predicate()) || PRIMARY_ACTOR.equals(t.predicate())
                        || SUPPORTING_ACTOR.equals(t.predicate())
                        || UBIQUITOUS_LANGUAGE_TERM.equals(t.predicate())
                        || BROADER.equals(t.predicate()));
    }

    /**
     * @return the term IRI a term specializes via {@code skos:broader}, if it has one
     *         (issue #252)
     */
    public Optional<String> broaderTerm(String termIri) {
        Objects.requireNonNull(termIri, "termIri");
        return outgoingBySubject.getOrDefault(termIri, List.of()).stream()
                .filter(t -> BROADER.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(o -> ((RdfNode.Resource) o).iri())
                .findFirst();
    }

    /**
     * @return {@code true} if a constraint is bound to at least one requirement via
     *         {@code oslc_rm:constrainedBy} - mirrors {@link #isReferencedTerm(String)}
     *         (issue #223).
     */
    public boolean isConstraintReferenced(String constraintIri) {
        Objects.requireNonNull(constraintIri, "constraintIri");
        return incomingByObject.getOrDefault(constraintIri, List.of()).stream()
                .anyMatch(t -> CONSTRAINED_BY.equals(t.predicate()));
    }

    /** @return the IRIs of every {@code arkddd:BoundedContext}, sorted. */
    public List<String> boundedContextIris() {
        return subjectsOfType(BOUNDED_CONTEXT_TYPE);
    }

    /** @return the term IRIs a bounded context links via {@code arkddd:ubiquitousLanguageTerm}, sorted. */
    public List<String> linkedTerms(String boundedContextIri) {
        Objects.requireNonNull(boundedContextIri, "boundedContextIri");
        return outgoingBySubject.getOrDefault(boundedContextIri, List.of()).stream()
                .filter(t -> UBIQUITOUS_LANGUAGE_TERM.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(o -> ((RdfNode.Resource) o).iri())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * @return the {@code dcterms:description} text and every {@code arkreq:acceptanceCriterion}
     *         resource's {@code arkreq:criterionText} of a requirement, for scanning it for
     *         unlinked glossary mentions. {@code arkreq:acceptanceCriterion} is an object property
     *         since issue #266 (pointing at its own {@code arkreq:AcceptanceCriterion} resource,
     *         mirroring {@code arkreq:mainStep}/{@code arkreq:Step}), so this is a two-hop read:
     *         the edge's target IRIs, then each target's own {@code criterionText} literal(s).
     */
    public List<String> requirementProseTexts(String requirementIri) {
        Objects.requireNonNull(requirementIri, "requirementIri");
        List<String> texts = new ArrayList<>(literals(requirementIri, DCTERMS_DESCRIPTION));
        for (String criterionIri : resourceObjects(requirementIri, ACCEPTANCE_CRITERION)) {
            texts.addAll(literals(criterionIri, CRITERION_TEXT));
        }
        return texts;
    }

    /**
     * @return the {@code arkddd:domainVision} text of a bounded context, for scanning it for
     *         unlinked glossary mentions
     */
    public List<String> boundedContextProseTexts(String boundedContextIri) {
        return literals(Objects.requireNonNull(boundedContextIri, "boundedContextIri"), DOMAIN_VISION);
    }

    /**
     * @return the {@code arkreq:useCaseGoal} text of a use case - the closest thing a use case
     *         has to a description - for {@code term_cooccurrence} to scan alongside a
     *         requirement's prose (issue #108)
     */
    public List<String> useCaseProseTexts(String useCaseIri) {
        return literals(Objects.requireNonNull(useCaseIri, "useCaseIri"), USE_CASE_GOAL);
    }

    /**
     * @return the {@code skos:definition} text of a term, for scanning it for unlinked mentions of
     *         other glossary terms (issue #252) - mirrors {@link #boundedContextProseTexts(String)}
     */
    public List<String> termProseTexts(String termIri) {
        return literals(Objects.requireNonNull(termIri, "termIri"), DEFINITION);
    }

    /** @return the {@code prefLabel}/{@code title} label of every term IRI that carries one. */
    public Map<String, String> termLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String termIri : termIris()) {
            labelOf(termIri).ifPresent(label -> labels.put(termIri, label));
        }
        return Map.copyOf(labels);
    }

    /**
     * Every requirement/bounded-context prose mention of a glossary term the source does not
     * link to: a requirement's {@code dcterms:description}/{@code
     * arkreq:acceptanceCriterion} checked against its {@code arkreq:usesTerm} edges, and a
     * bounded context's {@code arkddd:domainVision} checked against its {@code
     * arkddd:ubiquitousLanguageTerm} edges. Reuses the very matching rules the HTML report
     * already applies inline ({@code de.hauschel.arknet.mcp.report.Glossary}), via the shared
     * {@link LabelMentions} engine, so a text mention means the same thing in both places.
     *
     * <p>Two same-length competing labels break their matching tie by business code ({@link
     * #identifierOf(String)}), the same key {@code Glossary} sorts its terms by before handing
     * them to the identical engine - not by term IRI, which would let the two matching passes
     * pick different terms for the same ambiguous mention (issue #141).</p>
     *
     * <p>A third sweep (issue #252) scans every term's own {@code skos:definition} for a mention
     * of some <em>other</em> glossary term: a taxonomy term's prose commonly names its
     * superordinate ("A Human Actor is an Actor who ...") without that being an accident the way
     * an unrelated mention in a requirement/bounded-context prose is - so a mentioned term counts
     * as unlinked here only when it is not the very term {@link #broaderTerm(String)} already
     * names as this term's own broader term. A term's mention of its own label within its own
     * definition is never reported (a term cannot be its own broader term anyway, see
     * {@link de.hauschel.arknet.ul.domain.TermCycleException}).</p>
     *
     * @return the unlinked mentions found across every requirement, bounded context and term
     */
    public List<UnlinkedMention> unlinkedMentions() {
        Map<String, String> termLabels = termLabels();
        if (termLabels.isEmpty()) {
            return List.of();
        }
        List<String> termIris = termLabels.keySet().stream()
                .sorted(Comparator.comparing(iri -> identifierOf(iri).orElse(iri)))
                .toList();
        LabelMentions<String> matcher = LabelMentions.of(termIris, termLabels::get);

        List<UnlinkedMention> found = new ArrayList<>();
        for (String requirementIri : requirementIris()) {
            Set<String> linked = new HashSet<>(usedTerms(requirementIri));
            for (String termIri : matcher.mentionedIn(requirementProseTexts(requirementIri))) {
                if (!linked.contains(termIri)) {
                    found.add(new UnlinkedMention(requirementIri, termIri, termLabels.get(termIri), "usesTerm"));
                }
            }
        }
        for (String boundedContextIri : boundedContextIris()) {
            Set<String> linked = new HashSet<>(linkedTerms(boundedContextIri));
            for (String termIri : matcher.mentionedIn(boundedContextProseTexts(boundedContextIri))) {
                if (!linked.contains(termIri)) {
                    found.add(new UnlinkedMention(
                            boundedContextIri, termIri, termLabels.get(termIri), "ubiquitousLanguageTerm"));
                }
            }
        }
        for (String termIri : termIris) {
            String broader = broaderTerm(termIri).orElse(null);
            for (String mentionedTermIri : matcher.mentionedIn(termProseTexts(termIri))) {
                if (mentionedTermIri.equals(termIri)) {
                    continue;
                }
                if (!mentionedTermIri.equals(broader)) {
                    found.add(new UnlinkedMention(
                            termIri, mentionedTermIri, termLabels.get(mentionedTermIri), "broader"));
                }
            }
        }
        return List.copyOf(found);
    }

    /**
     * @param sourceIri     the requirement, bounded context or term whose prose names the term
     * @param termIri       the mentioned term
     * @param termLabel     the term's {@code skos:prefLabel}, as named in the prose
     * @param edgeLocalName the missing edge's local name ({@code usesTerm},
     *                      {@code ubiquitousLanguageTerm} or {@code broader}), for the
     *                      "no ... edge" message
     */
    public record UnlinkedMention(String sourceIri, String termIri, String termLabel, String edgeLocalName) {
    }

    private List<String> literals(String subject, String predicate) {
        return outgoingBySubject.getOrDefault(subject, List.of()).stream()
                .filter(t -> predicate.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Literal.class::isInstance)
                .map(o -> ((RdfNode.Literal) o).lexicalForm())
                .toList();
    }

    /**
     * {@link #literals(String, String)}, for a resource-object edge instead of a literal-valued
     * one - backs {@link #requirementProseTexts(String)}'s hop from an {@code
     * arkreq:acceptanceCriterion} edge to its target {@code arkreq:AcceptanceCriterion} resource
     * (issue #266).
     */
    private List<String> resourceObjects(String subject, String predicate) {
        return outgoingBySubject.getOrDefault(subject, List.of()).stream()
                .filter(t -> predicate.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(o -> ((RdfNode.Resource) o).iri())
                .toList();
    }

    /**
     * Transitive "who references this" closure: every resource reachable by following
     * {@link #DEPENDENT_EDGE_PREDICATES} backwards from {@code targetIri} - i.e. what is
     * affected when {@code targetIri} changes.
     *
     * <p>{@code arkreq:Step} nodes are traversed <em>through</em> (the
     * {@code mainStep}/{@code extensionStep} hop needs them to reach the owning use case) but
     * never appear in the returned set: a step is an aggregate-internal value object with no
     * identity of its own, never a reportable "affected" artifact in its own right.</p>
     *
     * @return the transitively affected IRIs, sorted, deduplicated, excluding {@code targetIri}
     *         itself and any {@code arkreq:Step}
     */
    public List<String> dependents(String targetIri) {
        Objects.requireNonNull(targetIri, "targetIri");
        Set<String> frontier = new HashSet<>();
        frontier.add(targetIri);
        Set<String> reported = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(targetIri);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            for (Triple triple : incomingByObject.getOrDefault(current, List.of())) {
                if (!DEPENDENT_EDGE_PREDICATES.contains(triple.predicate())) {
                    continue;
                }
                String subject = triple.subject();
                if (!frontier.add(subject)) {
                    continue;
                }
                queue.add(subject);
                if (!isType(subject, STEP_TYPE)) {
                    reported.add(subject);
                }
            }
        }
        return List.copyOf(reported);
    }

    /**
     * @return {@code true} if {@code iri} carries at least one statement, as either subject or
     *         object - the same "has this resource ever been written" check
     *         {@code resource_get}'s not-found notice is keyed on (issue #135), so
     *         {@code impact_analysis} can give that same notice for a syntactically valid but
     *         unknown handle instead of silently reporting zero affected resources.
     */
    public boolean knows(String iri) {
        Objects.requireNonNull(iri, "iri");
        return outgoingBySubject.containsKey(iri) || incomingByObject.containsKey(iri);
    }

    /** @return {@code true} if {@code iri} carries {@code typeIri} as one of its {@code rdf:type}s. */
    public boolean isType(String iri, String typeIri) {
        Objects.requireNonNull(iri, "iri");
        Objects.requireNonNull(typeIri, "typeIri");
        return typesBySubject.getOrDefault(iri, Set.of()).contains(typeIri);
    }

    /** @return every {@code rdf:type} IRI of {@code iri}, empty if untyped. */
    public Set<String> typesOf(String iri) {
        return typesBySubject.getOrDefault(Objects.requireNonNull(iri, "iri"), Set.of());
    }

    /** @return the {@code dcterms:identifier} of {@code iri} (its business code), if present. */
    public Optional<String> identifierOf(String iri) {
        return Optional.ofNullable(identifierBySubject.get(Objects.requireNonNull(iri, "iri")));
    }

    /** @return {@code iri}'s {@link StoreResource#label()}, if present. */
    public Optional<String> labelOf(String iri) {
        return Optional.ofNullable(labelBySubject.get(Objects.requireNonNull(iri, "iri")));
    }

    private List<String> subjectsOfType(String... typeIris) {
        Set<String> wanted = Set.of(typeIris);
        return typesBySubject.entrySet().stream()
                .filter(e -> !Collections.disjoint(e.getValue(), wanted))
                .map(Map.Entry::getKey)
                .sorted()
                .toList();
    }
}
