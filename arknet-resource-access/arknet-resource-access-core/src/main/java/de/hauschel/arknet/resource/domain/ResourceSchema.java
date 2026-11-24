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
    private final ReadableGraph shapes;
    private final ReadableGraph ontology;

    private ResourceSchema(final Map<IRI, Optional<String>> codePrefixByType,
            final Map<IRI, OwnedField> ownedFieldByProperty, final ReadableGraph shapes, final ReadableGraph ontology) {
        this.codePrefixByType = Map.copyOf(codePrefixByType);
        this.ownedFieldByProperty = Map.copyOf(ownedFieldByProperty);
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
        return new ResourceSchema(prefixes, owned, shapes, ontology);
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
}
