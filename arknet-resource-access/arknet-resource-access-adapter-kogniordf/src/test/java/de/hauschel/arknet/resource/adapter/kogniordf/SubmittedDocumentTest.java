// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.ReadableGraph;

import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;
import de.hauschel.arknet.resource.domain.SubmittedDocument;

/**
 * The checks a document passes before the store is touched, and the rule data the shapes carry.
 */
class SubmittedDocumentTest {

    private static final ResourceSchema SCHEMA = KognioRdfResourceRepositoryFactory.schema();

    @Test
    void theShapesDeclareEveryResourceTypeAndOwnedField() {
        assertEquals(Set.of(
                "https://w3id.org/arknet/requirements#FunctionalRequirement",
                "https://w3id.org/arknet/requirements#NonFunctionalRequirement",
                "https://w3id.org/arknet/requirements#TechnicalConstraint",
                "https://w3id.org/arknet/requirements#BusinessConstraint",
                "https://w3id.org/arknet/requirements#RegulatoryConstraint",
                "https://w3id.org/arknet/requirements#UseCase",
                "http://www.w3.org/2004/02/skos/core#Concept",
                "https://w3id.org/arknet/architecture#ArchitectureDecisionRecord",
                "https://w3id.org/arknet/process#Actor",
                "https://w3id.org/arknet/process#HumanActor",
                "https://w3id.org/arknet/process#SystemActor",
                "https://w3id.org/arknet/process#LegalActor",
                "https://w3id.org/arknet/process#GroupActor",
                "https://w3id.org/arknet/process#Role",
                "https://w3id.org/arknet/ddd#BoundedContext",
                "https://w3id.org/arknet/ddd#ContextRelationship"), SCHEMA.resourceTypes());
    }

    @Test
    void aDocumentWithoutActivityIsRejected() {
        assertRejected("no activity", "<https://w3id.org/arknet/id/x> skos:prefLabel \"A\"@en .");
    }

    @Test
    void aCreateNamesANewBlankNodeOfOneResourceType() {
        assertRejected("blank node", "[] a arkres:Create ; arkres:object <https://w3id.org/arknet/id/x> .");
        assertRejected("one resource type",
                "[] a arkres:Create ; arkres:object _:x .\n_:x a arkreq:Requirement ; dcterms:title \"A\"@en .");
    }

    @Test
    void aDeletedResourceIsNotChangedInTheSameDocument() {
        assertRejected("deleted and changed", """
                [] a arkres:Delete ; arkres:object <https://w3id.org/arknet/id/x> .
                [] a arkres:Update ; arkres:object <https://w3id.org/arknet/id/x> ; arkres:clear skos:definition .
                """);
    }

    @Test
    void anOwnedChildIsRemovedByPositionAndAReferenceByValue() {
        assertRejected("resource#position", "[] a arkres:RemoveFrom ; arkres:object <https://w3id.org/arknet/id/x> ;"
                + " arkres:field arkreq:acceptanceCriterion ; arkres:value <https://w3id.org/arknet/id/y> .");
        assertRejected("resource#value", "[] a arkres:RemoveFrom ; arkres:object <https://w3id.org/arknet/id/x> ;"
                + " arkres:field arkreq:usesTerm ; arkres:position 1 .");
    }

    @Test
    void anUnparseableDocumentIsRejected() {
        SubmitService service = new SubmitService(SCHEMA, (projectId, writes) -> {
            throw new AssertionError("an unparseable document reaches the store");
        }, KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory());

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> service.submit(SubmitRealStoreTest.PROJECT, "[] a undeclared:Create ."));

        assertTrue(rejected.getMessage().contains("not valid Turtle"), rejected.getMessage());
    }

    private static void assertRejected(final String reason, final String body) {
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> SubmittedDocument.of(parse(SubmitRealStoreTest.PREFIXES + body), SCHEMA));
        assertTrue(rejected.getMessage().contains(reason), rejected.getMessage());
        assertFalse(rejected.getMessage().contains("belongs to no activity") && !reason.equals("no activity"),
                rejected.getMessage());
    }

    private static ReadableGraph parse(final String document) {
        return KognioRdfResourceRepositoryFactory.parser()
                .readTurtle(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
    }
}
