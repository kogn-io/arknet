// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.DeletedResource;
import de.hauschel.arknet.persistence.RdfNode;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.VersionEntry;
import de.hauschel.arknet.resource.application.port.out.ResourceReader;

/**
 * {@link ResourceReader} over the generic store read path of {@code arknet-persistence-support}:
 * every call runs in one read transaction ({@link StoreReader#read}), the model in the project's
 * content graph, history and deletions in the library versioning.
 */
final class KognioRdfResourceReader implements ResourceReader {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final IRI CONTENT_GRAPH = RDF_FACTORY.createIRI(ContentGraph.GRAPH_IRI);
    private static final String BLANK_NODE_PREFIX = "_:";

    private final StoreReader storeReader;

    KognioRdfResourceReader(final StoreReader storeReader) {
        this.storeReader = Objects.requireNonNull(storeReader, "storeReader");
    }

    @Override
    public <T> T read(final ProjectId projectId, final Function<Snapshot, T> reading) {
        Objects.requireNonNull(reading, "reading");
        return storeReader.read(projectId, tx -> reading.apply(new InTransaction(tx)));
    }

    /** The reads of one transaction. */
    private final class InTransaction implements Snapshot {

        private final DatasetTx tx;

        private InTransaction(final DatasetTx tx) {
            this.tx = tx;
        }

        @Override
        public ReadableGraph slice(final IRI resource) {
            return storeReader.draft(tx, resource.getIRIString());
        }

        @Override
        public ReadableGraph pointingAt(final IRI resource) {
            return copy(tx.match(CONTENT_GRAPH, null, null, resource));
        }

        @Override
        public List<IRI> withCode(final String code) {
            return storeReader.findByIdentifier(tx, code).stream()
                    .filter(subject -> !subject.startsWith(BLANK_NODE_PREFIX))
                    .map(RDF_FACTORY::createIRI)
                    .toList();
        }

        @Override
        public Optional<IRI> deletedWithCode(final String code) {
            return storeReader.findDeletedByCode(tx, code).map(RDF_FACTORY::createIRI);
        }

        @Override
        public Optional<Deletion> deletion(final IRI resource) {
            return storeReader.findDeleted(tx, resource.getIRIString()).map(deleted -> toDeletion(resource, deleted));
        }

        @Override
        public List<HistoryEntry> history(final IRI resource) {
            return storeReader.history(tx, resource.getIRIString()).stream()
                    .map(KognioRdfResourceReader::entry)
                    .toList();
        }

        @Override
        public String publishState(final IRI resource) {
            return storeReader.publishState(tx, resource.getIRIString());
        }

        @Override
        public Set<IRI> typedWith(final Set<IRI> classes) {
            return classes.stream()
                    .flatMap(type -> tx.match(CONTENT_GRAPH, null, VocabRdf.TYPE, type).stream())
                    .map(Triple::getSubject)
                    .filter(IRI.class::isInstance)
                    .map(IRI.class::cast)
                    .filter(subject -> subject.getIRIString().indexOf('#') < 0)
                    .collect(Collectors.toUnmodifiableSet());
        }

        @Override
        public ReadableGraph literalsContaining(final String text) {
            Graph found = RDF_FACTORY.createGraph();
            storeReader.literalContaining(tx, text).forEach(triple -> found.add(triple(triple)));
            return found;
        }
    }

    private static Deletion toDeletion(final IRI resource, final DeletedResource deleted) {
        Graph lastVersion = RDF_FACTORY.createGraph();
        deleted.lastStatements().forEach(statement -> lastVersion.add(triple(statement)));
        return new Deletion(resource, deleted.optionalDeletedAt(), deleted.optionalComment(), deleted.identifier(),
                lastVersion);
    }

    private static HistoryEntry entry(final VersionEntry entry) {
        return new HistoryEntry(entry.sequence(), entry.tombstone(), entry.created(), Optional.ofNullable(entry.cid()),
                Optional.ofNullable(entry.comment()));
    }

    private static Graph copy(final ReadableGraph source) {
        Graph copy = RDF_FACTORY.createGraph();
        source.stream().forEach(copy::add);
        return copy;
    }

    private static Triple triple(final de.hauschel.arknet.persistence.Triple statement) {
        return RDF_FACTORY.createTriple((BlankNodeOrIRI) term(new RdfNode.Resource(statement.subject())),
                RDF_FACTORY.createIRI(statement.predicate()), term(statement.object()));
    }

    private static RDFTerm term(final RdfNode node) {
        return switch (node) {
            case RdfNode.Resource resource -> resource.iri().startsWith(BLANK_NODE_PREFIX)
                    ? RDF_FACTORY.createBlankNode(resource.iri().substring(BLANK_NODE_PREFIX.length()))
                    : RDF_FACTORY.createIRI(resource.iri());
            case RdfNode.Literal literal -> literal.languageTag() != null
                    ? RDF_FACTORY.createLiteral(literal.lexicalForm(), literal.languageTag())
                    : literal.datatypeIri() != null
                            ? RDF_FACTORY.createLiteral(literal.lexicalForm(),
                                    RDF_FACTORY.createIRI(literal.datatypeIri()))
                            : RDF_FACTORY.createLiteral(literal.lexicalForm());
        };
    }
}
