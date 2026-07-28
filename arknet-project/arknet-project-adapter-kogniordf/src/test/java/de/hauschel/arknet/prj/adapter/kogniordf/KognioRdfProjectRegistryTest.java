// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.persistence.ArkprjVocabulary;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.prj.application.port.out.ProjectRegistry;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorAlreadyRegisteredException;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.DuplicateProjectLabelException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;
import de.hauschel.arknet.prj.domain.StaleProjectException;

/**
 * Integration test for {@link KognioRdfProjectRegistry} against an in-memory RDF4J-backed
 * kognio-rdf store.
 */
class KognioRdfProjectRegistryTest {

    /**
     * Where the store would persist to. It does not - this test runs {@code IN_MEMORY} - but the
     * lifecycle takes a storage root regardless, and letting JUnit own it keeps the directory from
     * outliving the test the way {@code Files.createTempDirectory} let it (issue #180).
     */
    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private KognioRdfProjectRegistry registry;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        ShaclWriteGate gate = KognioRdfProjectRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        WriteFunnel funnel = new WriteFunnel(datasetLifecycle, gate, WriteFunnel.DEFAULT_WRITE_CONFLICT);
        registry = new KognioRdfProjectRegistry(datasetLifecycle, funnel);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    /** Fresh, valid opaque identity - every test picks its own so ids never collide. */
    private static ProjectId freshId() {
        return new ProjectId(UUID.randomUUID().toString());
    }

    private static Anchor pathAnchor(String value) {
        return new Anchor(value, AnchorType.PATH);
    }

    @Test
    void registersAndFindsAProjectByIdAndByAnchor() {
        Anchor anchor = pathAnchor("/home/dev/arknet");
        Project project = new Project(freshId(), "arknet", List.of(anchor));

        registry.register(project);

        assertEquals(Optional.of(project), registry.findById(project.id()));
        assertEquals(Optional.of(project), registry.findByAnchor(anchor));
        assertEquals(List.of(project), registry.findAll());
    }

    @Test
    void anAnchorRoundTripsWithItsFullType() {
        Anchor anchor = new Anchor("https://example.org/repo", AnchorType.URL);
        Project project = new Project(freshId(), "remote-project", List.of(anchor));

        registry.register(project);

        Project found = registry.findById(project.id()).orElseThrow();
        assertEquals(1, found.anchors().size());
        assertEquals(AnchorType.URL, found.anchors().get(0).type());
        assertEquals("https://example.org/repo", found.anchors().get(0).value());
    }

    @Test
    void bothAnchorsOfAProjectWithTwoAnchorsResolveToTheSameProject() {
        Anchor mainCheckout = pathAnchor("/home/dev/arknet");
        Anchor worktree = pathAnchor("/home/dev/arknet-worktree");
        Project project = new Project(freshId(), "arknet", List.of(mainCheckout, worktree));

        registry.register(project);

        assertEquals(Optional.of(project), registry.findByAnchor(mainCheckout));
        assertEquals(Optional.of(project), registry.findByAnchor(worktree));
    }

    @Test
    void findByAnchorIsEmptyForAnUnknownAnchor() {
        assertTrue(registry.findByAnchor(pathAnchor("/nowhere")).isEmpty());
    }

    @Test
    void findByIdIsEmptyForAnUnknownIdentity() {
        assertTrue(registry.findById(freshId()).isEmpty());
    }

    @Test
    void registerRejectsAnAnchorAlreadyOwnedByAnotherProjectAndNamesTheOwner() {
        Anchor sharedAnchor = pathAnchor("/home/dev/arknet");
        Project first = new Project(freshId(), "arknet", List.of(sharedAnchor));
        registry.register(first);

        Project second = new Project(freshId(), "arknet-copy", List.of(sharedAnchor));

        AnchorAlreadyRegisteredException thrown = assertThrows(AnchorAlreadyRegisteredException.class,
                () -> registry.register(second));
        assertEquals(first.id(), thrown.owner());
        assertEquals(sharedAnchor, thrown.anchor());

        // The rejected write must not have touched the store: the second project must not exist,
        // and the anchor must still resolve to the first project only.
        assertTrue(registry.findById(second.id()).isEmpty());
        assertEquals(Optional.of(first), registry.findByAnchor(sharedAnchor));
    }

    @Test
    void compareAndUpdateRejectsAttachingAnAnchorAlreadyOwnedByAnotherProject() {
        Anchor foreignAnchor = pathAnchor("/home/dev/other-project");
        Project owner = new Project(freshId(), "other-project", List.of(foreignAnchor));
        registry.register(owner);

        Anchor ownAnchor = pathAnchor("/home/dev/mine");
        Project mine = new Project(freshId(), "mine", List.of(ownAnchor));
        registry.register(mine);
        String head = registry.findCurrentById(mine.id()).orElseThrow().head();

        Project attemptsToStealAnchor = new Project(mine.id(), "mine", List.of(ownAnchor, foreignAnchor));

        AnchorAlreadyRegisteredException thrown = assertThrows(AnchorAlreadyRegisteredException.class,
                () -> registry.compareAndUpdate(head, attemptsToStealAnchor));
        assertEquals(owner.id(), thrown.owner());

        // The rejected write must not have touched "mine": it must still carry only its own
        // anchor, and the foreign anchor must still resolve to its true owner.
        assertEquals(List.of(ownAnchor), registry.findById(mine.id()).orElseThrow().anchors());
        assertEquals(Optional.of(owner), registry.findByAnchor(foreignAnchor));
    }

    /**
     * Anchor identity is the value alone, not the (value, type) pair (see {@link Anchor}'s
     * javadoc and {@link ProjectGraphs}'s class javadoc): a second project registering the same
     * value under a different type must be rejected exactly as if it had used the same type.
     */
    @Test
    void registerRejectsTheSameAnchorValueUnderADifferentTypeAsAlreadyOwnedByAnotherProject() {
        Anchor sharedValueAsPath = pathAnchor("/home/dev/arknet");
        Project first = new Project(freshId(), "arknet", List.of(sharedValueAsPath));
        registry.register(first);

        Anchor sameValueAsUrl = new Anchor("/home/dev/arknet", AnchorType.URL);
        Project second = new Project(freshId(), "arknet-copy", List.of(sameValueAsUrl));

        AnchorAlreadyRegisteredException thrown = assertThrows(AnchorAlreadyRegisteredException.class,
                () -> registry.register(second));
        assertEquals(first.id(), thrown.owner());
    }

    @Test
    void registerRejectsADuplicateLabelOnADifferentIdentity() {
        registry.register(new Project(freshId(), "arknet", List.of(pathAnchor("/a"))));

        assertThrows(DuplicateProjectLabelException.class,
                () -> registry.register(new Project(freshId(), "arknet", List.of(pathAnchor("/b")))));
    }

    @Test
    void compareAndUpdateRejectsRenamingToALabelAlreadyUsedByAnotherProject() {
        registry.register(new Project(freshId(), "taken", List.of(pathAnchor("/a"))));

        Project mine = new Project(freshId(), "mine", List.of(pathAnchor("/b")));
        registry.register(mine);
        String head = registry.findCurrentById(mine.id()).orElseThrow().head();

        Project renamed = new Project(mine.id(), "taken", mine.anchors());

        assertThrows(DuplicateProjectLabelException.class, () -> registry.compareAndUpdate(head, renamed));
    }

    /**
     * Replace-by-identity regression (see the class javadoc's "leaves no orphaned anchor nodes"
     * section): switching a project from one anchor to another must not leave the old anchor node
     * behind as a dangling {@code arkprj:Anchor} with no incoming {@code arkprj:anchor} edge.
     */
    @Test
    void replaceByIdentitySwitchingAnchorsLeavesNoOrphanedAnchorNode() {
        Anchor oldAnchor = pathAnchor("/home/dev/old-checkout");
        Project project = new Project(freshId(), "arknet", List.of(oldAnchor));
        registry.register(project);
        String head = registry.findCurrentById(project.id()).orElseThrow().head();

        Anchor newAnchor = pathAnchor("/home/dev/new-checkout");
        Project switched = new Project(project.id(), "arknet", List.of(newAnchor));
        registry.compareAndUpdate(head, switched);

        assertTrue(registry.findByAnchor(oldAnchor).isEmpty(), "the old anchor must no longer resolve");
        assertEquals(Optional.of(switched), registry.findByAnchor(newAnchor));

        String orphanCheck = "SELECT ?anchor WHERE { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { "
                + "?anchor a <" + ArkprjVocabulary.ANCHOR_CLASS + "> . "
                + "FILTER NOT EXISTS { ?project <" + ArkprjVocabulary.ANCHOR + "> ?anchor } } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(ProjectId.RESERVED_SYSTEM_DATASET))) {
            assertEquals(0, handle.sparqlQuery().select(orphanCheck).count(),
                    "no arkprj:Anchor node may exist without an incoming arkprj:anchor edge");
        }
    }

    // ---- concurrency (CAS) ---------------------------------------------------------------

    @Test
    void findCurrentByIdReturnsAHeadAfterRegistration() {
        Project project = new Project(freshId(), "arknet", List.of(pathAnchor("/a")));
        registry.register(project);

        ProjectRegistry.CurrentProject current = registry.findCurrentById(project.id()).orElseThrow();
        assertEquals(project, current.project());
        assertTrue(current.head() != null && !current.head().isBlank());
    }

    @Test
    void compareAndUpdateSucceedsWithTheCurrentHead() {
        Project project = new Project(freshId(), "arknet", List.of(pathAnchor("/a")));
        registry.register(project);
        String head = registry.findCurrentById(project.id()).orElseThrow().head();

        Project renamed = new Project(project.id(), "renamed", project.anchors());
        registry.compareAndUpdate(head, renamed);

        assertEquals(Optional.of(renamed), registry.findById(project.id()));
    }

    @Test
    void compareAndUpdateRejectsAStaleHead() {
        Project project = new Project(freshId(), "arknet", List.of(pathAnchor("/a")));
        registry.register(project);
        String staleHead = registry.findCurrentById(project.id()).orElseThrow().head();

        Project firstRename = new Project(project.id(), "renamed-once", project.anchors());
        registry.compareAndUpdate(staleHead, firstRename);

        Project secondRename = new Project(project.id(), "renamed-twice", project.anchors());
        assertThrows(StaleProjectException.class, () -> registry.compareAndUpdate(staleHead, secondRename));
    }

    @Test
    void compareAndUpdateRejectsAMissingIdentity() {
        Project missing = new Project(freshId(), "ghost", List.of(pathAnchor("/a")));

        assertThrows(ProjectNotFoundException.class, () -> registry.compareAndUpdate(null, missing));
    }

    // ---- SHACL write-gate -----------------------------------------------------------------

    @Test
    void writeRejectsAProjectWithABlankLabelViaTheShaclGate() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/project/" + UUID.randomUUID());
        IRI anchor = rdf.createIRI("https://w3id.org/arknet/anchor/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(ArkprjVocabulary.PROJECT_TYPE));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral(""));
        candidate.add(subject, rdf.createIRI(ArkprjVocabulary.ANCHOR), anchor);
        candidate.add(anchor, VocabRdf.TYPE, rdf.createIRI(ArkprjVocabulary.ANCHOR_CLASS));
        candidate.add(anchor, rdf.createIRI(ArkprjVocabulary.ANCHOR_VALUE), rdf.createLiteral("/a"));
        candidate.add(anchor, rdf.createIRI(ArkprjVocabulary.ANCHOR_TYPE), rdf.createIRI(ArkprjVocabulary.PATH_ANCHOR));

        ShaclWriteGate gate = KognioRdfProjectRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    @Test
    void writeRejectsAnAnchorWithoutAValueViaTheShaclGate() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/project/" + UUID.randomUUID());
        IRI anchor = rdf.createIRI("https://w3id.org/arknet/anchor/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(ArkprjVocabulary.PROJECT_TYPE));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("arknet"));
        candidate.add(subject, rdf.createIRI(ArkprjVocabulary.ANCHOR), anchor);
        candidate.add(anchor, VocabRdf.TYPE, rdf.createIRI(ArkprjVocabulary.ANCHOR_CLASS));
        candidate.add(anchor, rdf.createIRI(ArkprjVocabulary.ANCHOR_TYPE), rdf.createIRI(ArkprjVocabulary.PATH_ANCHOR));

        ShaclWriteGate gate = KognioRdfProjectRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        assertThrows(WriteConstraintViolationException.class, () -> gate.enforce(candidate));
    }

    // ---- self-description -------------------------------------------------------------------

    @Test
    void describeWritesIntoTheProjectsOwnDatasetNotTheSystemDataset() {
        ShaclWriteGate gate = KognioRdfProjectRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        KognioRdfProjectSelfDescription selfDescription = new KognioRdfProjectSelfDescription(lifecycle, gate);

        Project project = new Project(freshId(), "arknet", List.of(pathAnchor("/home/dev/arknet")));
        selfDescription.describe(project);

        String projectSubject = "https://w3id.org/arknet/project/" + project.id().value();
        String ask = "ASK { GRAPH <" + ArkprjVocabulary.IDENTITY_GRAPH + "> { <" + projectSubject
                + "> a <" + ArkprjVocabulary.PROJECT_TYPE + "> } }";

        try (DatasetHandle ownDataset = lifecycle.acquire(new DatasetId(project.id().value()))) {
            assertTrue(ownDataset.sparqlQuery().ask(ask), "self-description must land in the project's own dataset");
        }
        try (DatasetHandle systemDataset = lifecycle.acquire(new DatasetId(ProjectId.RESERVED_SYSTEM_DATASET))) {
            assertFalse(systemDataset.sparqlQuery().ask(ask), "self-description must not land in the system dataset");
        }
    }

    @Test
    void describingTheSameProjectTwiceIsIdempotent() {
        ShaclWriteGate gate = KognioRdfProjectRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        KognioRdfProjectSelfDescription selfDescription = new KognioRdfProjectSelfDescription(lifecycle, gate);
        IRI identityGraph = new SimpleRdf().createIRI(ArkprjVocabulary.IDENTITY_GRAPH);

        Project project = new Project(freshId(), "arknet",
                List.of(pathAnchor("/home/dev/arknet"), pathAnchor("/home/dev/arknet-worktree")));
        selfDescription.describe(project);

        long countAfterFirst;
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(project.id().value()))) {
            countAfterFirst = handle.graphStore().count(identityGraph);
        }

        selfDescription.describe(project);

        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(project.id().value()))) {
            long countAfterSecond = handle.graphStore().count(identityGraph);
            assertEquals(countAfterFirst, countAfterSecond, "describing twice must not change the triple count");
        }
    }
}
