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
 * {@code arkreq:usesTerm} (Requirement/UseCase -&gt; Term, issue #329), {@code arkreq:primaryActor}/
 * {@code arkreq:supportingActor} (UseCase -&gt; Term/Actor), {@code arkddd:ubiquitousLanguageTerm}
 * (BoundedContext -&gt; Term), {@code arkddd:upstream}/{@code arkddd:downstream} (ContextRelationship
 * -&gt; BoundedContext, issue #293), {@code oslc_rm:constrainedBy} (Requirement/UseCase -&gt;
 * Constraint, issue #223/#329), the four ADR edges
 * {@code arkarch:addressesRequirement} (ADR -&gt; Requirement), {@code arkarch:affectsContext}
 * (ADR -&gt; BoundedContext), {@code arkarch:usesTerm} (ADR -&gt; Term, kogn-io/arknet#393) and
 * {@code arkarch:supersededBy} (ADR -&gt; ADR, written on the
 * superseded decision, issue #69/kogn-io/arknet#357), and the
 * two-hop {@code arkreq:mainStep}/{@code arkreq:extensionStep} then {@code arkreq:stepRealises}
 * (UseCase -&gt; Step -&gt; Requirement). It also exposes the requirement/use-case/bounded-context/ADR
 * prose ({@code dcterms:description}/{@code arkreq:acceptanceCriterion}, {@link
 * #useCaseProseTexts(String)}, {@code arkddd:domainVision}, {@link #adrProseTexts(String)}, issue
 * #406) that {@code orphan_check}'s unlinked-mention check scans for a glossary term nothing links to.
 * {@code actor_usecase_matrix} needs the {@code primaryActor}/{@code
 * supportingActor} edges in the <em>forward</em> direction too ({@link #actorsOf(String)}/{@link
 * #useCasesOf(String)}), and {@code term_cooccurrence} reuses the same prose-scanning idea for a
 * use case's {@code arkreq:useCaseGoal} ({@link #useCaseProseTexts(String)}, issue #108).
 *
 * <p>Built once per read from a {@link StoreSnapshot} - the same generic {@code SELECT ?s ?p
 * ?o} {@link de.hauschel.arknet.mcp.store.StoreReader} already reads for {@code
 * store_overview}/{@code resource_get} - instead of three bespoke SPARQL
 * property-path queries: at this project's single-user, local-store scale an
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
    private static final String DESIGN_SCOPE = ArkreqVocabulary.DESIGN_SCOPE;
    private static final String TRIGGER = ArkreqVocabulary.TRIGGER;
    private static final String USE_CASE_PRECONDITION = ArkreqVocabulary.USE_CASE_PRECONDITION;
    private static final String USE_CASE_POSTCONDITION = ArkreqVocabulary.USE_CASE_POSTCONDITION;
    private static final String STEP_TEXT = ArkreqVocabulary.STEP_TEXT;
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

    /** {@code oslc_rm:constrainedBy} - Requirement/UseCase -&gt; Constraint (issue #223/#329). */
    private static final String CONSTRAINED_BY = ArkreqVocabulary.CONSTRAINED_BY;

    // The four arkarch: edges an architecture decision owns (issue #69, kogn-io/arknet#393). Unlike
    // ArkreqVocabulary/ArkdddVocabulary, whose scope is deliberately the cross-module subset only,
    // ArkarchVocabulary mirrors its whole (ADR-only) ontology module - so these come from the same
    // shared source the ADR out-adapter serializes them with, and a rename cannot silently desync
    // the two sides.
    private static final String ADDRESSES_REQUIREMENT = ArkarchVocabulary.ADDRESSES_REQUIREMENT;
    private static final String AFFECTS_CONTEXT = ArkarchVocabulary.AFFECTS_CONTEXT;

    /**
     * {@code arkarch:usesTerm} - ADR -&gt; Term (kogn-io/arknet#393), the ADR component's own
     * property (not the shared {@code arkreq:usesTerm} {@link #USES_TERM} above - see
     * {@code ArkarchVocabulary#USES_TERM}'s javadoc for why). Named {@code ADR_USES_TERM} rather
     * than reusing {@code USES_TERM} to avoid a naming collision with the constant above.
     */
    private static final String ADR_USES_TERM = ArkarchVocabulary.USES_TERM;

    /**
     * {@code arkarch:supersededBy} - ADR -&gt; ADR, written on the <em>superseded</em> decision
     * (kogn-io/arknet#357 moved the written edge here, off the superseding decision's old
     * forward-only {@code arkarch:supersedes}). {@link #dependents(String)} therefore follows this
     * one <em>forwards</em>, unlike every other predicate in {@link #DEPENDENT_EDGE_PREDICATES} -
     * see {@link #FORWARD_DEPENDENT_EDGE_PREDICATES}.
     */
    private static final String SUPERSEDED_BY = ArkarchVocabulary.SUPERSEDED_BY;

    /**
     * {@code arkarch:supersedes} - the pre-#357 write shape: no tool asserts it any more, but a
     * store-first record may still carry it, and {@link #dependents(String)} keeps following it
     * backwards (in {@link #DEPENDENT_EDGE_PREDICATES}) so such a record's successor stays reachable
     * the same way it always was.
     */
    private static final String SUPERSEDES = ArkarchVocabulary.SUPERSEDES;

    /** {@code arkarch:ArchitectureDecisionRecord} - the type of an architecture decision (issue #406). */
    private static final String ADR_TYPE = ArkarchVocabulary.ADR_TYPE;

    /**
     * {@code arknet:name} - an ADR's own decision name, and (reused, kogn-io/arknet#357) a
     * {@link #CONSIDERED_OPTION}'s short option name - see {@link ArkarchVocabulary#NAME}'s own
     * javadoc for why one predicate serves both. {@link #adrProseTexts(String)} (issue #406) reads
     * it from both places.
     */
    private static final String NAME = ArkarchVocabulary.NAME;

    /** {@code arkarch:adrContext} - ADR -&gt; why the decision was necessary (issue #406). */
    private static final String ADR_CONTEXT = ArkarchVocabulary.ADR_CONTEXT;

    /** {@code arkarch:adrDecision} - ADR -&gt; what was decided (issue #406). */
    private static final String ADR_DECISION = ArkarchVocabulary.ADR_DECISION;

    /**
     * {@code arkarch:consequence} - ADR -&gt; one of its {@code arkarch:Consequence} resources, the
     * two-hop edge {@link #adrProseTexts(String)} follows to each consequence's
     * {@link #CONSEQUENCE_STATEMENT} (issue #406) - mirrors {@link #ACCEPTANCE_CRITERION}'s hop.
     */
    private static final String CONSEQUENCE = ArkarchVocabulary.CONSEQUENCE;

    /** {@code arkarch:consequenceStatement} - Consequence -&gt; its multilingual text (issue #406). */
    private static final String CONSEQUENCE_STATEMENT = ArkarchVocabulary.CONSEQUENCE_STATEMENT;

    /**
     * {@code arkarch:consideredOption} - ADR -&gt; one of its {@code arkarch:ConsideredOption}
     * resources, the two-hop edge {@link #adrProseTexts(String)} follows to each option's
     * {@link #NAME}/{@link #OPTION_RATIONALE} (issue #406).
     */
    private static final String CONSIDERED_OPTION = ArkarchVocabulary.CONSIDERED_OPTION;

    /** {@code arkarch:optionRationale} - ConsideredOption -&gt; its multilingual reasoning text (issue #406). */
    private static final String OPTION_RATIONALE = ArkarchVocabulary.OPTION_RATIONALE;

    // arkddd:BoundedContext below is, unlike arkreq:acceptanceCriterion/arkddd:domainVision above,
    // used only within this class - ArkdddVocabulary's scope is deliberately limited to
    // predicates duplicated across modules (see its javadoc), so this one stays local rather than
    // growing that shared class further. arkddd:ubiquitousLanguageTerm moved to
    // ArkdddVocabulary#UBIQUITOUS_LANGUAGE_TERM with issue #335, once KognioRdfTermRepository's
    // reference check became a third reader.
    private static final String UBIQUITOUS_LANGUAGE_TERM = ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM;

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

    // arkproc:HumanActor/SystemActor/LegalActor/GroupActor below are, like UBIQUITOUS_LANGUAGE_TERM
    // above, used only within this class and duplicated rather than shared - the same four type
    // IRIs are already duplicated as adapter-private constants in arknet-actor's kogniordf
    // out-adapter, and this class stays free of any dependency on it (see the class javadoc).
    private static final String HUMAN_ACTOR_TYPE = ARKPROC_NAMESPACE + "HumanActor";
    private static final String SYSTEM_ACTOR_TYPE = ARKPROC_NAMESPACE + "SystemActor";
    private static final String LEGAL_ACTOR_TYPE = ARKPROC_NAMESPACE + "LegalActor";
    private static final String GROUP_ACTOR_TYPE = ARKPROC_NAMESPACE + "GroupActor";

    /**
     * The predicates {@link #dependents(String)} follows backwards ("who references this").
     * {@code mainStep}/{@code extensionStep} are in this set purely to hop a reached
     * {@code arkreq:Step} back to its owning use case - a step itself is never reported (see
     * {@link #dependents(String)}).
     *
     * <p>The {@code arkarch:} edges here are because an architecture decision is exactly the
     * kind of artifact "what breaks if this changes" is asked about: change a requirement and the
     * decision that addresses it is affected; change a bounded context and the decision affecting it
     * is; change (or delete) a glossary term and the decision that uses it
     * ({@code arkarch:usesTerm}, kogn-io/arknet#393) is too - the same reasoning
     * {@code arkreq:usesTerm} already earns a place in this set for. {@code arkarch:supersedes} is the
     * pre-#357 write shape - no tool asserts it any more, but a
     * store-first record may still carry it, and this backward read is what keeps such a record's
     * successor reachable. {@code arkarch:supersededBy}, the current write shape, is deliberately
     * <strong>not</strong> in this backward-followed set: kogn-io/arknet#357 moved that edge onto the
     * <em>superseded</em> decision, so "the successor is affected when the superseded decision
     * changes" is now a <em>forward</em> traversal - see {@link #FORWARD_DEPENDENT_EDGE_PREDICATES}
     * and {@link #dependents(String)}. {@code arkarch:relatedTo} stays out on purpose: a symmetric
     * "see also" cross-link
     * would make every related decision reachable from every other one and turn an impact report
     * into a cluster dump. {@code oslc_rm:constrainedBy} (Requirement/UseCase -&gt; Constraint,
     * issue #223/#329) joins the set for the same reason as {@code usesTerm}: a changed or removed
     * Constraint should surface the requirements/use cases bound by it in
     * {@code impact_analysis}. {@code arkddd:upstream}/
     * {@code arkddd:downstream} (ContextRelationship -&gt; BoundedContext, issue #293) join for the
     * same reason as the two {@code arkarch:} edges: a recorded context-map relationship is
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
            UBIQUITOUS_LANGUAGE_TERM, UPSTREAM, DOWNSTREAM, ADDRESSES_REQUIREMENT, AFFECTS_CONTEXT,
            ADR_USES_TERM, CONSTRAINED_BY, SUPERSEDES);

    /**
     * The predicates {@link #dependents(String)} follows <em>forwards</em> instead - the target of
     * the edge is what is affected, not the subject that carries it. The single member,
     * {@code arkarch:supersededBy}, is the kogn-io/arknet#357 exception to
     * {@link #DEPENDENT_EDGE_PREDICATES}'s uniform backward direction: the edge is written on the
     * <em>superseded</em> decision, pointing at its successor, so "the successor is affected when
     * the superseded decision changes" is reached by following the edge the way it was typed, not
     * against it.
     */
    private static final Set<String> FORWARD_DEPENDENT_EDGE_PREDICATES = Set.of(SUPERSEDED_BY);

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
     * @return the IRIs of every {@code skos:Concept} (glossary terms), sorted. Since issue #336 a
     *         term is a plain {@code skos:Concept} again - it no longer carries an actor facet -
     *         so this list and {@link #actorIris()} are disjoint unless a resource happens to be
     *         both a registered actor and a separately registered glossary term (multi-typing is
     *         still legal, just no longer the only way to be an actor).
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

    /**
     * @return the term IRIs a requirement or a use case uses via {@code arkreq:usesTerm}, sorted
     *         (issue #329 widened the edge's subject beyond Requirement).
     */
    public List<String> usedTerms(String subjectIri) {
        Objects.requireNonNull(subjectIri, "subjectIri");
        return outgoingBySubject.getOrDefault(subjectIri, List.of()).stream()
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
     * @return the IRIs of every {@code arkproc:HumanActor}/{@code SystemActor}/{@code LegalActor}/
     *         {@code GroupActor} in the project, sorted - independent of whether any use case
     *         references it via {@code arkreq:primaryActor}/{@code supportingActor}, and
     *         independent of which named graph it lives in: since issue #336 an actor lives in
     *         {@code arknet-actor}'s own register graph rather than the ubiquitous-language
     *         graph, but this traversal is graph-agnostic (it indexes {@link
     *         StoreSnapshot}'s resources by type, never by graph), so it finds the register's
     *         actors the same way it always found the old term-facetted ones.
     *         {@code actor_usecase_matrix}'s "Actors" section unions this with
     *         {@link #actorsOf(String)}'s results so an actor nobody's use case references yet
     *         still appears, instead of silently disappearing from a matrix whose own tool
     *         description promises "for every actor" (issue #147).
     */
    public List<String> actorIris() {
        return subjectsOfType(HUMAN_ACTOR_TYPE, SYSTEM_ACTOR_TYPE, LEGAL_ACTOR_TYPE, GROUP_ACTOR_TYPE);
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
     * @return {@code true} if a term is used by a requirement or a use case
     *         ({@code arkreq:usesTerm}, issue #329), by an architecture decision
     *         ({@code arkarch:usesTerm}, kogn-io/arknet#393), plays an actor role in a use case
     *         ({@code arkreq:primaryActor}/{@code arkreq:supportingActor}), is a bounded context's
     *         ubiquitous language ({@code arkddd:ubiquitousLanguageTerm}), or is another term's
     *         broader (superordinate) term ({@code skos:broader}, issue #252) - an interior/root
     *         taxonomy term stops being reported as an orphan once something is hung under it
     */
    public boolean isReferencedTerm(String termIri) {
        Objects.requireNonNull(termIri, "termIri");
        return incomingByObject.getOrDefault(termIri, List.of()).stream()
                .anyMatch(t -> USES_TERM.equals(t.predicate()) || ADR_USES_TERM.equals(t.predicate())
                        || PRIMARY_ACTOR.equals(t.predicate())
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
     * @return {@code true} if a constraint is bound to at least one requirement or use case via
     *         {@code oslc_rm:constrainedBy} - mirrors {@link #isReferencedTerm(String)}
     *         (issue #223/#329).
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

    /** @return the IRIs of every {@code arkarch:ArchitectureDecisionRecord}, sorted (issue #406). */
    public List<String> adrIris() {
        return subjectsOfType(ADR_TYPE);
    }

    /**
     * @return the term IRIs an architecture decision uses via {@code arkarch:usesTerm}
     *         (kogn-io/arknet#393), sorted - the ADR-owned counterpart of {@link #usedTerms(String)},
     *         which only traverses the shared {@code arkreq:usesTerm} property an ADR never carries
     *         (issue #406).
     */
    public List<String> adrUsedTerms(String adrIri) {
        Objects.requireNonNull(adrIri, "adrIri");
        return outgoingBySubject.getOrDefault(adrIri, List.of()).stream()
                .filter(t -> ADR_USES_TERM.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(o -> ((RdfNode.Resource) o).iri())
                .distinct()
                .sorted()
                .toList();
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
     * @return every text field of a use case that can carry ubiquitous-language prose: its
     *         {@code arkreq:useCaseGoal}/{@code designScope}/{@code trigger}/{@code
     *         useCasePrecondition}/{@code useCasePostcondition} literals, plus the {@code
     *         arkreq:stepText} of every main-flow and extension step reached via {@code
     *         arkreq:mainStep}/{@code extensionStep} - the same two-hop read {@link
     *         #requirementProseTexts(String)} already does for an acceptance criterion's text.
     *         Used by both {@code term_cooccurrence} (issue #108, originally just the goal) and
     *         {@link #unlinkedMentions()} (issue #333); an optional field simply contributes no
     *         literal when unset
     */
    public List<String> useCaseProseTexts(String useCaseIri) {
        Objects.requireNonNull(useCaseIri, "useCaseIri");
        List<String> texts = new ArrayList<>(literals(useCaseIri, USE_CASE_GOAL));
        texts.addAll(literals(useCaseIri, DESIGN_SCOPE));
        texts.addAll(literals(useCaseIri, TRIGGER));
        texts.addAll(literals(useCaseIri, USE_CASE_PRECONDITION));
        texts.addAll(literals(useCaseIri, USE_CASE_POSTCONDITION));
        for (String stepIri : resourceObjects(useCaseIri, MAIN_STEP)) {
            texts.addAll(literals(stepIri, STEP_TEXT));
        }
        for (String stepIri : resourceObjects(useCaseIri, EXTENSION_STEP)) {
            texts.addAll(literals(stepIri, STEP_TEXT));
        }
        return texts;
    }

    /**
     * @return the {@code skos:definition} text of a term, for scanning it for unlinked mentions of
     *         other glossary terms (issue #252) - mirrors {@link #boundedContextProseTexts(String)}
     */
    public List<String> termProseTexts(String termIri) {
        return literals(Objects.requireNonNull(termIri, "termIri"), DEFINITION);
    }

    /**
     * @return every prose text field of an architecture decision that can carry ubiquitous-language
     *         mentions: its own {@link #NAME}/{@link #ADR_CONTEXT}/{@link #ADR_DECISION} literals,
     *         plus each {@code arkarch:Consequence}'s {@link #CONSEQUENCE_STATEMENT} and each
     *         {@code arkarch:ConsideredOption}'s {@link #NAME}/{@link #OPTION_RATIONALE} - reached via
     *         the two-hop {@link #CONSEQUENCE}/{@link #CONSIDERED_OPTION} edges, the same pattern
     *         {@link #requirementProseTexts(String)} already uses for an acceptance criterion's text.
     *         Used by {@link #unlinkedMentions()}'s fourth sweep (issue #406): a decision's own text
     *         naming a glossary term without the matching {@code arkarch:usesTerm} edge was invisible
     *         to {@code orphan_check} before this, unlike the same gap for a requirement, use case or
     *         bounded context.
     */
    public List<String> adrProseTexts(String adrIri) {
        Objects.requireNonNull(adrIri, "adrIri");
        List<String> texts = new ArrayList<>(literals(adrIri, NAME));
        texts.addAll(literals(adrIri, ADR_CONTEXT));
        texts.addAll(literals(adrIri, ADR_DECISION));
        for (String consequenceIri : resourceObjects(adrIri, CONSEQUENCE)) {
            texts.addAll(literals(consequenceIri, CONSEQUENCE_STATEMENT));
        }
        for (String optionIri : resourceObjects(adrIri, CONSIDERED_OPTION)) {
            texts.addAll(literals(optionIri, NAME));
            texts.addAll(literals(optionIri, OPTION_RATIONALE));
        }
        return texts;
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
     * Every requirement/use-case/bounded-context prose mention of a glossary term the source does
     * not link to: a requirement's {@code dcterms:description}/{@code
     * arkreq:acceptanceCriterion} checked against its {@code arkreq:usesTerm} edges, a use case's
     * {@link #useCaseProseTexts(String)} checked against its own {@code arkreq:usesTerm} edges
     * plus its {@code arkreq:primaryActor}/{@code supportingActor} edges (issue #333 - a goal or
     * step naming the use case's own actor is not an unlinked mention, since that relationship is
     * already recorded, just under a different predicate than {@code usesTerm}), and a
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
     * <p>A fourth sweep (issue #406) checks an architecture decision's {@link
     * #adrProseTexts(String)} against its own {@link #adrUsedTerms(String)} - the {@code
     * arkarch:usesTerm} counterpart to the requirement/use-case sweep above, which only ever
     * traverses the shared {@code arkreq:usesTerm} property an ADR never carries. Reported with the
     * same {@code "usesTerm"} edge-local-name as that sweep: both name the term the source text
     * mentions without the matching link, only the concrete predicate namespace differs.</p>
     *
     * @return the unlinked mentions found across every requirement, use case, bounded context,
     *         term and architecture decision
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
        for (String useCaseIri : useCaseIris()) {
            Set<String> linked = new HashSet<>(usedTerms(useCaseIri));
            linked.addAll(actorsOf(useCaseIri));
            for (String termIri : matcher.mentionedIn(useCaseProseTexts(useCaseIri))) {
                if (!linked.contains(termIri)) {
                    found.add(new UnlinkedMention(useCaseIri, termIri, termLabels.get(termIri), "usesTerm"));
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
        for (String adrIri : adrIris()) {
            Set<String> linked = new HashSet<>(adrUsedTerms(adrIri));
            for (String termIri : matcher.mentionedIn(adrProseTexts(adrIri))) {
                if (!linked.contains(termIri)) {
                    found.add(new UnlinkedMention(adrIri, termIri, termLabels.get(termIri), "usesTerm"));
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
     * {@link #DEPENDENT_EDGE_PREDICATES} backwards, or {@link #FORWARD_DEPENDENT_EDGE_PREDICATES}
     * forwards, from {@code targetIri} - i.e. what is affected when {@code targetIri} changes.
     * Superseding a decision affects its successor either way the edge happens to be typed
     * (kogn-io/arknet#357): a live decision names its own {@code supersededBy}
     * ({@link #FORWARD_DEPENDENT_EDGE_PREDICATES}), while any pre-#357 store-first record still
     * asserting the old {@code arkarch:supersedes} shape is reached backwards, alongside every
     * other {@link #DEPENDENT_EDGE_PREDICATES} member.
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
                addDependent(triple.subject(), frontier, queue, reported);
            }
            for (Triple triple : outgoingBySubject.getOrDefault(current, List.of())) {
                if (!FORWARD_DEPENDENT_EDGE_PREDICATES.contains(triple.predicate())
                        || !(triple.object() instanceof RdfNode.Resource resourceObject)) {
                    continue;
                }
                addDependent(resourceObject.iri(), frontier, queue, reported);
            }
        }
        return List.copyOf(reported);
    }

    /**
     * Adds {@code candidate} to the traversal's frontier/queue/reported set, unless it was already
     * visited - shared by {@link #dependents(String)}'s backward and forward hops so both follow
     * exactly the same "visit once, report once, skip a {@code arkreq:Step}" rule.
     */
    private void addDependent(String candidate, Set<String> frontier, Deque<String> queue, Set<String> reported) {
        if (!frontier.add(candidate)) {
            return;
        }
        queue.add(candidate);
        if (!isType(candidate, STEP_TYPE)) {
            reported.add(candidate);
        }
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
