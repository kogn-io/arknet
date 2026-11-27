// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf.comparison;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.resource.adapter.kogniordf.comparison.ComparisonStore.ProjectPair;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * Runs one case: the operation under test once through the typed core on the core project and
 * once as a document on the submit project, both after the same preconditions, then compares.
 *
 * <p>What the two sides may differ in is decided by the case: the same graph, the same rejection
 * (each side showing the rule the case names), or a listed {@link Deviation}.</p>
 */
public final class Comparison {

    private final ComparisonStore store;
    private final DeviationList deviations;

    /**
     * @param store      the store of the test class
     * @param deviations the deviations the test class lists
     */
    public Comparison(final ComparisonStore store, final DeviationList deviations) {
        this.store = Objects.requireNonNull(store, "store");
        this.deviations = Objects.requireNonNull(deviations, "deviations");
    }

    /**
     * Runs the operation on both sides and records what each did.
     *
     * @param pair      the projects of the case, preconditions already in place
     * @param core      the core operation, given the core project
     * @param document  the document doing the same through {@code submit}
     * @return the outcome of both sides, to assert on
     */
    public Outcome run(final ProjectPair pair, final Consumer<ProjectId> core, final Document document) {
        CanonicalGraph coreBefore = store.graph(pair.core());
        CanonicalGraph submitBefore = store.graph(pair.submit());
        assertEquals(coreBefore, submitBefore, () -> "the preconditions left different graphs:"
                + coreBefore.differenceTo(submitBefore));
        RuntimeException coreFailure = attempt(() -> core.accept(pair.core()));
        RuntimeException submitFailure = attempt(() -> store.submit(pair.submit(), document));
        return new Outcome(coreBefore, store.graph(pair.core()), coreFailure, store.graph(pair.submit()),
                submitFailure, document);
    }

    private static RuntimeException attempt(final Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    /**
     * What one side must show when it rejects: the exception type and a fragment of its message
     * that names the rule - for the core an exception class (plus a message fragment for a bare
     * {@link IllegalArgumentException}), for {@code submit} the shape name or the engine's reason.
     *
     * @param type     the exception type
     * @param fragment a fragment the message must contain, or {@code null} for the type alone
     */
    public record Rejection(Class<? extends RuntimeException> type, String fragment) {

        /**
         * A rejection of the core.
         *
         * @param type     the exception type
         * @param fragment the message fragment, or {@code null}
         * @return the expectation
         */
        public static Rejection core(final Class<? extends RuntimeException> type, final String fragment) {
            return new Rejection(type, fragment);
        }

        /**
         * A rejection of {@code submit}, naming its rule.
         *
         * @param fragment the shape name or the engine's reason
         * @return the expectation
         */
        public static Rejection submit(final String fragment) {
            return new Rejection(SubmitRejectedException.class, Objects.requireNonNull(fragment, "fragment"));
        }

        void assertShownBy(final String side, final RuntimeException failure) {
            if (failure == null) {
                fail(side + " accepted, expected a rejection by " + this);
            }
            if (!type.isInstance(failure)) {
                throw new AssertionError(side + " rejected with " + failure.getClass().getName()
                        + ", expected " + type.getName(), failure);
            }
            if (fragment != null) {
                assertTrue(String.valueOf(failure.getMessage()).contains(fragment),
                        () -> side + " rejection does not name '" + fragment + "': " + failure.getMessage());
            }
        }
    }

    /**
     * Both sides of one case after the operation.
     */
    public final class Outcome {

        private final CanonicalGraph before;
        private final CanonicalGraph coreAfter;
        private final RuntimeException coreFailure;
        private final CanonicalGraph submitAfter;
        private final RuntimeException submitFailure;
        private final Document document;

        private Outcome(final CanonicalGraph before, final CanonicalGraph coreAfter,
                final RuntimeException coreFailure, final CanonicalGraph submitAfter,
                final RuntimeException submitFailure, final Document document) {
            this.before = before;
            this.coreAfter = coreAfter;
            this.coreFailure = coreFailure;
            this.submitAfter = submitAfter;
            this.submitFailure = submitFailure;
            this.document = document;
        }

        /**
         * Both sides accepted and wrote the same graph.
         */
        public void assertSameGraph() {
            assertAccepted("core", coreFailure);
            assertAccepted("submit", submitFailure);
            assertEquals(coreAfter, submitAfter, () -> "the graphs differ:" + coreAfter.differenceTo(submitAfter)
                    + "\ndocument:\n" + document);
            assertNotEquals(before, coreAfter, "the operation changed nothing; the case compares nothing");
        }

        /**
         * Both sides rejected, each naming the rule of the case, and neither changed its graph.
         *
         * @param core   what the core must show
         * @param submit what {@code submit} must show
         */
        public void assertBothReject(final Rejection core, final Rejection submit) {
            core.assertShownBy("core", coreFailure);
            submit.assertShownBy("submit", submitFailure);
            assertUnchanged("core", coreAfter);
            assertUnchanged("submit", submitAfter);
        }

        /**
         * The two sides differ as a listed deviation says, and only because of it: without the entry
         * this case fails.
         *
         * @param id        the id of the entry in the class's list
         * @param rejecting for an outcome deviation, what the rejecting side must show; ignored for
         *                  a graph deviation
         */
        public void assertDeviation(final String id, final Rejection rejecting) {
            Deviation deviation = deviations.use(id);
            switch (deviation.kind()) {
                case OUTCOME -> assertOutcomeDeviation(deviation, rejecting);
                case GRAPH -> assertGraphDeviation(deviation);
            }
        }

        private void assertOutcomeDeviation(final Deviation deviation, final Rejection rejecting) {
            Objects.requireNonNull(rejecting, "an outcome deviation names what the rejecting side shows");
            if (deviation.coreAccepts()) {
                assertAccepted("core (" + deviation.id() + ")", coreFailure);
                rejecting.assertShownBy("submit (" + deviation.id() + ")", submitFailure);
                assertUnchanged("submit", submitAfter);
                return;
            }
            assertAccepted("submit (" + deviation.id() + ")", submitFailure);
            rejecting.assertShownBy("core (" + deviation.id() + ")", coreFailure);
            assertUnchanged("core", coreAfter);
        }

        private void assertGraphDeviation(final Deviation deviation) {
            assertAccepted("core", coreFailure);
            assertAccepted("submit", submitFailure);
            assertNotEquals(coreAfter, submitAfter, () -> "deviation " + deviation + " no longer occurs");
            List<String> shared = coreAfter.statements().stream()
                    .filter(deviation.dropped())
                    .filter(submitAfter.statements()::contains)
                    .toList();
            assertTrue(shared.isEmpty(), () -> "deviation " + deviation.id()
                    + " drops statements both sides hold, so it would hide a difference in them too: " + shared);
            CanonicalGraph core = coreAfter.without(deviation.dropped());
            CanonicalGraph submit = submitAfter.without(deviation.dropped());
            assertEquals(core, submit, () -> "the graphs differ beyond " + deviation.id() + ":"
                    + core.differenceTo(submit) + "\ndocument:\n" + document);
        }

        private void assertUnchanged(final String side, final CanonicalGraph after) {
            assertEquals(before, after, () -> side + " changed its graph although it rejected:"
                    + before.differenceTo(after));
        }

        private void assertAccepted(final String side, final RuntimeException failure) {
            if (failure != null) {
                throw new AssertionError(side + " rejected: " + failure.getMessage()
                        + (failure instanceof SubmitRejectedException ? "\ndocument:\n" + document : ""), failure);
            }
        }
    }
}
