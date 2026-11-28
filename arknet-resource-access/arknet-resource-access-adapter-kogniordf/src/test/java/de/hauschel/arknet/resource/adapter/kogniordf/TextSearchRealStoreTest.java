// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.resource.application.ReadService;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.TextHit;
import de.hauschel.arknet.resource.application.port.in.TextSearchResult;

/**
 * The text search against the shipped shapes and a real store: a match in the child of a resource
 * is reported under the resource that owns it, found from the rule data and not from a type named in
 * code.
 */
class TextSearchRealStoreTest {

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private SubmitService submit;
    private ReadService reading;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        submit = new SubmitService(KognioRdfResourceRepositoryFactory.schema(),
                KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle),
                Clock.systemDefaultZone());
        reading = KognioRdfResourceRepositoryFactory.reading(lifecycle, DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void aMatchInAnAcceptanceCriterionIsReportedUnderTheRequirementWithTheFieldItWasReachedThrough() {
        // given
        submit.submit(SubmitRealStoreTest.PROJECT, SubmitRealStoreTest.PREFIXES + """
                [] a arkres:Create ; arkres:object _:fr .
                _:fr a arkreq:FunctionalRequirement ; dcterms:title "Login"@en ; dcterms:description "Logs in."@en ;
                    arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText "Needs a zzzvalidcredential."@en ] .
                """);

        // when
        TextSearchResult result = reading.find(SubmitRealStoreTest.PROJECT, "ZZZVALIDCREDENTIAL", null);

        // then
        assertEquals(List.of(new TextHit("FR-1", "arkreq:criterionText", Optional.of("arkreq:acceptanceCriterion"),
                Optional.of("en"), "Needs a zzzvalidcredential.")), result.hits());
    }

    @Test
    void aMatchInAStepOfAUseCaseIsReportedUnderTheUseCaseAndATypeKeepsOnlyItsOwnResources() {
        // given
        submit.submit(SubmitRealStoreTest.PROJECT, SubmitRealStoreTest.PREFIXES + """
                [] a arkres:Create ; arkres:object _:fr .
                _:fr a arkreq:FunctionalRequirement ; dcterms:title "Model zzzshared"@en ;
                    dcterms:description "Writes."@en ;
                    arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText "Works."@en ] .
                [] a arkres:Create ; arkres:object _:actor .
                _:actor a arkproc:HumanActor ; arknet:name "Architect"@en .
                [] a arkres:Create ; arkres:object _:role .
                _:role a arkproc:Role ; arknet:name "Modeller"@en ; arkproc:filledBy _:actor .
                [] a arkres:Create ; arkres:object _:uc .
                _:uc a arkreq:UseCase ; dcterms:title "Model"@en ; arkreq:useCaseGoal "Modelled."@en ;
                    arkreq:primaryRole _:role ; oslc_rm:satisfies _:fr ;
                    arkreq:mainStep [ arkreq:position 1 ; arkreq:stepText "Writes zzzshared."@en ;
                        arkreq:stepRealises _:fr ] .
                """);

        // when
        TextSearchResult all = reading.find(SubmitRealStoreTest.PROJECT, "zzzshared", null);
        TextSearchResult useCases = reading.find(SubmitRealStoreTest.PROJECT, "zzzshared", "UseCase");

        // then
        assertEquals(List.of("FR-1", "UC1"), all.hits().stream().map(TextHit::handle).sorted().toList());
        assertEquals(List.of("UC1"), useCases.hits().stream().map(TextHit::handle).toList());
        assertEquals(1, useCases.total());
        assertTrue(useCases.hits().get(0).viaField().isPresent(), useCases.hits().toString());
    }

    @Test
    void aStoreWithoutAnyMatchAnswersWithAnEmptyResult() {
        TextSearchResult result = reading.find(SubmitRealStoreTest.PROJECT, "nothing-matches-this-zzz", null);

        assertEquals(0, result.total());
    }
}
