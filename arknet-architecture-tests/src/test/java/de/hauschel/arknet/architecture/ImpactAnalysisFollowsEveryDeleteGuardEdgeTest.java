// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfActorRepository;
import de.hauschel.arknet.actor.adapter.kogniordf.KognioRdfRoleRepository;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepository;
import de.hauschel.arknet.mcp.trace.TraceabilityGraph;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfConstraintRepository;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepository;
import de.hauschel.arknet.uc.adapter.kogniordf.KognioRdfUseCaseRepository;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepository;

/**
 * Nails down that the tool asked "what breaks if I change this" never contradicts the one that
 * enforces the answer.
 *
 * <p>Two hand-written lists describe the same edges from opposite ends. Each out-adapter with a
 * delete path carries a {@code REFERENCING_PREDICATES} map naming the edges that block the delete;
 * {@code TraceabilityGraph} carries {@code DEPENDENT_EDGE_PREDICATES} (plus the one-member forward
 * set) naming the edges {@code impact_analysis} traverses. An edge in the first list but not the
 * second produces exactly the contradiction issue #293 named when it put
 * {@code arkddd:upstream}/{@code arkddd:downstream} into both, and kogn-io/arknet#405 repeated for
 * {@code arkproc:filledBy}: {@code impact_analysis} answers "nothing depends on this" for exactly
 * the resource whose delete is then refused with "still referenced".</p>
 *
 * <p>{@code orphan_check} is deliberately out of scope here: it does not read this set at all, but
 * three dedicated predicate checks of its own ({@code realisingUseCases}, {@code isReferencedTerm},
 * {@code isConstraintReferenced}), and it reports no use-case or bounded-context orphans in the
 * first place. Whether an orphan check ought to honour a guarded edge is a question about what
 * "orphan" means per resource type, not about this consistency obligation.</p>
 *
 * <p><strong>Why the obligation runs in one direction only.</strong> Every guarded edge must be
 * traversed, but not every traversed edge guards a delete: {@code arkreq:mainStep}/
 * {@code arkreq:extensionStep} are in the traversal purely to hop a reached step back to its use
 * case, and {@code arkarch:supersedes} is a read-only legacy write shape no delete blocks on. So
 * this test asserts containment, never equality.</p>
 *
 * <p><strong>Why this cannot be caught elsewhere.</strong> {@code ReferenceGuardsCoverEveryOntologyEdgeTest}
 * holds the guard maps against the shipped ontologies - it would stay green with an
 * {@code impact_analysis} that follows none of those edges at all, because the traversal is a
 * different module's private field in a different bounded context's reading path. Nothing but this
 * test ties the two sides together; kogn-io/arknet#566 shipped six guarded edges that the traversal
 * did not know about, and every test in either module stayed green.</p>
 *
 * <p><strong>Why reflection.</strong> Same reasoning as the sibling test: both sides are private
 * implementation detail, and widening them just so a test can read them would turn an internal into
 * API. A rename or move fails this test loudly rather than silently skipping the check.</p>
 */
class ImpactAnalysisFollowsEveryDeleteGuardEdgeTest {

    /** Every out-adapter that refuses a delete while something still points at the resource. */
    private static final Set<Class<?>> GUARDING_REPOSITORIES = Set.of(
            KognioRdfTermRepository.class, KognioRdfActorRepository.class, KognioRdfRoleRepository.class,
            KognioRdfConstraintRepository.class, KognioRdfRequirementRepository.class,
            KognioRdfUseCaseRepository.class, KognioRdfBoundedContextRepository.class);

    @Test
    void everyPredicateADeleteGuardBlocksOnIsTraversedByImpactAnalysis() {
        Set<String> traversed = traversedPredicates();
        Map<String, Set<String>> gapsByRepository = new LinkedHashMap<>();
        for (Class<?> repository : GUARDING_REPOSITORIES.stream()
                .sorted(Comparator.comparing(Class::getSimpleName)).toList()) {
            Set<String> gap = new TreeSet<>(referencingPredicatesOf(repository).keySet());
            gap.removeAll(traversed);
            if (!gap.isEmpty()) {
                gapsByRepository.put(repository.getSimpleName(), gap);
            }
        }

        assertTrue(gapsByRepository.isEmpty(),
                () -> "impact_analysis does not follow these delete-blocking edges: " + gapsByRepository
                        + " - add them to TraceabilityGraph.DEPENDENT_EDGE_PREDICATES, or, if the edge "
                        + "is genuinely not an impact edge, say so in that field's javadoc and exclude "
                        + "it here on purpose");
    }

    /** The union of {@code TraceabilityGraph}'s backward- and forward-followed predicate sets. */
    private static Set<String> traversedPredicates() {
        Set<String> traversed = new HashSet<>(predicateSetOf("DEPENDENT_EDGE_PREDICATES"));
        traversed.addAll(predicateSetOf("FORWARD_DEPENDENT_EDGE_PREDICATES"));
        return traversed;
    }

    @SuppressWarnings("unchecked")
    private static Set<String> predicateSetOf(String fieldName) {
        try {
            Field field = TraceabilityGraph.class.getDeclaredField(fieldName);
            field.setAccessible(true);
            return (Set<String>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("TraceabilityGraph." + fieldName
                    + " is gone or is no longer a private static Set<String> - this test reads it "
                    + "reflectively; adjust the test rather than dropping the check", e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> referencingPredicatesOf(Class<?> repository) {
        try {
            Field field = repository.getDeclaredField("REFERENCING_PREDICATES");
            field.setAccessible(true);
            return (Map<String, String>) field.get(null);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(repository.getName()
                    + " has no private static REFERENCING_PREDICATES map any more - this test reads it "
                    + "reflectively; adjust the test rather than dropping the check", e);
        }
    }
}
