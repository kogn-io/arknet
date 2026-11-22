// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.time.Clock;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import io.kogn.rdf.cid.sexpr.ContentAddressedIRIGeneratorSexpr;
import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.versioning.DeleteResult;
import io.kogn.rdf.versioning.DraftSlice;
import io.kogn.rdf.versioning.EntryDetails;
import io.kogn.rdf.versioning.HistoryEntry;
import io.kogn.rdf.versioning.PublishResult;
import io.kogn.rdf.versioning.PublishState;
import io.kogn.rdf.versioning.ResourceVersioning;
import io.kogn.rdf.versioning.ResourceVersioningNamedGraphs;
import io.kogn.rdf.versioning.RevisionConflictException;
import io.kogn.rdf.versioning.RevisionToken;

/**
 * The versioned write path of every model resource (ADR-61..63): each resource is the draft
 * slice of its subject IRI in the one content graph of its project dataset, written only through
 * the slice operations of kognio-rdf's {@link ResourceVersioning}, and published by the same
 * write into an immutable, content-addressed version.
 *
 * <p><strong>Shape of a write.</strong> Every write method owns one transaction and runs the same
 * skeleton: bind the dataset to the content graph, read the subject's draft slice and its
 * {@link RevisionToken}, run the caller's existence/code/token checks, let the caller's
 * {@link SliceBuilder} produce the complete new slice (it sees the transaction and the current
 * draft, so it can keep what an update must preserve and ask the store whatever a reference check
 * needs), validate exactly that slice through the {@link ShaclWriteGate}, and hand it to
 * {@link ResourceVersioning#replaceDraft}, then publish it with {@link ResourceVersioning#publish}
 * in the same transaction. What is validated is therefore what is written, not a
 * candidate a later body still merges into. A slice holds the subject IRI, its {@code #fragment}
 * IRIs and the blank nodes reachable from them; a triple on any other subject is rejected by the
 * library with an {@link IllegalArgumentException} and nothing is written. The fragment names of
 * new children come from {@link #childFragments}, minted from the funnel's {@link ChildIdSource};
 * the funnel keeps no record of them beside the slice.</p>
 *
 * <p><strong>Concurrency.</strong> The library's {@link RevisionToken} is the concurrency token:
 * {@link #compareAndUpdate} takes the token the caller read and reports a stale one through the
 * caller's signal, and a write that loses the store's commit-time conflict is reported through the
 * same signal - recognised by the {@code isWriteConflict} predicate, which by default matches
 * {@link ConcurrencyConflictException} and with it the library's {@link RevisionConflictException}.
 * The token is an in-memory value: it carries a revision counter the library does not expose, so it
 * cannot be serialized and only guards a read and a write made within one process call.</p>
 *
 * <p><strong>Every write publishes.</strong> A create or update writes the draft and publishes it
 * in one transaction, so the published view always equals the draft and no write leaves an
 * unpublished change behind: either both commit or neither does. A write that changes nothing
 * creates no history entry; every other one adds exactly one. There is no separate publish
 * operation. {@link #delete} removes the draft and the published slice and records a tombstone; a
 * business code handed in is kept on that tombstone in {@link #RETAINED_CODES_GRAPH}, so
 * {@link #findRetainedCodes} can keep it out of circulation. A lost commit is reported through a
 * signal of the caller, like every other write.</p>
 *
 * <p>Context differences are parameters: signals arrive as
 * {@link Supplier}s, the slice as a {@link SliceBuilder}. One instance per bounded context, built by
 * that context's repository factory with that context's gate; all instances of a dataset must name
 * the same content graph, or the library rejects the second with a
 * {@link io.kogn.rdf.versioning.ContentGraphMismatchException}. RDF4J-free: only the kognio-rdf
 * ports are used.</p>
 */
public final class VersionedWriteFunnel {

    /**
     * Recognises a lost write as the kognio-rdf ports report it: a commit-time {@code SERIALIZABLE}
     * conflict and a stale {@link RevisionToken} are both a {@link ConcurrencyConflictException}.
     */
    public static final Predicate<RuntimeException> DEFAULT_WRITE_CONFLICT =
            ConcurrencyConflictException.class::isInstance;

    /**
     * The named graph holding the business code of each deleted resource, as
     * {@code <tombstone entry> dcterms:identifier "code"}. It lies beside the library's graphs and
     * carries no model resource.
     */
    public static final String RETAINED_CODES_GRAPH = "https://w3id.org/arknet/model/retained-codes";

    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();

    /**
     * Produces the complete new draft slice of the subject being written.
     */
    @FunctionalInterface
    public interface SliceBuilder {

        /**
         * Builds the new slice.
         *
         * @param tx           the live write transaction, after all checks of the funnel passed;
         *                     for reads (reference checks, preserved neighbours), not for writes to
         *                     the content graph
         * @param currentDraft the subject's current draft slice, empty for a create
         * @return the new slice: triples on the subject IRI, its {@code #fragment} IRIs and blank
         *         nodes reachable from them; never {@code null}
         */
        ReadableGraph build(DatasetTx tx, ReadableGraph currentDraft);
    }

    private final DatasetLifecycle lifecycle;
    private final ShaclWriteGate gate;
    private final ResourceVersioning versioning;
    private final Predicate<RuntimeException> isWriteConflict;
    private final ChildIdSource childIds;
    private final RDF rdf = new SimpleRdf();
    private final Set<DatasetId> boundDatasets = ConcurrentHashMap.newKeySet();

    /**
     * Creates the funnel on the system UTC clock.
     *
     * @param lifecycle       the dataset lifecycle to acquire datasets from (must not be {@code null})
     * @param gate            the SHACL write-gate validating every new slice (must not be {@code null})
     * @param contentGraphIri the content graph of every dataset this funnel writes (must not be
     *                        {@code null})
     * @param isWriteConflict recognises a lost write - pass {@link #DEFAULT_WRITE_CONFLICT} for the
     *                        kognio-rdf-backed store (must not be {@code null})
     */
    public VersionedWriteFunnel(DatasetLifecycle lifecycle, ShaclWriteGate gate, String contentGraphIri,
            Predicate<RuntimeException> isWriteConflict) {
        this(lifecycle, gate, contentGraphIri, isWriteConflict, Clock.systemUTC());
    }

    /**
     * Creates the funnel on an explicit clock - the one each history entry's creation instant is
     * read from.
     *
     * @param lifecycle       the dataset lifecycle to acquire datasets from (must not be {@code null})
     * @param gate            the SHACL write-gate validating every new slice (must not be {@code null})
     * @param contentGraphIri the content graph of every dataset this funnel writes (must not be
     *                        {@code null})
     * @param isWriteConflict recognises a lost write - pass {@link #DEFAULT_WRITE_CONFLICT} for the
     *                        kognio-rdf-backed store (must not be {@code null})
     * @param clock           the clock of history entries (must not be {@code null})
     */
    public VersionedWriteFunnel(DatasetLifecycle lifecycle, ShaclWriteGate gate, String contentGraphIri,
            Predicate<RuntimeException> isWriteConflict, Clock clock) {
        this(lifecycle, gate, contentGraphIri, isWriteConflict, clock, ChildIdSource.random());
    }

    /**
     * Creates the funnel on an explicit clock and an explicit source of child ids - the seam a test
     * uses to know or to provoke the fragment names a write mints.
     *
     * @param lifecycle       the dataset lifecycle to acquire datasets from (must not be {@code null})
     * @param gate            the SHACL write-gate validating every new slice (must not be {@code null})
     * @param contentGraphIri the content graph of every dataset this funnel writes (must not be
     *                        {@code null})
     * @param isWriteConflict recognises a lost write - pass {@link #DEFAULT_WRITE_CONFLICT} for the
     *                        kognio-rdf-backed store (must not be {@code null})
     * @param clock           the clock of history entries (must not be {@code null})
     * @param childIds        the source of the opaque ids of new child resources (must not be
     *                        {@code null})
     */
    public VersionedWriteFunnel(DatasetLifecycle lifecycle, ShaclWriteGate gate, String contentGraphIri,
            Predicate<RuntimeException> isWriteConflict, Clock clock, ChildIdSource childIds) {
        this.childIds = Objects.requireNonNull(childIds, "childIds");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.isWriteConflict = Objects.requireNonNull(isWriteConflict, "isWriteConflict");
        Objects.requireNonNull(contentGraphIri, "contentGraphIri");
        Objects.requireNonNull(clock, "clock");
        this.versioning = new ResourceVersioningNamedGraphs(rdf.createIRI(contentGraphIri),
                new ContentAddressedIRIGeneratorSexpr(), clock);
    }

    /**
     * The naming state of a parent's child resources for one write (ADR-64), minting new names from
     * this funnel's {@link ChildIdSource}.
     *
     * @param parent       the parent's IRI, without a {@code #}
     * @param currentDraft the parent's current draft slice, an empty graph for a create
     * @return the naming state, for this one write
     */
    public ChildFragments childFragments(IRI parent, ReadableGraph currentDraft) {
        return ChildFragments.of(parent, currentDraft, childIds);
    }

    /**
     * Returns the content graph this funnel writes; read paths name it explicitly, because a query
     * without {@code GRAPH} also sees the published view and the library's graphs.
     *
     * @return the content graph IRI
     */
    public String contentGraph() {
        return versioning.contentGraph().getIRIString();
    }

    /**
     * Runs a guarded create whose lost commit race is reported as {@code duplicateCode}.
     *
     * @see #create(DatasetId, String, String, ReadableGraph, Supplier, Supplier, UnaryOperator,
     *      SliceBuilder)
     */
    public RevisionToken create(DatasetId dataset, String subjectIri, String code, ReadableGraph assertedContext,
            Supplier<RuntimeException> alreadyExists, Supplier<RuntimeException> duplicateCode,
            SliceBuilder slice) {
        Objects.requireNonNull(duplicateCode, "duplicateCode");
        return create(dataset, subjectIri, code, assertedContext, alreadyExists, duplicateCode,
                conflict -> duplicateCode.get(), slice);
    }

    /**
     * Runs a guarded create: the subject must have an empty draft and the business code must be
     * free in the content graph; only then is the slice built, validated and written.
     *
     * @param dataset         the dataset (project) to write into
     * @param subjectIri      the subject's opaque IRI, without a {@code #}
     * @param code            the business code checked against {@code dcterms:identifier} in the
     *                        content graph (pass it raw)
     * @param assertedContext validation-only context triples for the gate, or {@code null}
     * @param alreadyExists   the signal for a subject whose draft is not empty
     * @param duplicateCode   the signal for a code already held in the content graph
     * @param commitConflict  translates a lost commit race into the signal to throw; a {@code null}
     *                        result leaves the conflict untranslated
     * @param slice           builds the new slice
     * @return the token for the next write of the subject
     */
    public RevisionToken create(DatasetId dataset, String subjectIri, String code, ReadableGraph assertedContext,
            Supplier<RuntimeException> alreadyExists, Supplier<RuntimeException> duplicateCode,
            UnaryOperator<RuntimeException> commitConflict, SliceBuilder slice) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(alreadyExists, "alreadyExists");
        Objects.requireNonNull(duplicateCode, "duplicateCode");
        Objects.requireNonNull(commitConflict, "commitConflict");
        Objects.requireNonNull(slice, "slice");

        return write(dataset, commitConflict, tx -> {
            IRI subject = rdf.createIRI(subjectIri);
            DraftSlice draft = openDraft(tx, subject);
            if (!draft.triples().isEmpty()) {
                throw alreadyExists.get();
            }
            if (codeTaken(tx, code)) {
                throw duplicateCode.get();
            }
            return replace(tx, subject, draft, assertedContext, slice);
        });
    }

    /**
     * Runs an idempotent create: {@code findExisting} runs first in the write transaction and, if
     * it finds the resource, its IRI is returned and nothing is written; otherwise the guards of
     * {@link #create} apply. A lost commit race is answered by running {@code findExisting} once
     * more after the rollback - the winner's resource is then the result.
     *
     * @param dataset         the dataset (project) to write into
     * @param subjectIri      the subject's opaque IRI, without a {@code #}
     * @param code            the business code to check, or {@code null} for a resource without one
     * @param assertedContext validation-only context triples for the gate, or {@code null}
     * @param findExisting    looks up an equal resource, given the transaction
     * @param alreadyExists   the signal for a subject whose draft is not empty
     * @param duplicateCode   the signal for a code already held in the content graph
     * @param slice           builds the new slice
     * @return the IRI of the found or created resource
     */
    public String createIfAbsent(DatasetId dataset, String subjectIri, String code, ReadableGraph assertedContext,
            Function<DatasetTx, Optional<String>> findExisting, Supplier<RuntimeException> alreadyExists,
            Supplier<RuntimeException> duplicateCode, SliceBuilder slice) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(findExisting, "findExisting");
        Objects.requireNonNull(alreadyExists, "alreadyExists");
        Objects.requireNonNull(duplicateCode, "duplicateCode");
        Objects.requireNonNull(slice, "slice");

        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            ensureBound(dataset, handle);
            try {
                return handle.transactor().inTransaction(tx -> {
                    Optional<String> existing = findExisting.apply(tx);
                    if (existing.isPresent()) {
                        return existing.get();
                    }
                    IRI subject = rdf.createIRI(subjectIri);
                    DraftSlice draft = openDraft(tx, subject);
                    if (!draft.triples().isEmpty()) {
                        throw alreadyExists.get();
                    }
                    if (code != null && codeTaken(tx, code)) {
                        throw duplicateCode.get();
                    }
                    replace(tx, subject, draft, assertedContext, slice);
                    return subjectIri;
                });
            } catch (RuntimeException e) {
                if (isWriteConflict.test(e)) {
                    try (DatasetHandle retryHandle = lifecycle.acquire(dataset)) {
                        Optional<String> winner = retryHandle.transactor().inTransaction(findExisting::apply);
                        if (winner.isPresent()) {
                            return winner.get();
                        }
                    }
                }
                throw e;
            }
        }
    }

    /**
     * Runs a guarded update: the subject must have a non-empty draft and {@code expected} must
     * still be its current token. A stale token and a lost commit race both throw
     * {@code revisionMismatch}; in neither case is anything written.
     *
     * @param dataset          the dataset (project) to write into
     * @param subjectIri       the subject's opaque IRI, without a {@code #}
     * @param expected         the token the caller read with {@link #currentToken}, or {@code null},
     *                         which never matches
     * @param assertedContext  validation-only context triples for the gate, or {@code null}
     * @param notFound         the signal for a subject with an empty draft
     * @param revisionMismatch the signal for a stale token or a lost commit race
     * @param slice            builds the new slice from the current draft
     * @return the token for the next write of the subject, that of the published version
     */
    public RevisionToken compareAndUpdate(DatasetId dataset, String subjectIri, RevisionToken expected,
            ReadableGraph assertedContext, Supplier<RuntimeException> notFound,
            Supplier<RuntimeException> revisionMismatch, SliceBuilder slice) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(notFound, "notFound");
        Objects.requireNonNull(revisionMismatch, "revisionMismatch");
        Objects.requireNonNull(slice, "slice");

        return write(dataset, conflict -> revisionMismatch.get(), tx -> {
            IRI subject = rdf.createIRI(subjectIri);
            DraftSlice draft = openDraft(tx, subject);
            if (draft.triples().isEmpty()) {
                throw notFound.get();
            }
            if (!draft.token().equals(expected)) {
                throw revisionMismatch.get();
            }
            return replace(tx, subject, draft, assertedContext, slice);
        });
    }

    /**
     * Deletes a resource: the subject must have a non-empty draft; {@code guard} runs next in the
     * same transaction (a reference check throws there), then the library removes draft and
     * published slice and records a tombstone. No gate runs - nothing is written that could be
     * validated. A non-{@code null} {@code code} is kept on the tombstone in
     * {@link #RETAINED_CODES_GRAPH}.
     *
     * @param dataset    the dataset (project) to write into
     * @param subjectIri the subject's opaque IRI, without a {@code #}
     * @param code       the business code to keep out of circulation, or {@code null}
     * @param details    creator and comment of the tombstone
     * @param notFound   the signal for a subject with an empty draft
     * @param lostCommit the signal for a delete that lost the store's commit-time conflict to a
     *                   concurrent write
     * @param guard      checks run before the deletion, given the transaction
     * @return the tombstone entry's IRI
     */
    public String delete(DatasetId dataset, String subjectIri, String code, EntryDetails details,
            Supplier<RuntimeException> notFound, Supplier<RuntimeException> lostCommit, Consumer<DatasetTx> guard) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(details, "details");
        Objects.requireNonNull(notFound, "notFound");
        Objects.requireNonNull(lostCommit, "lostCommit");
        Objects.requireNonNull(guard, "guard");

        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            return handle.transactor().inTransaction(tx -> {
                IRI subject = rdf.createIRI(subjectIri);
                DraftSlice draft = openDraft(tx, subject);
                if (draft.triples().isEmpty()) {
                    throw notFound.get();
                }
                guard.accept(tx);
                DeleteResult tombstone = versioning.delete(tx, subject, draft.token(), details);
                if (code != null) {
                    Graph retained = rdf.createGraph();
                    retained.add(tombstone.entryIRI(), VocabDct.IDENTIFIER, rdf.createLiteral(code));
                    tx.add(rdf.createIRI(RETAINED_CODES_GRAPH), retained);
                }
                return tombstone.entryIRI().getIRIString();
            });
        } catch (RuntimeException e) {
            if (isWriteConflict.test(e)) {
                throw translateConflict(conflict -> lostCommit.get(), e);
            }
            throw e;
        }
    }

    /**
     * Writes and publishes a resource inside a transaction the caller owns - the write path of a
     * bulk import such as the startup migration of an earlier storage layout, which must move a
     * whole dataset in one transaction so an interrupted run leaves either the old or the new state.
     * The subject must have an empty draft; the slice runs through the same gate, the same
     * {@link ResourceVersioning#replaceDraft} and the same publish as {@link #create}, with
     * {@code comment} on the one history entry it adds. Nothing is committed here: a failure
     * leaves the caller's transaction to roll back.
     *
     * @param tx              the caller's open write transaction
     * @param subjectIri      the subject's IRI, without a {@code #}
     * @param slice           the complete slice of the subject
     * @param assertedContext validation-only context triples for the gate, or {@code null}
     * @param comment         the comment of the history entry
     * @throws IllegalStateException             if the subject already has a non-empty draft
     * @throws WriteConstraintViolationException if the slice violates the gate's shapes
     */
    public void importPublished(DatasetTx tx, String subjectIri, ReadableGraph slice, ReadableGraph assertedContext,
            String comment) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(comment, "comment");
        IRI subject = rdf.createIRI(subjectIri);
        replace(tx, subject, emptyDraft(tx, subject), assertedContext, (writeTx, current) -> slice,
                EntryDetails.NONE.withComment(comment));
    }

    /**
     * {@link #importPublished} without the gate, for the one caller that must carry a record over
     * as it is stored: the startup migration of an earlier storage layout, for a resource whose
     * stored form an earlier version kept past its own gate and the current gate rejects. The
     * resource still ends with exactly one published version in the caller's transaction; the next
     * regular write of it faces the gate again. Every other caller is barred by an architecture
     * rule ({@code DependencyRulesTest}, rule 15) - this is not a way around the gate for a write.
     *
     * @param tx         the caller's open write transaction
     * @param subjectIri the subject's IRI, without a {@code #}
     * @param slice      the complete slice of the subject, taken over unvalidated
     * @param comment    the comment of the history entry
     * @throws IllegalStateException if the subject already has a non-empty draft
     */
    public void importPublishedPastGate(DatasetTx tx, String subjectIri, ReadableGraph slice, String comment) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(slice, "slice");
        Objects.requireNonNull(comment, "comment");
        IRI subject = rdf.createIRI(subjectIri);
        publish(tx, subject, emptyDraft(tx, subject), slice, EntryDetails.NONE.withComment(comment));
    }

    private DraftSlice emptyDraft(DatasetTx tx, IRI subject) {
        DraftSlice draft = openDraft(tx, subject);
        if (!draft.triples().isEmpty()) {
            throw new IllegalStateException(subject.getIRIString() + " already has a draft; an import only creates");
        }
        return draft;
    }

    /**
     * Keeps a business code out of circulation inside a transaction the caller owns, for a resource
     * deleted before this write path existed and therefore without a tombstone of its own: the code
     * lands in {@link #RETAINED_CODES_GRAPH} on {@code holderIri}, where {@link #findRetainedCodes}
     * reads it like the code of a tombstone.
     *
     * @param tx        the caller's open write transaction
     * @param holderIri the IRI the code is recorded on, for instance the deleted resource's last
     *                  revision in the earlier layout
     * @param code      the business code
     */
    public void retainCode(DatasetTx tx, String holderIri, String code) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(holderIri, "holderIri");
        Objects.requireNonNull(code, "code");
        Graph retained = rdf.createGraph();
        retained.add(rdf.createIRI(holderIri), VocabDct.IDENTIFIER, rdf.createLiteral(code));
        tx.add(rdf.createIRI(RETAINED_CODES_GRAPH), retained);
    }

    /**
     * Reads the subject's current token inside the caller's own read transaction, so the token
     * belongs to exactly the state that transaction reads.
     *
     * @param tx         the caller's open transaction
     * @param subjectIri the subject's opaque IRI, without a {@code #}
     * @return the token to hand to {@link #compareAndUpdate}
     */
    public RevisionToken currentToken(DatasetTx tx, String subjectIri) {
        Objects.requireNonNull(tx, "tx");
        Objects.requireNonNull(subjectIri, "subjectIri");
        return versioning.readDraft(tx, rdf.createIRI(subjectIri)).token();
    }

    /**
     * Reads the subject's history: its publish entries and tombstones, oldest first.
     *
     * @param dataset    the dataset (project)
     * @param subjectIri the subject's opaque IRI, without a {@code #}
     * @return the history entries; empty for a subject never written through a funnel
     */
    public List<HistoryEntry> history(DatasetId dataset, String subjectIri) {
        return read(dataset, tx -> versioning.history(tx, rdf.createIRI(subjectIri)));
    }

    /**
     * Tells whether the subject's draft was published and whether it changed since.
     *
     * @param dataset    the dataset (project)
     * @param subjectIri the subject's opaque IRI, without a {@code #}
     * @return the publish state
     */
    public PublishState publishState(DatasetId dataset, String subjectIri) {
        return read(dataset, tx -> versioning.publishState(tx, rdf.createIRI(subjectIri)));
    }

    /**
     * Reads back the business codes {@link #delete} kept, filtered on a prefix. Codes are unique
     * across the whole content graph, so a prefix that is itself the start of another type's
     * prefix would read that type's codes too; the code prefixes of arknet's resource types do
     * not overlap.
     *
     * @param dataset    the dataset (project)
     * @param codePrefix the prefix the codes start with, for instance {@code "FR-"}
     * @return the retained codes, unordered
     */
    public List<String> findRetainedCodes(DatasetId dataset, String codePrefix) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(codePrefix, "codePrefix");

        String query = "SELECT ?identifier WHERE { GRAPH <" + RETAINED_CODES_GRAPH + "> { "
                + "?entry <" + IDENTIFIER_PROPERTY + "> ?identifier } "
                + "FILTER(STRSTARTS(STR(?identifier), \"" + SparqlTerms.escape(codePrefix) + "\")) }";

        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            return handle.sparqlQuery().select(query)
                    .map(row -> literalOf(row, "identifier").getLexicalForm())
                    .toList();
        }
    }

    private <T> T read(DatasetId dataset, Function<DatasetTx, T> work) {
        Objects.requireNonNull(dataset, "dataset");
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            return ReadTransactions.read(handle, work);
        }
    }

    private RevisionToken write(DatasetId dataset, UnaryOperator<RuntimeException> commitConflict,
            Function<DatasetTx, RevisionToken> work) {
        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            ensureBound(dataset, handle);
            try {
                return handle.transactor().inTransaction(work);
            } catch (RuntimeException e) {
                if (isWriteConflict.test(e)) {
                    throw translateConflict(commitConflict, e);
                }
                throw e;
            }
        }
    }

    /**
     * Commits the content-graph binding in a transaction of its own, once per dataset and funnel.
     * The library records the binding on first use; two first writes on a fresh dataset would
     * otherwise both record it and one would lose at commit - reported by {@link #create} as a
     * false {@code duplicateCode}. A binding lost to a concurrent binder is read back on the one
     * retry. Should a dataset be dropped and created anew, the cache only skips this step: every
     * library operation still binds itself inside the write transaction.
     */
    private void ensureBound(DatasetId dataset, DatasetHandle handle) {
        if (boundDatasets.contains(dataset)) {
            return;
        }
        try {
            bind(handle);
        } catch (RuntimeException e) {
            if (!isWriteConflict.test(e)) {
                throw e;
            }
            bind(handle);
        }
        boundDatasets.add(dataset);
    }

    private void bind(DatasetHandle handle) {
        handle.transactor().inTransaction(tx -> {
            versioning.bindContentGraph(tx);
            return null;
        });
    }

    private DraftSlice openDraft(DatasetTx tx, IRI subject) {
        versioning.bindContentGraph(tx);
        return versioning.readDraft(tx, subject);
    }

    private RevisionToken replace(DatasetTx tx, IRI subject, DraftSlice draft, ReadableGraph assertedContext,
            SliceBuilder slice) {
        return replace(tx, subject, draft, assertedContext, slice, EntryDetails.NONE);
    }

    private RevisionToken replace(DatasetTx tx, IRI subject, DraftSlice draft, ReadableGraph assertedContext,
            SliceBuilder slice, EntryDetails details) {
        ReadableGraph next = Objects.requireNonNull(slice.build(tx, draft.triples()), "slice");
        if (assertedContext == null) {
            gate.enforce(next);
        } else {
            gate.enforce(next, assertedContext);
        }
        return publish(tx, subject, draft, next, details);
    }

    private RevisionToken publish(DatasetTx tx, IRI subject, DraftSlice draft, ReadableGraph next,
            EntryDetails details) {
        RevisionToken written = versioning.replaceDraft(tx, subject, next, draft.token());
        PublishResult published = versioning.publish(tx, List.of(written), details);
        return published.results().get(subject).token();
    }

    private boolean codeTaken(DatasetTx tx, String code) {
        return tx.contains(versioning.contentGraph(), null, VocabDct.IDENTIFIER, rdf.createLiteral(code));
    }

    private static RuntimeException translateConflict(UnaryOperator<RuntimeException> translator,
            RuntimeException conflict) {
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

    private static Literal literalOf(BindingSet row, String name) {
        return (Literal) row.getValue(name)
                .orElseThrow(() -> new IllegalStateException("missing binding '" + name + "'"));
    }
}
