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
import java.util.regex.Pattern;

import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * Names the child resources of a parent resource (ADR-64): an acceptance criterion, a use-case
 * step or extension, a consequence or a considered option is the fragment
 * {@code <parent>#<kind>-<id>} of its parent's IRI, {@code <id>} an opaque id from a
 * {@link ChildIdSource}.
 *
 * <p><strong>Stable identity.</strong> The id is minted once, when the child is created, and the
 * child keeps it across every later write that keeps the child. It is opaque, no counter: a
 * random UUID, as for the top-level resource IRIs, so a removed child's name never comes back
 * and no record of issued names is needed. The child's place in its list is a separate datum (its
 * position triple), never read from the name; reordering or removing a sibling leaves the name
 * alone.</p>
 *
 * <p><strong>No collision under one parent.</strong> A minted name the current draft already
 * holds, or this instance already handed out, is discarded and a fresh id drawn. One instance
 * serves one write: obtain it from {@link VersionedWriteFunnel#childFragments}, which supplies
 * the funnel's id source.</p>
 */
public final class ChildFragments {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9-]+");
    private static final int MAX_ATTEMPTS = 16;

    private final IRI parent;
    private final ReadableGraph currentDraft;
    private final ChildIdSource ids;
    private final Set<String> taken;

    private ChildFragments(IRI parent, ReadableGraph currentDraft, ChildIdSource ids, Set<String> taken) {
        this.parent = parent;
        this.currentDraft = currentDraft;
        this.ids = ids;
        this.taken = taken;
    }

    /**
     * The naming state of {@code parent} for one write: the names its current draft holds are
     * taken.
     *
     * @param parent       the parent's IRI, without a {@code #}
     * @param currentDraft the parent's current draft slice, empty for a create
     * @param ids          the source of the opaque ids of new children
     * @return the naming state of this parent
     */
    public static ChildFragments of(IRI parent, ReadableGraph currentDraft, ChildIdSource ids) {
        Objects.requireNonNull(parent, "parent");
        Objects.requireNonNull(currentDraft, "currentDraft");
        Objects.requireNonNull(ids, "ids");
        Set<String> taken = new HashSet<>();
        currentDraft.stream().forEach(triple -> {
            addIfFragment(taken, parent, triple.getSubject());
            addIfFragment(taken, parent, triple.getObject());
        });
        return new ChildFragments(parent, currentDraft, ids, taken);
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
     * keeps that IRI, every other position gets a fresh {@code kind-<id>} no child of the parent
     * holds.
     *
     * @param kind  the kind's local-name prefix, e.g. {@code AC}
     * @param count the number of children the next slice holds
     * @param kept  the kept fragment IRIs by new position, see {@link #carried}
     * @return the fragment IRI of each position, in position order
     * @throws IllegalStateException if the id source yields no free, well-formed id within a few
     *                               attempts
     */
    public Map<Integer, IRI> name(String kind, int count, Map<Integer, IRI> kept) {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(kept, "kept");
        Map<Integer, IRI> named = new LinkedHashMap<>();
        for (int position = 1; position <= count; position++) {
            IRI child = kept.get(position);
            named.put(position, child == null ? mint(kind) : child);
        }
        return named;
    }

    private IRI mint(String kind) {
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            String id = ids.next();
            if (id == null || !ID.matcher(id).matches()) {
                throw new IllegalStateException("child id source yielded a malformed id: " + id);
            }
            String fragment = parent.getIRIString() + "#" + kind + "-" + id;
            if (taken.add(fragment)) {
                return RDF_FACTORY.createIRI(fragment);
            }
        }
        throw new IllegalStateException("child id source yielded no free id under " + parent.getIRIString()
                + " in " + MAX_ATTEMPTS + " attempts");
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
