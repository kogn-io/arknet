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
import de.hauschel.arknet.uc.application.port.out.RoleLookup;

/**
 * Integration test for {@link KognioRdfRoleLookup} against an in-memory RDF4J-backed
 * kognio-rdf store.
 *
 * <p>This carries the strict, code-based cross-BC resolution behaviour that used to be pinned in
 * {@code KognioRdfActorLookupTest} before ADR-37/kogn-io/arknet#405 Part C repointed
 * {@code arkreq:primaryRole}/{@code supportingRole} from {@code arkproc:Actor} to
 * {@code arkproc:Role} - a second, independent resource type of the same {@code arknet-actor}
 * hexagon, living in its own named graph ({@link #ROLE_GRAPH}).</p>
 */
class KognioRdfRoleLookupTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");
    private static final String ROLE_GRAPH = "https://w3id.org/arknet/model/roles";
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
    private RoleLookup roleLookup;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        roleLookup = new KognioRdfRoleLookup(datasetLifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    @Test
    void resolvesAKnownRoleCodeToItsSubjectIdentity() {
        String roleIri = givenRole(PROJECT_A, "requirements-engineer", "ROLE-1", "Requirements Engineer");

        ResourceId resolved = roleLookup.resolveByCode(PROJECT_A, "ROLE-1");

        assertEquals(ResourceId.of(roleIri), resolved);
    }

    @Test
    void rejectsAnUnknownRoleCode() {
        givenRole(PROJECT_A, "requirements-engineer", "ROLE-1", "Requirements Engineer");

        UnresolvedReferenceException ex = assertThrows(UnresolvedReferenceException.class,
                () -> roleLookup.resolveByCode(PROJECT_A, "ROLE-99"));

        assertTrue(ex.getMessage().contains("ROLE-99"), ex.getMessage());
        assertTrue(ex.getMessage().contains("role_add"), ex.getMessage());
    }

    /**
     * Belegs the plan's Beleg 2(a): an {@code ACTOR-N} code is rejected. The two resource types
     * live in distinct named graphs ({@link #ROLE_GRAPH} vs. {@link #ACTOR_GRAPH}), so an actor's
     * own business code - even a perfectly well-formed one - never satisfies a role reference:
     * this adapter's query never looks outside {@link #ROLE_GRAPH}.
     */
    @Test
    void rejectsAnActorCode() {
        givenActor(PROJECT_A, "requirements-engineer", "ACTOR-1", "Requirements Engineer");

        assertThrows(UnresolvedReferenceException.class, () -> roleLookup.resolveByCode(PROJECT_A, "ACTOR-1"));
    }

    /**
     * Belegs the plan's Beleg 2(c): an IRI that sits in {@link #ROLE_GRAPH} and carries the right
     * {@code dcterms:identifier} but is <strong>not</strong> typed {@code arkproc:Role} must not
     * resolve - the type check is this adapter's job (see {@link KognioRdfRoleLookup}'s own
     * javadoc on where the reference's type check actually lives), not merely a graph-membership
     * check. Mutation performed to confirm this test actually exercises the type join: removing
     * the {@code ?role a <ROLE_TYPE>} triple pattern from {@link KognioRdfRoleLookup#resolveByCode}'s
     * query makes this test fail with
     * {@code org.opentest4j.AssertionFailedError: Expected
     * de.hauschel.arknet.persistence.UnresolvedReferenceException to be thrown, but nothing was
     * thrown.} (the call succeeds and returns the untyped subject's identity instead of throwing);
     * the type triple was then restored.
     */
    @Test
    void aRoleGraphSubjectWithTheCodeButWithoutTheRoleTypeDoesNotSatisfyTheReference() {
        String untypedIri = "https://w3id.org/arknet/model/role/untyped";
        String insert = "INSERT DATA { GRAPH <" + ROLE_GRAPH + "> { "
                + "<" + untypedIri + "> <http://purl.org/dc/terms/identifier> \"ROLE-1\" } }";
        write(PROJECT_A, insert);

        assertThrows(UnresolvedReferenceException.class, () -> roleLookup.resolveByCode(PROJECT_A, "ROLE-1"));
    }

    /** A role of another project must not satisfy this project's reference. */
    @Test
    void aRoleOfAnotherProjectDoesNotSatisfyThisProjectsReference() {
        givenRole(PROJECT_B, "requirements-engineer", "ROLE-1", "Requirements Engineer");

        assertThrows(UnresolvedReferenceException.class, () -> roleLookup.resolveByCode(PROJECT_A, "ROLE-1"));
    }

    private String givenRole(ProjectId projectId, String slug, String code, String name) {
        String roleIri = "https://w3id.org/arknet/model/role/" + slug;
        String insert = "INSERT DATA { GRAPH <" + ROLE_GRAPH + "> { "
                + "<" + roleIri + "> a <https://w3id.org/arknet/process#Role> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<https://w3id.org/arknet/core#name> \"" + name + "\"@en } }";
        write(projectId, insert);
        return roleIri;
    }

    private String givenActor(ProjectId projectId, String slug, String code, String name) {
        String actorIri = "https://w3id.org/arknet/model/actor/" + slug;
        String insert = "INSERT DATA { GRAPH <" + ACTOR_GRAPH + "> { "
                + "<" + actorIri + "> a <https://w3id.org/arknet/process#HumanActor> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<https://w3id.org/arknet/core#name> \"" + name + "\" } }";
        write(projectId, insert);
        return actorIri;
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
