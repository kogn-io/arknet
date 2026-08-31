// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

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
import de.hauschel.arknet.adr.domain.NewConsideredOption;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;

/**
 * Reproduction for kogn-io/arknet#376, through the actual {@code adr_update} write shape rather
 * than the repository directly: {@link AdrService#update}/{@link AdrService#accept} each rebuild
 * the decision via {@code Adr#reviseText}/{@code #withAppendedConsideredOptions}/
 * {@code #withConsideredOptionCorrections}/{@code #reviseReferences} before handing the result to
 * {@link AdrRepository#compareAndUpdate}, wired here against the real, SHACL-gated
 * {@link KognioRdfAdrRepository}.
 *
 * <p><strong>Root cause: not this write path at all.</strong> Every one of these tests writes a
 * domain-correct {@link de.hauschel.arknet.adr.domain.Adr} carrying at most one {@code CHOSEN}
 * considered option; {@code AdrService} never touches {@code consideredOptions} for a correction
 * that does not name one. The actual bug is a genuine RDF4J 6.x ShaclSail misfire on the
 * pre-fix {@code sh:qualifiedValueShape}/{@code sh:qualifiedMaxCount 1} form of {@code
 * ashapes:ADR-consideredOption-atMostOneChosen} whenever the merged SHACL validation data carries a
 * <em>second</em> {@code arkarch:ArchitectureDecisionRecord} focus node beside the one being
 * written - exactly what a {@code relatedTo} peer's validation-only {@code assertedContext} copy
 * is (see {@link KognioRdfAdrRepositoryTest
 * #gateConformsWithExactlyOneChosenAmongSeveralWhenARelatedToPeerIsAlsoAFocusNode} for the direct-gate
 * pin). {@code @RepeatedTest} rather than a single run on the two tests below that involve a peer:
 * the flake only fired roughly two thirds of the time pre-fix, so a single run could pass by luck.
 */
class AdrServiceConsideredOptionRealStoreTest {

    private static final ProjectId PROJECT = new ProjectId("a");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-08-26T00:00:00Z"), ZoneOffset.UTC);

    private DatasetLifecycleRdf4j lifecycle;
    private AdrService service;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-adr-service-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        AdrRepository repository = KognioRdfAdrRepositoryFactory.over(
                datasetLifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        RequirementLookup neverCalledRequirementLookup = (projectId, code) -> {
            throw new AssertionError("not expected to resolve a requirement code in this test");
        };
        BoundedContextLookup neverCalledContextLookup = (projectId, code) -> {
            throw new AssertionError("not expected to resolve a bounded context code in this test");
        };
        TermLookup neverCalledTermLookup = (projectId, code) -> {
            throw new AssertionError("not expected to resolve a term code in this test");
        };
        service = new AdrService(repository, new UuidResourceIdFactory(), neverCalledRequirementLookup,
                neverCalledContextLookup, neverCalledTermLookup, CLOCK);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    /**
     * kogn-io/arknet#376's own reproduction: a decision recorded with two considered options - one
     * {@code REJECTED}, one {@code CHOSEN} - via {@code adr_add}, then corrected via
     * {@code adr_update} without touching the options at all (only {@code relatedTo}).
     */
    @RepeatedTest(10)
    void updateOfARecordCarryingSeveralOptionsWithOneChosenSucceedsWhenOnlyRelatedToIsTouched() {
        AdrDetail peer = service.add(PROJECT,
                new NewAdr("A peer decision", "Some context", "Some decision", List.of(), List.of(), "en",
                        List.of(), List.of(), List.of(), List.of()),
                "en");
        AdrDetail created = service.add(PROJECT,
                new NewAdr("A decision with several options", "Some context", "Some decision", List.of(),
                        List.of(
                                new NewConsideredOption("Rejected one", "Only here to count.",
                                        OptionOutcome.REJECTED),
                                new NewConsideredOption("Chosen one", "Only here to count.", OptionOutcome.CHOSEN)),
                        "en", List.of(), List.of(), List.of(), List.of()),
                "en");

        AdrCorrection correction = AdrCorrection.builder()
                .relatedToCodes(List.of(peer.adr().code().value()))
                .build();

        assertDoesNotThrow(() -> service.update(PROJECT, created.adr().code(), correction, "en"));

        AdrDetail updated = service.get(PROJECT, created.adr().code(), null).orElseThrow();
        assertEquals(2, updated.adr().consideredOptions().size());
        assertEquals(List.of(peer.adr().code()), updated.relatedTo());
    }

    /** Same reproduction, but the decision is ACCEPTED (with a stamped decisionDate) before the update. */
    @RepeatedTest(10)
    void updateOfAnAcceptedRecordCarryingSeveralOptionsWithOneChosenSucceeds() {
        AdrDetail peer = service.add(PROJECT,
                new NewAdr("A peer decision", "Some context", "Some decision", List.of(), List.of(), "en",
                        List.of(), List.of(), List.of(), List.of()),
                "en");
        AdrDetail created = service.add(PROJECT,
                new NewAdr("A decision with several options", "Some context", "Some decision", List.of(),
                        List.of(
                                new NewConsideredOption("Rejected one", "Only here to count.",
                                        OptionOutcome.REJECTED),
                                new NewConsideredOption("Chosen one", "Only here to count.", OptionOutcome.CHOSEN)),
                        "en", List.of(), List.of(), List.of(), List.of()),
                "en");
        service.accept(PROJECT, created.adr().code(), null);

        AdrCorrection correction = AdrCorrection.builder()
                .relatedToCodes(List.of(peer.adr().code().value()))
                .build();

        assertDoesNotThrow(() -> service.update(PROJECT, created.adr().code(), correction, "en"));
    }

    /** Baseline sanity check: a single CHOSEN option (the table's "laeuft" row) through the same path. */
    @Test
    void updateOfARecordCarryingASingleChosenOptionSucceeds() {
        AdrDetail peer = service.add(PROJECT,
                new NewAdr("A peer decision", "Some context", "Some decision", List.of(), List.of(), "en",
                        List.of(), List.of(), List.of(), List.of()),
                "en");
        AdrDetail created = service.add(PROJECT,
                new NewAdr("A decision with a single option", "Some context", "Some decision", List.of(),
                        List.of(new NewConsideredOption("Chosen one", "Only here to count.", OptionOutcome.CHOSEN)),
                        "en", List.of(), List.of(), List.of(), List.of()),
                "en");

        AdrCorrection correction = AdrCorrection.builder()
                .relatedToCodes(List.of(peer.adr().code().value()))
                .build();

        assertDoesNotThrow(() -> service.update(PROJECT, created.adr().code(), correction, "en"));
    }

    /**
     * Same 2-option decision, but {@code accept()} alone - no peer, no {@code relatedTo}, so
     * {@link KognioRdfAdrRepository#crossReferenceAssertedContext} contributes nothing and the
     * merged SHACL validation data graph carries only ONE {@code arkarch:ArchitectureDecisionRecord}
     * focus node. Confirms the second focus node (the peer) is what was load-bearing for the flake.
     */
    @Test
    void acceptOfARecordCarryingSeveralOptionsWithOneChosenSucceedsWithNoPeerInvolved() {
        AdrDetail created = service.add(PROJECT,
                new NewAdr("A decision with several options", "Some context", "Some decision", List.of(),
                        List.of(
                                new NewConsideredOption("Rejected one", "Only here to count.",
                                        OptionOutcome.REJECTED),
                                new NewConsideredOption("Chosen one", "Only here to count.", OptionOutcome.CHOSEN)),
                        "en", List.of(), List.of(), List.of(), List.of()),
                "en");

        assertDoesNotThrow(() -> service.accept(PROJECT, created.adr().code(), null));
    }
}
