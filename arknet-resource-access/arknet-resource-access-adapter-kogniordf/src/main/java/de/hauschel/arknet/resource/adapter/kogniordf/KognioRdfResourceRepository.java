// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.stream.Stream;

import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.versioning.EntryDetails;

import de.hauschel.arknet.kernel.CodeCounter;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ChildFragments;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.resource.application.port.out.ResourceBatch;
import de.hauschel.arknet.resource.application.port.out.ResourceRepository;
import de.hauschel.arknet.resource.domain.SubmitConflictException;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * {@link ResourceRepository} over the {@link VersionedWriteFunnel}: one {@link VersionedWriteFunnel#writeAll}
 * per call, one project dataset per {@link ProjectId}, every read on the batch's transaction.
 *
 * <p>A history entry carries no creator and no comment yet; activities are not kept as resources of
 * their own - the entry the versioning library records for each changed resource is the trace.</p>
 */
final class KognioRdfResourceRepository implements ResourceRepository {

    private static final RDF RDF_FACTORY = new SimpleRdf();

    private final VersionedWriteFunnel funnel;

    KognioRdfResourceRepository(final VersionedWriteFunnel funnel) {
        this.funnel = Objects.requireNonNull(funnel, "funnel");
    }

    @Override
    public void writeAll(final ProjectId projectId, final Consumer<ResourceBatch> writes) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(writes, "writes");
        try {
            funnel.writeAll(new DatasetId(projectId.value()), EntryDetails.NONE,
                    lost -> new SubmitConflictException("a concurrent write committed first", lost),
                    batch -> writes.accept(new FunnelBatch(batch)));
        } catch (WriteConstraintViolationException e) {
            throw new SubmitRejectedException("a resource after the document violates the shapes of its type: "
                    + e.getMessage());
        }
    }

    /** The core's view of one funnel batch. */
    private final class FunnelBatch implements ResourceBatch {

        private final VersionedWriteFunnel.Batch batch;

        private FunnelBatch(final VersionedWriteFunnel.Batch batch) {
            this.batch = batch;
        }

        @Override
        public ReadableGraph slice(final IRI subject) {
            return batch.draft(subject.getIRIString());
        }

        @Override
        public int highestRunningNumber(final String codePrefix) {
            DatasetTx tx = batch.tx();
            List<String> codes = Stream.concat(
                    identifiers(tx, funnel.contentGraph()),
                    identifiers(tx, VersionedWriteFunnel.RETAINED_CODES_GRAPH)).toList();
            return CodeCounter.highestRunningNumber(codePrefix, codes, code -> code);
        }

        @Override
        public ChildFragments childFragments(final IRI parent, final ReadableGraph current) {
            return funnel.childFragments(parent, current);
        }

        @Override
        public void create(final IRI subject, final String code, final ReadableGraph slice,
                final ReadableGraph context) {
            batch.create(subject.getIRIString(), code, context,
                    () -> new SubmitConflictException(subject.getIRIString() + " exists already", null),
                    () -> new SubmitConflictException("the code " + code + " was taken concurrently", null),
                    (tx, current) -> slice);
        }

        @Override
        public void update(final IRI subject, final ReadableGraph slice, final ReadableGraph context) {
            batch.update(subject.getIRIString(), context,
                    () -> new SubmitConflictException(subject.getIRIString() + " was deleted concurrently", null),
                    (tx, current) -> slice);
        }

        @Override
        public void delete(final IRI subject, final String code) {
            batch.delete(subject.getIRIString(), code,
                    () -> new SubmitConflictException(subject.getIRIString() + " was deleted concurrently", null),
                    tx -> {
                    });
        }

        private static Stream<String> identifiers(final DatasetTx tx, final String graph) {
            return tx.match(RDF_FACTORY.createIRI(graph), null, VocabDct.IDENTIFIER, null).stream()
                    .map(Triple::getObject)
                    .filter(Literal.class::isInstance)
                    .map(object -> ((Literal) object).getLexicalForm());
        }
    }
}
