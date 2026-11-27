// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.kogn.rdf.terms.BlankNode;
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

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.resource.application.port.out.ResourceReader;

/**
 * An in-memory {@link ResourceReader}: the content graph, the history and the deletions are what the
 * test puts in. A slice is a subject, its {@code #} fragments and the blank nodes they reach, as in the
 * store.
 */
final class FakeReader implements ResourceReader {

    private static final RDF RDF_FACTORY = new SimpleRdf();

    final Graph content = RDF_FACTORY.createGraph();
    final Map<IRI, List<HistoryEntry>> histories = new HashMap<>();
    final Map<IRI, Deletion> deletions = new HashMap<>();
    final Map<String, IRI> retainedCodes = new HashMap<>();
    int reads;

    @Override
    public <T> T read(final ProjectId projectId, final Function<Snapshot, T> reading) {
        reads++;
        return reading.apply(new InMemory());
    }

    private final class InMemory implements Snapshot {

        @Override
        public ReadableGraph slice(final IRI resource) {
            Graph slice = RDF_FACTORY.createGraph();
            content.stream().filter(triple -> triple.getSubject() instanceof IRI subject
                    && (subject.equals(resource)
                            || subject.getIRIString().startsWith(resource.getIRIString() + "#")))
                    .forEach(slice::add);
            List<BlankNode> pending = new ArrayList<>(slice.stream().map(Triple::getObject)
                    .filter(BlankNode.class::isInstance).map(BlankNode.class::cast).toList());
            while (!pending.isEmpty()) {
                BlankNode node = pending.remove(pending.size() - 1);
                content.stream(node, null, null).filter(triple -> !slice.contains(triple)).forEach(triple -> {
                    slice.add(triple);
                    if (triple.getObject() instanceof BlankNode next) {
                        pending.add(next);
                    }
                });
            }
            return slice;
        }

        @Override
        public ReadableGraph pointingAt(final IRI resource) {
            Graph pointing = RDF_FACTORY.createGraph();
            content.stream(null, null, resource).forEach(pointing::add);
            return pointing;
        }

        @Override
        public Optional<IRI> holding(final BlankNode node) {
            Set<RDFTerm> visited = new HashSet<>();
            List<RDFTerm> pending = new ArrayList<>(List.of(node));
            while (!pending.isEmpty()) {
                RDFTerm current = pending.remove(pending.size() - 1);
                if (!visited.add(current)) {
                    continue;
                }
                for (Triple triple : content.stream(null, null, current).toList()) {
                    if (triple.getSubject() instanceof IRI subject) {
                        return Optional.of(subject);
                    }
                    pending.add(triple.getSubject());
                }
            }
            return Optional.empty();
        }

        @Override
        public List<IRI> withCode(final String code) {
            return content.stream(null, VocabDct.IDENTIFIER, RDF_FACTORY.createLiteral(code))
                    .map(Triple::getSubject)
                    .map(IRI.class::cast)
                    .distinct()
                    .toList();
        }

        @Override
        public Optional<IRI> deletedWithCode(final String code) {
            return Optional.ofNullable(retainedCodes.get(code));
        }

        @Override
        public Optional<Deletion> deletion(final IRI resource) {
            return Optional.ofNullable(deletions.get(resource));
        }

        @Override
        public List<HistoryEntry> history(final IRI resource) {
            return histories.getOrDefault(resource, List.of());
        }

        @Override
        public String publishState(final IRI resource) {
            if (deletions.containsKey(resource)) {
                return "DELETED";
            }
            return histories.containsKey(resource) ? "PUBLISHED_UNCHANGED" : "NEVER_PUBLISHED";
        }

        @Override
        public Set<IRI> typedWith(final Set<IRI> classes) {
            return content.stream(null, VocabRdf.TYPE, null)
                    .filter(triple -> classes.contains(triple.getObject()))
                    .map(Triple::getSubject)
                    .map(IRI.class::cast)
                    .collect(Collectors.toSet());
        }

        @Override
        public ReadableGraph literalsContaining(final String text) {
            String needle = text.toLowerCase(Locale.ROOT);
            Graph found = RDF_FACTORY.createGraph();
            List<Triple> matches = new ArrayList<>();
            content.stream().filter(triple -> triple.getObject() instanceof Literal literal
                    && literal.getLexicalForm().toLowerCase(Locale.ROOT).contains(needle))
                    .forEach(matches::add);
            matches.forEach(found::add);
            return found;
        }
    }
}
