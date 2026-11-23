// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.domain;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.stream.Collectors;

import io.kogn.rdf.terms.BlankNode;
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
 * A parsed submit document: its activities and the graph they point into.
 *
 * <p>A document is a set of triples and has no order. Every triple belongs to an activity - to the
 * activity node itself or to a blank node reachable from it (the new resource of a create, the
 * values of an update, a new child); a triple about anything else is rejected, since the only way
 * to change a stored resource is an activity naming it. The checks here need nothing but the
 * document and the {@link ResourceSchema}; whether a named resource exists is the engine's to
 * check against the store.</p>
 */
public final class SubmittedDocument {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final IRI OBJECT = RDF_FACTORY.createIRI(ArkresVocabulary.OBJECT);
    private static final IRI SET = RDF_FACTORY.createIRI(ArkresVocabulary.SET);
    private static final IRI CLEAR = RDF_FACTORY.createIRI(ArkresVocabulary.CLEAR);
    private static final IRI FIELD = RDF_FACTORY.createIRI(ArkresVocabulary.FIELD);
    private static final IRI VALUE = RDF_FACTORY.createIRI(ArkresVocabulary.VALUE);
    private static final IRI POSITION = RDF_FACTORY.createIRI(ArkresVocabulary.POSITION);

    private final ReadableGraph graph;
    private final List<Create> creates;
    private final Map<IRI, List<Activity>> changesByObject;

    private SubmittedDocument(final ReadableGraph graph, final List<Create> creates,
            final Map<IRI, List<Activity>> changesByObject) {
        this.graph = graph;
        this.creates = List.copyOf(creates);
        this.changesByObject = changesByObject;
    }

    /**
     * Reads the activities of a parsed document.
     *
     * @param graph  the parsed document
     * @param schema the resource types and owned fields
     * @return the document
     * @throws SubmitRejectedException naming every problem found
     */
    public static SubmittedDocument of(final ReadableGraph graph, final ResourceSchema schema) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(schema, "schema");
        List<String> problems = new ArrayList<>();
        List<Create> creates = new ArrayList<>();
        Map<IRI, List<Activity>> changes = new LinkedHashMap<>();
        Set<BlankNodeOrIRI> activityNodes = new HashSet<>();

        graph.stream(null, VocabRdf.TYPE, null).forEach(typing -> {
            Optional<Activity> activity = read(graph, schema, typing.getSubject(), typing.getObject(), problems);
            activity.ifPresent(found -> {
                activityNodes.add(typing.getSubject());
                if (found instanceof Create create) {
                    creates.add(create);
                } else {
                    changes.computeIfAbsent((IRI) found.object(), key -> new ArrayList<>()).add(found);
                }
            });
        });
        if (creates.isEmpty() && changes.isEmpty() && problems.isEmpty()) {
            problems.add("the document states no activity: give at least one node typed "
                    + ArkresVocabulary.CREATE + ", " + ArkresVocabulary.UPDATE + ", " + ArkresVocabulary.ADD_TO
                    + ", " + ArkresVocabulary.REMOVE_FROM + " or " + ArkresVocabulary.DELETE);
        }
        changes.forEach((object, activities) -> {
            if (activities.size() > 1 && activities.stream().anyMatch(Delete.class::isInstance)) {
                problems.add(object.getIRIString() + " is deleted and changed in the same document");
            }
        });
        strayTriples(graph, activityNodes).forEach(triple -> problems.add("the triple " + triple
                + " belongs to no activity; a stored resource changes only through an activity naming it"));
        if (!problems.isEmpty()) {
            throw new SubmitRejectedException(String.join("; ", problems));
        }
        return new SubmittedDocument(graph, creates, changes);
    }

    /**
     * The parsed document.
     *
     * @return the graph
     */
    public ReadableGraph graph() {
        return graph;
    }

    /**
     * The create activities.
     *
     * @return the creates
     */
    public List<Create> creates() {
        return creates;
    }

    /**
     * Every other activity, grouped by the existing resource it acts on.
     *
     * @return the activities by object
     */
    public Map<IRI, List<Activity>> changesByObject() {
        return changesByObject;
    }

    private static Optional<Activity> read(final ReadableGraph graph, final ResourceSchema schema,
            final BlankNodeOrIRI node, final RDFTerm type, final List<String> problems) {
        if (!(type instanceof IRI typeIri) || !typeIri.getIRIString().startsWith(ArkresVocabulary.NAMESPACE)) {
            return Optional.empty();
        }
        String kind = typeIri.getIRIString();
        List<RDFTerm> objects = values(graph, node, OBJECT);
        if (objects.size() != 1) {
            problems.add("an activity of type " + kind + " names exactly one " + ArkresVocabulary.OBJECT
                    + ", this one names " + objects.size());
            return Optional.empty();
        }
        RDFTerm object = objects.get(0);
        if (ArkresVocabulary.CREATE.equals(kind)) {
            return readCreate(graph, schema, object, problems);
        }
        if (!(object instanceof IRI target)) {
            problems.add("the " + ArkresVocabulary.OBJECT + " of an activity of type " + kind
                    + " is the IRI of an existing resource, not " + object);
            return Optional.empty();
        }
        return switch (kind) {
            case ArkresVocabulary.UPDATE -> readUpdate(graph, node, target, problems);
            case ArkresVocabulary.ADD_TO -> readAddTo(graph, node, target, problems);
            case ArkresVocabulary.REMOVE_FROM -> readRemoveFrom(graph, schema, node, target, problems);
            case ArkresVocabulary.DELETE -> Optional.of(new Delete(target));
            default -> {
                problems.add(kind + " is no activity this document can carry");
                yield Optional.empty();
            }
        };
    }

    private static Optional<Activity> readCreate(final ReadableGraph graph, final ResourceSchema schema,
            final RDFTerm object, final List<String> problems) {
        if (!(object instanceof BlankNode resource)) {
            problems.add("the " + ArkresVocabulary.OBJECT + " of a create is the new resource written inline as a"
                    + " blank node; the store mints its IRI, but this one is " + object);
            return Optional.empty();
        }
        Set<IRI> types = graph.stream(resource, VocabRdf.TYPE, null).map(Triple::getObject)
                .filter(IRI.class::isInstance).map(IRI.class::cast).collect(Collectors.toSet());
        Optional<ResourceSchema.ResourceType> type = schema.resourceTypeOf(types);
        if (type.isEmpty()) {
            problems.add("a created resource is typed with one resource type (" + String.join(", ",
                    schema.resourceTypes().stream().sorted().toList()) + "), this one with "
                    + types.stream().map(IRI::getIRIString).sorted().toList());
            return Optional.empty();
        }
        return Optional.of(new Create(resource, type.get()));
    }

    private static Optional<Activity> readUpdate(final ReadableGraph graph, final BlankNodeOrIRI node,
            final IRI target, final List<String> problems) {
        List<BlankNode> sets = new ArrayList<>();
        for (RDFTerm set : values(graph, node, SET)) {
            if (set instanceof BlankNode values) {
                sets.add(values);
            } else {
                problems.add("the " + ArkresVocabulary.SET + " of an update on " + target.getIRIString()
                        + " is a blank node carrying the new values, not " + set);
            }
        }
        List<IRI> clears = new ArrayList<>();
        for (RDFTerm clear : values(graph, node, CLEAR)) {
            if (clear instanceof IRI field) {
                clears.add(field);
            } else {
                problems.add("the " + ArkresVocabulary.CLEAR + " of an update on " + target.getIRIString()
                        + " names a field by its property IRI, not " + clear);
            }
        }
        if (sets.isEmpty() && clears.isEmpty()) {
            problems.add("an update on " + target.getIRIString() + " names at least one " + ArkresVocabulary.SET
                    + " or " + ArkresVocabulary.CLEAR);
            return Optional.empty();
        }
        return Optional.of(new Update(target, sets, clears));
    }

    private static Optional<Activity> readAddTo(final ReadableGraph graph, final BlankNodeOrIRI node,
            final IRI target, final List<String> problems) {
        Optional<IRI> field = field(graph, node, target, ArkresVocabulary.ADD_TO, problems);
        List<RDFTerm> values = values(graph, node, VALUE);
        if (values.size() != 1) {
            problems.add("an add-to on " + target.getIRIString() + " names exactly one " + ArkresVocabulary.VALUE
                    + ", this one names " + values.size());
            return Optional.empty();
        }
        return field.map(property -> new AddTo(target, property, values.get(0)));
    }

    private static Optional<Activity> readRemoveFrom(final ReadableGraph graph, final ResourceSchema schema,
            final BlankNodeOrIRI node, final IRI target, final List<String> problems) {
        Optional<IRI> field = field(graph, node, target, ArkresVocabulary.REMOVE_FROM, problems);
        if (field.isEmpty()) {
            return Optional.empty();
        }
        List<RDFTerm> values = values(graph, node, VALUE);
        List<RDFTerm> positions = values(graph, node, POSITION);
        boolean owned = schema.ownedField(field.get()).isPresent();
        if (owned) {
            OptionalInt position = positions.size() == 1 ? positionOf(positions.get(0)) : OptionalInt.empty();
            if (!values.isEmpty() || position.isEmpty()) {
                problems.add("a remove-from on " + field.get().getIRIString() + " of " + target.getIRIString()
                        + " names the child by exactly one " + ArkresVocabulary.POSITION
                        + " (a positive integer, counted before the document) and no " + ArkresVocabulary.VALUE);
                return Optional.empty();
            }
            return Optional.of(new RemoveFrom(target, field.get(), Optional.empty(), position));
        }
        if (values.size() != 1 || !positions.isEmpty()) {
            problems.add("a remove-from on " + field.get().getIRIString() + " of " + target.getIRIString()
                    + " names exactly one " + ArkresVocabulary.VALUE + " and no " + ArkresVocabulary.POSITION);
            return Optional.empty();
        }
        return Optional.of(new RemoveFrom(target, field.get(), Optional.of(values.get(0)), OptionalInt.empty()));
    }

    private static Optional<IRI> field(final ReadableGraph graph, final BlankNodeOrIRI node, final IRI target,
            final String kind, final List<String> problems) {
        List<RDFTerm> fields = values(graph, node, FIELD);
        if (fields.size() == 1 && fields.get(0) instanceof IRI field) {
            return Optional.of(field);
        }
        problems.add("an activity of type " + kind + " on " + target.getIRIString() + " names exactly one "
                + ArkresVocabulary.FIELD + " by its property IRI");
        return Optional.empty();
    }

    /**
     * Reads a position: a positive integer literal.
     *
     * @param term the term
     * @return the position, empty if the term is none
     */
    public static OptionalInt positionOf(final RDFTerm term) {
        if (term instanceof Literal literal && literal.getLexicalForm().matches("[0-9]{1,9}")) {
            int position = Integer.parseInt(literal.getLexicalForm());
            return position > 0 ? OptionalInt.of(position) : OptionalInt.empty();
        }
        return OptionalInt.empty();
    }

    private static List<RDFTerm> values(final ReadableGraph graph, final BlankNodeOrIRI node, final IRI property) {
        return graph.stream(node, property, null).map(Triple::getObject).toList();
    }

    private static List<Triple> strayTriples(final ReadableGraph graph, final Set<BlankNodeOrIRI> activityNodes) {
        Set<BlankNodeOrIRI> reachable = new HashSet<>(activityNodes);
        Deque<BlankNodeOrIRI> pending = new ArrayDeque<>(activityNodes);
        while (!pending.isEmpty()) {
            graph.stream(pending.pop(), null, null).map(Triple::getObject)
                    .filter(BlankNode.class::isInstance).map(BlankNode.class::cast)
                    .filter(reachable::add)
                    .forEach(pending::push);
        }
        return graph.stream().filter(triple -> !reachable.contains(triple.getSubject())).toList();
    }

    /** One activity of the document. */
    public sealed interface Activity permits Create, Update, AddTo, RemoveFrom, Delete {

        /**
         * The resource the activity acts on: the blank node of a create, the IRI otherwise.
         *
         * @return the object
         */
        BlankNodeOrIRI object();
    }

    /**
     * Creates a resource.
     *
     * @param object the new resource as written in the document
     * @param type   its resource type
     */
    public record Create(BlankNode object, ResourceSchema.ResourceType type) implements Activity {
    }

    /**
     * Replaces or removes values of fields.
     *
     * @param object the resource
     * @param sets   the blank nodes carrying new values
     * @param clears the fields whose values go
     */
    public record Update(IRI object, List<BlankNode> sets, List<IRI> clears) implements Activity {

        /**
         * Copies the lists.
         *
         * @param object the resource
         * @param sets   the blank nodes carrying new values
         * @param clears the fields whose values go
         */
        public Update {
            sets = List.copyOf(sets);
            clears = List.copyOf(clears);
        }
    }

    /**
     * Adds a value or a new child to a multi-valued field.
     *
     * @param object the resource
     * @param field  the field
     * @param value  the value, or the new child as a blank node
     */
    public record AddTo(IRI object, IRI field, RDFTerm value) implements Activity {
    }

    /**
     * Removes a value, or the child at a position, from a multi-valued field.
     *
     * @param object   the resource
     * @param field    the field
     * @param value    the value to remove, for a field that does not own its values
     * @param position the position before the document, for a field that owns its children
     */
    public record RemoveFrom(IRI object, IRI field, Optional<RDFTerm> value, OptionalInt position)
            implements Activity {
    }

    /**
     * Deletes a resource with everything it owns.
     *
     * @param object the resource
     */
    public record Delete(IRI object) implements Activity {
    }
}
