// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BrokenBarrierException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.BindingSet;
import io.kogn.rdf.dataset.ConcurrencyConflictException;
import io.kogn.rdf.dataset.DatasetExport;
import io.kogn.rdf.dataset.DatasetTransactor;
import io.kogn.rdf.dataset.DatasetTx;
import io.kogn.rdf.dataset.GraphStore;
import io.kogn.rdf.dataset.SparqlQuery;
import io.kogn.rdf.dataset.SparqlUpdate;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.BlankNodeOrIRI;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.application.port.out.RevisionToken;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.DuplicateProjectLabelException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.StaleProjectException;
import de.hauschel.arknet.prj.domain.UnattributedRegistrationConflictException;

/**
 * Regression tests for {@link KognioRdfProjectRegistry#register} and
 * {@link KognioRdfProjectRegistry#compareAndUpdate} against a real RDF4J-backed store (on-disk
 * {@code NativeStore}) with real threads: transactions whose writers genuinely <em>overlap</em>,
 * so that both pass their in-transaction guards before either commits and only the second commit
 * is rejected - by the store, not by a guard, the {@code arknet-project} counterpart of the four
 * model contexts' {@code *RealStoreConcurrencyTest}s.
 *
 * <p><strong>What the {@code register} races prove.</strong> Two things the four model contexts' tests cannot cover,
 * because this context differs from them in two ways. First: it guards <em>two</em> uniqueness
 * rules, the label (through {@link de.hauschel.arknet.persistence.WriteFunnel}'s {@code code}
 * parameter) and the anchor ({@link KognioRdfProjectRegistry#checkAnchorUniqueness}
 * decision 4) - so "you lost the commit race" does not by itself say which rule was broken.
 * Second: there is no {@code CodeAssignment} retry to heal the loser (there is no {@code PRJ-N}
 * code to recompute), so whatever the loser is thrown is what a caller actually reads. The proof
 * is therefore not only that the invariant holds - one anchor, one project, even under a genuinely
 * overlapping race - but that the losing caller is told <em>which</em> collision cost it the
 * write.</p>
 *
 * <p><strong>What the {@code compareAndUpdate} races prove (issue #178).</strong> The register
 * race above is the create-path counterpart of {@code CodeAssignment}-style contests; the
 * read-modify-write path behind {@code project_attach_anchor}/{@code project_rename} had no real
 * -store, real-thread proof of its own before this class. Two {@code compareAndUpdate} calls
 * against the very same, already-registered project, both reading the same
 * {@code arkprov:head}: both pass the funnel's synchronous head check because neither sees the
 * other's uncommitted write, and only the second commit is rejected by the store - the funnel
 * translates that lost {@code SERIALIZABLE} conflict into the identical {@link StaleProjectException}
 * a synchronously stale caller would already get from the head comparison itself (see
 * {@link de.hauschel.arknet.persistence.WriteFunnel#compareAndUpdate}'s "two ways to observe a
 * conflict, one signal" javadoc). The proof is that this second, harder-to-reach interleaving
 * collapses onto the same signal rather than surfacing a raw store exception or, worse, silently
 * losing one writer's change - and that the loser's whole transaction, including its
 * {@link KognioRdfProjectRegistry#deleteProjectAndItsAnchors} deletes, rolls back rather than
 * leaving an orphaned or half-written anchor behind.</p>
 *
 * <p><strong>Why the on-disk sail.</strong> Every race here is decided by the store's commit-time
 * conflict detection, and that lives in each sail rather than in a shared layer above them:
 * {@code rdf4j-sail-memory} and {@code rdf4j-sail-nativerdf} are two separate code paths. The
 * daemon runs on the {@code NativeStore}, so this store is built {@code PERSISTENT} - with the very
 * {@link DatasetStoreConfig#persistentDefault()} configuration the composition root uses.
 * {@link KognioRdfProjectRegistryTest} stays {@code IN_MEMORY} on purpose: what it asserts
 * sits above the store, where the faster sail is the legitimate choice.</p>
 *
 * <p><strong>Driven at the out-port, not through {@code ProjectService}.</strong> The service's
 * {@code register} runs a {@code findByAnchor} pre-check outside any transaction, which catches the
 * <em>non</em>-parallel case and would only dilute what these tests pin down. For {@code attach}/
 * {@code rename} there is a second, stronger reason: {@code ProjectService#withProjectLock}
 * serialises every writer of the <em>same</em> {@link ProjectId} through one JVM-local monitor
 * (issue #173), so two overlapping {@code compareAndUpdate} calls against one project could never
 * be provoked through the service at all in a single JVM. The race lives below the service, in the
 * adapter, so that is where every test in this class drives it.</p>
 *
 * <p><strong>How the overlap is forced deterministically.</strong> A {@link DatasetLifecycle}
 * decorator wraps each caller's {@link DatasetTx} so that it blocks on a two-party
 * {@link CyclicBarrier} right before its first {@code add} - the moment every guard of that
 * transaction has passed and nothing has been written yet. The hook deliberately hangs on the
 * first write rather than on a counted {@code contains} call the way {@code
 * BoundedContextServiceRealStoreConcurrencyTest} does: this write path issues four such guards
 * (the funnel's identity and label checks, then the body's own label and per-anchor checks), and
 * a count would silently stop pinning the intended moment the day one of them is added or dropped.
 * "The last guard has passed" is what matters, and "about to write" says exactly that. The same
 * decorator serves the {@code compareAndUpdate} races unchanged: that path's own last guard
 * ({@link KognioRdfProjectRegistry#checkAnchorUniqueness}, reached after the funnel's subject and
 * head checks and after {@link KognioRdfProjectRegistry#deleteProjectAndItsAnchors}'s deletes)
 * also sits immediately before that transaction's first {@code add}. A
 * {@link CountDownLatch} then holds the loser until the winner's transaction has fully committed,
 * so which of the two loses is deterministic instead of flaky. The decorator disarms itself after
 * firing once, so nothing that follows the race is synchronised.</p>
 *
 * <p><strong>Timeout.</strong> {@link CyclicBarrier#await()}/{@link CountDownLatch#await()} block
 * indefinitely by default; a future regression that stops one caller from ever reaching its
 * barrier/latch would hang {@code join()} forever, so neither {@code @AfterEach} nor
 * {@code shutDownAll()} would ever run - the build would hang instead of failing. The project sets
 * no class-level timeouts: the backstop is project-wide,
 * {@code junit.jupiter.execution.timeout.default} in the root POM's Surefire
 * {@code configurationParameters}, sized to catch a hang rather than to police runtime
 * (kogn-io/arknet#458); each interleaving itself normally resolves in well under a second.</p>
 */
class ProjectRegistryRealStoreConcurrencyTest {

    /**
     * The {@code NativeStore}'s on-disk home, managed by JUnit rather than by
     * {@code Files.createTempDirectory}: a persistent store fills its directory, and JUnit deletes
     * this one after {@link #tearDown()} has shut the store down.
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j realLifecycle;
    private ProjectRegistry straightThrough;

    @BeforeEach
    void setUp() {
        realLifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        straightThrough = KognioRdfProjectRepositoryFactory.registryOver(realLifecycle, DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        realLifecycle.shutDownAll();
    }

    /**
     * The anchor uniqueness invariant itself under a genuinely overlapping race: one anchor
     * ends up owned by exactly one project - and the loser is told about the <em>anchor</em>.
     *
     * <p>Before the {@code commitConflict} translator existed, the loser was thrown
     * {@link DuplicateProjectLabelException} for a label no project had ever registered, because
     * {@code WriteFunnel#create} reported every lost commit as a code collision. Both
     * callers deliberately register a different label here, so nothing but the anchor can be the
     * real cause and a label complaint cannot be right by accident.</p>
     */
    @Test
    void concurrentRegistrationsOfTheSameAnchor_leaveOneOwnerAndTellTheLoserAboutTheAnchor()
            throws InterruptedException {
        Anchor contested = pathAnchor("/home/dev/arknet");
        Project winner = new Project(freshId(), "winner-label", List.of(contested));
        Project loser = new Project(freshId(), "loser-label", List.of(contested));

        Race race = raceRegistrations(winner, loser);

        assertNull(race.winnerFailure(), "the winner commits first and must not fail");
        AnchorAlreadyRegisteredException rejected = assertInstanceOf(AnchorAlreadyRegisteredException.class,
                race.loserFailure(), "the loser lost on the anchor, so that is what it must be told");
        assertEquals(contested, rejected.anchor());
        assertEquals(winner.id(), rejected.owner(), "the message must name the project that actually owns it");

        assertEquals(List.of(winner), straightThrough.findAll(),
                "the loser's whole transaction must have rolled back - one anchor, one project");
    }

    /**
     * The precedence itself (issue #179): a genuine <em>double</em> collision, where the losing
     * write breaks <em>both</em> uniqueness rules at once - same anchor <em>and</em> same label -
     * so the two tests above cannot tell the precedence apart from a coincidence. Only this test
     * pins {@link KognioRdfProjectRegistry#attributeLostRegistration}'s documented "anchor before
     * label" ordering: were the check ever swapped, this is the only test in the class that would
     * notice, because the two single-rule tests above would keep passing regardless of which rule
     * is checked first.
     */
    @Test
    void concurrentRegistrationsOfTheSameAnchorAndTheSameLabel_leaveOneOwnerAndTellTheLoserAboutTheAnchor()
            throws InterruptedException {
        Anchor contested = pathAnchor("/home/dev/arknet");
        Project winner = new Project(freshId(), "arknet", List.of(contested));
        Project loser = new Project(freshId(), "arknet", List.of(contested));

        Race race = raceRegistrations(winner, loser);

        assertNull(race.winnerFailure(), "the winner commits first and must not fail");
        AnchorAlreadyRegisteredException rejected = assertInstanceOf(AnchorAlreadyRegisteredException.class,
                race.loserFailure(),
                "both rules are genuinely broken, and the documented precedence names the anchor");
        assertEquals(contested, rejected.anchor());
        assertEquals(winner.id(), rejected.owner());

        assertEquals(List.of(winner), straightThrough.findAll(),
                "the loser's whole transaction must have rolled back - one anchor, one project");
    }

    /**
     * The label rule under the same overlapping race: both callers pass the funnel's synchronous
     * {@code dcterms:identifier} check because neither sees the other's uncommitted write, and the
     * loser must still be told its label is taken - here it genuinely is.
     */
    @Test
    void concurrentRegistrationsOfTheSameLabel_leaveOneHolderAndTellTheLoserAboutTheLabel()
            throws InterruptedException {
        Project winner = new Project(freshId(), "arknet", List.of(pathAnchor("/home/dev/arknet")));
        Project loser = new Project(freshId(), "arknet", List.of(pathAnchor("/home/dev/arknet-worktree")));

        Race race = raceRegistrations(winner, loser);

        assertNull(race.winnerFailure());
        assertInstanceOf(DuplicateProjectLabelException.class, race.loserFailure(),
                "the loser lost on the label, and that is a collision a human can cause and fix");
        assertEquals(List.of(winner), straightThrough.findAll());
    }

    /**
     * The same overlap, but between two registrations that share <em>nothing</em> - different
     * label, different anchor: both commit. The uniqueness guards cost concurrent registrations of
     * unrelated projects nothing, even though every one of them reads {@code dcterms:identifier}
     * with an unbound subject: the store's conflict detection is finer than the graph the guard
     * scans, so one new project's identifier triple does not invalidate another's read.
     *
     * <p>Measured, not assumed - and it is the reason the two tests above may assert a
     * <em>specific</em> collision at all. Were an unrelated writer enough to lose the race, the
     * loser's rejection would no longer identify what it collided with, and any collision named
     * for it would be a guess dressed up as a diagnosis.</p>
     */
    @Test
    void twoUnrelatedRegistrationsOverlapWithoutCostingEitherOfThemTheWrite() throws InterruptedException {
        Project first = new Project(freshId(), "alpha", List.of(pathAnchor("/home/dev/alpha")));
        Project second = new Project(freshId(), "beta", List.of(pathAnchor("/home/dev/beta")));

        Race race = raceRegistrations(first, second, false);

        assertNull(race.winnerFailure());
        assertNull(race.loserFailure(), "nothing collided, so neither write may be rejected");
        assertEquals(Set.of(first, second), Set.copyOf(straightThrough.findAll()));
    }

    /**
     * The residual case, and the reason {@link KognioRdfProjectRegistry} wraps the store's own
     * exception instead of inventing a rule violation: a lost commit that none of this context's
     * uniqueness rules explains. The test above shows the real store does not produce one today
     * for two unrelated registrations, so the conflict is injected here rather than raced - which
     * is the honest way to pin a fallback whose trigger is, by definition, something the adapter
     * does not know about (a future guard, a different sail, a store that detects conflicts more
     * coarsely - the store behind the port stays swappable).
     *
     * <p>What must not happen is the adapter inventing an explanation: reporting
     * {@link DuplicateProjectLabelException} for a label that is demonstrably free - a defect
     * uncovered before the {@code commitConflict} translator existed - would send the caller
     * after a collision that never existed. Wrapping the raw conflict in
     * {@link UnattributedRegistrationConflictException} is not that: it invents no collision, it
     * only makes the residual signal catchable by {@code ProjectService#register}'s retry loop -
     * {@code arknet-project-core} must stay free of the store's own exception type (see
     * {@code arknet-architecture-tests}' dependency rules), so this out-port cannot surface it
     * directly (issue #67).</p>
     */
    @Test
    void aLostCommitNoRuleExplainsSurfacesAsAnUnattributedRegistrationConflict() {
        ConcurrencyConflictException storeConflict = new ConcurrencyConflictException("lost the commit", null);
        ProjectRegistry failingCommits =
                KognioRdfProjectRepositoryFactory.registryOver(new GuardedLifecycle(realLifecycle,
                        tx -> tx, storeConflict), DisplayLocale.DEFAULT);
        Project project = new Project(freshId(), "free-label", List.of(pathAnchor("/home/dev/free")));

        UnattributedRegistrationConflictException thrown = assertThrows(
                UnattributedRegistrationConflictException.class, () -> failingCommits.register(project, null, null, null, null));

        assertSame(storeConflict, thrown.getCause(),
                "with no rule broken there is nothing truthful to translate into - the raw conflict "
                        + "must still be reachable, not swallowed");
        assertTrue(straightThrough.findAll().isEmpty(), "the rejected write must have left nothing behind");
    }

    /**
     * The {@code compareAndUpdate} counterpart of the {@code register} races above (issue #178):
     * two {@code project_attach_anchor}-shaped writes against the very same, already-registered
     * project, both reading the same {@code arkprov:head}, held open until both have passed the
     * funnel's synchronous head check. Only the second commit is rejected by the store, and the
     * funnel must translate that genuine {@code SERIALIZABLE} conflict into the same
     * {@link StaleProjectException} a synchronously stale caller already gets - not a raw store
     * exception, and not a silent merge that would keep the loser's anchor around as an orphan
     * with no owner.
     */
    @Test
    void concurrentAttachesOfDifferentAnchorsToTheSameProject_leaveOneWinnerAndTellTheLoserItsHeadIsStale()
            throws InterruptedException {
        Anchor original = pathAnchor("/home/dev/arknet");
        Project initial = new Project(freshId(), "arknet", List.of(original));
        straightThrough.register(initial, null, null, null, null);
        RevisionToken headBeforeRace = straightThrough.findCurrentById(initial.id()).orElseThrow().head();

        Anchor winnerAnchor = pathAnchor("/home/dev/arknet-winner");
        Anchor loserAnchor = pathAnchor("/home/dev/arknet-loser");
        Project winnerUpdate = new Project(initial.id(), initial.label(), List.of(original, winnerAnchor));
        Project loserUpdate = new Project(initial.id(), initial.label(), List.of(original, loserAnchor));

        Race race = raceCompareAndUpdates(headBeforeRace, winnerUpdate, loserUpdate);

        assertNull(race.winnerFailure(), "the winner commits first and must not fail");
        assertInstanceOf(StaleProjectException.class, race.loserFailure(),
                "the loser lost a genuine SERIALIZABLE conflict at commit, which compareAndUpdate must "
                        + "translate into the same signal a synchronously stale caller would get");

        Project stored = straightThrough.findById(initial.id()).orElseThrow();
        assertEquals(Set.of(original, winnerAnchor), Set.copyOf(stored.anchors()),
                "only the winner's attach may be visible");
        assertTrue(straightThrough.findByAnchor(loserAnchor).isEmpty(),
                "the loser's whole transaction must have rolled back - its anchor must not exist as an "
                        + "orphan with no owning project");
    }

    /**
     * The {@code project_rename} shape of the same {@code compareAndUpdate} race: both renames
     * read the same head, only the winner's commit succeeds, and the loser's label must not
     * survive anywhere in the registry - not as this project's label, and not dangling on some
     * other subject.
     */
    @Test
    void concurrentRenamesOfTheSameProject_leaveOneWinnerAndTellTheLoserItsHeadIsStale()
            throws InterruptedException {
        Project initial = new Project(freshId(), "arknet", List.of(pathAnchor("/home/dev/arknet")));
        straightThrough.register(initial, null, null, null, null);
        RevisionToken headBeforeRace = straightThrough.findCurrentById(initial.id()).orElseThrow().head();

        Project winnerUpdate = new Project(initial.id(), "arknet-winner", initial.anchors());
        Project loserUpdate = new Project(initial.id(), "arknet-loser", initial.anchors());

        Race race = raceCompareAndUpdates(headBeforeRace, winnerUpdate, loserUpdate);

        assertNull(race.winnerFailure(), "the winner commits first and must not fail");
        assertInstanceOf(StaleProjectException.class, race.loserFailure(),
                "the loser lost a genuine SERIALIZABLE conflict at commit, which compareAndUpdate must "
                        + "translate into the same signal a synchronously stale caller would get");

        Project stored = straightThrough.findById(initial.id()).orElseThrow();
        assertEquals("arknet-winner", stored.label(), "only the winner's rename may be visible");
        assertTrue(straightThrough.findAll().stream().noneMatch(p -> p.label().equals("arknet-loser")),
                "the loser's whole transaction must have rolled back - its label must not exist anywhere");
    }

    /**
     * The {@code updateAttributes} counterpart of the two {@code compareAndUpdate} races above
     * (issue #230 review) - proof that {@code arkprov:head} is a concurrency token per
     * <em>resource</em>, not per <em>predicate</em>, even though {@link
     * KognioRdfProjectRegistry#updateAttributes} is a targeted patch that never touches
     * {@code label}/{@code arkprj:anchor} at all. Both calls here patch the very same field
     * ({@code arkprj:defaultLanguage}) with a different value, both read the very same head, and
     * both pass the funnel's synchronous head check because neither sees the other's uncommitted
     * write - only the second commit is rejected by the store, exactly like {@link
     * #concurrentRenamesOfTheSameProject_leaveOneWinnerAndTellTheLoserItsHeadIsStale}. Were the
     * two calls patching genuinely disjoint predicates instead (e.g. one {@code description}, the
     * other {@code defaultLanguage}), the store's finer-grained conflict detection could let both
     * survive the way {@link #twoUnrelatedRegistrationsOverlapWithoutCostingEitherOfThemTheWrite}
     * shows for unrelated projects - this test pins the CAS guarantee on the case that actually
     * matters: two writers correcting the <em>same</em> attribute.
     */
    @Test
    void concurrentUpdateAttributesOfTheSameProject_leaveOneWinnerAndTellTheLoserItsHeadIsStale()
            throws InterruptedException {
        Project initial = new Project(freshId(), "arknet", List.of(pathAnchor("/home/dev/arknet")));
        straightThrough.register(initial, null, null, null, null);
        RevisionToken headBeforeRace = straightThrough.findCurrentById(initial.id()).orElseThrow().head();

        Race race = raceUpdateAttributes(initial.id(), headBeforeRace, "de", "fr");

        assertNull(race.winnerFailure(), "the winner commits first and must not fail");
        assertInstanceOf(StaleProjectException.class, race.loserFailure(),
                "the loser lost a genuine SERIALIZABLE conflict at commit, which compareAndUpdate must "
                        + "translate into the same signal a synchronously stale caller would get");

        Project stored = straightThrough.findById(initial.id()).orElseThrow();
        assertTrue(Set.of("de", "fr").contains(stored.defaultLanguage()),
                "only the winner's defaultLanguage may be visible - not both merged, not neither");
    }

    // ---- the race itself -----------------------------------------------------------------

    /** What both callers came out of a race with: the winner's outcome and the loser's. */
    private record Race(Throwable winnerFailure, Throwable loserFailure) {
    }

    /** Races two registrations that are expected to collide, so the loser must be rejected. */
    private Race raceRegistrations(Project winner, Project loser) throws InterruptedException {
        return raceRegistrations(winner, loser, true);
    }

    /**
     * Registers both projects concurrently through two separate registries, holding each caller at
     * its first write until both have passed every guard, and the loser additionally until the
     * winner has committed.
     *
     * @param expectLoserRejected whether the second committer must come back with a failure - the
     *                            guard that a test claiming to have raced actually raced, rather
     *                            than having run two writes that never overlapped
     */
    private Race raceRegistrations(Project winner, Project loser, boolean expectLoserRejected)
            throws InterruptedException {
        CyclicBarrier bothGuardsPassed = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        ProjectRegistry winnerRegistry = guardedRegistry(() -> awaitBarrier(bothGuardsPassed));
        ProjectRegistry loserRegistry = guardedRegistry(() -> {
            awaitBarrier(bothGuardsPassed);
            awaitLatch(winnerCommitted);
        });

        AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            try {
                winnerRegistry.register(winner, null, null, null, null);
            } catch (Throwable t) {
                // Throwable, not RuntimeException: a broken harness (a missing method, an
                // assertion inside the decorator) must surface as a failed assertion below
                // rather than as a silently absent failure.
                winnerFailure.set(t);
            } finally {
                winnerCommitted.countDown();
            }
        });
        Thread loserThread = new Thread(() -> {
            try {
                loserRegistry.register(loser, null, null, null, null);
            } catch (Throwable t) {
                loserFailure.set(t);
            }
        });

        winnerThread.start();
        loserThread.start();
        winnerThread.join();
        loserThread.join();

        if (expectLoserRejected) {
            assertNotNull(loserFailure.get(),
                    "the loser must not have committed - the race was not raced otherwise");
        }
        return new Race(winnerFailure.get(), loserFailure.get());
    }

    /**
     * Runs two {@code compareAndUpdate} calls against the same {@code expectedHead} concurrently
     * through two separate registries, holding each caller at its first write until both have
     * passed every guard, and the loser additionally until the winner has committed - the
     * {@code compareAndUpdate} counterpart of {@link #raceRegistrations}.
     */
    private Race raceCompareAndUpdates(RevisionToken expectedHead, Project winner, Project loser)
            throws InterruptedException {
        CyclicBarrier bothGuardsPassed = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        ProjectRegistry winnerRegistry = guardedRegistry(() -> awaitBarrier(bothGuardsPassed));
        ProjectRegistry loserRegistry = guardedRegistry(() -> {
            awaitBarrier(bothGuardsPassed);
            awaitLatch(winnerCommitted);
        });

        AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            try {
                winnerRegistry.compareAndUpdate(expectedHead, winner);
            } catch (Throwable t) {
                winnerFailure.set(t);
            } finally {
                winnerCommitted.countDown();
            }
        });
        Thread loserThread = new Thread(() -> {
            try {
                loserRegistry.compareAndUpdate(expectedHead, loser);
            } catch (Throwable t) {
                loserFailure.set(t);
            }
        });

        winnerThread.start();
        loserThread.start();
        winnerThread.join();
        loserThread.join();

        assertNotNull(loserFailure.get(), "the loser must not have committed - the race was not raced otherwise");
        return new Race(winnerFailure.get(), loserFailure.get());
    }

    /**
     * Runs two {@code updateAttributes} calls against the same {@code expectedHead} concurrently
     * through two separate registries, holding each caller at its first write until both have
     * passed every guard, and the loser additionally until the winner has committed - the
     * {@code updateAttributes} counterpart of {@link #raceCompareAndUpdates}. Both callers patch
     * {@code defaultLanguage} with a different value, so a wrongly merged result (both values
     * ending up stored) would be as visible a failure as neither losing.
     */
    private Race raceUpdateAttributes(ProjectId projectId, RevisionToken expectedHead,
            String winnerDefaultLanguage, String loserDefaultLanguage) throws InterruptedException {
        CyclicBarrier bothGuardsPassed = new CyclicBarrier(2);
        CountDownLatch winnerCommitted = new CountDownLatch(1);

        ProjectRegistry winnerRegistry = guardedRegistry(() -> awaitBarrier(bothGuardsPassed));
        ProjectRegistry loserRegistry = guardedRegistry(() -> {
            awaitBarrier(bothGuardsPassed);
            awaitLatch(winnerCommitted);
        });

        AtomicReference<Throwable> winnerFailure = new AtomicReference<>();
        AtomicReference<Throwable> loserFailure = new AtomicReference<>();

        Thread winnerThread = new Thread(() -> {
            try {
                winnerRegistry.updateAttributes(projectId, expectedHead, null, null, winnerDefaultLanguage, null);
            } catch (Throwable t) {
                winnerFailure.set(t);
            } finally {
                winnerCommitted.countDown();
            }
        });
        Thread loserThread = new Thread(() -> {
            try {
                loserRegistry.updateAttributes(projectId, expectedHead, null, null, loserDefaultLanguage, null);
            } catch (Throwable t) {
                loserFailure.set(t);
            }
        });

        winnerThread.start();
        loserThread.start();
        winnerThread.join();
        loserThread.join();

        assertNotNull(loserFailure.get(), "the loser must not have committed - the race was not raced otherwise");
        return new Race(winnerFailure.get(), loserFailure.get());
    }

    private static ProjectId freshId() {
        return new ProjectId(UUID.randomUUID().toString());
    }

    private static Anchor pathAnchor(String value) {
        return new Anchor(value, AnchorType.PATH);
    }

    /**
     * A registry whose first write transaction runs {@code beforeFirstWrite} right before its
     * first {@code add} - after every guard of that transaction has passed and before anything is
     * written. Disarms itself afterwards, so the store's own read paths and any later write run
     * unsynchronised.
     */
    private ProjectRegistry guardedRegistry(Runnable beforeFirstWrite) {
        AtomicBoolean armed = new AtomicBoolean(true);
        DatasetLifecycle guarded = new GuardedLifecycle(realLifecycle, tx -> {
            if (armed.compareAndSet(true, false)) {
                return new PausingTx(tx, beforeFirstWrite);
            }
            return tx;
        }, null);
        return KognioRdfProjectRepositoryFactory.registryOver(guarded, DisplayLocale.DEFAULT);
    }

    // ---- synchronisation helpers ---------------------------------------------------------

    private static void awaitBarrier(CyclicBarrier barrier) {
        try {
            barrier.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        } catch (BrokenBarrierException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    // ---- DatasetLifecycle decoration -----------------------------------------------------

    /**
     * Wraps a real {@link DatasetLifecycle}, decorating every acquired transaction's
     * {@link DatasetTx} and - if {@code failCommitWith} is given - rejecting every write
     * transaction with it once the body has run, the way a store rejects a commit it found in
     * conflict. The real transactor still sees the exception, so it rolls its transaction back:
     * nothing is written, exactly as in a genuinely lost race.
     */
    private static final class GuardedLifecycle implements DatasetLifecycle {

        private final DatasetLifecycle delegate;
        private final Function<DatasetTx, DatasetTx> txDecorator;
        private final RuntimeException failCommitWith;

        GuardedLifecycle(DatasetLifecycle delegate, Function<DatasetTx, DatasetTx> txDecorator,
                RuntimeException failCommitWith) {
            this.delegate = delegate;
            this.txDecorator = txDecorator;
            this.failCommitWith = failCommitWith;
        }

        @Override
        public DatasetHandle acquire(DatasetId id) {
            return new GuardedHandle(delegate.acquire(id), txDecorator, failCommitWith);
        }

        @Override
        public void close(DatasetId id) {
            delegate.close(id);
        }

        @Override
        public void delete(DatasetId id) {
            delegate.delete(id);
        }

        @Override
        public Set<DatasetId> list() {
            return delegate.list();
        }
    }

    private static final class GuardedHandle implements DatasetHandle {

        private final DatasetHandle delegate;
        private final Function<DatasetTx, DatasetTx> txDecorator;
        private final RuntimeException failCommitWith;

        GuardedHandle(DatasetHandle delegate, Function<DatasetTx, DatasetTx> txDecorator,
                RuntimeException failCommitWith) {
            this.delegate = delegate;
            this.txDecorator = txDecorator;
            this.failCommitWith = failCommitWith;
        }

        @Override
        public GraphStore graphStore() {
            return delegate.graphStore();
        }

        @Override
        public SparqlQuery sparqlQuery() {
            return delegate.sparqlQuery();
        }

        @Override
        public SparqlUpdate sparqlUpdate() {
            return delegate.sparqlUpdate();
        }

        @Override
        public DatasetExport datasetExport() {
            return delegate.datasetExport();
        }

        @Override
        public DatasetTransactor transactor() {
            DatasetTransactor real = delegate.transactor();
            return new DatasetTransactor() {
                @Override
                public <T> T inTransaction(Function<DatasetTx, T> fn) {
                    return real.inTransaction(tx -> {
                        T result = fn.apply(txDecorator.apply(tx));
                        if (failCommitWith != null) {
                            throw failCommitWith;
                        }
                        return result;
                    });
                }
            };
        }

        @Override
        public void close() {
            delegate.close();
        }
    }

    /**
     * Runs {@code beforeFirstWrite} once, immediately before its delegate's first
     * {@link GraphStore#add} call - the point in {@link KognioRdfProjectRegistry}'s write body
     * where every uniqueness guard has passed and no triple has been written yet.
     */
    private static final class PausingTx implements DatasetTx {

        private final DatasetTx delegate;
        private final Runnable beforeFirstWrite;
        private boolean pending = true;

        PausingTx(DatasetTx delegate, Runnable beforeFirstWrite) {
            this.delegate = delegate;
            this.beforeFirstWrite = beforeFirstWrite;
        }

        @Override
        public long add(IRI graph, ReadableGraph data) {
            if (pending) {
                pending = false;
                beforeFirstWrite.run();
            }
            return delegate.add(graph, data);
        }

        @Override
        public boolean contains(IRI graph, BlankNodeOrIRI subject, IRI predicate, RDFTerm object) {
            return delegate.contains(graph, subject, predicate, object);
        }

        @Override
        public boolean ask(String query) {
            return delegate.ask(query);
        }

        @Override
        public boolean ask(String query, Map<String, RDFTerm> bindings) {
            return delegate.ask(query, bindings);
        }

        @Override
        public long remove(IRI graph, ReadableGraph data) {
            return delegate.remove(graph, data);
        }

        @Override
        public void clear(IRI graph) {
            delegate.clear(graph);
        }

        @Override
        public ReadableGraph export(IRI graph) {
            return delegate.export(graph);
        }

        @Override
        public long count(IRI graph) {
            return delegate.count(graph);
        }

        @Override
        public long count() {
            return delegate.count();
        }

        @Override
        public void update(String sparqlUpdate) {
            delegate.update(sparqlUpdate);
        }

        @Override
        public void update(String sparqlUpdate, Map<String, RDFTerm> bindings) {
            delegate.update(sparqlUpdate, bindings);
        }

        @Override
        public Stream<BindingSet> select(String query) {
            return delegate.select(query);
        }

        @Override
        public Stream<BindingSet> select(String query, Map<String, RDFTerm> bindings) {
            return delegate.select(query, bindings);
        }

        @Override
        public ReadableGraph construct(String query) {
            return delegate.construct(query);
        }

        @Override
        public ReadableGraph construct(String query, Map<String, RDFTerm> bindings) {
            return delegate.construct(query, bindings);
        }
    }
}
