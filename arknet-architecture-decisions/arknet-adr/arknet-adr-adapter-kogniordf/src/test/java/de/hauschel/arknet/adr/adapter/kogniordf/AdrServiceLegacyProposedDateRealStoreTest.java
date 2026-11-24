// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.adr.application.AdrService;
import de.hauschel.arknet.adr.application.port.in.AddAdr.NewAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.UpdateAdr.AdrCorrection;
import de.hauschel.arknet.adr.application.port.out.AdrRepository;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ArkarchVocabulary;

/**
 * A Proposed record carrying a decision date - possible only before kogn-io/arknet#374, stored here
 * past the gate - against the typed tools since {@code ashapes:ADR-proposedUndated}
 * (kogn-io/arknet#742): a write that leaves it Proposed is rejected by the gate, the transition to
 * Accepted replaces the date and heals it, and a delete stays open.
 */
class AdrServiceLegacyProposedDateRealStoreTest {

    private static final ProjectId PROJECT = new ProjectId("a");
    private static final String CONTENT_GRAPH = "https://w3id.org/arknet/model/content";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-24T00:00:00Z"), ZoneOffset.UTC);

    private DatasetLifecycleRdf4j lifecycle;
    private AdrService service;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-adr-legacy-date");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        AdrRepository repository = KognioRdfAdrRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT);
        service = new AdrService(repository, new UuidResourceIdFactory(),
                (projectId, code) -> {
                    throw new AssertionError("no requirement lookup expected");
                },
                (projectId, code) -> {
                    throw new AssertionError("no bounded context lookup expected");
                },
                (projectId, code) -> {
                    throw new AssertionError("no term lookup expected");
                },
                CLOCK);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void anUpdateLeavingTheLegacyRecordProposedIsRejected() {
        // given
        AdrDetail legacy = legacyProposedWithDate();

        // when
        RuntimeException rejected = assertThrows(RuntimeException.class, () -> service.update(PROJECT,
                legacy.adr().code(), AdrCorrection.builder().name("A renamed decision").build(), "en"));

        // then
        assertTrue(rejected.getMessage().contains("A Proposed decision carries no arkarch:decisionDate"),
                rejected.getMessage());
        assertTrue(rejected.getMessage().contains("accept or reject it"), rejected.getMessage());
        assertEquals("A legacy decision",
                service.get(PROJECT, legacy.adr().code(), null).orElseThrow().adr().name());
    }

    @Test
    void acceptingTheLegacyRecordReplacesItsDate() {
        // given
        AdrDetail legacy = legacyProposedWithDate();

        // when
        service.accept(PROJECT, legacy.adr().code(), null, "en");

        // then
        AdrDetail accepted = service.get(PROJECT, legacy.adr().code(), null).orElseThrow();
        assertEquals(AdrStatus.ACCEPTED, accepted.adr().status());
        assertEquals(LocalDate.of(2026, 11, 24), accepted.adr().decisionDate());
    }

    @Test
    void theLegacyRecordStaysDeletable() {
        // given
        AdrDetail legacy = legacyProposedWithDate();

        // when
        service.delete(PROJECT, legacy.adr().code());

        // then
        assertTrue(service.get(PROJECT, legacy.adr().code(), null).isEmpty());
    }

    /** A record written through the tools, then given a date in the store directly, as before #374. */
    private AdrDetail legacyProposedWithDate() {
        AdrDetail created = service.add(PROJECT, new NewAdr("A legacy decision", "Some context", "Some decision",
                List.of(), List.of(), "en", List.of(), List.of(), List.of(), List.of()), "en");
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <" + CONTENT_GRAPH + "> { <" + created.adr().id().value().value()
                        + "> <" + ArkarchVocabulary.DECISION_DATE
                        + "> \"2026-08-01\"^^<http://www.w3.org/2001/XMLSchema#date> } }");
                return null;
            });
        }
        assertEquals(LocalDate.of(2026, 8, 1),
                service.get(PROJECT, created.adr().code(), null).orElseThrow().adr().decisionDate());
        return created;
    }
}
