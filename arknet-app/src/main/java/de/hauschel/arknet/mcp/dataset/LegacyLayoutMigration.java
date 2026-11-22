// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfAdrRepository;
import de.hauschel.arknet.adr.adapter.kogniordf.KognioRdfAdrRepositoryFactory;
import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfActorRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepository;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfContextRelationshipRepositoryFactory;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkarchVocabulary;
import de.hauschel.arknet.persistence.ArkdddVocabulary;
import de.hauschel.arknet.persistence.ArkprocVocabulary;
import de.hauschel.arknet.persistence.ArkreqVocabulary;
import de.hauschel.arknet.persistence.ChildFragments;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepository;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfUseCaseRepository;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfUseCaseRepositoryFactory;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Moves a project dataset from the storage layout of an earlier arknet version to the content
 * graph of the versioned write path (ADR-61, ADR-63, ADR-64), once, at daemon start.
 *
 * <p><strong>What is moved.</strong> Every triple of the component graphs in
 * {@link LegacyLayoutRefusingDatasetLifecycle#LEGACY_GRAPHS} becomes part of the draft slice of the
 * resource it describes: a subject typed as one of the {@linkplain #RESOURCE_TYPES resource types}
 * is a resource, and the objects of its child edges ({@link #CHILD_KINDS}) are its children. A
 * child gets the fragment IRI {@code <resource>#<kind>-<id>} from the same {@link ChildFragments}
 * naming a regular write uses, a subdomain node the fixed {@code <bounded context>#Subdomain}; every
 * edge pointing at an old child IRI, from any resource, is rewritten to the new one, and the
 * position triples keep the order. Each resource is then written and published through the
 * {@link VersionedWriteFunnel} of its bounded context, through that context's SHACL gate where it
 * passes (see below where it does not), and ends with exactly one published version and no open
 * draft.</p>
 *
 * <p><strong>What is not moved.</strong> The revision trail of the provenance graph has no content
 * and is dropped (ADR-61); only the business code a deletion hung on its last revision is kept,
 * through {@link VersionedWriteFunnel#retainCode}, so a code counter never hands it out again. An
 * edge whose object is a blank node is dropped and listed, as is every triple on a blank-node
 * subject and every subject that is neither a resource nor a child of one - nothing is lost
 * without a line in the {@link LegacyLayoutMigrationReport}.</p>
 *
 * <p><strong>Past the gate.</strong> A resource whose stored form the current gate rejects - a form
 * an earlier version wrote or kept past its own gate, such as a requirement without an acceptance
 * criterion, a criterion stored as a literal, or a reference edge whose object is a literal - is
 * carried over unchanged through {@link VersionedWriteFunnel#importPublishedPastGate}, still
 * published once, and listed in the report with the violation. It stays readable; its next regular
 * update runs through the gate and has to close the gap.</p>
 *
 * <p><strong>All or nothing.</strong> A dataset is migrated in one transaction: the writes, the
 * retained codes and the clearing of the old graphs commit together, and a technical failure - a
 * literal the store cannot read, a store error - rolls all of it back. The dataset then keeps its
 * earlier layout untouched, and {@link LegacyLayoutRefusingDatasetLifecycle} keeps refusing it.
 * A dataset without old graphs is left alone, so a second start does nothing. The reserved system
 * dataset of the project registry is outside the versioning and is never migrated.</p>
 */
public final class LegacyLayoutMigration {

    /** The comment of the one history entry a migrated resource gets. */
    public static final String MIGRATION_COMMENT = "migrated from the storage layout of an earlier arknet version";

    private static final Logger LOG = LoggerFactory.getLogger(LegacyLayoutMigration.class);

    private static final String PROVENANCE_GRAPH = "https://w3id.org/arknet/model/provenance";
    private static final String INVALIDATED_AT_TIME = "http://www.w3.org/ns/prov#invalidatedAtTime";
    private static final DatasetId SYSTEM_DATASET = new DatasetId(ProjectId.RESERVED_SYSTEM_DATASET);

    /**
     * The edges from a resource to its children, with the local-name prefix of the child fragment
     * a regular write names them with. A subdomain node is the one child with a fixed name.
     */
    static final Map<String, String> CHILD_KINDS = Map.of(
            ArkreqVocabulary.ACCEPTANCE_CRITERION, KognioRdfRequirementRepository.CRITERION_KIND,
            ArkreqVocabulary.MAIN_STEP, KognioRdfUseCaseRepository.STEP_KIND,
            ArkreqVocabulary.EXTENSION_STEP, KognioRdfUseCaseRepository.EXTENSION_KIND,
            ArkarchVocabulary.CONSEQUENCE, KognioRdfAdrRepository.CONSEQUENCE_KIND,
            ArkarchVocabulary.CONSIDERED_OPTION, KognioRdfAdrRepository.OPTION_KIND);

    private static final String SUBDOMAIN_EDGE = ArkdddVocabulary.PART_OF_PROPERTY;

    /** The resource types by their report label, each with the classes a resource of it is typed as. */
    static final Map<String, List<String>> RESOURCE_TYPES = orderedTypes();

    /** Supertypes a referenced resource is asserted as for the gate, as the regular write paths do. */
    private static final Map<String, String> SUPERTYPES = Map.of(
            ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE, ArkreqVocabulary.REQUIREMENT_TYPE,
            ArkreqVocabulary.NON_FUNCTIONAL_REQUIREMENT_TYPE, ArkreqVocabulary.REQUIREMENT_TYPE,
            ArkreqVocabulary.TECHNICAL_CONSTRAINT_TYPE, ArkreqVocabulary.CONSTRAINT_TYPE,
            ArkreqVocabulary.BUSINESS_CONSTRAINT_TYPE, ArkreqVocabulary.CONSTRAINT_TYPE,
            ArkreqVocabulary.REGULATORY_CONSTRAINT_TYPE, ArkreqVocabulary.CONSTRAINT_TYPE,
            ArkprocVocabulary.HUMAN_ACTOR_TYPE, ArkprocVocabulary.ACTOR_TYPE,
            ArkprocVocabulary.SYSTEM_ACTOR_TYPE, ArkprocVocabulary.ACTOR_TYPE,
            ArkprocVocabulary.LEGAL_ACTOR_TYPE, ArkprocVocabulary.ACTOR_TYPE,
            ArkprocVocabulary.GROUP_ACTOR_TYPE, ArkprocVocabulary.ACTOR_TYPE);

    private final RDF rdf = new SimpleRdf();
    private final Map<String, VersionedWriteFunnel> funnelByType;

    /**
     * @param funnelByType the funnel each resource type of {@link #RESOURCE_TYPES} is written
     *                     through, keyed by the type's label; every label must be present
     */
    LegacyLayoutMigration(Map<String, VersionedWriteFunnel> funnelByType) {
        Objects.requireNonNull(funnelByType, "funnelByType");
        for (String type : RESOURCE_TYPES.keySet()) {
            Objects.requireNonNull(funnelByType.get(type), "no funnel for " + type);
        }
        this.funnelByType = Map.copyOf(funnelByType);
    }

    /**
     * The migration over the gates of every bounded context, built from the same repository
     * factories the composition root writes through.
     *
     * @param lifecycle     the lifecycle the funnels are built over; the migration itself only
     *                      writes inside the transaction it opens
     * @param displayLocale the display-language preference for SHACL violation messages
     * @return the migration
     */
    public static LegacyLayoutMigration over(DatasetLifecycle lifecycle, DisplayLocale displayLocale) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(displayLocale, "displayLocale");
        VersionedWriteFunnel requirements = KognioRdfRequirementRepositoryFactory.buildFunnel(lifecycle, displayLocale);
        VersionedWriteFunnel actors = KognioRdfActorRepositoryFactory.buildFunnel(lifecycle, displayLocale);
        Map<String, VersionedWriteFunnel> funnels = new HashMap<>();
        funnels.put("requirement", requirements);
        funnels.put("constraint", requirements);
        funnels.put("use case", KognioRdfUseCaseRepositoryFactory.buildFunnel(lifecycle, displayLocale));
        funnels.put("glossary term", KognioRdfTermRepositoryFactory.buildFunnel(lifecycle, displayLocale));
        funnels.put("bounded context", KognioRdfBoundedContextRepositoryFactory.buildFunnel(lifecycle, displayLocale));
        funnels.put("context relationship",
                KognioRdfContextRelationshipRepositoryFactory.buildFunnel(lifecycle, displayLocale));
        funnels.put("architecture decision", KognioRdfAdrRepositoryFactory.buildFunnel(lifecycle, displayLocale));
        funnels.put("actor", actors);
        funnels.put("role", actors);
        return new LegacyLayoutMigration(funnels);
    }

    /**
     * Migrates every dataset {@code lifecycle} knows except the system dataset, logging one summary
     * per migrated or failed dataset and one line per dropped item.
     *
     * @param lifecycle the unguarded lifecycle of the storage root
     * @return one report per dataset, in dataset-id order
     */
    public List<LegacyLayoutMigrationReport> migrateAll(DatasetLifecycle lifecycle) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        List<LegacyLayoutMigrationReport> reports = new ArrayList<>();
        lifecycle.list().stream()
                .filter(id -> !SYSTEM_DATASET.equals(id))
                .sorted(Comparator.comparing(DatasetId::value))
                .forEach(id -> reports.add(migrate(lifecycle, id)));
        return reports;
    }

    /**
     * Migrates one dataset; never throws for a failed migration, the report says so.
     *
     * @param lifecycle the unguarded lifecycle of the storage root
     * @param id        the project dataset
     * @return the report
     */
    public LegacyLayoutMigrationReport migrate(DatasetLifecycle lifecycle, DatasetId id) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(id, "id");
        if (SYSTEM_DATASET.equals(id)) {
            return LegacyLayoutMigrationReport.notNeeded(id.value());
        }
        LegacyLayoutMigrationReport report;
        try (DatasetHandle handle = lifecycle.acquire(id)) {
            if (!LegacyLayoutRefusingDatasetLifecycle.holdsLegacyLayout(handle.sparqlQuery())) {
                return LegacyLayoutMigrationReport.notNeeded(id.value());
            }
            report = handle.transactor().inTransaction(tx -> migrate(tx, id.value()));
        } catch (RuntimeException e) {
            report = LegacyLayoutMigrationReport.failed(id.value(), describe(e));
        }
        log(report);
        return report;
    }

    private LegacyLayoutMigrationReport migrate(DatasetTx tx, String dataset) {
        List<Triple> legacy = new ArrayList<>();
        for (String graph : LegacyLayoutRefusingDatasetLifecycle.LEGACY_GRAPHS) {
            if (!graph.equals(PROVENANCE_GRAPH)) {
                tx.snapshot(rdf.createIRI(graph)).stream().forEach(legacy::add);
            }
        }
        List<String> droppedEdges = new ArrayList<>();
        Graph kept = rdf.createGraph();
        Map<BlankNodeOrIRI, Integer> blankSubjects = new LinkedHashMap<>();
        for (Triple triple : legacy) {
            if (triple.getSubject() instanceof BlankNode) {
                blankSubjects.merge(triple.getSubject(), 1, Integer::sum);
            } else if (triple.getObject() instanceof BlankNode) {
                droppedEdges.add(render(triple.getSubject()) + " " + render(triple.getPredicate()));
            } else {
                kept.add(triple);
            }
        }

        Map<IRI, String> resourceType = classify(kept);
        Map<IRI, IRI> renamed = new HashMap<>();
        Map<IRI, Set<IRI>> childrenOf = new TreeMap<>(Comparator.comparing(IRI::getIRIString));
        for (IRI resource : resourceType.keySet()) {
            childrenOf.put(resource, nameChildren(kept, resource, funnelByType.get(resourceType.get(resource)),
                    renamed));
        }

        Graph rewritten = rdf.createGraph();
        kept.stream().forEach(triple -> rewritten.add(rename(triple.getSubject(), renamed),
                triple.getPredicate(), rename(triple.getObject(), renamed)));

        Set<BlankNodeOrIRI> owned = new HashSet<>(resourceType.keySet());
        childrenOf.values().forEach(children -> children.forEach(child -> owned.add(renamed.get(child))));
        List<String> droppedSubjects = new ArrayList<>();
        blankSubjects.forEach((subject, count) -> droppedSubjects.add(render(subject) + " (" + count + " triples)"));
        countBySubject(rewritten).forEach((subject, count) -> {
            if (!owned.contains(subject)) {
                droppedSubjects.add(render(subject) + " (" + count + " triples)");
            }
        });

        Map<IRI, Set<IRI>> sliceSubjectsOf = new HashMap<>();
        childrenOf.forEach((resource, children) -> {
            Set<IRI> subjects = new LinkedHashSet<>();
            subjects.add(resource);
            children.forEach(child -> subjects.add(renamed.get(child)));
            sliceSubjectsOf.put(resource, subjects);
        });
        Map<String, Integer> counts = new TreeMap<>();
        List<String> carriedPastGate = new ArrayList<>();
        for (IRI resource : childrenOf.keySet()) {
            Set<IRI> sliceSubjects = sliceSubjectsOf.get(resource);
            Graph slice = rdf.createGraph();
            sliceSubjects.forEach(subject -> rewritten.stream(subject, null, null).forEach(slice::add));
            String type = resourceType.get(resource);
            VersionedWriteFunnel funnel = funnelByType.get(type);
            try {
                funnel.importPublished(tx, resource.getIRIString(), slice,
                        assertedContext(slice, sliceSubjects, rewritten, sliceSubjectsOf), MIGRATION_COMMENT);
            } catch (WriteConstraintViolationException e) {
                // the gate validates before anything is written, so the same slice can follow unvalidated
                funnel.importPublishedPastGate(tx, resource.getIRIString(), slice, MIGRATION_COMMENT);
                carriedPastGate.add(type + " " + describeResource(rewritten, resource) + ": " + e.getMessage());
            }
            counts.merge(type, 1, Integer::sum);
        }

        List<String> retainedCodes = retainCodes(tx, rewritten);
        for (String graph : LegacyLayoutRefusingDatasetLifecycle.LEGACY_GRAPHS) {
            tx.clear(rdf.createIRI(graph));
        }
        return new LegacyLayoutMigrationReport(dataset, LegacyLayoutMigrationReport.Outcome.MIGRATED, counts,
                droppedEdges, droppedSubjects, retainedCodes, carriedPastGate, null);
    }

    private Map<IRI, String> classify(ReadableGraph graph) {
        Map<String, String> typeLabelByClass = new HashMap<>();
        RESOURCE_TYPES.forEach((label, classes) -> classes.forEach(type -> typeLabelByClass.putIfAbsent(type, label)));
        List<String> labelOrder = List.copyOf(RESOURCE_TYPES.keySet());
        Map<IRI, String> resourceType = new TreeMap<>(Comparator.comparing(IRI::getIRIString));
        graph.stream(null, VocabRdf.TYPE, null).forEach(triple -> {
            String label = triple.getObject() instanceof IRI type ? typeLabelByClass.get(type.getIRIString()) : null;
            if (label == null || !(triple.getSubject() instanceof IRI subject)) {
                return;
            }
            resourceType.merge(subject, label,
                    (first, second) -> labelOrder.indexOf(first) <= labelOrder.indexOf(second) ? first : second);
        });
        return resourceType;
    }

    /**
     * Names the children of one resource, kind by kind in stored position order, and records each
     * old child IRI's new name in {@code renamed}.
     *
     * @return the old IRIs of the resource's children
     */
    private Set<IRI> nameChildren(ReadableGraph graph, IRI resource, VersionedWriteFunnel funnel,
            Map<IRI, IRI> renamed) {
        Set<IRI> children = new LinkedHashSet<>();
        ChildFragments fragments = funnel.childFragments(resource, rdf.createGraph());
        for (Map.Entry<String, String> kind : new TreeMap<>(CHILD_KINDS).entrySet()) {
            List<IRI> stored = childrenByPosition(graph, resource, rdf.createIRI(kind.getKey()));
            Map<Integer, IRI> names = fragments.name(kind.getValue(), stored.size(), Map.of());
            for (int index = 0; index < stored.size(); index++) {
                IRI child = stored.get(index);
                if (children.add(child)) {
                    renamed.put(child, names.get(index + 1));
                }
            }
        }
        graph.stream(resource, rdf.createIRI(SUBDOMAIN_EDGE), null)
                .map(Triple::getObject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .findFirst()
                .ifPresent(subdomain -> {
                    children.add(subdomain);
                    renamed.put(subdomain, rdf.createIRI(resource.getIRIString() + "#"
                            + KognioRdfBoundedContextRepository.SUBDOMAIN_FRAGMENT));
                });
        return children;
    }

    private List<IRI> childrenByPosition(ReadableGraph graph, IRI resource, IRI edge) {
        return graph.stream(resource, edge, null)
                .map(Triple::getObject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .distinct()
                .sorted(Comparator.comparingInt((IRI child) -> position(graph, child))
                        .thenComparing(IRI::getIRIString))
                .toList();
    }

    private int position(ReadableGraph graph, IRI child) {
        for (String property : List.of(ArkreqVocabulary.POSITION, ArkarchVocabulary.POSITION)) {
            Optional<Integer> position = graph.stream(child, rdf.createIRI(property), null)
                    .map(Triple::getObject)
                    .filter(Literal.class::isInstance)
                    .map(object -> ((Literal) object).getLexicalForm())
                    .filter(lexical -> lexical.matches("[0-9]{1,9}"))
                    .map(Integer::parseInt)
                    .findFirst();
            if (position.isPresent()) {
                return position.get();
            }
        }
        return Integer.MAX_VALUE;
    }

    /**
     * The validation-only context of one slice, what a regular write asserts for its reference
     * targets: the migrated slice of every resource the slice points at - a target's shape then
     * sees the target as stored, not a bare type - and the type, with its supertype, of everything
     * the slice or those target slices point at.
     */
    private ReadableGraph assertedContext(ReadableGraph slice, Set<IRI> sliceSubjects, ReadableGraph all,
            Map<IRI, Set<IRI>> sliceSubjectsOf) {
        Graph context = rdf.createGraph();
        Set<IRI> targets = referencedIris(slice, sliceSubjects);
        Set<IRI> typed = new LinkedHashSet<>(targets);
        for (IRI target : targets) {
            Set<IRI> targetSubjects = sliceSubjectsOf.get(target);
            if (targetSubjects == null) {
                continue;
            }
            Graph targetSlice = rdf.createGraph();
            targetSubjects.forEach(subject -> all.stream(subject, null, null).forEach(targetSlice::add));
            targetSlice.stream().forEach(context::add);
            referencedIris(targetSlice, targetSubjects).stream()
                    .filter(reference -> !sliceSubjects.contains(reference))
                    .forEach(typed::add);
        }
        for (IRI target : typed) {
            all.stream(target, VocabRdf.TYPE, null).forEach(typing -> {
                context.add(typing);
                if (typing.getObject() instanceof IRI type && SUPERTYPES.containsKey(type.getIRIString())) {
                    context.add(target, VocabRdf.TYPE, rdf.createIRI(SUPERTYPES.get(type.getIRIString())));
                }
            });
        }
        return context;
    }

    private static Set<IRI> referencedIris(ReadableGraph slice, Set<IRI> sliceSubjects) {
        Set<IRI> referenced = new LinkedHashSet<>();
        slice.stream()
                .filter(triple -> !VocabRdf.TYPE.equals(triple.getPredicate()))
                .map(Triple::getObject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .filter(target -> !sliceSubjects.contains(target))
                .forEach(referenced::add);
        return referenced;
    }

    private List<String> retainCodes(DatasetTx tx, ReadableGraph migrated) {
        ReadableGraph provenance = tx.snapshot(rdf.createIRI(PROVENANCE_GRAPH));
        Set<String> liveCodes = new HashSet<>();
        migrated.stream(null, VocabDct.IDENTIFIER, null)
                .map(Triple::getObject)
                .filter(Literal.class::isInstance)
                .forEach(code -> liveCodes.add(((Literal) code).getLexicalForm()));
        Map<String, String> holderByCode = new TreeMap<>();
        provenance.stream(null, rdf.createIRI(INVALIDATED_AT_TIME), null)
                .map(Triple::getSubject)
                .filter(IRI.class::isInstance)
                .forEach(revision -> provenance.stream(revision, VocabDct.IDENTIFIER, null)
                        .map(Triple::getObject)
                        .filter(Literal.class::isInstance)
                        .map(code -> ((Literal) code).getLexicalForm())
                        .filter(code -> !liveCodes.contains(code))
                        .forEach(code -> holderByCode.putIfAbsent(code, ((IRI) revision).getIRIString())));
        VersionedWriteFunnel anyFunnel = funnelByType.get(RESOURCE_TYPES.keySet().iterator().next());
        holderByCode.forEach((code, holder) -> anyFunnel.retainCode(tx, holder, code));
        return List.copyOf(holderByCode.keySet());
    }

    private static Map<BlankNodeOrIRI, Integer> countBySubject(ReadableGraph graph) {
        Map<BlankNodeOrIRI, Integer> counts = new TreeMap<>(Comparator.comparing(LegacyLayoutMigration::render));
        graph.stream().forEach(triple -> counts.merge(triple.getSubject(), 1, Integer::sum));
        return counts;
    }

    private static BlankNodeOrIRI rename(BlankNodeOrIRI term, Map<IRI, IRI> renamed) {
        return term instanceof IRI iri ? renamed.getOrDefault(iri, iri) : term;
    }

    private static RDFTerm rename(RDFTerm term, Map<IRI, IRI> renamed) {
        return term instanceof IRI iri ? renamed.getOrDefault(iri, iri) : term;
    }

    private String describeResource(ReadableGraph graph, IRI resource) {
        return graph.stream(resource, VocabDct.IDENTIFIER, null)
                .map(Triple::getObject)
                .filter(Literal.class::isInstance)
                .map(code -> ((Literal) code).getLexicalForm() + " (" + resource.getIRIString() + ")")
                .findFirst()
                .orElse(resource.getIRIString());
    }

    private static String render(RDFTerm term) {
        return term instanceof IRI iri ? "<" + iri.getIRIString() + ">" : String.valueOf(term);
    }

    private static String describe(RuntimeException e) {
        return e.getClass().getSimpleName() + ": " + e.getMessage();
    }

    private static void log(LegacyLayoutMigrationReport report) {
        switch (report.outcome()) {
            case NOT_NEEDED -> LOG.debug("{}", report.summary());
            case FAILED -> LOG.error("legacy layout migration: {}", report.summary());
            case MIGRATED -> {
                LOG.info("legacy layout migration: {}", report.summary());
                report.droppedEdges().forEach(edge ->
                        LOG.warn("legacy layout migration: dataset '{}': dropped blank-node edge {}",
                                report.dataset(), edge));
                report.droppedSubjects().forEach(subject ->
                        LOG.warn("legacy layout migration: dataset '{}': dropped unattached subject {}",
                                report.dataset(), subject));
                report.carriedPastGate().forEach(resource ->
                        LOG.warn("legacy layout migration: dataset '{}': carried over unvalidated, the current"
                                + " write gate rejects it and its next update must close the gap: {}",
                                report.dataset(), resource));
                report.retainedCodes().forEach(code ->
                        LOG.info("legacy layout migration: dataset '{}': retained code of deleted resource {}",
                                report.dataset(), code));
            }
            default -> throw new IllegalStateException("unknown outcome " + report.outcome());
        }
    }

    private static Map<String, List<String>> orderedTypes() {
        Map<String, List<String>> types = new LinkedHashMap<>();
        types.put("requirement", List.of(ArkreqVocabulary.FUNCTIONAL_REQUIREMENT_TYPE,
                ArkreqVocabulary.NON_FUNCTIONAL_REQUIREMENT_TYPE, ArkreqVocabulary.REQUIREMENT_TYPE));
        types.put("constraint", List.of(ArkreqVocabulary.TECHNICAL_CONSTRAINT_TYPE,
                ArkreqVocabulary.BUSINESS_CONSTRAINT_TYPE, ArkreqVocabulary.REGULATORY_CONSTRAINT_TYPE,
                ArkreqVocabulary.CONSTRAINT_TYPE));
        types.put("use case", List.of(ArkreqVocabulary.USE_CASE_TYPE));
        types.put("glossary term", List.of(ArkreqVocabulary.CONCEPT_TYPE));
        types.put("bounded context", List.of(ArkdddVocabulary.BOUNDED_CONTEXT_TYPE));
        types.put("context relationship", List.of(ArkdddVocabulary.CONTEXT_RELATIONSHIP_TYPE));
        types.put("architecture decision", List.of(ArkarchVocabulary.ADR_TYPE));
        types.put("actor", List.of(ArkprocVocabulary.HUMAN_ACTOR_TYPE, ArkprocVocabulary.SYSTEM_ACTOR_TYPE,
                ArkprocVocabulary.LEGAL_ACTOR_TYPE, ArkprocVocabulary.GROUP_ACTOR_TYPE, ArkprocVocabulary.ACTOR_TYPE));
        types.put("role", List.of(ArkprocVocabulary.ROLE_TYPE));
        return Collections.unmodifiableMap(types);
    }
}
