// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.Objects;
import java.util.function.Supplier;

/**
 * Assigns a workspace-unique, human-readable business code ({@code BC-N}, {@code FR-N},
 * {@code TERM-N}, {@code UCn}, ...) to a brand-new resource, retrying whenever a concurrent
 * caller claims the same candidate code first.
 *
 * <p><strong>The race this closes (issue #144).</strong> Every bounded context computes its next
 * free code client-side - "read the highest running number in the workspace, add one" - and only
 * then asks its out-adapter to {@code create()} the resource. That read and that write are two
 * separate store round trips, so two callers adding a resource of the same type at the same time
 * legitimately compute the <em>same</em> candidate code. The out-adapter's in-transaction
 * uniqueness guard still stops both from ever <em>persisting</em> under one code - it rejects the
 * second writer with a duplicate-code signal - but without this helper that rejection surfaces to
 * one of two perfectly well-formed callers as a failure, even though nothing about its own request
 * was wrong. A single "single-user" model is not a "single-writer" model: parallel sessions of one
 * user against one local store are the normal case (ADR-001), so this guard is needed already
 * there, not just with a multi-tenant remote store.</p>
 *
 * <p><strong>Why it lives in the shared kernel.</strong> All four bounded contexts share this
 * exact read-compute-create-retry shape; duplicating it once per context is precisely what made
 * this bug reproducible four times over. It does not belong in
 * {@code arknet-persistence-support} despite that module already hosting shared out-adapter
 * technique: that module carries {@code io.kogn.rdf} compile dependencies, and the {@code *-core}
 * services calling this helper must stay free of RDF technology (ArchUnit rule 3). The kernel is
 * the one technology-neutral module every {@code *-core} already depends on, so the helper stays
 * pure JDK and reachable without dragging RDF onto a core's classpath.</p>
 *
 * <p><strong>What it deliberately does not do.</strong> It does not know how a code is computed or
 * formatted - the caller's {@code attempt} recomputes the next code against a fresh read on every
 * try (a stale candidate is the whole point). It only knows "run the attempt; if it fails with the
 * caller's duplicate-code signal, compute again; give up loudly after a bound rather than looping
 * forever on pathological, sustained contention".</p>
 */
public final class CodeAssignment {

    /**
     * Default bound on the retry loop. A code collision is resolved by a single retry in the
     * overwhelming majority of cases, since each retry re-reads the now-current state before
     * recomputing; this bound exists only so a pathological, sustained storm of concurrent writers
     * of the same type fails loudly instead of looping forever.
     */
    public static final int DEFAULT_MAX_ATTEMPTS = 20;

    private CodeAssignment() {
    }

    /**
     * Runs {@code attempt} with {@link #DEFAULT_MAX_ATTEMPTS} retries on a code collision.
     *
     * @param <T>            the type of resource being created and returned
     * @param <C>            the caller's duplicate-code signal type
     * @param collisionSignal the exception type that means "this candidate code is taken, compute
     *                        again" - any other runtime exception propagates unchanged (must not be
     *                        {@code null})
     * @param attempt         computes the next free code against a fresh read, builds the resource
     *                        and persists it, returning the persisted resource; re-invoked on each
     *                        retry (must not be {@code null})
     * @return the resource {@code attempt} persisted once it no longer collides
     */
    public static <T, C extends RuntimeException> T createRetryingOnCodeCollision(
            Class<C> collisionSignal, Supplier<T> attempt) {
        return createRetryingOnCodeCollision(DEFAULT_MAX_ATTEMPTS, collisionSignal, attempt);
    }

    /**
     * Runs {@code attempt}, retrying on a code collision up to {@code maxAttempts} times.
     *
     * @param <T>            the type of resource being created and returned
     * @param <C>            the caller's duplicate-code signal type
     * @param maxAttempts     the maximum number of attempts (must be at least 1)
     * @param collisionSignal the exception type that means "this candidate code is taken, compute
     *                        again" - any other runtime exception propagates unchanged (must not be
     *                        {@code null})
     * @param attempt         computes the next free code against a fresh read, builds the resource
     *                        and persists it, returning the persisted resource; re-invoked on each
     *                        retry (must not be {@code null})
     * @return the resource {@code attempt} persisted once it no longer collides
     * @throws IllegalArgumentException if {@code maxAttempts} is less than 1
     * @throws C                        the last observed collision, if every attempt collided
     */
    public static <T, C extends RuntimeException> T createRetryingOnCodeCollision(
            int maxAttempts, Class<C> collisionSignal, Supplier<T> attempt) {
        Objects.requireNonNull(collisionSignal, "collisionSignal");
        Objects.requireNonNull(attempt, "attempt");
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("maxAttempts must be at least 1, was " + maxAttempts);
        }
        C lastCollision = null;
        for (int attemptNo = 1; attemptNo <= maxAttempts; attemptNo++) {
            try {
                return attempt.get();
            } catch (RuntimeException e) {
                if (!collisionSignal.isInstance(e)) {
                    throw e;
                }
                // A concurrent caller took this candidate code between our read and our write;
                // recompute against the now-current state instead of failing this caller.
                lastCollision = collisionSignal.cast(e);
            }
        }
        throw lastCollision;
    }
}
