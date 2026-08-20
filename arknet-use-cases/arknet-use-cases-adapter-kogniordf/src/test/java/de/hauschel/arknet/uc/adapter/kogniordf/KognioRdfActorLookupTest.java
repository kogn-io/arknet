// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.uc.application.port.out.ActorLookup;

/**
 * Integration test for {@link KognioRdfActorLookup} against an in-memory RDF4J-backed
 * kognio-rdf store.
 *
 * <p>This carries the strict, name-based cross-BC resolution behaviour that used to be pinned
 * inside {@code KognioRdfUseCaseRepositoryTest} - extracted here because the resolution moved out
 * of the use-case repository's write path into this dedicated port/adapter. Since issue #336 this
 * resolves against {@code arknet-actor}'s own register graph rather than the (now removed)
 * ubiquitous-language actor facet.</p>
 */
class KognioRdfActorLookupTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final String ACTOR_GRAPH = "https://w3id.org/arknet/model/actors";

    /**
     * The store's on-disk home, managed by JUnit rather than {@code Files.createTempDirectory},
     * which left its directories behind - harmless while the store is {@code IN_MEMORY}, but
     * still an inode left in {@code /tmp} for every test run. Deleted after {@link #tearDown()}
     * has shut the store down.
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private ActorLookup actorLookup;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        actorLookup = new KognioRdfActorLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void resolvesAKnownHumanActorNameToItsSubjectIdentity() {
        String actorIri = givenActor(PROJECT_A, "customer", "HumanActor", "Customer");

        ResourceId resolved = actorLookup.resolveByName(PROJECT_A, "Customer");

        assertEquals(ResourceId.of(actorIri), resolved);
    }

    @Test
    void resolvesAKnownSystemActorNameToItsSubjectIdentity() {
        String actorIri = givenActor(PROJECT_A, "payment-provider", "SystemActor", "PaymentProvider");

        ResourceId resolved = actorLookup.resolveByName(PROJECT_A, "PaymentProvider");

        assertEquals(ResourceId.of(actorIri), resolved);
    }

    @Test
    void resolvesAKnownLegalActorNameToItsSubjectIdentity() {
        String actorIri = givenActor(PROJECT_A, "kunde-gmbh", "LegalActor", "Kunde GmbH");

        ResourceId resolved = actorLookup.resolveByName(PROJECT_A, "Kunde GmbH");

        assertEquals(ResourceId.of(actorIri), resolved);
    }

    @Test
    void resolvesAKnownGroupActorNameToItsSubjectIdentity() {
        String actorIri = givenActor(PROJECT_A, "compliance-team", "GroupActor", "Compliance Team");

        ResourceId resolved = actorLookup.resolveByName(PROJECT_A, "Compliance Team");

        assertEquals(ResourceId.of(actorIri), resolved);
    }

    /**
     * The type-union constraint (human, system, legal or group actor) must actually matter: a
     * resource sharing the same {@code arknet:name} but carrying no actor type must not satisfy
     * the reference.
     */
    @Test
    void aNonActorResourceWithTheSameNameDoesNotSatisfyTheReference() {
        givenNonActorResource(PROJECT_A, "order", "Order");

        assertThrows(UnresolvedReferenceException.class,
                () -> actorLookup.resolveByName(PROJECT_A, "Order"));
    }

    @Test
    void rejectsAnUnknownActorName() {
        givenActor(PROJECT_A, "customer", "HumanActor", "Customer");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> actorLookup.resolveByName(PROJECT_A, "Unknown"));

        assertTrue(ex.getMessage().contains("Unknown"), ex.getMessage());
        assertTrue(ex.getMessage().contains("actor_add"), ex.getMessage());
    }

    @Test
    void rejectsAnAmbiguousActorName() {
        givenActor(PROJECT_A, "customer-1", "HumanActor", "Customer");
        givenActor(PROJECT_A, "customer-2", "HumanActor", "Customer");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> actorLookup.resolveByName(PROJECT_A, "Customer"));

        assertTrue(ex.getMessage().contains("ambiguous"), ex.getMessage());
    }

    /** An actor of another project must not satisfy this project's reference. */
    @Test
    void anActorOfAnotherProjectDoesNotSatisfyThisProjectsReference() {
        givenActor(PROJECT_B, "customer", "HumanActor", "Customer");

        assertThrows(UnresolvedReferenceException.class,
                () -> actorLookup.resolveByName(PROJECT_A, "Customer"));
    }

    private String givenActor(ProjectId projectId, String slug, String actorTypeLocalName, String name) {
        String actorIri = "https://w3id.org/arknet/model/actor/" + slug;
        String insert = "INSERT DATA { GRAPH <" + ACTOR_GRAPH + "> { "
                + "<" + actorIri + "> a <https://w3id.org/arknet/process#" + actorTypeLocalName + "> ; "
                + "<https://w3id.org/arknet/core#name> \"" + name + "\" } }";
        write(projectId, insert);
        return actorIri;
    }

    private String givenNonActorResource(ProjectId projectId, String slug, String name) {
        String resourceIri = "https://w3id.org/arknet/model/term/" + slug;
        String insert = "INSERT DATA { GRAPH <" + ACTOR_GRAPH + "> { "
                + "<" + resourceIri + "> <https://w3id.org/arknet/core#name> \"" + name + "\" } }";
        write(projectId, insert);
        return resourceIri;
    }

    private void write(ProjectId projectId, String insert) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }
}
