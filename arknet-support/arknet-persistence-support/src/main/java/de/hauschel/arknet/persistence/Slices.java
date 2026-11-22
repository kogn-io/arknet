// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;

/**
 * In-memory edits of a draft slice, for a {@link VersionedWriteFunnel.SliceBuilder} that patches
 * a few predicates of the current draft instead of rebuilding the whole resource: copy the draft,
 * drop the values being replaced, add the new ones.
 */
public final class Slices {

    private Slices() {
    }

    /**
     * Copies a graph into a new, mutable one.
     *
     * @param source the graph to copy
     * @return a new graph holding every triple of {@code source}
     */
    public static Graph copyOf(ReadableGraph source) {
        Objects.requireNonNull(source, "source");
        Graph copy = new SimpleRdf().createGraph();
        source.stream().forEach(copy::add);
        return copy;
    }

    /**
     * Adds every triple of {@code source} to {@code target}.
     *
     * @param target the graph to add to
     * @param source the triples to add
     */
    public static void addAll(Graph target, ReadableGraph source) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(source, "source");
        source.stream().forEach(target::add);
    }

    /**
     * Carries a blank node of the current draft over into the new slice: every triple of
     * {@code draft} whose subject is {@code node}, and so on for every blank node those triples
     * reach. A blank node reachable from the subject is part of its slice, so an edge kept onto
     * one must keep the node's own triples too, or the write would drop them. An IRI or literal
     * {@code node} adds nothing.
     *
     * @param target the new slice
     * @param draft  the current draft the node is read from
     * @param node   the edge target being kept
     */
    public static void addBlankNodeClosure(Graph target, ReadableGraph draft, RDFTerm node) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(draft, "draft");
        java.util.Deque<BlankNode> pending = new java.util.ArrayDeque<>();
        java.util.Set<BlankNode> seen = new java.util.HashSet<>();
        if (node instanceof BlankNode blankNode) {
            pending.push(blankNode);
        }
        while (!pending.isEmpty()) {
            BlankNode current = pending.pop();
            if (!seen.add(current)) {
                continue;
            }
            draft.stream(current, null, null).forEach(triple -> {
                target.add(triple);
                if (triple.getObject() instanceof BlankNode next) {
                    pending.push(next);
                }
            });
        }
    }

    /**
     * Removes every triple {@code subject predicate ?o} whose object passes {@code objectFilter}.
     *
     * @param graph        the graph to edit
     * @param subject      the subject
     * @param predicate    the predicate
     * @param objectFilter selects the objects to remove
     */
    public static void removeAll(Graph graph, BlankNodeOrIRI subject, IRI predicate,
            Predicate<RDFTerm> objectFilter) {
        Objects.requireNonNull(graph, "graph");
        Objects.requireNonNull(objectFilter, "objectFilter");
        List<Triple> doomed = graph.stream(subject, predicate, null)
                .filter(triple -> objectFilter.test(triple.getObject()))
                .toList();
        doomed.forEach(graph::remove);
    }

    /**
     * Removes every triple {@code subject predicate ?o}.
     *
     * @param graph     the graph to edit
     * @param subject   the subject
     * @param predicate the predicate
     */
    public static void removeAll(Graph graph, BlankNodeOrIRI subject, IRI predicate) {
        removeAll(graph, subject, predicate, object -> true);
    }
}
