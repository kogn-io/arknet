// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabRdfs;

/**
 * What generic resource access knows about the resource types, read at runtime from the rule data
 * the shape files carry ({@code arkres:} annotations, see {@code arknet-resource.ttl}) - never
 * written down in code (ADR-69).
 *
 * <p>Two kinds of facts: which classes are resource types and the prefix of their business codes
 * ({@code arkres:ResourceType}, {@code arkres:codePrefix} on the class), and which fields own their
 * values as children ({@code arkres:childKind}, {@code arkres:orderedBy} on a property shape, the
 * field being the shape's {@code sh:path}). An owned field is identified by its property alone: two
 * property shapes annotating the same property differently are a defect of the rule data and fail
 * construction. So is an owned field whose shapes name no {@code sh:class} for its children, or more
 * than one: the
 * class is what makes a child a focus node of its own node shape, and the engine types every new
 * child with it.</p>
 *
 * <p>Beside the rule data it answers two questions over shapes and ontology together, for any class:
 * its <em>fields</em> - the {@code sh:path} of every property shape of every node shape whose
 * {@code sh:targetClass} is the class or one of its superclasses ({@code rdfs:subClassOf} of the
 * ontology), at any severity; {@code describe} lists exactly these - and the <em>edges</em> pointing
 * at it - every property a property shape constrains to the class or a superclass by
 * {@code sh:class} or by an {@code sh:or} over classes, and every property the ontology gives such a
 * class as {@code rdfs:range}.</p>
 *
 * <p>A third kind of rule data marks the <em>status field</em> of a type: a property shape carrying
 * {@code arkres:initialStatus} makes its {@code sh:path} the status of every class its node shape
 * targets, and of their subclasses. On the same property shape, {@code arkres:transition} names
 * each allowed change ({@code arkres:fromStatus}, {@code arkres:toStatus}, any number of
 * {@code arkres:condition} shapes, each a named shape) and {@code arkres:deletableIn} each status a
 * resource may be deleted in. Transition or deletability data on a shape that marks no status field,
 * a transition without exactly one source and one target status, a condition that is no IRI, no
 * shape the shapes declare, or a shape with a target of its own, a status the {@code sh:in} of the
 * status field's shape does not allow, and two status fields reaching one class - on the class itself
 * or across its superclasses - are defects of the rule data and fail construction.</p>
 */
public final class ResourceSchema {

    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final IRI TARGET_CLASS = RDF_FACTORY.createIRI(SHACL + "targetClass");
    private static final IRI PROPERTY = RDF_FACTORY.createIRI(SHACL + "property");
    private static final IRI PATH = RDF_FACTORY.createIRI(SHACL + "path");
    private static final IRI CLASS = RDF_FACTORY.createIRI(SHACL + "class");
    private static final IRI OR = RDF_FACTORY.createIRI(SHACL + "or");
    private static final IRI SUB_CLASS_OF = RDF_FACTORY.createIRI(VocabRdfs.NAMESPACE + "subClassOf");
    private static final IRI RANGE = RDF_FACTORY.createIRI(VocabRdfs.NAMESPACE + "range");

    private final Map<IRI, Optional<String>> codePrefixByType;
    private final Map<IRI, OwnedField> ownedFieldByProperty;
    private final Map<IRI, StatusField> statusFieldByClass;
    private final ReadableGraph shapes;
    private final ReadableGraph ontology;

    private ResourceSchema(final Map<IRI, Optional<String>> codePrefixByType,
            final Map<IRI, OwnedField> ownedFieldByProperty, final Map<IRI, StatusField> statusFieldByClass,
            final ReadableGraph shapes, final ReadableGraph ontology) {
        this.codePrefixByType = Map.copyOf(codePrefixByType);
        this.ownedFieldByProperty = Map.copyOf(ownedFieldByProperty);
        this.statusFieldByClass = Map.copyOf(statusFieldByClass);
        this.shapes = shapes;
        this.ontology = ontology;
    }

    /**
     * Reads the schema off the shapes graph and the class hierarchy of the ontologies.
     *
     * @param shapes   the shapes with their {@code arkres:} rule data
     * @param ontology the model ontologies: class hierarchy ({@code rdfs:subClassOf}) and ranges
     *                 ({@code rdfs:range})
     * @return the schema
     * @throws IllegalStateException if the rule data contradicts itself
     */
    public static ResourceSchema of(final ReadableGraph shapes, final ReadableGraph ontology) {
        Objects.requireNonNull(shapes, "shapes");
        Objects.requireNonNull(ontology, "ontology");
        RDF rdf = RDF_FACTORY;
        IRI codePrefix = rdf.createIRI(ArkresVocabulary.CODE_PREFIX);
        IRI childKind = rdf.createIRI(ArkresVocabulary.CHILD_KIND);
        IRI orderedBy = rdf.createIRI(ArkresVocabulary.ORDERED_BY);
        IRI path = rdf.createIRI(SHACL + "path");
        IRI shaclClass = rdf.createIRI(SHACL + "class");

        Map<IRI, Optional<String>> prefixes = new HashMap<>();
        shapes.stream(null, VocabRdf.TYPE, rdf.createIRI(ArkresVocabulary.RESOURCE_TYPE))
                .map(Triple::getSubject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .forEach(type -> prefixes.put(type, single(shapes, type, codePrefix).map(ResourceSchema::lexical)));

        Map<IRI, OwnedField> owned = new HashMap<>();
        shapes.stream(null, childKind, null).forEach(annotation -> {
            IRI property = single(shapes, annotation.getSubject(), path).filter(IRI.class::isInstance)
                    .map(IRI.class::cast)
                    .orElseThrow(() -> new IllegalStateException(
                            annotation.getSubject() + " carries arkres:childKind but no sh:path"));
            IRI order = single(shapes, annotation.getSubject(), orderedBy).filter(IRI.class::isInstance)
                    .map(IRI.class::cast)
                    .orElseThrow(() -> new IllegalStateException(
                            annotation.getSubject() + " carries arkres:childKind but no arkres:orderedBy"));
            Set<IRI> childClasses = shapes.stream(null, path, property)
                    .flatMap(shape -> shapes.stream(shape.getSubject(), shaclClass, null))
                    .map(Triple::getObject)
                    .filter(IRI.class::isInstance)
                    .map(IRI.class::cast)
                    .collect(Collectors.toSet());
            if (childClasses.size() != 1) {
                throw new IllegalStateException(annotation.getSubject() + " owns its children, so the property"
                        + " shapes on " + property + " name exactly one sh:class for them - the class every new"
                        + " child is typed with; they name " + childClasses);
            }
            IRI childClass = childClasses.iterator().next();
            OwnedField field = new OwnedField(property, lexical(annotation.getObject()), order, childClass);
            OwnedField earlier = owned.putIfAbsent(property, field);
            if (earlier != null && !earlier.equals(field)) {
                throw new IllegalStateException("the rule data annotates " + property + " twice: " + earlier
                        + " and " + field);
            }
        });
        ResourceSchema schema = new ResourceSchema(prefixes, owned, statusFields(shapes), shapes, ontology);
        schema.statusFieldByClass.keySet().forEach(type -> schema.statusField(Set.of(type)));
        return schema;
    }

    /**
     * Reads the status rule data: for every property shape marking a status field, the classes its
     * node shapes target.
     */
    private static Map<IRI, StatusField> statusFields(final ReadableGraph shapes) {
        IRI initialStatus = RDF_FACTORY.createIRI(ArkresVocabulary.INITIAL_STATUS);
        for (String marker : List.of(ArkresVocabulary.TRANSITION, ArkresVocabulary.DELETABLE_IN)) {
            shapes.stream(null, RDF_FACTORY.createIRI(marker), null).map(Triple::getSubject)
                    .filter(shape -> single(shapes, shape, initialStatus).isEmpty())
                    .findFirst()
                    .ifPresent(shape -> {
                        throw new IllegalStateException(shape + " carries " + marker + " but no "
                                + ArkresVocabulary.INITIAL_STATUS + ": transitions and deletable statuses belong"
                                + " to the property shape that marks the status field");
                    });
        }
        Map<IRI, StatusField> byClass = new HashMap<>();
        shapes.stream(null, initialStatus, null).map(Triple::getSubject).distinct().forEach(shape -> {
            StatusField field = statusField(shapes, shape);
            List<IRI> classes = shapes.stream(null, PROPERTY, shape).map(Triple::getSubject)
                    .flatMap(node -> shapes.stream(node, TARGET_CLASS, null))
                    .map(Triple::getObject)
                    .filter(IRI.class::isInstance)
                    .map(IRI.class::cast)
                    .toList();
            if (classes.isEmpty()) {
                throw new IllegalStateException(shape + " marks a status field, but no node shape with an"
                        + " sh:targetClass holds it");
            }
            classes.forEach(type -> {
                StatusField earlier = byClass.putIfAbsent(type, field);
                if (earlier != null && !earlier.equals(field)) {
                    throw new IllegalStateException(type + " has two status fields: " + earlier.property() + " and "
                            + field.property());
                }
            });
        });
        return byClass;
    }

    private static StatusField statusField(final ReadableGraph shapes, final BlankNodeOrIRI shape) {
        IRI property = single(shapes, shape, PATH).filter(IRI.class::isInstance).map(IRI.class::cast)
                .orElseThrow(() -> new IllegalStateException(shape + " marks a status field but has no sh:path"));
        RDFTerm initial = exactlyOne(shapes, shape, ArkresVocabulary.INITIAL_STATUS);
        List<Transition> transitions = shapes.stream(shape, RDF_FACTORY.createIRI(ArkresVocabulary.TRANSITION), null)
                .map(Triple::getObject)
                .map(transition -> {
                    if (!(transition instanceof BlankNodeOrIRI node)) {
                        throw new IllegalStateException(shape + " names the literal " + transition + " as "
                                + ArkresVocabulary.TRANSITION);
                    }
                    List<IRI> conditions = shapes.stream(node, RDF_FACTORY.createIRI(ArkresVocabulary.CONDITION), null)
                            .map(Triple::getObject)
                            .map(condition -> {
                                if (condition instanceof IRI named) {
                                    return declaredCondition(shapes, property, named);
                                }
                                throw new IllegalStateException("a transition of " + property + " names " + condition
                                        + " as " + ArkresVocabulary.CONDITION + "; a condition is a named shape");
                            })
                            .sorted((a, b) -> a.getIRIString().compareTo(b.getIRIString()))
                            .toList();
                    return new Transition(exactlyOne(shapes, node, ArkresVocabulary.FROM_STATUS),
                            exactlyOne(shapes, node, ArkresVocabulary.TO_STATUS), conditions);
                })
                .toList();
        Set<Transition> distinct = Set.copyOf(transitions);
        Set<RDFTerm> deletable = shapes.stream(shape, RDF_FACTORY.createIRI(ArkresVocabulary.DELETABLE_IN), null)
                .map(Triple::getObject)
                .collect(Collectors.toUnmodifiableSet());
        long pairs = distinct.stream().map(transition -> List.of(transition.from(), transition.to())).distinct()
                .count();
        if (pairs < distinct.size()) {
            throw new IllegalStateException(property + " declares one transition twice with different conditions");
        }
        Set<RDFTerm> statuses = new LinkedHashSet<>(List.of(initial));
        distinct.forEach(transition -> statuses.addAll(List.of(transition.from(), transition.to())));
        statuses.addAll(deletable);
        allowedValues(shapes, shape).ifPresent(allowed -> statuses.stream().filter(status -> !allowed.contains(status))
                .findFirst()
                .ifPresent(status -> {
                    throw new IllegalStateException("the status rule data of " + property + " names " + status
                            + ", which the sh:in of the status field does not allow: " + allowed);
                }));
        return new StatusField(property, initial, distinct, deletable);
    }

    /**
     * A condition is a shape the shapes declare and that targets nothing itself: a target would make
     * the write gate check it on every write, not only at the transition.
     */
    private static IRI declaredCondition(final ReadableGraph shapes, final IRI property, final IRI condition) {
        if (shapes.stream(condition, null, null).findAny().isEmpty()) {
            throw new IllegalStateException("a transition of " + property + " names " + condition + " as "
                    + ArkresVocabulary.CONDITION + ", but the shapes declare no such shape");
        }
        shapes.stream(condition, null, null).map(Triple::getPredicate)
                .filter(predicate -> predicate.getIRIString().startsWith(SHACL + "target"))
                .findFirst()
                .ifPresent(target -> {
                    throw new IllegalStateException("the condition " + condition + " of a transition of " + property
                            + " carries " + target + "; a condition names no target of its own, the engine checks"
                            + " it on the changed resource only");
                });
        return condition;
    }

    /**
     * The values the {@code sh:in} lists of a status field's shape allow, every list applying; empty
     * if it names none.
     */
    private static Optional<Set<RDFTerm>> allowedValues(final ReadableGraph shapes, final BlankNodeOrIRI shape) {
        IRI in = RDF_FACTORY.createIRI(SHACL + "in");
        Set<RDFTerm> allowed = null;
        for (RDFTerm head : shapes.stream(shape, in, null).map(Triple::getObject).toList()) {
            Set<RDFTerm> items = new LinkedHashSet<>();
            RDFTerm cell = head;
            while (cell instanceof BlankNodeOrIRI node && !VocabRdf.NIL.equals(node)) {
                single(shapes, node, VocabRdf.FIRST).ifPresent(items::add);
                cell = single(shapes, node, VocabRdf.REST).orElse(VocabRdf.NIL);
            }
            if (allowed == null) {
                allowed = items;
            } else {
                allowed.retainAll(items);
            }
        }
        return Optional.ofNullable(allowed);
    }

    private static RDFTerm exactlyOne(final ReadableGraph shapes, final BlankNodeOrIRI subject, final String property) {
        List<RDFTerm> values = shapes.stream(subject, RDF_FACTORY.createIRI(property), null).map(Triple::getObject)
                .distinct().toList();
        if (values.size() != 1) {
            throw new IllegalStateException("status rule data on " + subject + " names " + values.size()
                    + " values of " + property + "; it takes exactly one");
        }
        return values.get(0);
    }

    /**
     * The classes {@code type} is an instance of by the ontology: itself and every class it reaches
     * over {@code rdfs:subClassOf}.
     *
     * @param type a class
     * @return the class and its superclasses, the class first
     */
    public Set<IRI> superclasses(final IRI type) {
        Set<IRI> seen = new LinkedHashSet<>();
        Deque<IRI> open = new ArrayDeque<>(List.of(type));
        while (!open.isEmpty()) {
            IRI next = open.pop();
            if (seen.add(next)) {
                ontology.stream(next, SUB_CLASS_OF, null).map(Triple::getObject)
                        .filter(IRI.class::isInstance)
                        .map(IRI.class::cast)
                        .forEach(open::push);
            }
        }
        return seen;
    }

    /**
     * The node shapes that apply to an instance of {@code type}: every shape whose
     * {@code sh:targetClass} is the class or one of its superclasses.
     *
     * @param type a class
     * @return the node shapes, without duplicates
     */
    public List<BlankNodeOrIRI> nodeShapes(final IRI type) {
        return superclasses(type).stream()
                .flatMap(superclass -> shapes.stream(null, TARGET_CLASS, superclass).map(Triple::getSubject))
                .distinct()
                .toList();
    }

    /**
     * The fields of a node typed with {@code types}: the {@code sh:path} of every property shape of
     * every node shape that applies to one of them, at any severity - the fields {@code describe}
     * lists.
     *
     * @param types the {@code rdf:type} values of a node
     * @return the field properties
     */
    public Set<IRI> fields(final Set<IRI> types) {
        return types.stream()
                .flatMap(type -> nodeShapes(type).stream())
                .distinct()
                .flatMap(node -> shapes.stream(node, PROPERTY, null))
                .map(Triple::getObject)
                .flatMap(property -> single(shapes, property, PATH).stream())
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .collect(Collectors.toUnmodifiableSet());
    }

    /**
     * The edges that point at a node typed with {@code types}: every property a property shape
     * constrains to one of those classes or their superclasses ({@code sh:class}, or an
     * {@code sh:or} over shapes naming a {@code sh:class}), and every property the ontology gives
     * such a class as {@code rdfs:range}.
     *
     * @param types the {@code rdf:type} values of a node
     * @return the edge properties
     */
    public Set<IRI> edgesTo(final Set<IRI> types) {
        Set<IRI> classes = types.stream().flatMap(type -> superclasses(type).stream())
                .collect(Collectors.toSet());
        Set<IRI> edges = new LinkedHashSet<>();
        for (IRI target : classes) {
            ontology.stream(null, RANGE, target).map(Triple::getSubject)
                    .filter(IRI.class::isInstance)
                    .map(IRI.class::cast)
                    .forEach(edges::add);
            shapes.stream(null, CLASS, target).map(Triple::getSubject)
                    .flatMap(this::constrainedShapes)
                    .flatMap(shape -> single(shapes, shape, PATH).stream())
                    .filter(IRI.class::isInstance)
                    .map(IRI.class::cast)
                    .forEach(edges::add);
        }
        return Set.copyOf(edges);
    }

    /**
     * The property shapes a shape naming {@code sh:class} constrains: the shape itself, and, when it
     * is an alternative of an {@code sh:or}, every shape carrying that list.
     */
    private Stream<BlankNodeOrIRI> constrainedShapes(final BlankNodeOrIRI classShape) {
        List<BlankNodeOrIRI> found = new ArrayList<>(List.of(classShape));
        shapes.stream(null, VocabRdf.FIRST, classShape).map(Triple::getSubject).forEach(cell -> {
            BlankNodeOrIRI head = cell;
            Optional<BlankNodeOrIRI> previous = listPredecessor(head);
            while (previous.isPresent()) {
                head = previous.get();
                previous = listPredecessor(head);
            }
            shapes.stream(null, OR, head).map(Triple::getSubject).forEach(found::add);
        });
        return found.stream();
    }

    private Optional<BlankNodeOrIRI> listPredecessor(final BlankNodeOrIRI cell) {
        return shapes.stream(null, VocabRdf.REST, cell).map(Triple::getSubject).findFirst();
    }

    /**
     * The resource type among {@code types}.
     *
     * @param types the {@code rdf:type} values of a node
     * @return the resource type, empty if none of them is one
     * @throws SubmitRejectedException if they name resource types with different code prefixes
     */
    public Optional<ResourceType> resourceTypeOf(final Set<IRI> types) {
        Set<IRI> resourceTypes = types.stream().filter(codePrefixByType::containsKey).collect(Collectors.toSet());
        if (resourceTypes.isEmpty()) {
            return Optional.empty();
        }
        Set<Optional<String>> prefixes = resourceTypes.stream().map(codePrefixByType::get).collect(Collectors.toSet());
        if (prefixes.size() > 1) {
            throw new SubmitRejectedException("a resource has exactly one resource type, but this one names "
                    + resourceTypes.stream().map(IRI::getIRIString).sorted().toList());
        }
        IRI type = resourceTypes.stream().min((a, b) -> a.getIRIString().compareTo(b.getIRIString())).orElseThrow();
        return Optional.of(new ResourceType(type, prefixes.iterator().next()));
    }

    /**
     * The status field of a node typed with {@code types}: the one a shape on one of the types or a
     * superclass marks with {@code arkres:initialStatus}.
     *
     * @param types the {@code rdf:type} values of a node
     * @return the status field, empty for a type without status rules
     * @throws IllegalStateException if the types reach two different status fields
     */
    public Optional<StatusField> statusField(final Set<IRI> types) {
        Set<StatusField> found = types.stream()
                .flatMap(type -> superclasses(type).stream())
                .map(statusFieldByClass::get)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (found.size() > 1) {
            throw new IllegalStateException("a node typed " + types + " reaches two status fields: " + found);
        }
        return found.stream().findFirst();
    }

    /**
     * The owned field a property names.
     *
     * @param property a field's property
     * @return the owned field, empty if the property refers to values it does not own
     */
    public Optional<OwnedField> ownedField(final IRI property) {
        return Optional.ofNullable(ownedFieldByProperty.get(property));
    }

    /**
     * The resource types this schema knows, by IRI.
     *
     * @return the resource type IRIs
     */
    public Set<String> resourceTypes() {
        return codePrefixByType.keySet().stream().map(IRI::getIRIString).collect(Collectors.toUnmodifiableSet());
    }

    private static Optional<RDFTerm> single(final ReadableGraph graph, final RDFTerm subject, final IRI property) {
        if (!(subject instanceof BlankNodeOrIRI node)) {
            return Optional.empty();
        }
        return graph.stream(node, property, null).map(Triple::getObject).findFirst();
    }

    private static String lexical(final RDFTerm term) {
        if (term instanceof Literal literal) {
            return literal.getLexicalForm();
        }
        throw new IllegalStateException("expected a literal, found " + term);
    }

    /**
     * A resource type and the prefix of its business codes.
     *
     * @param type       the class
     * @param codePrefix the literal its codes start with, empty for a type without codes
     */
    public record ResourceType(IRI type, Optional<String> codePrefix) {
    }

    /**
     * A field that owns its values as children of the resource.
     *
     * @param property   the field's property
     * @param childKind  the fragment kind naming its children ({@code <resource>#<kind>-<id>})
     * @param orderedBy  the property carrying each child's position
     * @param childClass the class the shapes require of a child; every new child is typed with it
     */
    public record OwnedField(IRI property, String childKind, IRI orderedBy, IRI childClass) {
    }

    /**
     * The field carrying the status of a resource type, with the rules of its changes.
     *
     * @param property    the field's property
     * @param initial     the status every created resource starts with
     * @param transitions the allowed changes
     * @param deletable   the statuses a resource may be deleted in; empty for deletion in every status
     */
    public record StatusField(IRI property, RDFTerm initial, Set<Transition> transitions, Set<RDFTerm> deletable) {

        /**
         * Copies the sets.
         */
        public StatusField {
            transitions = Set.copyOf(transitions);
            deletable = Set.copyOf(deletable);
        }

        /**
         * The transition from one status to another.
         *
         * @param from the status before the document
         * @param to   the status after it
         * @return the transition, empty if the field allows no such change
         */
        public Optional<Transition> transition(final RDFTerm from, final RDFTerm to) {
            return transitions.stream().filter(transition -> transition.from().equals(from)
                    && transition.to().equals(to)).findFirst();
        }

        /**
         * Whether a resource in {@code status} may be deleted.
         *
         * @param status the status before the document
         * @return true if the field names no deletable status or names this one
         */
        public boolean deletableIn(final RDFTerm status) {
            return deletable.isEmpty() || deletable.contains(status);
        }
    }

    /**
     * One allowed change of a status field.
     *
     * @param from       the status it leaves
     * @param to         the status it reaches
     * @param conditions the named shapes the resource must satisfy after the document, sorted
     */
    public record Transition(RDFTerm from, RDFTerm to, List<IRI> conditions) {

        /**
         * Copies the conditions.
         */
        public Transition {
            conditions = List.copyOf(conditions);
        }
    }
}
