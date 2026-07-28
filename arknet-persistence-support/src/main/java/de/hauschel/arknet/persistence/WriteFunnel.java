// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

/**
 * The shared write funnel of the kognio-rdf out-adapters (ADR-013): the transactional
 * skeleton every bounded context's guarded graph write runs through - SHACL gate, dataset
 * acquisition, the in-transaction existence checks, and the commit-conflict translation.
 * Everything that differs per context arrives as a parameter; the funnel knows no bounded
 * context, no domain type and no domain exception.
 *
 * <p><strong>Create vs. update (opaque identity).</strong> {@link #create} and {@link #update}
 * check whether the subject already exists <em>inside</em> the write transaction (a
 * {@link DatasetTx#contains} existence check) - not via a separate read beforehand, which would
 * leave a check-then-act race between the check and the write. {@link #create} rejects an
 * existing subject via {@code alreadyExists} and, through a second {@code contains} check by
 * {@code dcterms:identifier}, a business-code collision via {@code duplicateCode} - deliberately
 * two different signals: an opaque-identity collision is a programming error (identities are
 * minted once and never reused), a code collision is an expected, rejectable outcome a human can
 * cause. {@link #update} rejects a missing subject via {@code notFound}.</p>
 *
 * <p><strong>The second interleaving (issue #144).</strong> The synchronous existence checks only
 * catch a concurrent create that already fully committed; two <em>genuinely overlapping</em>
 * transactions instead run under the store's {@code SERIALIZABLE} isolation
 * (kogn-io/rdf-core#18), both existence checks pass, and the loser's {@code commit()} itself is
 * rejected as a conflict. Which exception that surfaces as is a property of the store behind the
 * ports, not of this class, so {@code isWriteConflict} stays an injected, technology-neutral
 * {@link Predicate} (ADR-001: the store is swappable); {@link #DEFAULT_WRITE_CONFLICT} is the
 * ready-made one for the kognio-rdf-backed store every adapter here uses. Which domain signal a
 * lost commit surfaces as is the caller's decision, not the funnel's: {@link #create}'s
 * {@code commitConflict} translator receives the store's own conflict exception and returns the
 * signal to throw in its place. The short overload binds it to {@code duplicateCode} - the signal
 * {@code CodeAssignment}'s retry consumes (see {@code arknet-shared-kernel}), which is why the four
 * model contexts need no translator of their own. {@link #update} deliberately does <em>not</em>
 * translate: a conflict there is not a code collision, and the pre-funnel adapters rethrew it raw -
 * preserved as-is, not repaired in passing (the ul adapter's unmigrated patch-update, which
 * translates into its own concurrent-modification signal, stays outside this funnel for exactly
 * such differences).</p>
 *
 * <p><strong>Why the translator is a parameter and not a fixed rule (issue #181).</strong> A lost
 * commit means "somebody else wrote here first" and nothing more; the funnel cannot know
 * <em>which</em> of its caller's uniqueness rules that writer actually broke. Where the business
 * code is the only thing that can collide, mapping the conflict onto {@code duplicateCode} states
 * a fact - and a healing retry consumes it before any user sees it. Where a context guards a
 * second uniqueness rule of its own inside the {@code body} (the project registry's anchor
 * uniqueness, ADR-016 decision 4) and has no such retry, the same mapping would state a
 * <em>falsehood</em> straight to the caller: "label already taken" for a write that lost on an
 * anchor. Only the caller can tell those apart, so only the caller may name the signal - and
 * returning the conflict unchanged ({@link UnaryOperator#identity()}) stays available for the
 * residual case where even the caller cannot attribute the loss to any of its rules.</p>
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
 * That makes it the single place where the per-write revision record (ADR-011) is appended
 * <em>atomically with the model write</em> - the attachment point ADR-013 kept open,
 * implemented per ADR-014: after the {@code body} ran, and still inside the same transaction,
 * the funnel records exactly one immutable PROV-O revision (see {@link ArkprovVocabulary} for
 * the exact triple shape) and rewrites the resource's {@code arkprov:head} pointer to it,
 * chaining the superseded head via {@code prov:wasRevisionOf}. A rejected or failing write
 * therefore never leaves a revision behind - the transaction aborts as a whole. The write
 * activity carries no resolved agent yet - agent attribution is additive (ADR-014
 * decision 5).</p>
 *
 * <p><strong>What the head promises now (ADR-014 decisions 3+4, issue #167).</strong> Revision
 * and head follow every write this funnel runs - {@link #create}, {@link #update} and
 * {@link #compareAndUpdate} each call {@link #recordRevision} after their body, so the head
 * always points at the latest write regardless of which of the three a caller reached. The
 * requirements context's {@code compareAndUpdate} (behind {@code req_update}, {@code
 * req_set_status} and {@code req_link_term}) and the glossary's patch-{@code update} (behind
 * {@code term_update}), both kept outside on purpose for their own transaction semantics by
 * ADR-013 decision 5, are resolved into {@link #compareAndUpdate} rather than integrated
 * unchanged - a full-snapshot comparison (requirements) or an in-adapter-transaction field merge
 * (glossary) each degenerate to a head comparison against this method's {@code expectedHead}. The
 * bounded context's {@code bc_link_term} joined them in issue #176 - its read-modify-write used to
 * run through the unguarded {@link #update} and could silently lose one of two concurrently linked
 * edges. But the head is a usable <em>concurrency token</em> only where a caller actually goes
 * through {@link #compareAndUpdate}: that method closes the lost-update window a plain
 * {@link #update} cannot, precisely because {@link #update} runs no head check at all. The head
 * moving with a write and the write being guarded by that head are two different properties; only
 * {@link #compareAndUpdate} callers get both. {@link #update} survives as the funnel's
 * unconditional write path - the use-case adapter's replace-by-identity write uses it - but no
 * in-port reaches a read-modify-write through it any more: there is no {@code uc_update} tool.</p>
 *
 * <p><strong>Technology-neutral.</strong> Depends only on the {@code io.kogn.rdf} ports
 * ({@code dataset} + {@code terms}), never on RDF4J - same property, same reasoning and same
 * ArchUnit guard as {@link ShaclWriteGate} (ADR-007).</p>
 */
public final class WriteFunnel {

    /**
     * Recognises a lost {@code SERIALIZABLE} write conflict (issue #144) as the kognio-rdf ports
     * report it: since {@code io.kogn.rdf} 0.2.x (kogn-io/rdf-core#30) the RDF4J-backed
     * transactor translates the store's own commit-time exception into the neutral
     * {@link ConcurrencyConflictException} itself, so recognising it needs no RDF4J type and
     * therefore no longer has to live in the repository factories - the one place ArchUnit lets
     * an adapter name RDF4J. This is the predicate every adapter in this codebase wants; it is
     * offered as a default rather than hard-wired, because a different store behind
     * {@link DatasetLifecycle} (ADR-001) may fail its writers differently, and the funnel would
     * then be the wrong place to encode that.
     */
    public static final Predicate<RuntimeException> DEFAULT_WRITE_CONFLICT =
            ConcurrencyConflictException.class::isInstance;

    private static final String IDENTIFIER_PROPERTY = VocabDct.IDENTIFIER.getIRIString();

    /**
     * Base IRIs the funnel mints revision/activity identities under - flat and opaque like the
     * kernel's resource identities, but deliberately under their own bases: a revision is
     * infrastructure the funnel owns, not a model resource a bounded context minted.
     */
    private static final String REVISION_IRI_BASE = "https://w3id.org/arknet/revision/";
    private static final String ACTIVITY_IRI_BASE = "https://w3id.org/arknet/activity/";

    private final DatasetLifecycle lifecycle;
    private final ShaclWriteGate gate;
    private final Predicate<RuntimeException> isWriteConflict;
    private final Clock clock;
    private final RDF rdf = new SimpleRdf();

    /**
     * Creates the funnel on the system UTC clock. Per bounded context one instance, built by
     * that context's repository factory - exactly like the {@link ShaclWriteGate} it wraps.
     *
     * @param lifecycle       the kognio-rdf dataset lifecycle to acquire datasets from (must not
     *                        be {@code null})
     * @param gate            the SHACL write-gate validating every candidate graph before the
     *                        write transaction opens (must not be {@code null})
     * @param isWriteConflict recognises the store's commit-time exception of a lost
     *                        {@code SERIALIZABLE} transaction conflict (issue #144) - pass
     *                        {@link #DEFAULT_WRITE_CONFLICT} for the kognio-rdf-backed store
     *                        (must not be {@code null})
     */
    public WriteFunnel(DatasetLifecycle lifecycle, ShaclWriteGate gate,
            Predicate<RuntimeException> isWriteConflict) {
        this(lifecycle, gate, isWriteConflict, Clock.systemUTC());
    }

    /**
     * Creates the funnel on an explicit clock - the one the revision's
     * {@code prov:generatedAtTime} is read from. Every other collaborator arrives by
     * constructor, and the timestamp is the only externally observable value a revision carries
     * that is not derived from its inputs; a fixed clock is what makes it assertable and what
     * gives a later "revisions between T1 and T2" read path deterministic fixtures.
     *
     * @param lifecycle       the kognio-rdf dataset lifecycle to acquire datasets from (must not
     *                        be {@code null})
     * @param gate            the SHACL write-gate validating every candidate graph before the
     *                        write transaction opens (must not be {@code null})
     * @param isWriteConflict recognises the store's commit-time exception of a lost
     *                        {@code SERIALIZABLE} transaction conflict (issue #144) - pass
     *                        {@link #DEFAULT_WRITE_CONFLICT} for the kognio-rdf-backed store
     *                        (must not be {@code null})
     * @param clock           the clock each revision's generation instant is read from (must not
     *                        be {@code null})
     */
    public WriteFunnel(DatasetLifecycle lifecycle, ShaclWriteGate gate,
            Predicate<RuntimeException> isWriteConflict, Clock clock) {
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.gate = Objects.requireNonNull(gate, "gate");
        this.isWriteConflict = Objects.requireNonNull(isWriteConflict, "isWriteConflict");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Runs a guarded create whose lost commit races are reported as {@code duplicateCode} - the
     * signal {@code CodeAssignment}'s retry consumes, and the right one wherever the business code
     * is the only uniqueness rule the write can break. A context guarding a second rule of its own
     * inside {@code body} wants
     * {@link #create(DatasetId, String, String, String, ReadableGraph, ReadableGraph, Supplier,
     * Supplier, UnaryOperator, Consumer)} instead (see that method and the class javadoc's
     * "why the translator is a parameter").
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
        Objects.requireNonNull(duplicateCode, "duplicateCode");
        create(dataset, graphIri, subjectIri, code, candidate, assertedContext, alreadyExists,
                duplicateCode, conflict -> duplicateCode.get(), body);
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
     * @param duplicateCode   the bounded context's signal for a business-code collision, as found
     *                        by the synchronous {@code dcterms:identifier} check
     * @param commitConflict  translates a lost commit race (issue #144) into the signal the caller
     *                        wants thrown, given the store's own conflict exception; return that
     *                        exception unchanged to leave the loss untranslated. Runs after the
     *                        transaction has been rolled back, so it may read the store to
     *                        attribute the loss - the dataset handle is still open at that point,
     *                        but no transaction is. A {@code null} result is treated as
     *                        "untranslated" rather than allowed to mask the conflict. Should the
     *                        translator itself throw (for instance while re-reading the store to
     *                        attribute the loss, see {@link #translateCommitConflict}), that
     *                        exception is what reaches the caller instead - with the original
     *                        store conflict kept on it as {@linkplain Throwable#addSuppressed
     *                        suppressed}, never dropped
     * @param body            the write itself, given the live transaction after all checks passed
     */
    public void create(DatasetId dataset, String graphIri, String subjectIri, String code,
            ReadableGraph candidate, ReadableGraph assertedContext,
            Supplier<RuntimeException> alreadyExists, Supplier<RuntimeException> duplicateCode,
            UnaryOperator<RuntimeException> commitConflict, Consumer<DatasetTx> body) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(graphIri, "graphIri");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(alreadyExists, "alreadyExists");
        Objects.requireNonNull(duplicateCode, "duplicateCode");
        Objects.requireNonNull(commitConflict, "commitConflict");
        Objects.requireNonNull(body, "body");

        enforceGate(candidate, assertedContext);

        IRI graph = rdf.createIRI(graphIri);
        IRI subject = rdf.createIRI(subjectIri);
        IRI identifierProperty = rdf.createIRI(IDENTIFIER_PROPERTY);
        Literal codeLiteral = rdf.createLiteral(code);

        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            try {
                handle.transactor().inTransaction(tx -> {
                    if (tx.contains(graph, subject, null, null)) {
                        throw alreadyExists.get();
                    }
                    // Identity is opaque and unique by construction, but the human-readable code
                    // is a separate triple the subject existence check alone cannot rule out -
                    // checked here, inside the same write transaction, so no concurrent create
                    // can race in between.
                    if (tx.contains(graph, null, identifierProperty, codeLiteral)) {
                        throw duplicateCode.get();
                    }
                    body.accept(tx);
                    recordRevision(tx, subjectIri);
                    return null;
                });
            } catch (RuntimeException e) {
                if (isWriteConflict.test(e)) {
                    throw translateCommitConflict(commitConflict, e);
                }
                throw e;
            }
        }
    }

    /**
     * Runs {@code commitConflict} on a recognised commit conflict, without ever letting the
     * conflict itself vanish - neither into a swallowed return value (that half is
     * {@link Objects#requireNonNullElse}) nor into a translator that throws instead of returning.
     *
     * <p>A translator such as {@code KognioRdfProjectRegistry#attributeLostRegistration} re-reads
     * the store after the rollback to name what the loser actually collided with (issue #181) -
     * itself a query that can fail (e.g. an unrecognised anchor-type IRI surfacing as an
     * {@link IllegalStateException} from {@code ProjectGraphs#anchorTypeFromIri}). Letting that
     * failure simply propagate would erase the original {@code ConcurrencyConflictException}
     * without a trace: not the cause, not a suppressed exception, nothing a caller could use to
     * even tell a race happened. The translator's own exception is the more actionable diagnosis
     * - it says what went wrong attributing the loss, where the original conflict says only
     * "somebody else committed first" - so it is what reaches the caller, with the original
     * conflict attached via {@link Throwable#addSuppressed} so the loser is never left with no
     * information at all.</p>
     *
     * @param commitConflict the caller's translator, as documented on the caller-facing overload
     * @param conflict       the store's own conflict exception, already recognised by
     *                       {@code isWriteConflict}
     * @return the exception to throw in place of {@code conflict}
     */
    private static RuntimeException translateCommitConflict(
            UnaryOperator<RuntimeException> commitConflict, RuntimeException conflict) {
        RuntimeException translated;
        try {
            translated = commitConflict.apply(conflict);
        } catch (RuntimeException attributionFailed) {
            // Throwable#addSuppressed(this) throws IllegalArgumentException; a translator that
            // simply rethrows the conflict it was handed (rather than returning it, the documented
            // "leave it untranslated" contract) would otherwise fail here instead of passing the
            // conflict through cleanly.
            if (attributionFailed != conflict) {
                attributionFailed.addSuppressed(conflict);
            }
            return attributionFailed;
        }
        return Objects.requireNonNullElse(translated, conflict);
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

        IRI graph = rdf.createIRI(graphIri);
        IRI subject = rdf.createIRI(subjectIri);

        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            handle.transactor().inTransaction(tx -> {
                if (!tx.contains(graph, subject, null, null)) {
                    throw notFound.get();
                }
                body.accept(tx);
                recordRevision(tx, subjectIri);
                return null;
            });
        }
    }

    /**
     * Runs a guarded compare-and-set update (ADR-014 decision 3): the subject must already
     * exist and its current {@code arkprov:head} - read inside this same write transaction -
     * must equal {@code expectedHead}; only then does {@code body} run. This is the resolution
     * of the two special paths ADR-013 kept outside the funnel (issue #167): a stale caller (one
     * whose read is no longer current) is rejected via {@code headMismatch}, exactly like a
     * missing subject is rejected via {@code notFound} - the same supplier-signal shape, so a
     * head conflict is now the same pattern in every bounded context.
     *
     * <p><strong>Two ways to observe a conflict, one signal.</strong> A caller whose read is
     * already stale by the time this method's transaction opens is caught by the synchronous
     * head comparison below. A caller whose read was current but loses a genuinely overlapping
     * {@code SERIALIZABLE} transaction at commit time (the same "second interleaving" issue #144
     * documents for {@link #create}) is caught by {@code isWriteConflict} in the surrounding
     * catch and translated into the identical {@code headMismatch} signal - the caller cannot
     * tell, and does not need to, which of the two actually happened.</p>
     *
     * @param dataset         the dataset (workspace) to write into
     * @param graphIri        the named graph the check is scoped to
     * @param subjectIri      the subject's opaque IRI; expected IRIREF-safe by construction
     * @param expectedHead    the {@code arkprov:head} revision IRI the caller last observed for
     *                        this subject, or {@code null} if the caller expects no revision to
     *                        exist yet (the subject predates the funnel's revision recording)
     * @param candidate       the instance graph handed to the SHACL gate before the transaction
     * @param assertedContext validation-only context triples for the gate (issue #63), or
     *                        {@code null} if the shapes need none
     * @param notFound        the bounded context's signal for a missing subject
     * @param headMismatch    the bounded context's signal for a stale {@code expectedHead}
     * @param body            the write itself, given the live transaction after both checks passed
     */
    public void compareAndUpdate(DatasetId dataset, String graphIri, String subjectIri,
            String expectedHead, ReadableGraph candidate, ReadableGraph assertedContext,
            Supplier<RuntimeException> notFound, Supplier<RuntimeException> headMismatch,
            Consumer<DatasetTx> body) {
        Objects.requireNonNull(dataset, "dataset");
        Objects.requireNonNull(graphIri, "graphIri");
        Objects.requireNonNull(subjectIri, "subjectIri");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(notFound, "notFound");
        Objects.requireNonNull(headMismatch, "headMismatch");
        Objects.requireNonNull(body, "body");

        enforceGate(candidate, assertedContext);

        IRI graph = rdf.createIRI(graphIri);
        IRI subject = rdf.createIRI(subjectIri);

        try (DatasetHandle handle = lifecycle.acquire(dataset)) {
            try {
                handle.transactor().inTransaction(tx -> {
                    if (!tx.contains(graph, subject, null, null)) {
                        throw notFound.get();
                    }
                    Optional<IRI> currentHead = readHead(tx, subjectIri);
                    String currentHeadIri = currentHead.map(IRI::getIRIString).orElse(null);
                    if (!Objects.equals(currentHeadIri, expectedHead)) {
                        throw headMismatch.get();
                    }
                    body.accept(tx);
                    recordRevision(tx, subjectIri, currentHead);
                    return null;
                });
            } catch (RuntimeException e) {
                if (isWriteConflict.test(e)) {
                    throw headMismatch.get();
                }
                throw e;
            }
        }
    }

    /**
     * Records the write's revision (ADR-014): one {@code prov:Activity}, one immutable
     * {@code arkprov:Revision} entity chained to the superseded head via
     * {@code prov:wasRevisionOf}, and the rewritten {@code arkprov:head} pointer - all inside
     * the caller's still-open write transaction, so the revision commits or aborts with the
     * model write. Runs after the {@code body} so a failing body never reaches it. Reads the
     * current head itself; used by {@link #create} and {@link #update}, which have not read it
     * beforehand.
     */
    private void recordRevision(DatasetTx tx, String subjectIri) {
        recordRevision(tx, subjectIri, readHead(tx, subjectIri));
    }

    /**
     * Same as {@link #recordRevision(DatasetTx, String)}, but takes the current head as already
     * read by the caller instead of reading it again - used by {@link #compareAndUpdate}, whose
     * CAS check already read it in the same transaction, saving a second {@code SELECT} for the
     * same value.
     */
    private void recordRevision(DatasetTx tx, String subjectIri, Optional<IRI> previousHead) {
        String subject = SparqlTerms.iriRef(subjectIri);
        if (previousHead.isPresent()) {
            String headPattern = "GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                    + subject + " <" + ArkprovVocabulary.HEAD + "> ?head }";
            tx.update("DELETE WHERE { " + headPattern + " }");
        }

        IRI revision = rdf.createIRI(REVISION_IRI_BASE + UUID.randomUUID());
        IRI activity = rdf.createIRI(ACTIVITY_IRI_BASE + UUID.randomUUID());
        IRI resource = rdf.createIRI(subjectIri);

        Graph provenance = rdf.createGraph();
        provenance.add(activity, VocabRdf.TYPE, rdf.createIRI(ArkprovVocabulary.ACTIVITY_TYPE));
        provenance.add(revision, VocabRdf.TYPE, rdf.createIRI(ArkprovVocabulary.ENTITY_TYPE));
        provenance.add(revision, VocabRdf.TYPE, rdf.createIRI(ArkprovVocabulary.REVISION_TYPE));
        provenance.add(revision, rdf.createIRI(ArkprovVocabulary.SPECIALIZATION_OF), resource);
        provenance.add(revision, rdf.createIRI(ArkprovVocabulary.WAS_GENERATED_BY), activity);
        provenance.add(revision, rdf.createIRI(ArkprovVocabulary.GENERATED_AT_TIME),
                rdf.createLiteral(Instant.now(clock).toString(), VocabXsd.DATETIME));
        previousHead.ifPresent(predecessor ->
                provenance.add(revision, rdf.createIRI(ArkprovVocabulary.WAS_REVISION_OF), predecessor));
        provenance.add(resource, rdf.createIRI(ArkprovVocabulary.HEAD), revision);
        tx.add(rdf.createIRI(ArkprovVocabulary.PROVENANCE_GRAPH), provenance);
    }

    /**
     * Reads a resource's current {@code arkprov:head} pointer, if it has ever been written
     * through this funnel - shared by {@link #recordRevision} (chaining the new revision to its
     * predecessor) and {@link #compareAndUpdate} (the compare-and-set check itself).
     */
    private Optional<IRI> readHead(DatasetTx tx, String subjectIri) {
        String subject = SparqlTerms.iriRef(subjectIri);
        String headPattern = "GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + subject + " <" + ArkprovVocabulary.HEAD + "> ?head }";
        return tx.select("SELECT ?head WHERE { " + headPattern + " }")
                .map(row -> row.getValue("head").orElse(null))
                .filter(IRI.class::isInstance)
                .map(IRI.class::cast)
                .findFirst();
    }

    private void enforceGate(ReadableGraph candidate, ReadableGraph assertedContext) {
        if (assertedContext == null) {
            gate.enforce(candidate);
        } else {
            gate.enforce(candidate, assertedContext);
        }
    }
}
