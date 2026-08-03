// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.out.RevisionToken;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;

/**
 * Integration tests for the multilingual {@code dcterms:title}/{@code arkreq:useCaseGoal}/
 * {@code arkreq:stepText} behaviour of {@link KognioRdfUseCaseRepository}: language-scoped writes
 * on {@link UseCaseRepository#create}/{@link UseCaseRepository#compareAndUpdate}, and
 * {@link DisplayLocale}-selected reads on {@link UseCaseRepository#findByCode}. Mirrors
 * {@code KognioRdfRequirementRepositoryMultilingualTest}.
 *
 * <p>A step's own subject IRI is re-minted on every {@link UseCaseRepository#compareAndUpdate}
 * write (see {@link KognioRdfUseCaseRepository}'s class-level "opaque value object" note) - the
 * regression this class exists to pin is that an other-language {@code stepText} variant still
 * survives that re-minting, re-attached by <em>position</em> to the freshly-minted step.</p>
 */
class KognioRdfUseCaseRepositoryMultilingualTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ActorRef CUSTOMER = new ActorRef(ResourceId.of("https://w3id.org/arknet/model/term/customer"));

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private UseCaseRepository repository;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        repository = KognioRdfUseCaseRepositoryFactory.over(
                lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        seedCustomerActor();
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private void seedCustomerActor() {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                        + "<https://w3id.org/arknet/model/term/customer> "
                        + "a <http://www.w3.org/2004/02/skos/core#Concept> , "
                        + "<https://w3id.org/arknet/process#HumanActor> ; "
                        + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Customer\" . } }");
                return null;
            });
        }
    }

    private static UseCaseId freshId() {
        return new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static UseCase useCase(UseCaseId id, UseCaseCode code, String title, String goal, String stepText) {
        return new UseCase(id, code, title, goal, null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, stepText, List.of())), List.of());
    }

    private RevisionToken currentHead(UseCaseCode code) {
        return repository.findCurrentByCode(PROJECT_A, code)
                .map(UseCaseRepository.CurrentUseCase::head)
                .orElse(null);
    }

    @Test
    void createWritesTaggedTitleGoalAndStepTextSelectableViaDisplayLocale() {
        UseCaseCode code = new UseCaseCode("UC1");
        repository.create(PROJECT_A, useCase(freshId(), code, "Bestellung aufgeben", "Bestellung abschliessen",
                "Kunde waehlt Artikel"), "de");

        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Bestellung aufgeben", asGerman.title());
        assertEquals("Bestellung abschliessen", asGerman.goal());
        assertEquals("Kunde waehlt Artikel", asGerman.steps().get(0).text());
    }

    /**
     * The regression this issue exists to fix: a {@code compareAndUpdate} that corrects only
     * {@code title} under a new language must leave the previously-written language variant of
     * {@code title} completely intact - not delete it, not silently retag it. {@code goal} and the
     * step's text (untouched by this call) must survive under their original tag too.
     */
    @Test
    void compareAndUpdateWithANewLanguageForTitlePreservesEveryOtherFieldsLanguageVariant() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCase created = useCase(freshId(), code, "Place order", "Order is placed", "Customer selects items");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        UseCase withGermanTitle = useCase(created.id(), code, "Bestellung aufgeben", "Order is placed",
                "Customer selects items");
        repository.compareAndUpdate(PROJECT_A, head, withGermanTitle, "de", "en",
                Map.of(1, "en"));

        UseCase asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Place order", asEnglish.title());
        assertEquals("Bestellung aufgeben", asGerman.title());
        assertEquals("Order is placed", asEnglish.goal());
        assertEquals("Order is placed", asGerman.goal());
        assertEquals("Customer selects items", asEnglish.steps().get(0).text());
        assertEquals("Customer selects items", asGerman.steps().get(0).text());
    }

    /**
     * The step-specific regression: a step's opaque subject is re-minted on every write, so its
     * other-language text variant must be re-attached to the freshly-minted subject at the same
     * position, not lost along with the deleted old step subject.
     */
    @Test
    void compareAndUpdateWithANewLanguageForStepTextPreservesTheOriginalLanguageVariant() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCase created = useCase(freshId(), code, "Place order", "Order is placed", "Customer selects items");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        UseCase withGermanStepText = useCase(created.id(), code, "Place order", "Order is placed",
                "Kunde waehlt Artikel");
        repository.compareAndUpdate(PROJECT_A, head, withGermanStepText, "en", "en", Map.of(1, "de"));

        UseCase asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Customer selects items", asEnglish.steps().get(0).text());
        assertEquals("Kunde waehlt Artikel", asGerman.steps().get(0).text());
    }
}
