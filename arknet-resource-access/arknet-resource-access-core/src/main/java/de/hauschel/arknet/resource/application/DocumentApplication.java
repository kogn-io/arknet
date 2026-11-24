// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

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
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.persistence.ChildFragments;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;
import de.hauschel.arknet.resource.application.port.out.ResourceBatch;
import de.hauschel.arknet.resource.domain.ArkresVocabulary;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.ResourceSchema.OwnedField;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;
import de.hauschel.arknet.resource.domain.SubmittedDocument;
import de.hauschel.arknet.resource.domain.SubmittedDocument.Activity;
import de.hauschel.arknet.resource.domain.SubmittedDocument.AddTo;
import de.hauschel.arknet.resource.domain.SubmittedDocument.Create;
import de.hauschel.arknet.resource.domain.SubmittedDocument.Delete;
import de.hauschel.arknet.resource.domain.SubmittedDocument.RemoveFrom;
import de.hauschel.arknet.resource.domain.SubmittedDocument.Update;

/**
 * Applies one {@link SubmittedDocument} inside one {@link ResourceBatch}: computes the slice every
 * touched resource has after the document, then names the writes. One instance serves one attempt.
 *
 * <p><strong>End state first.</strong> All new slices are built before the first write, from the
 * state before the document; the document has no order, so no activity sees the effect of another.
 * Every write is then validated by the shapes together with an asserted context: the slices, after
 * the document, of every resource reachable over the written slice's references. A neighbour
 * asserted by its type alone would activate its own node shape and fail on fields the context does
 * not carry; its real slice passes, and a reference to a resource that does not exist after the
 * document finds no type and fails the shape's {@code sh:class}.</p>
 *
 * <p><strong>Known predicates.</strong> The document writes only fields: a create, an inline child,
 * a set and an add-to name only predicates some property shape gives the node's class or one of its
 * superclasses ({@link ResourceSchema#fields}, the fields {@code describe} lists), besides
 * {@code rdf:type}; an inline value written as a blank node is held to the classes it is typed with.
 * The shapes are open, so this is checked on the document, not on the stored slice: a stored
 * predicate outside the fields stays, and a clear or a remove-from may still take it away.</p>
 *
 * <p><strong>Delete guard.</strong> A resource is deleted only if no stored edge is left pointing at
 * it: a triple of another resource, or of one of its children, over a property the shapes or the
 * ontology make an edge to the deleted resource's class ({@link ResourceSchema#edgesTo}). An edge
 * whose holder the same document deletes, or whose slice after the document no longer carries it,
 * does not count; an edge the document itself writes, into a created or an updated resource, counts
 * like a stored one, whether or not its shape names a {@code sh:class} the gate would check. The gate cannot catch this itself: it validates what is written, and the holder
 * of the edge is not.</p>
 *
 * <p><strong>Codes and children.</strong> A created resource of a type with a code prefix gets the
 * next free code of that prefix, counted over living resources and codes kept by deleted ones. An
 * owned child is the fragment {@code <resource>#<kind>-<id>} of its resource and keeps that name
 * across every later write that keeps it (ADR-64); positions are renumbered {@code 1..n} on every
 * write that touches the field.</p>
 */
final class DocumentApplication {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final IRI IDENTIFIER = VocabDct.IDENTIFIER;

    private final SubmittedDocument document;
    private final ResourceSchema schema;
    private final ResourceBatch batch;
    private final ResourceIdFactory ids;
    private final ReadableGraph source;
    private final Map<BlankNode, IRI> minted = new HashMap<>();
    private final Map<IRI, Graph> after = new LinkedHashMap<>();
    private final Map<IRI, String> codes = new HashMap<>();
    private final Set<IRI> deleted = new HashSet<>();
    private final Map<IRI, Set<IRI>> deletedTypes = new HashMap<>();
    private final Map<Set<IRI>, Set<IRI>> fieldsByTypes = new HashMap<>();
    private final Map<String, Integer> nextNumber = new HashMap<>();
    private final List<String> problems = new ArrayList<>();

    DocumentApplication(final SubmittedDocument document, final ResourceSchema schema, final ResourceBatch batch,
            final ResourceIdFactory ids) {
        this.document = document;
        this.schema = schema;
        this.batch = batch;
        this.ids = ids;
        this.source = document.graph();
    }

    /**
     * Builds every new slice and names the writes on the batch.
     *
     * @return the changes, created resources first, then updated, then deleted
     */
    List<SubmittedChange> apply() {
        document.creates().forEach(create -> minted.put(create.object(), RDF_FACTORY.createIRI(ids.newId().value())));
        List<IRI> created = new ArrayList<>();
        for (Create create : document.creates()) {
            IRI subject = minted.get(create.object());
            after.put(subject, createdSlice(create, subject));
            created.add(subject);
        }
        List<IRI> updated = new ArrayList<>();
        document.changesByObject().forEach((object, activities) -> {
            Graph current = copy(batch.slice(object));
            if (current.isEmpty()) {
                problems.add("the store holds no resource " + object.getIRIString());
                return;
            }
            codeOf(current, object).ifPresent(code -> codes.put(object, code));
            if (activities.get(0) instanceof Delete) {
                deleted.add(object);
                deletedTypes.put(object, typesOf(current, object));
                return;
            }
            after.put(object, changedSlice(object, current, activities));
            updated.add(object);
        });
        deletedTypes.forEach(this::rejectEdgesLeftPointingAt);
        if (!problems.isEmpty()) {
            throw new SubmitRejectedException(String.join("; ", problems));
        }

        List<SubmittedChange> changes = new ArrayList<>();
        for (IRI subject : created) {
            batch.create(subject, codes.get(subject), after.get(subject), context(subject));
            changes.add(new SubmittedChange(SubmittedChange.Kind.CREATED, subject.getIRIString(), codes.get(subject)));
        }
        for (IRI subject : updated) {
            batch.update(subject, after.get(subject), context(subject));
            changes.add(new SubmittedChange(SubmittedChange.Kind.UPDATED, subject.getIRIString(), codes.get(subject)));
        }
        for (IRI subject : deleted) {
            batch.delete(subject, codes.get(subject));
            changes.add(new SubmittedChange(SubmittedChange.Kind.DELETED, subject.getIRIString(), codes.get(subject)));
        }
        return changes;
    }

    private Graph createdSlice(final Create create, final IRI subject) {
        Graph slice = RDF_FACTORY.createGraph();
        Map<IRI, List<BlankNode>> children = new LinkedHashMap<>();
        Set<IRI> types = typesOf(source, create.object());
        source.stream(create.object(), null, null).forEach(triple -> {
            IRI property = triple.getPredicate();
            if (IDENTIFIER.equals(property)) {
                problems.add("a created resource carries no " + IDENTIFIER.getIRIString()
                        + "; the store assigns its code");
                return;
            }
            if (!known(property, types, "a created resource")) {
                return;
            }
            Optional<OwnedField> owned = schema.ownedField(property);
            if (owned.isEmpty()) {
                addValue(slice, subject, property, triple.getObject());
                return;
            }
            if (triple.getObject() instanceof BlankNode child) {
                children.computeIfAbsent(property, key -> new ArrayList<>()).add(child);
            } else {
                problems.add(property.getIRIString() + " owns its children: a created resource writes each new"
                        + " child inline as a blank node, not " + triple.getObject());
            }
        });
        create.type().codePrefix().ifPresent(prefix -> {
            String code = prefix + next(prefix);
            codes.put(subject, code);
            slice.add(subject, IDENTIFIER, RDF_FACTORY.createLiteral(code));
        });
        ChildFragments fragments = batch.childFragments(subject, RDF_FACTORY.createGraph());
        children.forEach((property, nodes) -> {
            OwnedField field = schema.ownedField(property).orElseThrow();
            List<BlankNode> ordered = byRequestedPosition(field, nodes, subject, true);
            Map<Integer, IRI> names = fragments.name(field.childKind(), ordered.size(), Map.of());
            for (int index = 0; index < ordered.size(); index++) {
                addNewChild(slice, subject, field, ordered.get(index), names.get(index + 1), index + 1);
            }
        });
        return slice;
    }

    private Graph changedSlice(final IRI subject, final Graph slice, final List<Activity> activities) {
        Map<IRI, Set<Integer>> removedChildren = new HashMap<>();
        Map<IRI, List<BlankNode>> addedChildren = new LinkedHashMap<>();
        rejectConflictingFieldWrites(subject, activities);
        for (Activity activity : activities) {
            switch (activity) {
                case Update update -> applyUpdate(subject, slice, update);
                case AddTo addTo -> applyAddTo(subject, slice, addTo, addedChildren);
                case RemoveFrom removeFrom -> applyRemoveFrom(subject, slice, removeFrom, removedChildren);
                default -> problems.add(subject.getIRIString() + " is deleted and changed in the same document");
            }
        }
        Set<IRI> touched = new HashSet<>(removedChildren.keySet());
        touched.addAll(addedChildren.keySet());
        if (!touched.isEmpty()) {
            ChildFragments fragments = batch.childFragments(subject, copy(slice));
            touched.forEach(property -> reorder(subject, slice, schema.ownedField(property).orElseThrow(), fragments,
                    removedChildren.getOrDefault(property, Set.of()), addedChildren.getOrDefault(property, List.of())));
        }
        return slice;
    }

    /**
     * Rejects two activities on one field of one resource whose result would depend on the order they
     * are applied in - the document has none. Independent are only: sets of distinct languages, and
     * add-to/remove-from of distinct values of a list. A clear, a set without language tag, or a list
     * change next to any other write of the same field is a contradiction.
     */
    private void rejectConflictingFieldWrites(final IRI subject, final List<Activity> activities) {
        Map<IRI, List<String>> writesByField = new LinkedHashMap<>();
        for (Activity activity : activities) {
            switch (activity) {
                case Update update -> {
                    update.clears().forEach(field -> writesByField.computeIfAbsent(field, key -> new ArrayList<>())
                            .add("clear"));
                    for (BlankNode values : update.sets()) {
                        Map<IRI, Set<String>> languages = new LinkedHashMap<>();
                        source.stream(values, null, null).forEach(triple -> languages
                                .computeIfAbsent(triple.getPredicate(), key -> new HashSet<>())
                                .add(languageOf(triple.getObject()).map(tag -> "set@" + tag).orElse("set")));
                        languages.forEach((field, tags) -> writesByField
                                .computeIfAbsent(field, key -> new ArrayList<>())
                                .addAll(tags.contains("set") ? Set.of("set") : tags));
                    }
                }
                case AddTo addTo when schema.ownedField(addTo.field()).isEmpty() -> writesByField
                        .computeIfAbsent(addTo.field(), key -> new ArrayList<>()).add("list " + addTo.value());
                case RemoveFrom removeFrom when removeFrom.value().isPresent() -> writesByField
                        .computeIfAbsent(removeFrom.field(), key -> new ArrayList<>())
                        .add("list " + removeFrom.value().get());
                default -> {
                }
            }
        }
        writesByField.forEach((field, writes) -> {
            Set<String> kinds = new HashSet<>();
            writes.forEach(write -> kinds.add(write.startsWith("set@") ? "set@" : write.startsWith("list ")
                    ? "list" : write));
            boolean repeated = new HashSet<>(writes).size() < writes.size() && !kinds.equals(Set.of("clear"));
            if (kinds.size() > 1 || repeated) {
                problems.add("the activities on " + field.getIRIString() + " of " + subject.getIRIString()
                        + " contradict each other: a document has no order, so a field takes either one clear, or"
                        + " one set without language tag, or sets of distinct languages, or changes of distinct"
                        + " list values");
            }
        });
    }

    private void applyUpdate(final IRI subject, final Graph slice, final Update update) {
        for (IRI field : update.clears()) {
            if (writable(subject, field)) {
                removeValues(slice, subject, field, object -> true);
            }
        }
        for (BlankNode values : update.sets()) {
            Map<IRI, List<RDFTerm>> byField = new LinkedHashMap<>();
            source.stream(values, null, null)
                    .forEach(triple -> byField.computeIfAbsent(triple.getPredicate(), key -> new ArrayList<>())
                            .add(triple.getObject()));
            byField.forEach((field, newValues) -> {
                if (!writable(subject, field) || !known(field, typesOf(slice, subject), subject.getIRIString())) {
                    return;
                }
                Set<Optional<String>> languages = new HashSet<>();
                newValues.forEach(value -> languages.add(languageOf(value)));
                for (Optional<String> language : languages) {
                    removeValues(slice, subject, field, old -> language.isEmpty()
                            || language.equals(languageOf(old)));
                }
                newValues.forEach(value -> addValue(slice, subject, field, value));
            });
        }
    }

    private void applyAddTo(final IRI subject, final Graph slice, final AddTo addTo,
            final Map<IRI, List<BlankNode>> addedChildren) {
        if (schema.ownedField(addTo.field()).isPresent()) {
            if (addTo.value() instanceof BlankNode child && !minted.containsKey(child)) {
                addedChildren.computeIfAbsent(addTo.field(), key -> new ArrayList<>()).add(child);
            } else {
                problems.add(addTo.field().getIRIString() + " owns its children: an add-to writes the new child"
                        + " inline as a blank node, not " + addTo.value());
            }
            return;
        }
        if (!writable(subject, addTo.field())
                || !known(addTo.field(), typesOf(slice, subject), subject.getIRIString())) {
            return;
        }
        RDFTerm value = resolve(addTo.value());
        if (slice.contains(RDF_FACTORY.createTriple(subject, addTo.field(), value))) {
            problems.add(subject.getIRIString() + " already holds " + value + " in " + addTo.field().getIRIString());
            return;
        }
        addValue(slice, subject, addTo.field(), addTo.value());
    }

    private void applyRemoveFrom(final IRI subject, final Graph slice, final RemoveFrom removeFrom,
            final Map<IRI, Set<Integer>> removedChildren) {
        if (removeFrom.position().isPresent()) {
            if (!removedChildren.computeIfAbsent(removeFrom.field(), key -> new HashSet<>())
                    .add(removeFrom.position().getAsInt())) {
                problems.add("two remove-from activities name position " + removeFrom.position().getAsInt()
                        + " of " + removeFrom.field().getIRIString() + " of " + subject.getIRIString());
            }
            return;
        }
        if (!writable(subject, removeFrom.field())) {
            return;
        }
        RDFTerm value = resolve(removeFrom.value().orElseThrow());
        if (!slice.contains(RDF_FACTORY.createTriple(subject, removeFrom.field(), value))) {
            problems.add(subject.getIRIString() + " holds no " + value + " in " + removeFrom.field().getIRIString());
            return;
        }
        removeValues(slice, subject, removeFrom.field(), value::equals);
    }

    private void reorder(final IRI subject, final Graph slice, final OwnedField field, final ChildFragments fragments,
            final Set<Integer> removed, final List<BlankNode> added) {
        Map<Integer, IRI> stored = new TreeMap<>(fragments.storedByPosition(field.property(), field.orderedBy()));
        removed.stream().filter(position -> !stored.containsKey(position)).forEach(position -> problems.add(
                subject.getIRIString() + " holds no child at position " + position + " of "
                        + field.property().getIRIString()));
        List<Integer> keptPositions = stored.keySet().stream().filter(position -> !removed.contains(position)).toList();
        List<Object> order = new ArrayList<>(keptPositions);
        for (BlankNode child : byRequestedPosition(field, added, subject, false)) {
            OptionalInt requested = requestedPosition(field, child);
            int index = requested.isPresent() ? Math.min(requested.getAsInt() - 1, order.size()) : order.size();
            order.add(index, child);
        }
        Map<Integer, Integer> newPositionOf = new HashMap<>();
        for (int index = 0; index < order.size(); index++) {
            if (order.get(index) instanceof Integer storedPosition) {
                newPositionOf.put(storedPosition, index + 1);
            }
        }
        Map<Integer, IRI> kept = fragments.carried(field.property(), field.orderedBy(),
                storedPosition -> newPositionOf.containsKey(storedPosition)
                        ? OptionalInt.of(newPositionOf.get(storedPosition)) : OptionalInt.empty());
        Map<Integer, IRI> names = fragments.name(field.childKind(), order.size(), kept);

        stored.forEach((storedPosition, child) -> {
            if (removed.contains(storedPosition)) {
                removeChild(slice, child);
                removeValues(slice, subject, field.property(), child::equals);
            }
        });
        kept.forEach((position, child) -> setPosition(slice, child, field, position));
        for (int index = 0; index < order.size(); index++) {
            if (order.get(index) instanceof BlankNode child) {
                addNewChild(slice, subject, field, child, names.get(index + 1), index + 1);
            }
        }
    }

    private void addNewChild(final Graph slice, final IRI subject, final OwnedField field, final BlankNode child,
            final IRI name, final int position) {
        slice.add(subject, field.property(), name);
        source.stream(child, VocabRdf.TYPE, null).map(Triple::getObject)
                .filter(type -> !field.childClass().equals(type))
                .forEach(type -> problems.add("a child in " + field.property().getIRIString() + " is a "
                        + field.childClass().getIRIString() + " and nothing else, not " + type));
        Set<IRI> childTypes = Set.of(field.childClass());
        source.stream(child, null, null).forEach(triple -> {
            IRI property = triple.getPredicate();
            if (!property.equals(field.orderedBy()) && !VocabRdf.TYPE.equals(property)
                    && known(property, childTypes, "a new child in " + field.property().getIRIString())) {
                addValue(slice, name, property, triple.getObject());
            }
        });
        slice.add(name, VocabRdf.TYPE, field.childClass());
        setPosition(slice, name, field, position);
    }

    private void setPosition(final Graph slice, final IRI child, final OwnedField field, final int position) {
        removeValues(slice, child, field.orderedBy(), object -> true);
        slice.add(child, field.orderedBy(), RDF_FACTORY.createLiteral(Integer.toString(position), VocabXsd.INTEGER));
    }

    private static void removeChild(final Graph slice, final IRI child) {
        List<Triple> triples = slice.stream(child, null, null).toList();
        triples.forEach(triple -> {
            slice.remove(triple);
            if (triple.getObject() instanceof BlankNode node) {
                removeBlankNodeClosure(slice, node);
            }
        });
    }

    private List<BlankNode> byRequestedPosition(final OwnedField field, final List<BlankNode> children,
            final IRI subject, final boolean required) {
        Set<Integer> seen = new HashSet<>();
        for (BlankNode child : children) {
            OptionalInt position = requestedPosition(field, child);
            if (position.isEmpty() && required) {
                problems.add("every new child in " + field.property().getIRIString() + " of a created resource"
                        + " carries its position as " + field.orderedBy().getIRIString() + " (1, 2, ...)");
            }
            if (position.isPresent() && !seen.add(position.getAsInt())) {
                problems.add("two new children in " + field.property().getIRIString() + " of "
                        + subject.getIRIString() + " ask for position " + position.getAsInt());
            }
        }
        return children.stream()
                .sorted(Comparator.comparingInt(child -> requestedPosition(field, child).orElse(Integer.MAX_VALUE)))
                .toList();
    }

    private OptionalInt requestedPosition(final OwnedField field, final BlankNode child) {
        return source.stream(child, field.orderedBy(), null).map(Triple::getObject)
                .map(SubmittedDocument::positionOf).filter(OptionalInt::isPresent).findFirst()
                .orElse(OptionalInt.empty());
    }

    private boolean writable(final IRI subject, final IRI field) {
        if (VocabRdf.TYPE.equals(field) || IDENTIFIER.equals(field)) {
            problems.add(field.getIRIString() + " of " + subject.getIRIString()
                    + " is not changed by an activity: the type stays, the store assigns the code");
            return false;
        }
        if (schema.ownedField(field).isPresent()) {
            problems.add(field.getIRIString() + " owns its children: change it with " + ArkresVocabulary.ADD_TO
                    + " and " + ArkresVocabulary.REMOVE_FROM + ", not with an update");
            return false;
        }
        return true;
    }

    private void addValue(final Graph slice, final BlankNodeOrIRI subject, final IRI property, final RDFTerm value) {
        RDFTerm resolved = resolve(value);
        slice.add(subject, property, resolved);
        if (value instanceof BlankNode node && !minted.containsKey(node)) {
            Set<IRI> types = typesOf(source, node);
            source.stream(node, null, null)
                    .filter(triple -> known(triple.getPredicate(), types,
                            "a blank node written inline as the value of " + property.getIRIString()))
                    .forEach(triple -> addValue(slice, node, triple.getPredicate(), triple.getObject()));
        }
    }

    /**
     * Whether a document may write {@code property} on a node typed with {@code types}: it is
     * {@code rdf:type} or a field a shape gives one of the types or a superclass. Records the
     * rejection otherwise.
     */
    private boolean known(final IRI property, final Set<IRI> types, final String node) {
        if (VocabRdf.TYPE.equals(property)
                || fieldsByTypes.computeIfAbsent(types, schema::fields).contains(property)) {
            return true;
        }
        problems.add(property.getIRIString() + " is no field of " + node + " typed "
                + types.stream().map(IRI::getIRIString).sorted().toList() + ": no shape of those classes or"
                + " their superclasses names it, so a document does not write it; describe lists the fields,"
                + " and a stored value outside them can still be cleared");
        return false;
    }

    /**
     * Records a rejection for every edge left pointing at {@code target} after the document: a stored
     * edge to its class whose holder is neither deleted nor rid of it by the same document, and an
     * edge to its class the same document writes into a created or an updated resource.
     */
    private void rejectEdgesLeftPointingAt(final IRI target, final Set<IRI> types) {
        Set<IRI> edges = schema.edgesTo(types);
        Map<String, Set<String>> predicatesByHolder = new TreeMap<>();
        batch.pointingAt(target).stream()
                .filter(edge -> edges.contains(edge.getPredicate()))
                .filter(edge -> !(edge.getSubject() instanceof IRI holder) || leftAfter(edge, holder, target))
                .forEach(edge -> predicatesByHolder.computeIfAbsent(edge.getSubject() instanceof IRI holder
                        ? holder.getIRIString() : "a stored blank node", key -> new TreeSet<>())
                        .add(edge.getPredicate().getIRIString()));
        after.forEach((resource, slice) -> slice.stream(null, null, target)
                .filter(edge -> edges.contains(edge.getPredicate()))
                .forEach(edge -> predicatesByHolder.computeIfAbsent(holderOf(edge, resource), key -> new TreeSet<>())
                        .add(edge.getPredicate().getIRIString())));
        predicatesByHolder.forEach((holder, predicates) -> problems.add(target.getIRIString() + " is not deleted: "
                + holder + " still points at it over " + String.join(", ", predicates)
                + "; delete that resource or remove the edge in the same document, or keep "
                + target.getIRIString()));
    }

    /**
     * A stored edge counts if the resource holding it, or holding the child that does, is not the
     * deleted resource and still carries the edge after the document; a resource the document
     * deletes carries nothing after it.
     */
    private boolean leftAfter(final Triple edge, final IRI holder, final IRI target) {
        IRI resource = resourceOf(holder);
        return !resource.equals(target) && stateAfter(resource).contains(edge);
    }

    /** Names the holder of an edge in a slice the document writes: the node, or its resource. */
    private String holderOf(final Triple edge, final IRI resource) {
        String created = minted.containsValue(resource) ? " (created by this document)" : "";
        return edge.getSubject() instanceof IRI holder ? holder.getIRIString() + created
                : "a blank node of " + resource.getIRIString() + created;
    }

    private static Set<IRI> typesOf(final ReadableGraph graph, final BlankNodeOrIRI node) {
        return graph.stream(node, VocabRdf.TYPE, null).map(Triple::getObject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .collect(Collectors.toUnmodifiableSet());
    }

    private RDFTerm resolve(final RDFTerm value) {
        if (value instanceof BlankNode node && minted.containsKey(node)) {
            return minted.get(node);
        }
        return value;
    }

    private static void removeValues(final Graph slice, final BlankNodeOrIRI subject, final IRI property,
            final java.util.function.Predicate<RDFTerm> which) {
        List<Triple> doomed = slice.stream(subject, property, null).filter(triple -> which.test(triple.getObject()))
                .toList();
        doomed.forEach(triple -> {
            slice.remove(triple);
            if (triple.getObject() instanceof BlankNode node) {
                removeBlankNodeClosure(slice, node);
            }
        });
    }

    private static void removeBlankNodeClosure(final Graph slice, final BlankNode node) {
        Deque<BlankNode> pending = new ArrayDeque<>(List.of(node));
        while (!pending.isEmpty()) {
            List<Triple> triples = slice.stream(pending.pop(), null, null).toList();
            triples.forEach(triple -> {
                slice.remove(triple);
                if (triple.getObject() instanceof BlankNode next) {
                    pending.push(next);
                }
            });
        }
    }

    private static Optional<String> languageOf(final RDFTerm term) {
        return term instanceof Literal literal ? literal.getLanguageTag() : Optional.empty();
    }

    private int next(final String prefix) {
        int number = nextNumber.computeIfAbsent(prefix, key -> batch.highestRunningNumber(key)) + 1;
        nextNumber.put(prefix, number);
        return number;
    }

    private static Optional<String> codeOf(final ReadableGraph slice, final IRI subject) {
        return slice.stream(subject, IDENTIFIER, null).map(Triple::getObject).filter(Literal.class::isInstance)
                .map(object -> ((Literal) object).getLexicalForm()).findFirst();
    }

    private ReadableGraph context(final IRI subject) {
        Graph context = RDF_FACTORY.createGraph();
        Set<IRI> seen = new HashSet<>(Set.of(subject));
        Deque<IRI> pending = new ArrayDeque<>(references(after.get(subject), subject));
        while (!pending.isEmpty()) {
            IRI neighbour = resourceOf(pending.pop());
            if (!seen.add(neighbour)) {
                continue;
            }
            ReadableGraph slice = stateAfter(neighbour);
            slice.stream().forEach(context::add);
            pending.addAll(references(slice, neighbour));
        }
        return context;
    }

    private ReadableGraph stateAfter(final IRI resource) {
        if (deleted.contains(resource)) {
            return RDF_FACTORY.createGraph();
        }
        Graph changed = after.get(resource);
        return changed != null ? changed : batch.slice(resource);
    }

    private static List<IRI> references(final ReadableGraph slice, final IRI subject) {
        String ownFragments = subject.getIRIString() + "#";
        return slice.stream()
                .filter(triple -> !VocabRdf.TYPE.equals(triple.getPredicate()))
                .map(Triple::getObject)
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .filter(object -> !object.equals(subject) && !object.getIRIString().startsWith(ownFragments))
                .distinct()
                .toList();
    }

    private static IRI resourceOf(final IRI iri) {
        int hash = iri.getIRIString().indexOf('#');
        return hash < 0 ? iri : RDF_FACTORY.createIRI(iri.getIRIString().substring(0, hash));
    }

    private static Graph copy(final ReadableGraph graph) {
        Graph copy = RDF_FACTORY.createGraph();
        graph.stream().forEach(copy::add);
        return copy;
    }
}
