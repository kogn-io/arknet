// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static de.hauschel.arknet.resource.adapter.kogniordf.SubmitRealStoreTest.PREFIXES;
import static de.hauschel.arknet.resource.adapter.kogniordf.SubmitRealStoreTest.PROJECT;
import static de.hauschel.arknet.resource.adapter.kogniordf.SubmitRealStoreTest.iriOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The two document checks of the engine against the shipped shapes and ontologies and a real store:
 * a document writes only predicates a shape of the node's class names, and a delete leaves no edge
 * pointing into nothing.
 */
class SubmitDocumentChecksRealStoreTest {

    private static final String REQ = "https://w3id.org/arknet/requirements#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String OSLC_RM = "http://open-services.net/ns/rm#";

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private SubmitService submit;
    private StoreReader reader;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        submit = new SubmitService(KognioRdfResourceRepositoryFactory.schema(),
                KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle));
        reader = new StoreReader(lifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void aCreateWritingAFieldOfAnotherTypeIsRejected() {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Create ; arkres:object _:t .\n"
                        + "_:t a skos:Concept ; skos:prefLabel \"Order\"@en ; skos:definition \"A request.\"@en ;"
                        + " arkreq:criterionText \"Not a field of a term.\"@en .\n"));

        // then
        assertTrue(rejected.getMessage().contains(REQ + "criterionText"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(SKOS + "Concept"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("@prefix arkreq: <" + REQ + "> ."), rejected.getMessage());
        assertTrue(reader.findByIdentifier(PROJECT, "TERM-1").isEmpty());
    }

    @Test
    void anInlineChildWritingAFieldOfItsParentIsRejected() {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Create ; arkres:object _:fr .\n"
                        + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ;"
                        + " dcterms:description \"Writes.\"@en ;"
                        + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Works.\"@en ;"
                        + " dcterms:title \"A title belongs to the requirement.\"@en ] .\n"));

        // then
        assertTrue(rejected.getMessage().contains("http://purl.org/dc/terms/title"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(REQ + "acceptanceCriterion"), rejected.getMessage());
        assertTrue(reader.findByIdentifier(PROJECT, "FR-1").isEmpty());
    }

    @Test
    void aSetOfAFieldOfAnotherTypeIsRejected() {
        // given
        String fr = storedRequirementUsingATerm()[0];

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Update ; arkres:object <" + fr + "> ;"
                        + " arkres:set [ skos:definition \"A definition belongs to a term.\"@en ] .\n"));

        // then
        assertTrue(rejected.getMessage().contains(SKOS + "definition"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(fr), rejected.getMessage());
    }

    @Test
    void anAddToOfAFieldOfAnotherTypeIsRejected() {
        // given
        String[] stored = storedRequirementUsingATerm();

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:AddTo ; arkres:object <" + stored[0] + "> ; arkres:field skos:broader ;"
                        + " arkres:value <" + stored[1] + "> .\n"));

        // then
        assertTrue(rejected.getMessage().contains(SKOS + "broader"), rejected.getMessage());
    }

    @Test
    void deletingATermARequirementStillUsesIsRejected() {
        // given
        String[] stored = storedRequirementUsingATerm();

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Delete ; arkres:object <" + stored[1] + "> .\n"));

        // then
        assertTrue(rejected.getMessage().contains(stored[1] + " is not deleted"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(stored[0]), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(REQ + "usesTerm"), rejected.getMessage());
        assertFalse(reader.findByIdentifier(PROJECT, "TERM-1").isEmpty());
    }

    @Test
    void deletingTheTermTogetherWithTheRequirementUsingItIsAccepted() {
        // given
        String[] stored = storedRequirementUsingATerm();

        // when
        assertDoesNotThrow(() -> submit.submit(PROJECT, PREFIXES
                + "[] a arkres:Delete ; arkres:object <" + stored[1] + "> .\n"
                + "[] a arkres:Delete ; arkres:object <" + stored[0] + "> .\n"));

        // then
        assertTrue(reader.findByIdentifier(PROJECT, "TERM-1").isEmpty());
        assertTrue(reader.findByIdentifier(PROJECT, "FR-1").isEmpty());
    }

    @Test
    void deletingTheTermWhileRemovingTheEdgeToItIsAccepted() {
        // given
        String[] stored = storedRequirementUsingATerm();

        // when
        assertDoesNotThrow(() -> submit.submit(PROJECT, PREFIXES
                + "[] a arkres:Delete ; arkres:object <" + stored[1] + "> .\n"
                + "[] a arkres:RemoveFrom ; arkres:object <" + stored[0] + "> ; arkres:field arkreq:usesTerm ;"
                + " arkres:value <" + stored[1] + "> .\n"));

        // then
        assertTrue(reader.findByIdentifier(PROJECT, "TERM-1").isEmpty());
        assertFalse(reader.findByIdentifier(PROJECT, "FR-1").isEmpty());
    }

    @Test
    void deletingARequirementAStepOfAUseCaseRealisesIsRejectedNamingTheStep() {
        // given
        SubmitResult result = submit.submit(PROJECT, PREFIXES + """
                [] a arkres:Create ; arkres:object _:fr .
                _:fr a arkreq:FunctionalRequirement ; dcterms:title "Write"@en ; dcterms:description "Writes."@en ;
                    arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText "Works."@en ] .
                [] a arkres:Create ; arkres:object _:actor .
                _:actor a arkproc:HumanActor ; arknet:name "Architect"@en .
                [] a arkres:Create ; arkres:object _:role .
                _:role a arkproc:Role ; arknet:name "Modeller"@en ; arkproc:filledBy _:actor .
                [] a arkres:Create ; arkres:object _:uc .
                _:uc a arkreq:UseCase ; dcterms:title "Model"@en ; arkreq:useCaseGoal "Modelled."@en ;
                    arkreq:primaryRole _:role ; oslc_rm:satisfies _:fr ;
                    arkreq:mainStep [ arkreq:position 1 ; arkreq:stepText "Writes."@en ; arkreq:stepRealises _:fr ] .
                """);
        String fr = iriOf(result, "FR-1");
        String uc = iriOf(result, "UC1");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Delete ; arkres:object <" + fr + "> .\n"));

        // then
        assertTrue(rejected.getMessage().contains(uc + " still points at it over " + OSLC_RM + "satisfies"),
                rejected.getMessage());
        assertTrue(rejected.getMessage().contains(uc + "#Step-"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(REQ + "stepRealises"), rejected.getMessage());
    }

    /** A stored requirement using a stored term: the requirement's IRI first, the term's second. */
    private String[] storedRequirementUsingATerm() {
        SubmitResult result = submit.submit(PROJECT, PREFIXES + """
                [] a arkres:Create ; arkres:object _:t .
                _:t a skos:Concept ; skos:prefLabel "Order"@en ; skos:definition "A request."@en .
                [] a arkres:Create ; arkres:object _:fr .
                _:fr a arkreq:FunctionalRequirement ; dcterms:title "Write"@en ; dcterms:description "Writes."@en ;
                    arkreq:usesTerm _:t ;
                    arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText "Works."@en ] .
                """);
        return new String[] {iriOf(result, "FR-1"), iriOf(result, "TERM-1")};
    }
}
