// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.adr.application.AdrService;
import de.hauschel.arknet.adr.application.port.in.AddAdr.NewAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.UpdateAdr.AdrCorrection;
import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.application.port.out.BoundedContextLookup;
import de.hauschel.arknet.adr.application.port.out.RequirementLookup;
import de.hauschel.arknet.adr.application.port.out.TermLookup;
import de.hauschel.arknet.adr.domain.AdrPeerVanishedException;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.testsupport.GuardedLifecycle;

/**
 * Regression test for kogn-io/arknet#356 against a real RDF4J-backed store: {@code
 * AdrService#update}/{@code AdrRepository#compareAndUpdate} resolve/read a {@code relatedTo} peer
 * outside any transaction ({@code AdrService#resolvePeers} by code, {@code
 * KognioRdfAdrRepository#crossReferenceAssertedContext} by a second, later store read), so a
 * concurrent {@code adr_delete} of that very peer can commit in the window between either read and
 * this write's own commit - before the fix, the SHACL gate then validated against the now-stale
 * asserted context and the write landed a dangling {@code relatedTo} edge onto a peer that no
 * longer existed.
 *
 * <p><strong>How the overlap is forced deterministically.</strong> Mirrors {@code
 * BoundedContextServiceRealStoreConcurrencyTest
 * #linkTermRetriesAndKeepsBothEdgesWhenAConcurrentWriterAdvancedTheHead}: no real threads, a {@link
 * GuardedLifecycle} {@code beforeTransaction} hook fires exactly where {@code
 * WriteFunnel#compareAndUpdate}'s own write transaction is about to open - after {@code
 * crossReferenceAssertedContext}'s pre-transaction read and the SHACL gate have both already run
 * against the peer as it still existed, and before this class's in-transaction backstop ({@code
 * KognioRdfAdrRepository#rejectIfPeersVanished}) gets a chance to see it gone. The hook deletes the
 * peer straight through, one-shot (an {@code AtomicBoolean} guard, since neither hook disarms
 * itself), so the interleaving is pinned rather than hoped for.</p>
 */
class AdrServiceRealStoreConcurrencyTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-09-04T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j realLifecycle;

    @BeforeEach
    void setUp() {
        realLifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
    }

    @AfterEach
    void tearDown() {
        realLifecycle.shutDownAll();
    }

    /**
     * The race kogn-io/arknet#356 describes, played out deterministically: session A prepares
     * {@code adr_update ADR-1 relatedTo=[ADR-2]} (peer resolved, SHACL-validated against ADR-2 as it
     * still exists), session B's {@code adr_delete ADR-2} commits in the window this test pins right
     * before A's own write transaction opens, and A's write must then be rejected - never landing a
     * {@code relatedTo} edge onto the now-deleted ADR-2.
     */
    @Test
    void updateRejectsRatherThanWritingADanglingRelatedToEdgeWhenThePeerIsDeletedConcurrently() {
        AdrService straightThrough = serviceOver(realLifecycle);
        AdrDetail peer = straightThrough.add(PROJECT, newAdr("Peer decision"), "en");
        AdrDetail underTest = straightThrough.add(PROJECT, newAdr("Decision under test"), "en");

        AtomicBoolean pending = new AtomicBoolean(true);
        AdrService racing = serviceOver(new GuardedLifecycle(realLifecycle, tx -> tx, () -> {
            if (pending.compareAndSet(true, false)) {
                straightThrough.delete(PROJECT, peer.adr().code());
            }
        }));

        AdrCorrection correction = AdrCorrection.builder()
                .relatedToCodes(List.of(peer.adr().code().value()))
                .build();

        assertThrows(AdrPeerVanishedException.class,
                () -> racing.update(PROJECT, underTest.adr().code(), correction, "en"));

        assertFalse(pending.get(), "the concurrent delete must have committed - nothing was raced otherwise");
        AdrDetail stored = straightThrough.get(PROJECT, underTest.adr().code(), null).orElseThrow();
        assertTrue(stored.relatedTo().isEmpty(),
                "no dangling relatedTo edge may survive the rejected write - see kogn-io/arknet#356");
    }

    private static NewAdr newAdr(String name) {
        return new NewAdr(name, "Some context", "Some decision", List.of(), List.of(), "en",
                List.of(), List.of(), List.of(), List.of());
    }

    /**
     * A service wired over {@code lifecycle} whose cross-BC lookups are never expected to be
     * called - this test only ever exercises {@code relatedTo}, resolved through the repository
     * itself.
     */
    private static AdrService serviceOver(DatasetLifecycle lifecycle) {
        AdrRepository repository =
                KognioRdfAdrRepositoryFactory.over(lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        RequirementLookup neverCalledRequirementLookup = (projectId, code) -> {
            throw new AssertionError("not expected to resolve a requirement code in this test");
        };
        BoundedContextLookup neverCalledContextLookup = (projectId, code) -> {
            throw new AssertionError("not expected to resolve a bounded context code in this test");
        };
        TermLookup neverCalledTermLookup = (projectId, code) -> {
            throw new AssertionError("not expected to resolve a term code in this test");
        };
        return new AdrService(repository, new UuidResourceIdFactory(), neverCalledRequirementLookup,
                neverCalledContextLookup, neverCalledTermLookup, CLOCK);
    }
}
