// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabRdf;

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
 * construction. So is an owned field whose shapes name no {@code sh:class} for its children: the
 * class is what makes a child a focus node of its own node shape, and the engine types every new
 * child with it.</p>
 */
public final class ResourceSchema {

    private static final String SHACL = "http://www.w3.org/ns/shacl#";

    private final Map<IRI, Optional<String>> codePrefixByType;
    private final Map<IRI, OwnedField> ownedFieldByProperty;

    private ResourceSchema(Map<IRI, Optional<String>> codePrefixByType, Map<IRI, OwnedField> ownedFieldByProperty) {
        this.codePrefixByType = Map.copyOf(codePrefixByType);
        this.ownedFieldByProperty = Map.copyOf(ownedFieldByProperty);
    }

    /**
     * Reads the schema off the shapes graph.
     *
     * @param shapes the shapes with their {@code arkres:} rule data
     * @return the schema
     * @throws IllegalStateException if the rule data contradicts itself
     */
    public static ResourceSchema of(final ReadableGraph shapes) {
        Objects.requireNonNull(shapes, "shapes");
        RDF rdf = new SimpleRdf();
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
            IRI childClass = shapes.stream(null, path, property)
                    .flatMap(shape -> shapes.stream(shape.getSubject(), shaclClass, null))
                    .map(Triple::getObject)
                    .filter(IRI.class::isInstance)
                    .map(IRI.class::cast)
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(annotation.getSubject() + " owns its children,"
                            + " but no property shape on " + property + " names their sh:class; an untyped child"
                            + " would escape every node shape"));
            OwnedField field = new OwnedField(property, lexical(annotation.getObject()), order, childClass);
            OwnedField earlier = owned.putIfAbsent(property, field);
            if (earlier != null && !earlier.equals(field)) {
                throw new IllegalStateException("the rule data annotates " + property + " twice: " + earlier
                        + " and " + field);
            }
        });
        return new ResourceSchema(prefixes, owned);
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
