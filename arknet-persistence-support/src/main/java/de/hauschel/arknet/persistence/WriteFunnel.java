// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

import io.kogn.rdf.dataset.DatasetHandle;
import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.vocab.VocabDct;

/**
 * The shared write funnel of the kognio-rdf out-adapters (ADR-013): the transactional
 * skeleton every bounded context's guarded graph write runs through - SHACL gate, dataset
 * acquisition, the in-transaction existence checks, and the commit-conflict translation.
 * Everything that differs per context arrives as a parameter; the funnel knows no bounded
 * context, no domain type and no domain exception.
 *
 * <p><strong>Create vs. update (opaque identity).</strong> {@link #create} and {@link #update}
 * check whether the subject already exists <em>inside</em> the write transaction (an
 * {@code ASK}) - not via a separate read beforehand, which would leave a check-then-act race
 * between the check and the write. {@link #create} rejects an existing subject via
 * {@code alreadyExists} and, through a second {@code ASK} by {@code dcterms:identifier}, a
 * business-code collision via {@code duplicateCode} - deliberately two different signals: an
 * opaque-identity collision is a programming error (identities are minted once and never
 * reused), a code collision is an expected, rejectable outcome a human can cause.
 * {@link #update} rejects a missing subject via {@code notFound}.</p>
 *
 * <p><strong>The second interleaving (issue #144).</strong> The synchronous {@code ASK}s only
 * catch a concurrent create that already fully committed; two <em>genuinely overlapping</em>
 * transactions instead run under the store's {@code SERIALIZABLE} isolation
 * (kogn-io/rdf-core#18), both {@code ASK}s pass, and the loser's {@code commit()} itself is
 * rejected as a conflict - surfacing as the RDF4J-backed store's own commit-time exception.
 * That technology-specific exception must not reach this class or any adapter (ArchUnit rule 2
 * in {@code arknet-architecture-tests}), so {@code isWriteConflict} is a technology-neutral
 * {@link Predicate} each repository factory builds and injects. {@link #create} translates a
 * positive answer into the same {@code duplicateCode} signal the synchronous check throws (the
 * signal {@code CodeAssignment}'s retry consumes, see {@code arknet-shared-kernel}).
 * {@link #update} deliberately does <em>not</em> translate: a conflict there is not a code
 * collision, and the pre-funnel adapters rethrew it raw - preserved as-is, not repaired in
 * passing (the ul adapter's unmigrated patch-update, which translates into its own
 * concurrent-modification signal, stays outside this funnel for exactly such differences).</p>
 *
 * <p><strong>The gate is structurally unavoidable.</strong> Both methods run
 * {@link ShaclWriteGate#enforce} on the candidate (plus the optional validation-only
 * {@code assertedContext}, issue #63) before opening the transaction; a violation throws
 * {@link WriteConstraintViolationException} and nothing is acquired or persisted. What the
 * {@code body} then writes is the adapter's own business - the deliberate #65-style bypass
 * (re-attaching preserved, never-newly-asserted edges past the gate, ADR-007 Nachtrag)
 * remains possible and remains the adapter's decision.</p>
 *
 * <p><strong>Owning the transaction is the point.</strong> The funnel, not the adapter, opens
 * and commits the write transaction and hands the {@code body} only the live {@link DatasetTx}.
 * That makes it the single place where a later per-write revision record (ADR-011) can be
 * appended <em>atomically with the model write</em> - kept open by this design, deliberately
 * not implemented by it (ADR-013).</p>
 *
 * <p><strong>Technology-neutral.</strong> Depends only on the {@code io.kogn.rdf} ports
 * ({@code dataset} + {@code terms}), never on RDF4J - same property, same reasoning and same
 * ArchUnit guard as {@link ShaclWriteGate} (ADR-007).</p>
 */
public final class WriteFunnel {

    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();

    private final DatasetLifecycle lifecycle;
    private final ShaclWriteGate gate;
    private final Predicate<RuntimeException> isWriteConflict;

    /**
     * Creates the funnel. Per bounded context one instance, built by that context's repository
     * factory - exactly like the {@link ShaclWriteGate} it wraps.
     *
     * @param lifecycle       the kognio-rdf dataset lifecycle to acquire datasets from (must not
     *                        be {@code null})
     * @param gate            the SHACL write-gate validating every candidate graph before the
     *                        write transaction opens (must not be {@code null})
     * @param isWriteConflict recognises the technology-specific commit-time exception of a lost
     *                        {@code SERIALIZABLE} transaction conflict (issue #144), without this
     *                        class ever naming the RDF4J type (must not be {@code null})
     */
    public WriteFunnel(DatasetLifecycle lifecycle, ShaclWriteGate gate,
            Predicate<RuntimeException> isWriteConflict) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.isWriteConflict = Objects.requireNonNull(isWriteConflict, "isWriteConflict");
    }

    /**
     * Runs a guarded create: the subject must not exist yet and the business code must still be
     * free; only then does {@code body} run, inside the same write transaction as both checks.
     *
     * @param dataset         the dataset (workspace) to write into
     * @param graphIri        the named graph the checks are scoped to
     * @param subjectIri      the subject's opaque IRI; expected IRIREF-safe by construction
     *                        (a {@code de.hauschel.arknet.kernel.ResourceId} value)
     * @param code            the human-readable business code checked against
     *                        {@code dcterms:identifier} (escaped here, pass it raw)
     * @param candidate       the instance graph handed to the SHACL gate before the transaction
     * @param assertedContext validation-only context triples for the gate (issue #63), or
     *                        {@code null} if the shapes need none
     * @param alreadyExists   the bounded context's signal for an opaque-identity collision
     * @param duplicateCode   the bounded context's signal for a business-code collision - also
     *                        thrown when the commit itself loses a {@code SERIALIZABLE} conflict
     *                        (issue #144)
     * @param body            the write itself, given the live transaction after all checks passed
     */
    public void create(DatasetId dataset, String graphIri, String subjectIri, String code,
            ReadableGraph candidate, ReadableGraph assertedContext,
            Supplier<RuntimeException> alreadyExists, Supplier<RuntimeException> duplicateCode,
            Consumer<DatasetTx> body) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(graphIri, "graphIri");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(alreadyExists, "alreadyExists");
        Objects.requireNonNull(duplicateCode, "duplicateCode");
        Objects.requireNonNull(body, "body");

        enforceGate(candidate, assertedContext);

        String askExists = askSubjectExists(graphIri, subjectIri);
        String askCodeExists = "ASK { GRAPH <" + graphIri + "> { "
                + "?s <" + IDENTIFIER_PROPERTY + "> \"" + SparqlTerms.escape(code) + "\" } }";

        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            try {
                handle.transactor().inTransaction(tx -> {
                    if (tx.ask(askExists)) {
                        throw alreadyExists.get();
                    }
                    // Identity is opaque and unique by construction, but the human-readable code
                    // is a separate triple the subject ASK alone cannot rule out - checked here,
                    // inside the same write transaction, so no concurrent create can race in
                    // between.
                    if (tx.ask(askCodeExists)) {
                        throw duplicateCode.get();
                    }
                    body.accept(tx);
                    return null;
                });
            } catch (RuntimeException e) {
                if (isWriteConflict.test(e)) {
                    throw duplicateCode.get();
                }
                throw e;
            }
        }
    }

    /**
     * Runs a guarded update: the subject must already exist; only then does {@code body} run,
     * inside the same write transaction as the check. No code check (an update never rewrites
     * the code triple through this path) and no conflict translation (see the class javadoc).
     *
     * @param dataset         the dataset (workspace) to write into
     * @param graphIri        the named graph the check is scoped to
     * @param subjectIri      the subject's opaque IRI; expected IRIREF-safe by construction
     * @param candidate       the instance graph handed to the SHACL gate before the transaction
     * @param assertedContext validation-only context triples for the gate (issue #63), or
     *                        {@code null} if the shapes need none
     * @param notFound        the bounded context's signal for a missing subject
     * @param body            the write itself, given the live transaction after the check passed
     */
    public void update(DatasetId dataset, String graphIri, String subjectIri,
            ReadableGraph candidate, ReadableGraph assertedContext,
            Supplier<RuntimeException> notFound, Consumer<DatasetTx> body) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(graphIri, "graphIri");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(notFound, "notFound");
        Objects.requireNonNull(body, "body");

        enforceGate(candidate, assertedContext);

        String askExists = askSubjectExists(graphIri, subjectIri);

        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            handle.transactor().inTransaction(tx -> {
                if (!tx.ask(askExists)) {
                    throw notFound.get();
                }
                body.accept(tx);
                return null;
            });
        }
    }

    private void enforceGate(ReadableGraph candidate, ReadableGraph assertedContext) {
        if (assertedContext == null) {
            gate.enforce(candidate);
        } else {
            gate.enforce(candidate, assertedContext);
        }
    }

    private static String askSubjectExists(String graphIri, String subjectIri) {
        return "ASK { GRAPH <" + graphIri + "> { " + SparqlTerms.iriRef(subjectIri) + " ?p ?o } }";
    }
}
