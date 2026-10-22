// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreResource;
import de.hauschel.arknet.persistence.StoreSnapshot;
import de.hauschel.arknet.persistence.Triple;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;

/**
 * An in-memory directed multigraph over one project's statements, purpose-built for the
 * cross-bounded-context edges the traceability tools traverse:
 * {@code arkreq:usesTerm} (Requirement/UseCase -&gt; Term, issue #329), {@code arkreq:primaryRole}/
 * {@code arkreq:supportingRole} (UseCase -&gt; Role, ADR-37/kogn-io/arknet#405 Part C - formerly
 * {@code arkreq:primaryActor}/{@code supportingActor}, UseCase -&gt; Term/Actor),
 * {@code arkddd:ubiquitousLanguageTerm}
 * (BoundedContext -&gt; Term), {@code arkddd:upstream}/{@code arkddd:downstream} (ContextRelationship
 * -&gt; BoundedContext, issue #293), {@code oslc_rm:constrainedBy} (Requirement/UseCase -&gt;
 * Constraint, issue #223/#329), the four ADR edges
 * {@code arkarch:addressesRequirement} (ADR -&gt; Requirement), {@code arkarch:affectsContext}
 * (ADR -&gt; BoundedContext), {@code arkarch:usesTerm} (ADR -&gt; Term, kogn-io/arknet#393) and
 * {@code arkarch:supersededBy} (ADR -&gt; ADR, written on the
 * superseded decision, issue #69/kogn-io/arknet#357), {@code arkproc:filledBy} (Role -&gt; Actor,
 * ADR-37/kogn-io/arknet#405), and the
 * two-hop {@code arkreq:mainStep}/{@code arkreq:extensionStep} then {@code arkreq:stepRealises}
 * (UseCase -&gt; Step -&gt; Requirement). It also exposes the requirement/use-case/bounded-context/ADR
 * prose ({@code dcterms:description}/{@code arkreq:acceptanceCriterion}, {@link
 * #useCaseProseTexts(String)}, {@code arkddd:domainVision}, {@link #adrProseTexts(String)}, issue
 * #406) that {@code orphan_check}'s unlinked-mention check scans for a glossary term nothing links to.
 * {@code role_usecase_matrix} (ADR-37/kogn-io/arknet#405 Part C, formerly {@code
 * actor_usecase_matrix}) needs the {@code primaryRole}/{@code
 * supportingRole} edges in the <em>forward</em> direction too ({@link #rolesOf(String)}/{@link
 * #useCasesOf(String)}), plus {@code filledBy} in reverse ({@link #rolesFilledBy(String)},
 * kogn-io/arknet#524) for its "Actors" section, and {@code term_cooccurrence} reuses the same
 * prose-scanning idea for a use case's {@code arkreq:useCaseGoal} ({@link
 * #useCaseProseTexts(String)}, issue #108).
 *
 * <p>Built once per read from a {@link StoreSnapshot} - the same generic {@code SELECT ?s ?p
 * ?o} {@link de.hauschel.arknet.persistence.StoreReader} already reads for {@code
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

    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String DCTERMS_DESCRIPTION = "http://purl.org/dc/terms/description";

    // The traversed arkreq: object-property IRIs, the literal-valued arkreq:acceptanceCriterion/
    // arkddd:domainVision predicates and the type IRIs below come from the single shared source
    // of truth (arknet-persistence-support), the very same constants the requirements/
    // bounded-context/ubiquitous-language/use-cases out-adapters serialize them with - so a
    // predicate or type rename cannot silently desync the write side from this read-side
    // traversal.
    private static final String USES_TERM = ArkreqVocabulary.USES_TERM;
    private static final String PRIMARY_ROLE = ArkreqVocabulary.PRIMARY_ROLE;
    private static final String SUPPORTING_ROLE = ArkreqVocabulary.SUPPORTING_ROLE;
    private static final String MAIN_STEP = ArkreqVocabulary.MAIN_STEP;
    private static final String EXTENSION_STEP = ArkreqVocabulary.EXTENSION_STEP;
    private static final String STEP_REALISES = ArkreqVocabulary.STEP_REALISES;
    private static final String ACCEPTANCE_CRITERION = ArkreqVocabulary.ACCEPTANCE_CRITERION;
    private static final String CRITERION_TEXT = ArkreqVocabulary.CRITERION_TEXT;

    /**
     * {@code arkreq:rationale} - a requirement's "why" (kogn-io/arknet#697). Scanned by {@link
     * #unlinkedMentions()} like the description, but as a non-normative field: a term named only
     * here is a hint, not a finding.
     */
    private static final String RATIONALE = ArkreqVocabulary.RATIONALE;
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

    /**
     * The six edges kogn-io/arknet#566's delete guards block on that this read side did not yet
     * follow: {@code arkreq:scopedTo} (Requirement -&gt; BoundedContext) and {@code arkddd:hasContext}
     * (Domain -&gt; BoundedContext) block {@code bc_delete}; {@code oslc_rm:satisfies} (UseCase -&gt;
     * Requirement) and {@code arkreq:dependsOn} (Requirement -&gt; Requirement) block
     * {@code req_delete}; {@code arkreq:includesUseCase}/{@code arkreq:extendsUseCase} (UseCase -&gt;
     * UseCase) block {@code uc_delete}. Only {@code oslc_rm:satisfies} is written by a tool today
     * ({@code uc_add}/{@code uc_update} mint it alongside a step's {@code arkreq:stepRealises}),
     * so that one was a live edge this traversal did not follow, not a precaution. The other five
     * are declared by a shipped ontology or shape all the same, which is precisely the store-first
     * shape the guards exist to catch, so this side has to see them too (see
     * {@link #DEPENDENT_EDGE_PREDICATES}).
     */
    private static final String SCOPED_TO = ArkreqVocabulary.SCOPED_TO;
    private static final String HAS_CONTEXT = ArkdddVocabulary.HAS_CONTEXT;
    private static final String SATISFIES = ArkreqVocabulary.SATISFIES;
    private static final String DEPENDS_ON = ArkreqVocabulary.DEPENDS_ON;
    private static final String INCLUDES_USE_CASE = ArkreqVocabulary.INCLUDES_USE_CASE;
    private static final String EXTENDS_USE_CASE = ArkreqVocabulary.EXTENDS_USE_CASE;

    /** {@code skos:broader} - Term -&gt; its broader (superordinate) Term (issue #252). */
    private static final String BROADER = ArkreqVocabulary.BROADER;

    /**
     * {@code skos:related} - Term -&gt; an associatively related Term (kogn-io/arknet#420). Written
     * in one direction only, so both directions are read wherever this graph asks whether two terms
     * are linked at all.
     */
    private static final String RELATED = ArkreqVocabulary.RELATED;

    /**
     * {@link UnlinkedMention#edgeLocalName()} value for {@link #unlinkedMentions()}'s term-to-term
     * sweep (kogn-io/arknet#595): any of {@code broader}, the reverse (narrower) direction of the
     * same edge, or {@code related} would resolve the finding, so the message names all three
     * instead of prescribing one as the fix.
     */
    private static final String GLOSSARY_EDGE_LOCAL_NAME = "broader/narrower/related";

    /**
     * {@code skos:definition} - a term's meaning, scanned by {@link #unlinkedMentions()}'s third
     * sweep for a mention of another term the source links neither via {@code broader} nor via
     * {@code related} (issue #252, kogn-io/arknet#420).
     */
    private static final String DEFINITION = ArkreqVocabulary.DEFINITION;

    /** {@code oslc_rm:constrainedBy} - Requirement/UseCase -&gt; Constraint (issue #223/#329). */
    private static final String CONSTRAINED_BY = ArkreqVocabulary.CONSTRAINED_BY;

    // The four arkarch: edges an architecture decision owns (issue #69, kogn-io/arknet#393). Unlike
    // ArkreqVocabulary, whose scope is deliberately the cross-module subset only, ArkarchVocabulary
    // mirrors its whole (ADR-only) ontology module (the same "whole module" pattern ArkdddVocabulary
    // joined with kogn-io/arknet#148) - so these come from the same shared source the ADR
    // out-adapter serializes them with, and a rename cannot silently desync the two sides.
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

    // arkddd:ubiquitousLanguageTerm moved to ArkdddVocabulary#UBIQUITOUS_LANGUAGE_TERM with issue
    // #335, once KognioRdfTermRepository's reference check became a third reader. Since
    // kogn-io/arknet#148, ArkdddVocabulary mirrors the whole arknet-ddd.ttl module - the class
    // javadoc's earlier claim that arkddd:BoundedContext "stays local" turned out to be false, once
    // KognioRdfAdrRepository/KognioRdfBoundedContextLookup/KognioRdfContextRelationshipRepository
    // each turned out to already carry their own private copy of the exact same type IRI.
    private static final String UBIQUITOUS_LANGUAGE_TERM = ArkdddVocabulary.UBIQUITOUS_LANGUAGE_TERM;

    /**
     * {@code arkddd:delimitsTerm} - BoundedContext -&gt; a term it names only to draw its boundary
     * against it. An impact edge like {@link #UBIQUITOUS_LANGUAGE_TERM}: the context's prose names
     * the term, so a change to the term reaches it.
     */
    private static final String DELIMITS_TERM = ArkdddVocabulary.DELIMITS_TERM;

    private static final String FUNCTIONAL_REQUIREMENT_TYPE = ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE;
    private static final String NON_FUNCTIONAL_REQUIREMENT_TYPE = ArkreqVocabulary.NON_FUNCTIONAL_REQUIREMENT_TYPE;
    private static final String USE_CASE_TYPE = ArkreqVocabulary.USE_CASE_TYPE;
    private static final String STEP_TYPE = ArkreqVocabulary.STEP_TYPE;
    private static final String CONCEPT_TYPE = ArkreqVocabulary.CONCEPT_TYPE;
    private static final String BOUNDED_CONTEXT_TYPE = ArkdddVocabulary.BOUNDED_CONTEXT_TYPE;

    // The three arkreq: Constraint subtypes (issue #223) - unlike arkreq:Requirement's two
    // subtypes, there is no abstract arkreq:Constraint type triple to check against: this
    // adapter's out-adapter always writes one of the three concrete subtypes, mirroring
    // FUNCTIONAL_REQUIREMENT_TYPE/NON_FUNCTIONAL_REQUIREMENT_TYPE above.
    private static final String TECHNICAL_CONSTRAINT_TYPE = ArkreqVocabulary.TECHNICAL_CONSTRAINT_TYPE;
    private static final String BUSINESS_CONSTRAINT_TYPE = ArkreqVocabulary.BUSINESS_CONSTRAINT_TYPE;
    private static final String REGULATORY_CONSTRAINT_TYPE = ArkreqVocabulary.REGULATORY_CONSTRAINT_TYPE;

    // arkproc:HumanActor/SystemActor/LegalActor/GroupActor come from ArkprocVocabulary
    // (kogn-io/arknet#148): before that class existed, these four type IRIs were each an
    // adapter-private copy duplicated across this class, arknet-actor's KognioRdfActorRepository
    // (the writer) and arknet-use-cases' KognioRdfActorLookup. This class still stays free of any
    // dependency on either out-adapter (see the class javadoc) - only the shared, technology-neutral
    // IRI constants are pulled in.
    private static final String HUMAN_ACTOR_TYPE = ArkprocVocabulary.HUMAN_ACTOR_TYPE;
    private static final String SYSTEM_ACTOR_TYPE = ArkprocVocabulary.SYSTEM_ACTOR_TYPE;
    private static final String LEGAL_ACTOR_TYPE = ArkprocVocabulary.LEGAL_ACTOR_TYPE;
    private static final String GROUP_ACTOR_TYPE = ArkprocVocabulary.GROUP_ACTOR_TYPE;

    /**
     * {@code arkproc:Role} (ADR-37/kogn-io/arknet#405 Part B/C) - the matrix row type
     * {@link #roleIris()} collects, since {@code arkreq:primaryRole}/{@code supportingRole}
     * repointed the use-case edges at this type instead of {@link #HUMAN_ACTOR_TYPE} and its three
     * siblings.
     */
    private static final String ROLE_TYPE = ArkprocVocabulary.ROLE_TYPE;

    /**
     * {@code arkproc:filledBy} - Role -&gt; Actor (ADR-37/kogn-io/arknet#405), the same shared
     * constant {@code KognioRdfRoleRepository} serialises the occupancy edge with.
     */
    private static final String FILLED_BY = ArkprocVocabulary.FILLED_BY;

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
     * follow-up if ever needed. {@code arkproc:filledBy} (Role -&gt; Actor, ADR-37/
     * kogn-io/arknet#405) joins the set backwards for the same reason and with the same
     * consistency obligation issue #293 taught: {@code KognioRdfActorRepository}'s delete guard
     * refuses to remove an actor a role still occupies, so leaving the edge out here would let
     * {@code impact_analysis} answer "nothing depends on this" for exactly the actor
     * {@code actor_delete} then refuses to delete - the one tool asked "what breaks if I change
     * this" contradicting the one that enforces the answer.
     *
     * <p>{@code arkreq:scopedTo}, {@code arkddd:hasContext}, {@code oslc_rm:satisfies},
     * {@code arkreq:dependsOn}, {@code arkreq:includesUseCase} and {@code arkreq:extendsUseCase}
     * (kogn-io/arknet#566) join under exactly that obligation: each one blocks
     * {@code bc_delete}/{@code req_delete}/{@code uc_delete}, so leaving any of them out would
     * reproduce the {@code filledBy} contradiction six times over - {@code impact_analysis}
     * answering "nothing depends on this" for a store-first-referenced use case that
     * {@code uc_delete} then refuses to delete. ({@code orphan_check} is untouched by this set: it
     * reads its own dedicated checks through {@link #isCarriedByUseCase(String)} (which follows
     * {@code oslc_rm:satisfies} and nothing else of this set), {@link #isReferencedTerm(String)} and {@link #isConstraintReferenced(String)}, and reports no
     * use-case or bounded-context orphans at all.) The {@code relatedTo} exception does not
     * cover the two use-case edges: it rests on that edge being <em>symmetric</em>, which turns a
     * "see also" cluster into a single reachable blob, while {@code includesUseCase}/
     * {@code extendsUseCase} are directed - the including use case is reported as affected by the
     * included one, not the other way round, so a chain stays a chain. {@code
     * KognioRdf*Repository#REFERENCING_PREDICATES} is kept in step with this set mechanically by
     * {@code ImpactAnalysisFollowsEveryDeleteGuardEdgeTest} in arknet-architecture-tests, so the
     * next guard added cannot quietly drop out here.
     */
    private static final Set<String> DEPENDENT_EDGE_PREDICATES = Set.of(
            USES_TERM, PRIMARY_ROLE, SUPPORTING_ROLE, STEP_REALISES, MAIN_STEP, EXTENSION_STEP,
            UBIQUITOUS_LANGUAGE_TERM, DELIMITS_TERM, UPSTREAM, DOWNSTREAM, ADDRESSES_REQUIREMENT, AFFECTS_CONTEXT,
            ADR_USES_TERM, CONSTRAINED_BY, SUPERSEDES, FILLED_BY,
            SCOPED_TO, HAS_CONTEXT, SATISFIES, DEPENDS_ON, INCLUDES_USE_CASE, EXTENDS_USE_CASE);

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

    /**
     * How a term label's letter case takes part in the mention scan, derived from the display
     * language this graph was built under: German prose keeps a label's leading capital
     * significant ({@code soll} is not the term {@code Soll}), every other language ignores case
     * (kogn-io/arknet#696). The same locale that selects which label variant is matched also
     * selects the casing rule, so both decisions follow the project's language together.
     */
    private final LabelMentions.Casing casing;

    private TraceabilityGraph(List<StoreResource> resources, DisplayLocale displayLocale) {
        this.casing = LabelMentions.Casing.forLocale(displayLocale.requested());
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
     *         so this list and {@link #actorIris()}/{@link #roleIris()} are disjoint unless a
     *         resource happens to be both a registered actor/role and a separately registered
     *         glossary term (multi-typing is still legal, just no longer the only way to be an
     *         actor).
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

    /**
     * Whether a use case carries the requirement: some use case binds it through {@code
     * oslc_rm:satisfies}, or some step realises it (see {@link #realisingUseCases(String)}). The
     * {@code satisfies} edge counts on its own because it is the edge {@code req_delete} refuses
     * to break; a requirement it binds is not an orphan even when no step realises it.
     *
     * @return {@code true} if a use case satisfies or realises the requirement
     */
    public boolean isCarriedByUseCase(String requirementIri) {
        Objects.requireNonNull(requirementIri, "requirementIri");
        return incomingByObject.getOrDefault(requirementIri, List.of()).stream()
                .anyMatch(t -> SATISFIES.equals(t.predicate()))
                || !realisingUseCases(requirementIri).isEmpty();
    }

    /** @return the IRIs of every {@code arkreq:UseCase}, sorted. */
    public List<String> useCaseIris() {
        return subjectsOfType(USE_CASE_TYPE);
    }

    /**
     * @return the IRIs of every {@code arkproc:HumanActor}/{@code SystemActor}/{@code LegalActor}/
     *         {@code GroupActor} in the project, sorted - independent of which named graph it
     *         lives in: since issue #336 an actor lives in {@code arknet-actor}'s own register
     *         graph rather than the ubiquitous-language graph, but this traversal is graph-agnostic
     *         (it indexes {@link StoreSnapshot}'s resources by type, never by graph), so it finds
     *         the register's actors the same way it always found the old term-facetted ones. Since
     *         ADR-37/kogn-io/arknet#405 Part C no use case references an actor directly any more -
     *         see {@link #roleIris()} for the type a use case's {@code primaryRole}/
     *         {@code supportingRole} now targets.
     */
    public List<String> actorIris() {
        return subjectsOfType(HUMAN_ACTOR_TYPE, SYSTEM_ACTOR_TYPE, LEGAL_ACTOR_TYPE, GROUP_ACTOR_TYPE);
    }

    /**
     * @return the IRIs of every {@code arkproc:Role} in the project, sorted - independent of
     *         whether any use case references it via {@code arkreq:primaryRole}/{@code
     *         supportingRole} (ADR-37/kogn-io/arknet#405 Part C, formerly {@link #actorIris()}'s
     *         four concrete Actor types before this edge repointed at {@code arkproc:Role}).
     *         {@code role_usecase_matrix}'s "Roles" section unions this with
     *         {@link #rolesOf(String)}'s results so a role nobody's use case references yet still
     *         appears, instead of silently disappearing from a matrix whose own tool description
     *         promises "for every role" (issue #147).
     */
    public List<String> roleIris() {
        return subjectsOfType(ROLE_TYPE);
    }

    /**
     * The role(s) a use case references: its {@code arkreq:primaryRole} plus every
     * {@code arkreq:supportingRole} - the forward direction of the same two edges
     * {@link #DEPENDENT_EDGE_PREDICATES} already traverses backwards for {@link #dependents(String)}
     * (issue #108; ADR-37/kogn-io/arknet#405 Part C repointed both edges from {@code arkproc:Actor}
     * at {@code arkproc:Role}).
     *
     * @return the role IRIs, sorted, deduplicated
     */
    public List<String> rolesOf(String useCaseIri) {
        Objects.requireNonNull(useCaseIri, "useCaseIri");
        return outgoingBySubject.getOrDefault(useCaseIri, List.of()).stream()
                .filter(t -> PRIMARY_ROLE.equals(t.predicate()) || SUPPORTING_ROLE.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(o -> ((RdfNode.Resource) o).iri())
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The use case(s) a role is played in, as primary or supporting role - the reverse
     * lookup of {@link #rolesOf(String)} (issue #108).
     *
     * @return the use-case IRIs, sorted, deduplicated
     */
    public List<String> useCasesOf(String roleIri) {
        Objects.requireNonNull(roleIri, "roleIri");
        return incomingByObject.getOrDefault(roleIri, List.of()).stream()
                .filter(t -> PRIMARY_ROLE.equals(t.predicate()) || SUPPORTING_ROLE.equals(t.predicate()))
                .map(Triple::subject)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * The role(s) occupying an actor via {@code arkproc:filledBy} - the reverse lookup of that
     * edge (Role -&gt; filledBy -&gt; Actor), mirroring {@link #useCasesOf(String)}'s reverse
     * lookup of {@code primaryRole}/{@code supportingRole}. Since ADR-37/kogn-io/arknet#405 Part B
     * a Role and an Actor are separate resources - {@code filledBy} is the only edge connecting a
     * specification-side role to the carrier(s) that occupy it, so {@code role_usecase_matrix}'s
     * "Actors" section uses this to report each actor's roles the same way its "Roles" section
     * reports each role's use cases (kogn-io/arknet#524 review finding: {@link #actorIris()} had
     * no production caller left after the Part C role cut - this method re-wires it instead of
     * deleting it, keeping the issue #147 promise "what nothing references does not silently
     * vanish from the inventory" for actors too).
     *
     * @return the role IRIs occupying {@code actorIri}, sorted, deduplicated - empty if no role
     *         names it
     */
    public List<String> rolesFilledBy(String actorIri) {
        Objects.requireNonNull(actorIri, "actorIri");
        return incomingByObject.getOrDefault(actorIri, List.of()).stream()
                .filter(t -> FILLED_BY.equals(t.predicate()))
                .map(Triple::subject)
                .distinct()
                .sorted()
                .toList();
    }

    /**
     * @return {@code true} if a term is used by a requirement or a use case
     *         ({@code arkreq:usesTerm}, issue #329), by an architecture decision
     *         ({@code arkarch:usesTerm}, kogn-io/arknet#393), is a bounded context's
     *         ubiquitous language ({@code arkddd:ubiquitousLanguageTerm}), is another term's
     *         broader (superordinate) term ({@code skos:broader}, issue #252) - an interior/root
     *         taxonomy term stops being reported as an orphan once something is hung under it - or
     *         is another term's associatively related peer ({@code skos:related},
     *         kogn-io/arknet#420)
     *
     *         <p>Only the incoming direction counts, for {@code skos:related} as much as for every
     *         other predicate here: a term that merely names peers of its own has still not been
     *         woven into the model by anything else, which is exactly what "orphan" means. The
     *         symmetric peer at the other end of that edge does get credit for it - which is the
     *         whole point of reading incoming edges.</p>
     *
     *         <p>No longer checks {@code arkreq:primaryRole}/{@code supportingRole}: since issue
     *         #336 an actor/role is not a glossary term, and since ADR-37/kogn-io/arknet#405 Part C
     *         these two edges target {@code arkproc:Role} rather than a term-facetted resource, so
     *         an incoming edge of either predicate on a {@code skos:Concept} cannot occur under a
     *         well-formed write path; the pre-#336 compatibility branch this method carried for a
     *         term-facetted actor is removed along with the edge it checked for (Vorentscheidung
     *         2), not merely renamed.</p>
     */
    public boolean isReferencedTerm(String termIri) {
        Objects.requireNonNull(termIri, "termIri");
        return incomingByObject.getOrDefault(termIri, List.of()).stream()
                .anyMatch(t -> USES_TERM.equals(t.predicate()) || ADR_USES_TERM.equals(t.predicate())
                        || UBIQUITOUS_LANGUAGE_TERM.equals(t.predicate())
                        || BROADER.equals(t.predicate())
                        || RELATED.equals(t.predicate()));
    }

    /**
     * @return the IRIs of every term this one is associatively related to, in either direction
     *         ({@code skos:related}, kogn-io/arknet#420) - the relation is symmetric and written
     *         once, so a caller asking "are these two linked" must look both ways or it would see
     *         the link from one end only
     */
    public Set<String> relatedTerms(String termIri) {
        Objects.requireNonNull(termIri, "termIri");
        Set<String> peers = new HashSet<>();
        outgoingBySubject.getOrDefault(termIri, List.of()).stream()
                .filter(t -> RELATED.equals(t.predicate()))
                .map(Triple::object)
                .filter(RdfNode.Resource.class::isInstance)
                .map(o -> ((RdfNode.Resource) o).iri())
                .forEach(peers::add);
        incomingByObject.getOrDefault(termIri, List.of()).stream()
                .filter(t -> RELATED.equals(t.predicate()))
                .map(Triple::subject)
                .forEach(peers::add);
        return peers;
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
     * @return the IRIs of every term that names this one as its own {@link #broaderTerm(String)}
     *         (the reverse {@code skos:broader} direction, kogn-io/arknet#595) - {@link
     *         #broaderTerm(String)} itself only ever looks outward from a term to its superordinate,
     *         so a term's own subordinates need this separate, incoming-only lookup
     */
    public Set<String> narrowerTerms(String termIri) {
        Objects.requireNonNull(termIri, "termIri");
        Set<String> subordinates = new HashSet<>();
        incomingByObject.getOrDefault(termIri, List.of()).stream()
                .filter(t -> BROADER.equals(t.predicate()))
                .map(Triple::subject)
                .forEach(subordinates::add);
        return subordinates;
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
        return textsOf(requirementProse(requirementIri));
    }

    /**
     * {@link #requirementProseTexts(String)} with each text's field kept (kogn-io/arknet#697):
     * the {@code dcterms:description} is the requirement's normative text; its {@code
     * arkreq:rationale} and every acceptance criterion's text are not - they explain and
     * exemplify the requirement rather than state it, so a glossary term named only there is a
     * reading hint rather than a missing edge. The rationale joins the scan here for the first
     * time, as a non-normative field from the start.
     *
     * @return the requirement's prose fields, description first
     */
    public List<ProseText> requirementProse(String requirementIri) {
        Objects.requireNonNull(requirementIri, "requirementIri");
        List<ProseText> texts = new ArrayList<>();
        addProse(texts, requirementIri, DCTERMS_DESCRIPTION, true);
        addProse(texts, requirementIri, RATIONALE, false);
        for (String criterionIri : resourceObjects(requirementIri, ACCEPTANCE_CRITERION)) {
            addProse(texts, criterionIri, CRITERION_TEXT, StoreResource.localName(ACCEPTANCE_CRITERION), false);
        }
        return List.copyOf(texts);
    }

    /**
     * @return the {@code arkddd:domainVision} text of a bounded context, for scanning it for
     *         unlinked glossary mentions
     */
    public List<String> boundedContextProseTexts(String boundedContextIri) {
        return textsOf(boundedContextProse(boundedContextIri));
    }

    /** {@link #boundedContextProseTexts(String)} with the field kept; the vision is normative text. */
    public List<ProseText> boundedContextProse(String boundedContextIri) {
        Objects.requireNonNull(boundedContextIri, "boundedContextIri");
        List<ProseText> texts = new ArrayList<>();
        addProse(texts, boundedContextIri, DOMAIN_VISION, true);
        return List.copyOf(texts);
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
        return textsOf(useCaseProse(useCaseIri));
    }

    /**
     * {@link #useCaseProseTexts(String)} with each text's field kept. Every use-case field is
     * normative - a goal, a scope, a trigger, a condition or a step all state what the use case
     * does; a step's text is reported under its owning edge ({@code mainStep}/{@code
     * extensionStep}), the same way an acceptance criterion is reported under {@code
     * acceptanceCriterion}.
     *
     * @return the use case's prose fields, in the order {@link #useCaseProseTexts(String)} lists them
     */
    public List<ProseText> useCaseProse(String useCaseIri) {
        Objects.requireNonNull(useCaseIri, "useCaseIri");
        List<ProseText> texts = new ArrayList<>();
        addProse(texts, useCaseIri, USE_CASE_GOAL, true);
        addProse(texts, useCaseIri, DESIGN_SCOPE, true);
        addProse(texts, useCaseIri, TRIGGER, true);
        addProse(texts, useCaseIri, USE_CASE_PRECONDITION, true);
        addProse(texts, useCaseIri, USE_CASE_POSTCONDITION, true);
        for (String stepIri : resourceObjects(useCaseIri, MAIN_STEP)) {
            addProse(texts, stepIri, STEP_TEXT, StoreResource.localName(MAIN_STEP), true);
        }
        for (String stepIri : resourceObjects(useCaseIri, EXTENSION_STEP)) {
            addProse(texts, stepIri, STEP_TEXT, StoreResource.localName(EXTENSION_STEP), true);
        }
        return List.copyOf(texts);
    }

    /**
     * @return the {@code skos:definition} text of a term, for scanning it for unlinked mentions of
     *         other glossary terms (issue #252) - mirrors {@link #boundedContextProseTexts(String)}
     */
    public List<String> termProseTexts(String termIri) {
        return textsOf(termProse(termIri));
    }

    /** {@link #termProseTexts(String)} with the field kept; a definition is normative text. */
    public List<ProseText> termProse(String termIri) {
        Objects.requireNonNull(termIri, "termIri");
        List<ProseText> texts = new ArrayList<>();
        addProse(texts, termIri, DEFINITION, true);
        return List.copyOf(texts);
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
        return textsOf(adrProse(adrIri));
    }

    /**
     * {@link #adrProseTexts(String)} with each text's field kept. Every decision field is
     * normative - the record exists to state a decision and its consequences, and a considered
     * option's name and rationale are part of that statement, not an aside to it; the two-hop
     * texts are reported under their owning edge ({@code consequence}/{@code consideredOption}).
     *
     * @return the decision's prose fields, in the order {@link #adrProseTexts(String)} lists them
     */
    public List<ProseText> adrProse(String adrIri) {
        Objects.requireNonNull(adrIri, "adrIri");
        List<ProseText> texts = new ArrayList<>();
        addProse(texts, adrIri, NAME, true);
        addProse(texts, adrIri, ADR_CONTEXT, true);
        addProse(texts, adrIri, ADR_DECISION, true);
        for (String consequenceIri : resourceObjects(adrIri, CONSEQUENCE)) {
            addProse(texts, consequenceIri, CONSEQUENCE_STATEMENT, StoreResource.localName(CONSEQUENCE), true);
        }
        for (String optionIri : resourceObjects(adrIri, CONSIDERED_OPTION)) {
            addProse(texts, optionIri, NAME, StoreResource.localName(CONSIDERED_OPTION), true);
            addProse(texts, optionIri, OPTION_RATIONALE, StoreResource.localName(CONSIDERED_OPTION), true);
        }
        return List.copyOf(texts);
    }

    /**
     * One prose literal of a resource together with the field it was read from (kogn-io/arknet#697).
     *
     * @param field     the local name of the predicate the literal sits under or, for a text read
     *                  two hops away on an owned sub-resource (an acceptance criterion, a step, a
     *                  consequence, a considered option), the local name of the owning edge - the
     *                  same naming {@code store_check LANGUAGE} uses for such a child
     * @param normative whether the field states what the resource is or does (a description, a
     *                  step, a decision), as opposed to explaining or exemplifying it (a
     *                  rationale, an acceptance criterion) - a glossary term named only in a
     *                  non-normative field is reported as a hint, not as a missing edge
     * @param text      the literal's lexical form
     */
    public record ProseText(String field, boolean normative, String text) {
        public ProseText {
            Objects.requireNonNull(field, "field");
            Objects.requireNonNull(text, "text");
        }
    }

    private static List<String> textsOf(List<ProseText> prose) {
        return prose.stream().map(ProseText::text).toList();
    }

    private void addProse(List<ProseText> into, String subject, String predicate, boolean normative) {
        addProse(into, subject, predicate, StoreResource.localName(predicate), normative);
    }

    private void addProse(List<ProseText> into, String subject, String predicate, String field, boolean normative) {
        for (String text : literals(subject, predicate)) {
            into.add(new ProseText(field, normative, text));
        }
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
     * plus its {@code arkreq:primaryRole}/{@code supportingRole} edges (issue #333 - a goal or
     * step naming the use case's own role is not an unlinked mention, since that relationship is
     * already recorded, just under a different predicate than {@code usesTerm}), and a
     * bounded context's {@code arkddd:domainVision} checked against its {@code
     * arkddd:ubiquitousLanguageTerm} edges. Reuses the very matching rules the HTML report
     * already applies inline ({@code de.hauschel.arknet.mcp.report.Glossary}), via the shared
     * {@link LabelMentions} engine, so a text mention means the same thing in both places.
     *
     * <p><strong>How far the role exemption actually reaches</strong> (kogn-io/arknet#524
     * review): {@link #rolesOf(String)} yields ROLE IRIs, and they are compared against the TERM
     * IRIs {@code LabelMentions} reports - so the exemption can only ever fire where one and the
     * same IRI is typed both {@code skos:Concept} and {@code arkproc:Role}. Under a well-formed
     * write path that does not happen: {@code role_add} mints a role's own identity, and ADR-37
     * keeps role and glossary term apart as separate resources. A project that names a role in a
     * goal text and keeps a same-named term therefore still gets that mention reported as
     * unlinked. Stated here rather than quietly widened: making the exemption reach a same-named
     * term would mean resolving a role to its term (by {@code filledBy}, or by label), which is a
     * modelling decision, not a rendering detail. The wording above dates from issue #333, when
     * {@code primaryActor} still pointed at a term-facetted actor and the two sets did overlap;
     * issue #336 ended that, and the role split renamed the edge without restoring it.</p>
     *
     * <p>Two same-length competing labels break their matching tie by business code ({@link
     * #identifierOf(String)}), the same key {@code Glossary} sorts its terms by before handing
     * them to the identical engine - not by term IRI, which would let the two matching passes
     * pick different terms for the same ambiguous mention (issue #141).</p>
     *
     * <p>A third sweep (issue #252) scans every term's own {@code skos:definition} for a mention
     * of some <em>other</em> glossary term: a taxonomy term's prose commonly names its
     * superordinate ("A Human Actor is an Actor who ...") or one of its own subordinates without
     * that being an accident the way an unrelated mention in a requirement/bounded-context prose
     * is - so a mentioned term counts as unlinked here only when this term links it neither as its
     * own broader term ({@link #broaderTerm(String)}) nor as one of its own narrower terms (the
     * reverse {@code skos:broader} direction, kogn-io/arknet#595) nor as an associatively related
     * peer ({@link #relatedTerms(String)}, kogn-io/arknet#420; read in both directions, since only
     * one is ever asserted). A term's mention of its own label within its own definition is never
     * reported (a term cannot be its own broader term anyway, see
     * {@link de.hauschel.arknet.ul.domain.TermCycleException}).</p>
     *
     * <p>A fourth sweep (issue #406) checks an architecture decision's {@link
     * #adrProseTexts(String)} against its own {@link #adrUsedTerms(String)} - the {@code
     * arkarch:usesTerm} counterpart to the requirement/use-case sweep above, which only ever
     * traverses the shared {@code arkreq:usesTerm} property an ADR never carries. Reported with the
     * same {@code "usesTerm"} edge-local-name as that sweep: both name the term the source text
     * mentions without the matching link, only the concrete predicate namespace differs.</p>
     *
     * <p>Since kogn-io/arknet#697 every mention carries the fields it was found in, and a pair
     * (source, term) is <em>demoted</em> - still returned, but with a {@link Demotion} instead of
     * counting as a finding - in two cases: every hit lies in a non-normative field (a
     * requirement's rationale or acceptance criterion, see {@link #requirementProse(String)}), or
     * every hit in a normative field stands right next to a business code naming another resource
     * that already carries the edge to the term ("Kontoauszug (FR-3)", "laut FR-3 ... Kontoauszug"
     * - see {@link #MAX_REFERENCE_GAP_WORDS}): the term then belongs to the referenced resource,
     * not to the one quoting it. Demoted rather than dropped, because a description can name the
     * term in an inflected form the literal matcher misses while a criterion names it verbatim -
     * the one hit the scan sees is then in the weak field, but the gap is real.</p>
     *
     * @return the unlinked mentions found across every requirement, use case, bounded context,
     *         term and architecture decision - findings and demoted mentions alike, told apart by
     *         {@link UnlinkedMention#isFinding()}
     */
    public List<UnlinkedMention> unlinkedMentions() {
        Map<String, String> termLabels = termLabels();
        if (termLabels.isEmpty()) {
            return List.of();
        }
        List<String> termIris = termLabels.keySet().stream()
                .sorted(Comparator.comparing(iri -> identifierOf(iri).orElse(iri)))
                .toList();
        LabelMentions<String> matcher = LabelMentions.of(termIris, termLabels::get, casing);
        LabelMentions<String> codes = codeMatcher();

        List<UnlinkedMention> found = new ArrayList<>();
        for (String requirementIri : requirementIris()) {
            found.addAll(sweep(requirementIri, requirementProse(requirementIri),
                    new HashSet<>(usedTerms(requirementIri)), "usesTerm", matcher, codes, termLabels));
        }
        for (String useCaseIri : useCaseIris()) {
            Set<String> linked = new HashSet<>(usedTerms(useCaseIri));
            linked.addAll(rolesOf(useCaseIri));
            found.addAll(sweep(useCaseIri, useCaseProse(useCaseIri), linked, "usesTerm", matcher, codes, termLabels));
        }
        for (String boundedContextIri : boundedContextIris()) {
            found.addAll(sweep(boundedContextIri, boundedContextProse(boundedContextIri),
                    new HashSet<>(linkedTerms(boundedContextIri)), "ubiquitousLanguageTerm", matcher, codes, termLabels));
        }
        for (String termIri : termIris) {
            Set<String> linked = new HashSet<>(relatedTerms(termIri));
            broaderTerm(termIri).ifPresent(linked::add);
            linked.addAll(narrowerTerms(termIri));
            // A term's mention of its own label within its own definition is never reported.
            linked.add(termIri);
            found.addAll(sweep(termIri, termProse(termIri), linked, GLOSSARY_EDGE_LOCAL_NAME, matcher, codes, termLabels));
        }
        for (String adrIri : adrIris()) {
            found.addAll(sweep(adrIri, adrProse(adrIri),
                    new HashSet<>(adrUsedTerms(adrIri)), "usesTerm", matcher, codes, termLabels));
        }
        return List.copyOf(found);
    }

    /**
     * How many words may stand between a term mention and a business code for the code to count
     * as the reference the mention belongs to (kogn-io/arknet#697): zero for "Kontoauszug (FR-3)",
     * one for "Kontoauszug laut FR-3", two for "laut FR-3 muss der Kontoauszug". A sentence
     * boundary ({@code .;!?} or a line break) between the two always breaks the adjacency, however
     * few words lie between them.
     */
    static final int MAX_REFERENCE_GAP_WORDS = 2;

    private static final Pattern SENTENCE_BOUNDARY = Pattern.compile("[.;!?\\n\\r]");
    private static final Pattern NON_WORD = Pattern.compile("[^\\p{L}\\p{N}]+");

    /**
     * One sweep over one source's prose: every unlinked term it mentions, with the fields the
     * mentions were found in and the demotion the hits earn, if any.
     */
    private List<UnlinkedMention> sweep(
            String sourceIri, List<ProseText> prose, Set<String> linked, String edgeLocalName,
            LabelMentions<String> terms, LabelMentions<String> codes, Map<String, String> termLabels) {
        Map<String, Tally> tallies = new LinkedHashMap<>();
        for (ProseText text : prose) {
            List<LabelMentions.Mention<String>> codeHits = null;
            for (LabelMentions.Mention<String> hit : terms.in(text.text())) {
                String termIri = hit.item();
                if (linked.contains(termIri)) {
                    continue;
                }
                Tally tally = tallies.computeIfAbsent(termIri, iri -> new Tally());
                tally.fields.add(text.field());
                if (!text.normative()) {
                    continue;
                }
                tally.normativeHit = true;
                if (codeHits == null) {
                    codeHits = codes.in(text.text());
                }
                Optional<String> reference = linkingReferenceNextTo(hit, codeHits, text.text(), sourceIri, termIri);
                if (reference.isPresent()) {
                    tally.references.add(reference.get());
                } else {
                    tally.unreferencedNormativeHit = true;
                }
            }
        }
        List<UnlinkedMention> out = new ArrayList<>();
        tallies.forEach((termIri, tally) -> out.add(new UnlinkedMention(
                sourceIri, termIri, termLabels.get(termIri), edgeLocalName, List.copyOf(tally.fields), tally.demotion())));
        return out;
    }

    /** What one source's prose says about one term, accumulated across its fields. */
    private static final class Tally {
        final Set<String> fields = new LinkedHashSet<>();
        final Set<String> references = new LinkedHashSet<>();
        boolean normativeHit;
        boolean unreferencedNormativeHit;

        Demotion demotion() {
            if (unreferencedNormativeHit) {
                return null;
            }
            if (!normativeHit) {
                return new Demotion.NonNormativeFieldsOnly();
            }
            return new Demotion.NextToReference(List.copyOf(references));
        }
    }

    /**
     * A matcher over every business code ({@code dcterms:identifier}) in the project, mapping a
     * code found in prose back to the resource carrying it - the "Begriff (FR-3)" half of the
     * reference rule. Case-insensitive like {@code CodeReferences} in the HTML report.
     */
    private LabelMentions<String> codeMatcher() {
        List<String> subjects = identifierBySubject.keySet().stream()
                .sorted(Comparator.comparing(identifierBySubject::get))
                .toList();
        return LabelMentions.of(subjects, identifierBySubject::get);
    }

    /**
     * @return the IRI of a resource whose code stands next to {@code termHit} in {@code text} and
     *         which already links {@code termIri} - never the source itself
     */
    private Optional<String> linkingReferenceNextTo(
            LabelMentions.Mention<String> termHit, List<LabelMentions.Mention<String>> codeHits,
            String text, String sourceIri, String termIri) {
        for (LabelMentions.Mention<String> codeHit : codeHits) {
            if (codeHit.item().equals(sourceIri) || !adjacent(text, termHit, codeHit)) {
                continue;
            }
            if (termsLinkedBy(codeHit.item()).contains(termIri)) {
                return Optional.of(codeHit.item());
            }
        }
        return Optional.empty();
    }

    /** Whether at most {@link #MAX_REFERENCE_GAP_WORDS} words and no sentence boundary separate the two hits. */
    private static boolean adjacent(
            String text, LabelMentions.Mention<String> a, LabelMentions.Mention<String> b) {
        String gap;
        if (a.end() <= b.start()) {
            gap = text.substring(a.end(), b.start());
        } else if (b.end() <= a.start()) {
            gap = text.substring(b.end(), a.start());
        } else {
            return false;
        }
        if (SENTENCE_BOUNDARY.matcher(gap).find()) {
            return false;
        }
        long words = NON_WORD.splitAsStream(gap).filter(token -> !token.isEmpty()).count();
        return words <= MAX_REFERENCE_GAP_WORDS;
    }

    /**
     * @return every term {@code iri} links through an edge the mention scan accepts as "carries
     *         the term": {@code arkreq:usesTerm} (requirement, use case), {@code arkarch:usesTerm}
     *         (decision), {@code arkddd:ubiquitousLanguageTerm}/{@code delimitsTerm} (bounded
     *         context) - the union the reference rule checks on a referenced resource
     */
    private Set<String> termsLinkedBy(String iri) {
        Set<String> linked = new HashSet<>();
        for (Triple triple : outgoingBySubject.getOrDefault(iri, List.of())) {
            String predicate = triple.predicate();
            if ((USES_TERM.equals(predicate) || ADR_USES_TERM.equals(predicate)
                    || UBIQUITOUS_LANGUAGE_TERM.equals(predicate) || DELIMITS_TERM.equals(predicate))
                    && triple.object() instanceof RdfNode.Resource resource) {
                linked.add(resource.iri());
            }
        }
        return linked;
    }

    /**
     * Why a mention is returned by {@link #unlinkedMentions()} without counting as a finding
     * (kogn-io/arknet#697).
     */
    public sealed interface Demotion {

        /**
         * Every hit lies in a non-normative field ({@link ProseText#normative()} false): the term
         * is named only in a rationale or an acceptance criterion.
         */
        record NonNormativeFieldsOnly() implements Demotion {
        }

        /**
         * Every hit in a normative field stands next to a business code naming another resource
         * that already carries the edge to the term.
         *
         * @param referencedIris the resources those codes name, in first-appearance order
         */
        record NextToReference(List<String> referencedIris) implements Demotion {
            public NextToReference {
                referencedIris = List.copyOf(referencedIris);
            }
        }
    }

    /**
     * @param sourceIri     the requirement, use case, bounded context, term or decision whose
     *                      prose names the term
     * @param termIri       the mentioned term
     * @param termLabel     the term's {@code skos:prefLabel}, as named in the prose
     * @param edgeLocalName the missing edge's local name ({@code usesTerm},
     *                      {@code ubiquitousLanguageTerm} or, for the term-to-term sweep,
     *                      {@code broader/narrower/related}), for the "no ... edge" message; the
     *                      term-to-term value names every glossary edge kind that would resolve the
     *                      finding rather than prescribing one (kogn-io/arknet#595)
     * @param fields        the fields the term was found in ({@link ProseText#field()}), in
     *                      first-appearance order, never empty
     * @param demotion      {@code null} for a finding; otherwise why this mention does not count
     *                      as one (kogn-io/arknet#697)
     */
    public record UnlinkedMention(
            String sourceIri, String termIri, String termLabel, String edgeLocalName,
            List<String> fields, Demotion demotion) {

        public UnlinkedMention {
            fields = List.copyOf(fields);
        }

        /** @return whether this mention counts as a finding rather than a hint */
        public boolean isFinding() {
            return demotion == null;
        }
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
     * <p>Equivalent to {@link #dependents(String, boolean)} with {@code directOnly=false}.</p>
     *
     * @return the transitively affected IRIs, sorted, deduplicated, excluding {@code targetIri}
     *         itself and any {@code arkreq:Step}
     */
    public List<String> dependents(String targetIri) {
        return dependents(targetIri, false);
    }

    /**
     * {@link #dependents(String)}, optionally narrowed to only the resources one hop away from
     * {@code targetIri} - the "who references this directly" view a strongly-coupled term's full
     * transitive closure can bury (issue #594, e.g. a glossary term used by a requirement dozens
     * of use cases realise transitively reports only that requirement, not every use case behind
     * it). A hop through an {@code arkreq:Step} does not count against this depth-1 cutoff: the
     * two-RDF-hop {@code mainStep}/{@code extensionStep} then {@code stepRealises} path is one
     * semantic "realises" edge, per the {@code arkreq:Step} note above, so a use case realising a
     * requirement directly still counts as depth 1 from that requirement.
     *
     * @param directOnly {@code true} to report only depth-1 dependents instead of the full
     *                   transitive closure
     * @return the (transitively, or depth-1-only if {@code directOnly}) affected IRIs, sorted,
     *         deduplicated, excluding {@code targetIri} itself and any {@code arkreq:Step}
     */
    public List<String> dependents(String targetIri, boolean directOnly) {
        Objects.requireNonNull(targetIri, "targetIri");
        Set<String> frontier = new HashSet<>();
        frontier.add(targetIri);
        Map<String, Integer> depthOf = new HashMap<>();
        depthOf.put(targetIri, 0);
        Set<String> reported = new TreeSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(targetIri);
        while (!queue.isEmpty()) {
            String current = queue.poll();
            int currentDepth = depthOf.get(current);
            for (Triple triple : incomingByObject.getOrDefault(current, List.of())) {
                if (!DEPENDENT_EDGE_PREDICATES.contains(triple.predicate())) {
                    continue;
                }
                addDependent(triple.subject(), currentDepth, frontier, queue, reported, depthOf);
            }
            for (Triple triple : outgoingBySubject.getOrDefault(current, List.of())) {
                if (!FORWARD_DEPENDENT_EDGE_PREDICATES.contains(triple.predicate())
                        || !(triple.object() instanceof RdfNode.Resource resourceObject)) {
                    continue;
                }
                addDependent(resourceObject.iri(), currentDepth, frontier, queue, reported, depthOf);
            }
        }
        if (!directOnly) {
            return List.copyOf(reported);
        }
        return reported.stream().filter(iri -> depthOf.get(iri) == 1).toList();
    }

    /**
     * Adds {@code candidate} to the traversal's frontier/queue/reported set, unless it was already
     * visited - shared by {@link #dependents(String, boolean)}'s backward and forward hops so both
     * follow exactly the same "visit once, report once, skip a {@code arkreq:Step}" rule. Records
     * {@code candidate}'s depth in {@code depthOf}: a {@code arkreq:Step} inherits {@code
     * sourceDepth} unchanged (it is not a reportable hop of its own), every other candidate gets
     * {@code sourceDepth + 1} - so a use case reached only through a step still lands one depth
     * below the requirement it realises, not two.
     */
    private void addDependent(String candidate, int sourceDepth, Set<String> frontier, Deque<String> queue,
            Set<String> reported, Map<String, Integer> depthOf) {
        if (!frontier.add(candidate)) {
            return;
        }
        queue.add(candidate);
        if (isType(candidate, STEP_TYPE)) {
            depthOf.put(candidate, sourceDepth);
            return;
        }
        depthOf.put(candidate, sourceDepth + 1);
        reported.add(candidate);
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
