// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;

import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.SparqlTerms;

/**
 * The write path of the project registry: the reserved system dataset is no project and holds no
 * model resource, so it stays outside the content graph and the library versioning (ADR-63 speaks
 * of a project's model resources) and keeps a guarded graph write of its own.
 *
 * <p>Each write validates its candidate through the SHACL gate before the transaction opens, runs
 * the existence checks inside the transaction, hands the live transaction to the caller's body and
 * translates a lost {@code SERIALIZABLE} commit into the caller's signal. The concurrency token of
 * {@link #compareAndUpdate} is the registry record's own state - {@link #stateToken} renders every
 * triple of the project and of its anchors - so a write that changed the record is seen as a
 * change without any revision vocabulary in the store.</p>
 */
final class RegistryWrites {

    private static final Predicate<RuntimeException> WRITE_CONFLICT = ConcurrencyConflictException.class::isInstance;

    private final DatasetLifecycle lifecycle;
    private final ShaclWriteGate gate;
    private final SimpleRdf rdf = new SimpleRdf();

    RegistryWrites(DatasetLifecycle lifecycle, ShaclWriteGate gate) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gate = Objects.requireNonNull(gate, "gate");
    }

    /**
     * Creates a record: the subject must not exist and {@code code} must be free in the graph.
     *
     * @param commitConflict translates a lost commit race into the signal to throw; a {@code null}
     *                       result leaves the conflict untranslated
     */
    void create(DatasetId dataset, String graphIri, String subjectIri, String code, ReadableGraph candidate,
            Supplier<RuntimeException> alreadyExists, Supplier<RuntimeException> duplicateCode,
            UnaryOperator<RuntimeException> commitConflict, Consumer<DatasetTx> body) {
        gate.enforce(candidate);
        write(dataset, commitConflict, tx -> {
            IRI graph = rdf.createIRI(graphIri);
            if (tx.contains(graph, rdf.createIRI(subjectIri), null, null)) {
                throw alreadyExists.get();
            }
            if (tx.contains(graph, null, VocabDct.IDENTIFIER, rdf.createLiteral(code))) {
                throw duplicateCode.get();
            }
            body.accept(tx);
        });
    }

    /**
     * Updates a record whose {@link #stateToken} still equals {@code expectedToken}; a stale token
     * and a lost commit race both throw {@code stale}.
     */
    void compareAndUpdate(DatasetId dataset, String graphIri, String subjectIri, String expectedToken,
            ReadableGraph candidate, ReadableGraph assertedContext, Supplier<RuntimeException> notFound,
            Supplier<RuntimeException> stale, Consumer<DatasetTx> body) {
        if (assertedContext == null) {
            gate.enforce(candidate);
        } else {
            gate.enforce(candidate, assertedContext);
        }
        write(dataset, conflict -> stale.get(), tx -> {
            if (!tx.contains(rdf.createIRI(graphIri), rdf.createIRI(subjectIri), null, null)) {
                throw notFound.get();
            }
            if (!stateToken(tx::select, subjectIri).equals(expectedToken)) {
                throw stale.get();
            }
            body.accept(tx);
        });
    }

    /** Deletes a record; the subject must exist. A lost commit race is reported as {@code notFound}. */
    void delete(DatasetId dataset, String graphIri, String subjectIri, Supplier<RuntimeException> notFound,
            Consumer<DatasetTx> body) {
        write(dataset, conflict -> notFound.get(), tx -> {
            if (!tx.contains(rdf.createIRI(graphIri), rdf.createIRI(subjectIri), null, null)) {
                throw notFound.get();
            }
            body.accept(tx);
        });
    }

    /**
     * The registry record's state as a token: every triple of the project and of each of its
     * anchors in the registry graph, rendered and sorted. Two reads return the same token exactly
     * when the record did not change in between.
     *
     * @param select     runs a {@code SELECT} - a transaction's or a plain query's
     * @param subjectIri the project's IRI
     * @return the token
     */
    static String stateToken(Function<String, Stream<BindingSet>> select, String subjectIri) {
        String subject = SparqlTerms.iriRef(subjectIri);
        String query = "SELECT ?s ?p ?o WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + "{ " + subject + " ?p ?o . BIND(" + subject + " AS ?s) } UNION "
                + "{ " + subject + " <" + ArkprjVocabulary.ANCHOR + "> ?s . ?s ?p ?o } } }";
        return select.apply(query)
                .map(row -> render(row.getValue("s").orElse(null)) + " " + render(row.getValue("p").orElse(null))
                        + " " + render(row.getValue("o").orElse(null)))
                .sorted()
                .collect(Collectors.joining("\n"));
    }

    private static String render(RDFTerm term) {
        if (term instanceof Literal literal) {
            return "\"" + literal.getLexicalForm() + "\"@" + literal.getLanguageTag().orElse("")
                    + "^^" + literal.getDatatype().getIRIString();
        }
        return term instanceof IRI iri ? "<" + iri.getIRIString() + ">" : String.valueOf(term);
    }

    private void write(DatasetId dataset, UnaryOperator<RuntimeException> commitConflict, Consumer<DatasetTx> work) {
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            try {
                handle.transactor().inTransaction(tx -> {
                    work.accept(tx);
                    return null;
                });
            } catch (RuntimeException e) {
                if (WRITE_CONFLICT.test(e)) {
                    throw translate(commitConflict, e);
                }
                throw e;
            }
        }
    }

    /**
     * Runs the caller's translator without ever losing the conflict: a translator that throws
     * while attributing the loss reaches the caller with the conflict kept as suppressed.
     */
    private static RuntimeException translate(UnaryOperator<RuntimeException> translator, RuntimeException conflict) {
        RuntimeException translated;
        try {
            translated = translator.apply(conflict);
        } catch (RuntimeException attributionFailed) {
            if (attributionFailed != conflict) {
                attributionFailed.addSuppressed(conflict);
            }
            return attributionFailed;
        }
        return Objects.requireNonNullElse(translated, conflict);
    }
}
