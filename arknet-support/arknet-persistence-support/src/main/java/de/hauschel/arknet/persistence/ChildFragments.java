// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.IntFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabDct;

/**
 * Names the child resources of a parent resource (ADR-64): an acceptance criterion, a use-case
 * step or extension, a consequence or a considered option is the fragment
 * {@code <parent>#<kind>-<n>} of its parent's IRI.
 *
 * <p><strong>Stable identity.</strong> The number {@code n} is issued once, when the child is
 * created: one more than the highest number of that kind ever issued under the parent. It is
 * never reused, not even after the child was removed, so an outside reference to a removed child
 * never silently points at a newer one. The child's place in its list is a separate datum (its
 * position triple), never read from the name; reordering or removing a sibling leaves the name
 * alone.</p>
 *
 * <p><strong>Where the issued numbers live.</strong> {@link VersionedWriteFunnel} records every
 * fragment IRI a written slice holds as {@code <parent> dcterms:hasPart <fragment>} in
 * {@link #ISSUED_FRAGMENTS_GRAPH}, in the same transaction as the slice. The records outlive the
 * child and the parent alike; they are bookkeeping, not model content, and no read path of the
 * model shows them.</p>
 */
public final class ChildFragments {

    /** The graph holding every fragment IRI ever issued, one {@code dcterms:hasPart} triple each. */
    public static final String ISSUED_FRAGMENTS_GRAPH = "https://w3id.org/arknet/model/issued-fragments";

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final IRI ISSUED_GRAPH = RDF_FACTORY.createIRI(ISSUED_FRAGMENTS_GRAPH);
    private static final IRI HAS_PART = RDF_FACTORY.createIRI(VocabDct.NAMESPACE + "hasPart");
    private static final Pattern NUMBERED = Pattern.compile("([A-Za-z]+)-([0-9]{1,9})");

    private final IRI parent;
    private final ReadableGraph currentDraft;
    private final Set<String> issued;

    private ChildFragments(IRI parent, ReadableGraph currentDraft, Set<String> issued) {
        this.parent = parent;
        this.currentDraft = currentDraft;
        this.issued = issued;
    }

    /**
     * Reads what is issued under {@code parent}: the recorded fragments plus every fragment the
     * current draft holds (a draft written before the records existed counts as issued too).
     *
     * @param tx           the live write transaction
     * @param parent       the parent's IRI, without a {@code #}
     * @param currentDraft the parent's current draft slice, empty for a create
     * @return the naming state of this parent
     */
    public static ChildFragments of(DatasetTx tx, IRI parent, ReadableGraph currentDraft) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(currentDraft, "currentDraft");
        Set<String> issued = new HashSet<>();
        tx.match(ISSUED_GRAPH, parent, HAS_PART, null).stream()
                .map(Triple::getObject)
                .filter(IRI.class::isInstance)
                .map(object -> ((IRI) object).getIRIString())
                .forEach(issued::add);
        currentDraft.stream().forEach(triple -> {
            addIfFragment(issued, parent, triple.getSubject());
            addIfFragment(issued, parent, triple.getObject());
        });
        return new ChildFragments(parent, currentDraft, issued);
    }

    /**
     * The children of one kind that keep their name in the next slice: the stored child at former
     * position {@code p} keeps its fragment IRI at {@code newPosition.apply(p)}; a child the
     * function maps to nothing is removed and keeps nothing. A stored child that is no fragment of
     * the parent (a store-first node) keeps nothing either and is named afresh.
     *
     * @param edge             the parent's edge to the children of this kind
     * @param positionProperty the children's position property
     * @param newPosition      the position a stored child holds after this write, or empty
     * @return the kept fragment IRIs by new position
     */
    public Map<Integer, IRI> carried(IRI edge, IRI positionProperty, IntFunction<OptionalInt> newPosition) {
        Objects.requireNonNull(edge, "edge");
        Objects.requireNonNull(positionProperty, "positionProperty");
        Objects.requireNonNull(newPosition, "newPosition");
        Map<Integer, IRI> kept = new LinkedHashMap<>();
        storedByPosition(edge, positionProperty).forEach((storedPosition, child) -> {
            OptionalInt position = newPosition.apply(storedPosition);
            if (position.isPresent()) {
                kept.putIfAbsent(position.getAsInt(), child);
            }
        });
        return kept;
    }

    /**
     * The stored children of one kind by their stored position, fragments of the parent only.
     *
     * @param edge             the parent's edge to the children of this kind
     * @param positionProperty the children's position property
     * @return the stored fragment IRIs by stored position
     */
    public Map<Integer, IRI> storedByPosition(IRI edge, IRI positionProperty) {
        Map<Integer, IRI> stored = new LinkedHashMap<>();
        currentDraft.stream(parent, edge, null)
                .map(Triple::getObject)
                .filter(this::isFragment)
                .map(IRI.class::cast)
                .forEach(child -> positionOf(child, positionProperty)
                        .ifPresent(position -> stored.putIfAbsent(position, child)));
        return stored;
    }

    /**
     * Names the children of one kind at positions {@code 1..count}: a position in {@code kept}
     * keeps that IRI, every other position gets the next number of {@code kind} not yet issued.
     *
     * @param kind  the kind's local-name prefix, e.g. {@code AC}
     * @param count the number of children the next slice holds
     * @param kept  the kept fragment IRIs by new position, see {@link #carried}
     * @return the fragment IRI of each position, in position order
     */
    public Map<Integer, IRI> name(String kind, int count, Map<Integer, IRI> kept) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(kept, "kept");
        int next = highestIssued(kind) + 1;
        Map<Integer, IRI> named = new LinkedHashMap<>();
        for (int position = 1; position <= count; position++) {
            IRI child = kept.get(position);
            if (child == null) {
                child = RDF_FACTORY.createIRI(parent.getIRIString() + "#" + kind + "-" + next);
                next++;
            }
            named.put(position, child);
        }
        return named;
    }

    /**
     * Names the children of a new parent: {@code kind-1} to {@code kind-count}.
     *
     * @param parent the parent's IRI, without a {@code #}
     * @param kind   the kind's local-name prefix
     * @param count  the number of children
     * @return the fragment IRI of each position, in position order
     */
    public static Map<Integer, IRI> forNewParent(IRI parent, String kind, int count) {
        return new ChildFragments(parent, RDF_FACTORY.createGraph(), Set.of()).name(kind, count, Map.of());
    }

    /**
     * Records every fragment of {@code parent} that {@code slice} holds as issued. Called by
     * {@link VersionedWriteFunnel} for each slice it writes.
     *
     * @param tx     the live write transaction
     * @param parent the parent's IRI, without a {@code #}
     * @param slice  the slice being written
     */
    static void recordIssued(DatasetTx tx, IRI parent, ReadableGraph slice) {
        Set<String> fragments = new HashSet<>();
        slice.stream().forEach(triple -> {
            addIfFragment(fragments, parent, triple.getSubject());
            addIfFragment(fragments, parent, triple.getObject());
        });
        if (fragments.isEmpty()) {
            return;
        }
        Graph records = RDF_FACTORY.createGraph();
        fragments.forEach(fragment -> records.add(parent, HAS_PART, RDF_FACTORY.createIRI(fragment)));
        tx.add(ISSUED_GRAPH, records);
    }

    private int highestIssued(String kind) {
        int highest = 0;
        String prefix = parent.getIRIString() + "#";
        for (String fragment : issued) {
            Matcher matcher = NUMBERED.matcher(fragment.substring(prefix.length()));
            if (matcher.matches() && matcher.group(1).equals(kind)) {
                highest = Math.max(highest, Integer.parseInt(matcher.group(2)));
            }
        }
        return highest;
    }

    private boolean isFragment(RDFTerm term) {
        return term instanceof IRI iri && iri.getIRIString().startsWith(parent.getIRIString() + "#");
    }

    private OptionalInt positionOf(IRI child, IRI positionProperty) {
        return currentDraft.stream(child, positionProperty, null)
                .map(Triple::getObject)
                .filter(Literal.class::isInstance)
                .map(object -> ((Literal) object).getLexicalForm())
                .filter(lexical -> lexical.matches("[0-9]{1,9}"))
                .mapToInt(Integer::parseInt)
                .findFirst();
    }

    private static void addIfFragment(Set<String> target, IRI parent, RDFTerm term) {
        if (term instanceof IRI iri && iri.getIRIString().startsWith(parent.getIRIString() + "#")) {
            target.add(iri.getIRIString());
        }
    }
}
