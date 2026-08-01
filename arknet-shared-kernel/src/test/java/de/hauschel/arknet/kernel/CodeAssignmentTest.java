// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for the retry algorithm of {@link CodeAssignment}.
 *
 * <p>Pure, framework-free - a fake collision signal plus a counting supplier stand in for the
 * real out-adapter round trip so the loop's bound-enforcement and exception discrimination are
 * covered directly, without going through a {@code *RealStoreConcurrencyTest} three modules
 * away (issue #144, issue #95).</p>
 */
class CodeAssignmentTest {

    private static final class CollisionException extends RuntimeException {
    }

    private static final class OtherException extends RuntimeException {
    }

    @Test
    void succeedsOnTheFirstAttempt() {
        AtomicInteger calls = new AtomicInteger();

        String result = CodeAssignment.createRetryingOnCodeCollision(
                CollisionException.class, () -> {
                    calls.incrementAndGet();
                    return "FR-1";
                });

        assertEquals("FR-1", result);
        assertEquals(1, calls.get());
    }

    @Test
    void succeedsAfterCollisionsWithinTheBound() {
        AtomicInteger calls = new AtomicInteger();

        String result = CodeAssignment.createRetryingOnCodeCollision(
                5, CollisionException.class, () -> {
                    if (calls.incrementAndGet() < 3) {
                        throw new CollisionException();
                    }
                    return "FR-3";
                });

        assertEquals("FR-3", result);
        assertEquals(3, calls.get());
    }

    @Test
    void throwsTheLastCollisionOnceExhausted() {
        AtomicInteger calls = new AtomicInteger();

        CollisionException thrown = assertThrows(CollisionException.class,
                () -> CodeAssignment.createRetryingOnCodeCollision(
                        3, CollisionException.class, () -> {
                            calls.incrementAndGet();
                            throw new CollisionException();
                        }));

        assertEquals(3, calls.get());
        assertEquals(thrown.getClass(), CollisionException.class);
    }

    @Test
    void rethrowsANonCollisionExceptionUnchangedWithoutRetrying() {
        AtomicInteger calls = new AtomicInteger();

        assertThrows(OtherException.class,
                () -> CodeAssignment.createRetryingOnCodeCollision(
                        CollisionException.class, () -> {
                            calls.incrementAndGet();
                            throw new OtherException();
                        }));

        assertEquals(1, calls.get());
    }

    @Test
    void rejectsMaxAttemptsBelowOne() {
        assertThrows(IllegalArgumentException.class,
                () -> CodeAssignment.createRetryingOnCodeCollision(
                        0, CollisionException.class, () -> "unused"));
    }

    @Test
    void rejectsNullCollisionSignal() {
        assertThrows(NullPointerException.class,
                () -> CodeAssignment.createRetryingOnCodeCollision(null, () -> "unused"));
    }

    @Test
    void rejectsNullAttempt() {
        assertThrows(NullPointerException.class,
                () -> CodeAssignment.createRetryingOnCodeCollision(CollisionException.class, null));
    }
}
