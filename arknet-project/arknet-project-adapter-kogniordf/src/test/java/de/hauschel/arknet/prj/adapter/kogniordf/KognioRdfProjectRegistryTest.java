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
import de.hauschel.arknet.prj.application.port.out.RevisionToken;
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
     * outliving the test the way {@code Files.createTempDirectory} let it.
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
        registry = new KognioRdfProjectRegistry(datasetLifecycle, DisplayLocale.DEFAULT, funnel);
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

        registry.register(project, null, null, null, null);

        assertEquals(Optional.of(project), registry.findById(project.id()));
        assertEquals(Optional.of(project), registry.findByAnchor(anchor));
        assertEquals(List.of(project), registry.findAll());
    }

    @Test
    void anAnchorRoundTripsWithItsFullType() {
        Anchor anchor = new Anchor("https://example.org/repo", AnchorType.URL);
        Project project = new Project(freshId(), "remote-project", List.of(anchor));

        registry.register(project, null, null, null, null);

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

        registry.register(project, null, null, null, null);

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
        registry.register(first, null, null, null, null);

        Project second = new Project(freshId(), "arknet-copy", List.of(sharedAnchor));

        AnchorAlreadyRegisteredException thrown = assertThrows(AnchorAlreadyRegisteredException.class,
                () -> registry.register(second, null, null, null, null));
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
        registry.register(owner, null, null, null, null);

        Anchor ownAnchor = pathAnchor("/home/dev/mine");
        Project mine = new Project(freshId(), "mine", List.of(ownAnchor));
        registry.register(mine, null, null, null, null);
        RevisionToken head = registry.findCurrentById(mine.id()).orElseThrow().head();

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
        registry.register(first, null, null, null, null);

        Anchor sameValueAsUrl = new Anchor("/home/dev/arknet", AnchorType.URL);
        Project second = new Project(freshId(), "arknet-copy", List.of(sameValueAsUrl));

        AnchorAlreadyRegisteredException thrown = assertThrows(AnchorAlreadyRegisteredException.class,
                () -> registry.register(second, null, null, null, null));
        assertEquals(first.id(), thrown.owner());
    }

    @Test
    void registerRejectsADuplicateLabelOnADifferentIdentity() {
        registry.register(new Project(freshId(), "arknet", List.of(pathAnchor("/a"))), null, null, null, null);

        assertThrows(DuplicateProjectLabelException.class,
                () -> registry.register(new Project(freshId(), "arknet", List.of(pathAnchor("/b"))), null, null, null, null));
    }

    @Test
    void compareAndUpdateRejectsRenamingToALabelAlreadyUsedByAnotherProject() {
        registry.register(new Project(freshId(), "taken", List.of(pathAnchor("/a"))), null, null, null, null);

        Project mine = new Project(freshId(), "mine", List.of(pathAnchor("/b")));
        registry.register(mine, null, null, null, null);
        RevisionToken head = registry.findCurrentById(mine.id()).orElseThrow().head();

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
        registry.register(project, null, null, null, null);
        RevisionToken head = registry.findCurrentById(project.id()).orElseThrow().head();

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

    // ---- maintained language set (kogn-io/arknet#412) --------------------------------------

    @Test
    void registerStoresTheMaintainedLanguageSetCanonicalizedAndReadsItBackSorted() {
        ProjectId id = freshId();
        Project project = new Project(id, "arknet-langs", List.of(pathAnchor("/home/dev/arknet-langs")));

        registry.register(project, null, null, "de", List.of("EN", "de"));

        assertEquals(List.of("de", "en"), registry.findById(id).orElseThrow().maintainedLanguages(),
                "stored canonicalized, read back in a deterministic order - RDF gives none");
        assertEquals(List.of("de", "en"),
                registry.findCurrentById(id).orElseThrow().project().maintainedLanguages());
        assertEquals(List.of("de", "en"), registry.findAll().stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow()
                .maintainedLanguages(),
                "findAll's bulk read must agree with the single-project reads");
    }

    @Test
    void updateAttributesReplacesTheWholeMaintainedSetRatherThanAddingToIt() {
        ProjectId id = freshId();
        registry.register(new Project(id, "arknet-langs-2", List.of(pathAnchor("/home/dev/arknet-langs-2"))),
                null, null, "de", List.of("de", "en"));

        RevisionToken head = registry.findCurrentById(id).orElseThrow().head();
        Project updated = registry.updateAttributes(id, head, null, null, null, List.of("de", "fr"));

        assertEquals(List.of("de", "fr"), updated.maintainedLanguages());
        assertEquals(List.of("de", "fr"), registry.findById(id).orElseThrow().maintainedLanguages(),
                "the old 'en' triple must be gone, not merged with the new set");
    }

    @Test
    void updateAttributesWithAnEmptyListRemovesTheSetAndWithNullLeavesItAlone() {
        ProjectId id = freshId();
        registry.register(new Project(id, "arknet-langs-3", List.of(pathAnchor("/home/dev/arknet-langs-3"))),
                null, null, "de", List.of("de", "en"));

        RevisionToken head = registry.findCurrentById(id).orElseThrow().head();
        registry.updateAttributes(id, head, "eine Beschreibung", "de", null, null);
        assertEquals(List.of("de", "en"), registry.findById(id).orElseThrow().maintainedLanguages(),
                "null means unchanged, the same as for every other argument of this patch");

        RevisionToken nextHead = registry.findCurrentById(id).orElseThrow().head();
        registry.updateAttributes(id, nextHead, null, null, null, List.of());
        assertEquals(List.of(), registry.findById(id).orElseThrow().maintainedLanguages(),
                "an empty list is the deliberate way back to no commitment");
    }

    /**
     * The regression the description field already has a test for: {@code compareAndUpdate} is a
     * replace-by-identity write, so anything it neither excludes from its delete nor re-adds is
     * silently wiped by the next rename or attached anchor.
     */
    @Test
    void aRenameLeavesTheMaintainedLanguageSetUntouched() {
        ProjectId id = freshId();
        registry.register(new Project(id, "arknet-langs-4", List.of(pathAnchor("/home/dev/arknet-langs-4"))),
                null, null, "de", List.of("de", "en"));

        RevisionToken head = registry.findCurrentById(id).orElseThrow().head();
        registry.compareAndUpdate(head,
                new Project(id, "arknet-langs-renamed", List.of(pathAnchor("/home/dev/arknet-langs-4"))));

        assertEquals(List.of("de", "en"), registry.findById(id).orElseThrow().maintainedLanguages(),
                "a rename must not collapse a project's declared language set");
    }

    /** A malformed tag has to be refused by the shipped shape, not only by the Java write path. */
    @Test
    void theShaclGateRejectsAMalformedMaintainedLanguageTag() {
        RDF rdf = new SimpleRdf();
        ProjectId id = freshId();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/project/" + id.value());
        IRI anchor = rdf.createIRI("https://w3id.org/arknet/anchor/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE, rdf.createIRI(ArkprjVocabulary.PROJECT_TYPE));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("arknet-bad-tag"));
        candidate.add(subject, rdf.createIRI(ArkprjVocabulary.ANCHOR), anchor);
        candidate.add(anchor, VocabRdf.TYPE, rdf.createIRI(ArkprjVocabulary.ANCHOR_CLASS));
        candidate.add(anchor, rdf.createIRI(ArkprjVocabulary.ANCHOR_VALUE), rdf.createLiteral("/home/dev/bad"));
        candidate.add(anchor, rdf.createIRI(ArkprjVocabulary.ANCHOR_TYPE),
                rdf.createIRI(ArkprjVocabulary.PATH_ANCHOR));
        candidate.add(subject, rdf.createIRI(ArkprjVocabulary.MAINTAINED_LANGUAGE),
                rdf.createLiteral("de_DE"));

        assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfProjectRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));
    }

    // ---- concurrency (CAS) ---------------------------------------------------------------

    @Test
    void findCurrentByIdReturnsAHeadAfterRegistration() {
        Project project = new Project(freshId(), "arknet", List.of(pathAnchor("/a")));
        registry.register(project, null, null, null, null);

        ProjectRegistry.CurrentProject current = registry.findCurrentById(project.id()).orElseThrow();
        assertEquals(project, current.project());
        assertTrue(current.head() != null && !current.head().value().isBlank());
    }

    @Test
    void compareAndUpdateSucceedsWithTheCurrentHead() {
        Project project = new Project(freshId(), "arknet", List.of(pathAnchor("/a")));
        registry.register(project, null, null, null, null);
        RevisionToken head = registry.findCurrentById(project.id()).orElseThrow().head();

        Project renamed = new Project(project.id(), "renamed", project.anchors());
        registry.compareAndUpdate(head, renamed);

        assertEquals(Optional.of(renamed), registry.findById(project.id()));
    }

    @Test
    void compareAndUpdateRejectsAStaleHead() {
        Project project = new Project(freshId(), "arknet", List.of(pathAnchor("/a")));
        registry.register(project, null, null, null, null);
        RevisionToken staleHead = registry.findCurrentById(project.id()).orElseThrow().head();

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

    // ---- description language-scoped delete (PR #230 review) --------------------------------

    /**
     * P0 regression (PR #230 review comment): {@code deleteDescriptionOfLanguage}'s
     * {@code FILTER(lang(?o) = "tag")} compared the raw, unnormalized BCP-47 tag
     * case-sensitively - a later correction spelling the same language with a different case
     * (e.g. {@code "DE"} where the description was originally written with {@code "de"}) missed
     * the existing literal and inserted a second, differently-cased one instead of replacing it,
     * defeating {@code sh:uniqueLang}. Fixed by canonicalizing every tag through
     * {@code canonicalLanguageTag} before both writing a literal and building the delete filter,
     * so the two calls always agree on one case - mirrors {@code KognioRdfTermRepositoryTest}'s
     * equivalent regression test for {@code skos:prefLabel}.
     */
    @Test
    void updateAttributesWithADifferentlyCasedLanguageTagReplacesTheSameStoredVariantInsteadOfDuplicatingIt() {
        ProjectId id = freshId();
        Project project = new Project(id, "arknet", List.of(pathAnchor("/home/dev/arknet")));
        registry.register(project, "Architekturmodelle, die Maschinen verstehen.", "de", null, null);

        ProjectRegistry.CurrentProject current = registry.findCurrentById(id).orElseThrow();
        Project updated = registry.updateAttributes(id, current.head(),
                "Architecture models machines understand.", "DE", null, null);

        assertEquals("Architecture models machines understand.", updated.description());
        assertTrue(subjectHasDescriptionTaggedAs(id, "Architecture models machines understand.", "de"));
        assertFalse(subjectHasDescriptionWithRawLanguageTag(id, "DE"),
                "a case-differing language argument must not leave a duplicate, differently-cased literal behind");
    }

    /** Whether the registered project {@code id} carries this exact {@code dcterms:description} literal+tag. */
    private boolean subjectHasDescriptionTaggedAs(ProjectId id, String value, String tag) {
        String projectSubject = "https://w3id.org/arknet/project/" + id.value();
        String query = "ASK { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { <" + projectSubject
                + "> <" + ArkprjVocabulary.DESCRIPTION + "> \"" + value + "\"@" + tag + " } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(ProjectId.RESERVED_SYSTEM_DATASET))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    /**
     * Whether {@code id} carries a {@code dcterms:description} literal whose RAW, unnormalized
     * language tag is exactly {@code rawTag} - bypasses the {@code "value"@tag} triple-pattern
     * matching {@link #subjectHasDescriptionTaggedAs} relies on (RDF term equality may treat
     * differently-cased tags as the same term) and instead mirrors production's own
     * {@code deleteDescriptionOfLanguage} filter ({@code FILTER(lang(?o) = "tag")}, a
     * case-sensitive string comparison against the tag exactly as stored).
     */
    private boolean subjectHasDescriptionWithRawLanguageTag(ProjectId id, String rawTag) {
        String projectSubject = "https://w3id.org/arknet/project/" + id.value();
        String query = "ASK { GRAPH <" + ArkprjVocabulary.REGISTRY_GRAPH + "> { <" + projectSubject
                + "> <" + ArkprjVocabulary.DESCRIPTION + "> ?o . FILTER(lang(?o) = \"" + rawTag + "\") } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(ProjectId.RESERVED_SYSTEM_DATASET))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    // ---- description locale merge (issue #296) -----------------------------------------------

    /**
     * Regression test for issue #296: {@code selectDescription} used to select purely via the
     * injected, process-wide {@link DisplayLocale} ({@code setUp()} wires {@link
     * DisplayLocale#DEFAULT}, requested English) - not merged with the very project's own
     * registered {@code arkprj:defaultLanguage}, even though both are read from the same query.
     * A project configured with German as its default language, but carrying both a German and
     * an English description, must surface the German one - the same merge {@code store_overview}
     * already applies to the report body (issue #276), which this read path feeds via {@code
     * FindProject} for the report's header.
     */
    @Test
    void findByIdSelectsTheDescriptionInTheProjectsOwnDefaultLanguageNotTheProcessWideLocale() {
        ProjectId id = freshId();
        Project project = new Project(id, "arknet-de-default", List.of(pathAnchor("/home/dev/arknet-de-default")));
        registry.register(project, "Architekturmodelle, die Maschinen verstehen.", "de", "de", null);
        ProjectRegistry.CurrentProject current = registry.findCurrentById(id).orElseThrow();
        registry.updateAttributes(id, current.head(), "Architecture models machines understand.", "en", null, null);

        Project found = registry.findById(id).orElseThrow();

        assertEquals("Architekturmodelle, die Maschinen verstehen.", found.description());
    }

    /** {@link #findAll()} must apply the same per-project merge, not just the single-project reads. */
    @Test
    void findAllSelectsEachProjectsDescriptionInItsOwnDefaultLanguage() {
        ProjectId id = freshId();
        Project project = new Project(id, "arknet-de-default-2", List.of(pathAnchor("/home/dev/arknet-de-default-2")));
        registry.register(project, "Architekturmodelle, die Maschinen verstehen.", "de", "de", null);
        ProjectRegistry.CurrentProject current = registry.findCurrentById(id).orElseThrow();
        registry.updateAttributes(id, current.head(), "Architecture models machines understand.", "en", null, null);

        Project found = registry.findAll().stream()
                .filter(candidate -> candidate.id().equals(id)).findFirst().orElseThrow();

        assertEquals("Architekturmodelle, die Maschinen verstehen.", found.description());
    }

    /** {@link #findCurrentById} feeds {@code project_update}'s read-modify-write path and must merge too. */
    @Test
    void findCurrentByIdSelectsTheDescriptionInTheProjectsOwnDefaultLanguage() {
        ProjectId id = freshId();
        Project project = new Project(id, "arknet-de-default-3", List.of(pathAnchor("/home/dev/arknet-de-default-3")));
        registry.register(project, "Architekturmodelle, die Maschinen verstehen.", "de", "de", null);
        ProjectRegistry.CurrentProject current = registry.findCurrentById(id).orElseThrow();
        registry.updateAttributes(id, current.head(), "Architecture models machines understand.", "en", null, null);

        ProjectRegistry.CurrentProject reread = registry.findCurrentById(id).orElseThrow();

        assertEquals("Architekturmodelle, die Maschinen verstehen.", reread.project().description());
    }

    /**
     * A project without a configured default language must keep degrading exactly as before this
     * merge existed: {@link DisplayLocale#withRequestedOverride} is a no-op for a {@code null}
     * override, so the process-wide {@link DisplayLocale} decides alone.
     */
    @Test
    void findByIdFallsBackToTheProcessWideLocaleWhenTheProjectHasNoDefaultLanguage() {
        ProjectId id = freshId();
        Project project = new Project(id, "arknet-no-default", List.of(pathAnchor("/home/dev/arknet-no-default")));
        registry.register(project, "Architekturmodelle, die Maschinen verstehen.", "de", null, null);
        ProjectRegistry.CurrentProject current = registry.findCurrentById(id).orElseThrow();
        registry.updateAttributes(id, current.head(), "Architecture models machines understand.", "en", null, null);

        Project found = registry.findById(id).orElseThrow();

        assertEquals("Architecture models machines understand.", found.description());
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
