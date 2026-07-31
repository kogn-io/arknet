// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.adr.application.port.out.BoundedContextLookup;
import de.hauschel.arknet.bc.adapter.kogniordf.KognioRdfBoundedContextRepositoryFactory;
import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;

/**
 * Schema-drift regression guard, the bounded-context counterpart of
 * {@link KognioRdfRequirementLookupRequirementsSchemaTest}: {@link KognioRdfBoundedContextLookup}
 * hardcodes the exact storage schema (graph IRI, {@code arkddd:BoundedContext} type,
 * {@code dcterms:identifier} predicate) that {@code arknet-bounded-context-adapter-kogniordf}'s
 * {@code KognioRdfBoundedContextRepository} writes, with no compile-time coupling between the two
 * adapter modules.
 *
 * <p>It writes a bounded context through the bounded-context BC's <em>actual</em> out-adapter and
 * resolves it through the ADR BC's actual lookup against the very same store - so a future schema
 * drift fails here rather than as a silent {@link UnresolvedReferenceException} at runtime for a
 * context that demonstrably exists.</p>
 */
class KognioRdfBoundedContextLookupBoundedContextSchemaTest {

    private static final ProjectId PROJECT = new ProjectId("adr-bc-schema");

    private DatasetLifecycleRdf4j lifecycle;
    private BoundedContextRepository boundedContextRepository;
    private BoundedContextLookup adrBoundedContextLookup;

    @BeforeEach
    void setUp() throws IOException {
        Path tmp = Files.createTempDirectory("arknet-adr-bc-schema-it");
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), tmp);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        boundedContextRepository = KognioRdfBoundedContextRepositoryFactory.over(
                datasetLifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        adrBoundedContextLookup = new KognioRdfBoundedContextLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void adrResolvesABoundedContextWrittenByTheRealBoundedContextOutAdapter() {
        BoundedContextId id =
                new BoundedContextId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
        BoundedContext boundedContext = new BoundedContext(id, new BoundedContextCode("BC-1"),
                "OrderManagement", "Owns the lifecycle of a customer order from placement to fulfilment.",
                null, null, List.of());

        boundedContextRepository.create(PROJECT, boundedContext);

        assertEquals(id.value(), adrBoundedContextLookup.resolveByCode(PROJECT, "BC-1"));
    }

    @Test
    void anUnknownBoundedContextCodeIsRejectedDidactically() {
        assertThrows(UnresolvedReferenceException.class,
                () -> adrBoundedContextLookup.resolveByCode(PROJECT, "BC-99"));
    }
}
