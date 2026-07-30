// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.trace;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
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

import de.hauschel.arknet.mcp.store.RdfNode;
import de.hauschel.arknet.mcp.store.StoreSnapshot;
import de.hauschel.arknet.mcp.store.Triple;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;

/**
 * An in-memory directed multigraph over one project's statements, purpose-built for the
 * cross-bounded-context edges the traceability tools traverse (issue #131):
 * {@code arkreq:usesTerm} (Requirement -&gt; Term), {@code arkreq:primaryActor}/
 * {@code arkreq:supportingActor} (UseCase -&gt; Term/Actor), {@code arkddd:ubiquitousLanguageTerm}
 * (BoundedContext -&gt; Term, issue #185), and the two-hop {@code arkreq:mainStep}/
 * {@code arkreq:extensionStep} then {@code arkreq:stepRealises} (UseCase -&gt; Step -&gt;
 * Requirement). It also exposes the requirement/bounded-context prose ({@code
 * dcterms:description}/{@code arkreq:acceptanceCriterion}/{@code arkddd:domainVision}) that
 * {@code orphan_check}'s unlinked-mention check scans for a glossary term nothing links to
 * (issue #185).
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
 * domain-agnostic - it knows the {@code arkreq:}/{@code arkddd:}/{@code skos:} predicate and
 * type IRIs it traverses. That is a bounded exception in the same spirit as {@code
 * StoreResource#status()}/{@code #priority()} (issue #111): a fully generic "follow every
 * object-typed predicate" traversal would report noise indistinguishable from the specific
 * edges traceability cares about.</p>
 */
public final class TraceabilityGraph {

    private static final String SKOS_NAMESPACE = "http://www.w3.org/2004/02/skos/core#";
    private static final String ARKDDD_NAMESPACE = "https://w3id.org/arknet/ddd#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    private static final String DCTERMS_IDENTIFIER = "http://purl.org/dc/terms/identifier";
    private static final String DCTERMS_TITLE = "http://purl.org/dc/terms/title";
    private static final String DCTERMS_DESCRIPTION = "http://purl.org/dc/terms/description";
    private static final String SKOS_PREF_LABEL = SKOS_NAMESPACE + "prefLabel";

    // The traversed arkreq: object-property IRIs, the literal-valued arkreq:acceptanceCriterion/
    // arkddd:domainVision predicates and the type IRIs below come from the single shared source
    // of truth (arknet-persistence-support), the very same constants the requirements/
    // bounded-context/ubiquitous-language/use-cases out-adapters serialize them with - so a
    // predicate or type rename cannot silently desync the write side from this read-side
    // traversal (issue #134).
    private static final String USES_TERM = ArkreqVocabulary.USES_TERM;
    private static final String PRIMARY_ACTOR = ArkreqVocabulary.PRIMARY_ACTOR;
    private static final String SUPPORTING_ACTOR = ArkreqVocabulary.SUPPORTING_ACTOR;
    private static final String MAIN_STEP = ArkreqVocabulary.MAIN_STEP;
    private static final String EXTENSION_STEP = ArkreqVocabulary.EXTENSION_STEP;
    private static final String STEP_REALISES = ArkreqVocabulary.STEP_REALISES;
    private static final String ACCEPTANCE_CRITERION = ArkreqVocabulary.ACCEPTANCE_CRITERION;
    private static final String DOMAIN_VISION = ArkdddVocabulary.DOMAIN_VISION;

    // arkddd:ubiquitousLanguageTerm (BoundedContext -> Term) and arkddd:BoundedContext below are,
    // unlike arkreq:acceptanceCriterion/arkddd:domainVision above, used only within this class -
    // ArkdddVocabulary's scope is deliberately limited to predicates duplicated across modules
    // (see its javadoc), so these two stay local rather than growing that shared class further.
    private static final String UBIQUITOUS_LANGUAGE_TERM = ARKDDD_NAMESPACE + "ubiquitousLanguageTerm";

    private static final String FUNCTIONAL_REQUIREMENT_TYPE = ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE;
    private static final String NON_FUNCTIONAL_REQUIREMENT_TYPE = ArkreqVocabulary.NON_FUNCTIONAL_REQUIREMENT_TYPE;
    private static final String STEP_TYPE = ArkreqVocabulary.STEP_TYPE;
    private static final String CONCEPT_TYPE = ArkreqVocabulary.CONCEPT_TYPE;
    private static final String BOUNDED_CONTEXT_TYPE = ARKDDD_NAMESPACE + "BoundedContext";

    /**
     * The predicates {@link #dependents(String)} follows backwards ("who references this").
     * {@code mainStep}/{@code extensionStep} are in this set purely to hop a reached
     * {@code arkreq:Step} back to its owning use case - a step itself is never reported (see
     * {@link #dependents(String)}).
     */
    private static final Set<String> DEPENDENT_EDGE_PREDICATES = Set.of(
            USES_TERM, PRIMARY_ACTOR, SUPPORTING_ACTOR, STEP_REALISES, MAIN_STEP, EXTENSION_STEP,
            UBIQUITOUS_LANGUAGE_TERM);

    private final Map<String, List<Triple>> outgoingBySubject;
    private final Map<String, List<Triple>> incomingByObject;
    private final Map<String, Set<String>> typesBySubject;
    private final Map<String, String> identifierBySubject;
    private final Map<String, String> labelBySubject;

    private TraceabilityGraph(List<Triple> triples) {
        Map<String, List<Triple>> outgoing = new LinkedHashMap<>();
        Map<String, List<Triple>> incoming = new LinkedHashMap<>();
        Map<String, Set<String>> types = new LinkedHashMap<>();
        Map<String, String> identifiers = new LinkedHashMap<>();
        Map<String, String> labels = new LinkedHashMap<>();
        for (Triple triple : triples) {
            outgoing.computeIfAbsent(triple.subject(), s -> new ArrayList<>()).add(triple);
            if (triple.object() instanceof RdfNode.Resource resourceObject) {
                incoming.computeIfAbsent(resourceObject.iri(), s -> new ArrayList<>()).add(triple);
                if (RDF_TYPE.equals(triple.predicate())) {
                    types.computeIfAbsent(triple.subject(), s -> new LinkedHashSet<>()).add(resourceObject.iri());
                }
            } else if (triple.object() instanceof RdfNode.Literal literalObject) {
                if (DCTERMS_IDENTIFIER.equals(triple.predicate())) {
                    identifiers.putIfAbsent(triple.subject(), literalObject.lexicalForm());
                } else if (DCTERMS_TITLE.equals(triple.predicate()) || SKOS_PREF_LABEL.equals(triple.predicate())) {
                    labels.putIfAbsent(triple.subject(), literalObject.lexicalForm());
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
     * @param snapshot the snapshot to traverse (as read by {@code StoreReader#readSnapshot})
     * @return the assembled graph
     */
    public static TraceabilityGraph of(StoreSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        List<Triple> triples = snapshot.resources().stream()
                .flatMap(resource -> resource.outgoing().stream())
                .toList();
        return new TraceabilityGraph(triples);
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

    /**
     * @return {@code true} if a term is used by a requirement ({@code arkreq:usesTerm}), plays
     *         an actor role in a use case ({@code arkreq:primaryActor}/
     *         {@code arkreq:supportingActor}), or is a bounded context's ubiquitous language
     *         ({@code arkddd:ubiquitousLanguageTerm})
     */
    public boolean isReferencedTerm(String termIri) {
        Objects.requireNonNull(termIri, "termIri");
        return incomingByObject.getOrDefault(termIri, List.of()).stream()
                .anyMatch(t -> USES_TERM.equals(t.predicate()) || PRIMARY_ACTOR.equals(t.predicate())
                        || SUPPORTING_ACTOR.equals(t.predicate())
                        || UBIQUITOUS_LANGUAGE_TERM.equals(t.predicate()));
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
     * @return the {@code dcterms:description} and {@code arkreq:acceptanceCriterion} texts of a
     *         requirement, for scanning it for unlinked glossary mentions (issue #185)
     */
    public List<String> requirementProseTexts(String requirementIri) {
        Objects.requireNonNull(requirementIri, "requirementIri");
        List<String> texts = new ArrayList<>(literals(requirementIri, DCTERMS_DESCRIPTION));
        texts.addAll(literals(requirementIri, ACCEPTANCE_CRITERION));
        return texts;
    }

    /**
     * @return the {@code arkddd:domainVision} text of a bounded context, for scanning it for
     *         unlinked glossary mentions (issue #185)
     */
    public List<String> boundedContextProseTexts(String boundedContextIri) {
        return literals(Objects.requireNonNull(boundedContextIri, "boundedContextIri"), DOMAIN_VISION);
    }

    /** @return the {@code prefLabel}/{@code title} label of every term IRI that carries one. */
    public Map<String, String> termLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        for (String termIri : termIris()) {
            labelOf(termIri).ifPresent(label -> labels.put(termIri, label));
        }
        return Map.copyOf(labels);
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

    /** @return the {@code dcterms:title} or {@code skos:prefLabel} of {@code iri}, if present. */
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
