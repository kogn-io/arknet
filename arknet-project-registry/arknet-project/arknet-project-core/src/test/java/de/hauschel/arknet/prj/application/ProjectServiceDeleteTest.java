// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.application.port.in.DeleteProject.Deletion;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.OwnProjectDeletionException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.ProjectDatasetRetainedException;
import de.hauschel.arknet.prj.domain.UnconfirmedProjectDeletionException;
import de.hauschel.arknet.prj.domain.UnknownAnchorException;
import de.hauschel.arknet.prj.domain.UnknownProjectException;

/**
 * Policy tests for {@link ProjectService#delete}: how the project to delete is named, the two
 * guardrails (own project, unconfirmed loss of model resources) and the order in which registry
 * entry and dataset go away.
 */
class ProjectServiceDeleteTest {

    private static final Anchor ELSEWHERE = pathAnchor("/home/fred/DEV/somewhere-else");

    private InMemoryProjectRegistry registry;
    private InMemoryProjectDatasets projectDatasets;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        registry = new InMemoryProjectRegistry();
        projectDatasets = new InMemoryProjectDatasets();
        service = new ProjectService(registry, new InMemoryProjectSelfDescription(), new InMemoryDatasetInventory(),
                projectDatasets);
    }

    @Test
    void deletingAnEmptyProjectByLabelRemovesItsRegistryEntryAndItsDataset() {
        Project project = service.register("arknet-plugin", pathAnchor("/home/fred/DEV/arknet-plugin"), null, null,
                null, null);

        Deletion deletion = service.delete("arknet-plugin", List.of(ELSEWHERE), false);

        assertEquals(project, deletion.project());
        assertEquals(Map.of(), deletion.removedResources());
        assertEquals(Optional.empty(), service.findById(project.id()));
        assertTrue(projectDatasets.wasDeleted(project.id()));
    }

    @Test
    void aProjectCanBeNamedByItsIdAsWellAsByItsLabel() {
        Project project = service.register("arknet-plugin", pathAnchor("/home/fred/DEV/arknet-plugin"), null, null,
                null, null);

        service.delete(project.id().value(), List.of(ELSEWHERE), false);

        assertEquals(List.of(), service.list());
    }

    @Test
    void afterDeletionTheProjectsAnchorNoLongerResolves() {
        Anchor anchor = pathAnchor("/home/fred/DEV/arknet-plugin");
        service.register("arknet-plugin", anchor, null, null, null, null);

        service.delete("arknet-plugin", List.of(ELSEWHERE), false);

        assertThrows(UnknownAnchorException.class, () -> service.resolve(anchor));
    }

    @Test
    void otherProjectsAreLeftUntouched() {
        Project kept = service.register("arknet", pathAnchor("/home/fred/DEV/arknet"), null, null, null, null);
        service.register("arknet-plugin", pathAnchor("/home/fred/DEV/arknet-plugin"), null, null, null, null);

        service.delete("arknet-plugin", List.of(ELSEWHERE), false);

        assertEquals(List.of(kept), service.list());
        assertFalse(projectDatasets.wasDeleted(kept.id()));
    }

    /**
     * A call without any anchor of its own has no project it works in, so there is nothing for the
     * own-project guard to protect - it is never a reason to pick the project to delete either.
     */
    @Test
    void aCallWithoutAnAnchorOfItsOwnCanDelete() {
        Project project = service.register("arknet-plugin", pathAnchor("/home/fred/DEV/arknet-plugin"), null, null,
                null, null);

        service.delete("arknet-plugin", List.of(), false);

        assertEquals(Optional.empty(), service.findById(project.id()));
    }

    @Test
    void anUnknownReferenceIsRejectedAndNamesProjectList() {
        UnknownProjectException ex = assertThrows(UnknownProjectException.class,
                () -> service.delete("no-such-project", List.of(ELSEWHERE), false));

        assertTrue(ex.getMessage().contains("project_list"), ex.getMessage());
    }

    @Test
    void aBlankReferenceIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> service.delete("  ", List.of(ELSEWHERE), false));
    }

    /**
     * Ids are opaque and labels free-form, so one project's label can spell another project's id.
     * Picking either would delete a project the caller may not have meant.
     */
    @Test
    void aReferenceThatIsOneProjectsIdAndAnotherProjectsLabelIsRejectedAsAmbiguous() {
        Project first = service.register("first", pathAnchor("/home/fred/DEV/first"), null, null, null, null);
        Project second = service.register(first.id().value(), pathAnchor("/home/fred/DEV/second"), null, null, null,
                null);

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.delete(first.id().value(), List.of(ELSEWHERE), false));

        assertTrue(ex.getMessage().contains("ambiguous"), ex.getMessage());
        assertEquals(List.of(first, second), service.list());
    }

    @Test
    void theCallersOwnProjectIsNeverDeleted() {
        Anchor own = pathAnchor("/home/fred/DEV/arknet-plugin");
        Project project = service.register("arknet-plugin", own, null, null, null, null);

        OwnProjectDeletionException ex = assertThrows(OwnProjectDeletionException.class,
                () -> service.delete("arknet-plugin", List.of(own), true));

        assertEquals(project, ex.project());
        assertEquals(Optional.of(project), service.findById(project.id()));
        assertFalse(projectDatasets.wasDeleted(project.id()));
    }

    /** Any anchor of the caller's project counts, not only the first one it was registered with. */
    @Test
    void theOwnProjectGuardHoldsForEveryAnchorOfTheProject() {
        Project project = service.register("arknet", pathAnchor("/home/fred/DEV/arknet"), null, null, null, null);
        Anchor worktree = pathAnchor("/home/fred/DEV/arknet-576");
        service.attach(project.id(), worktree);

        assertThrows(OwnProjectDeletionException.class, () -> service.delete("arknet", List.of(worktree), true));
    }

    @Test
    void aProjectHoldingModelResourcesIsNotDeletedWithoutConfirmationAndTheRefusalCountsThem() {
        Project project = service.register("arknet", pathAnchor("/home/fred/DEV/arknet"), null, null, null, null);
        projectDatasets.holding(project.id(), "arkreq:FunctionalRequirement", 3)
                .holding(project.id(), "skos:Concept", 2);

        UnconfirmedProjectDeletionException ex = assertThrows(UnconfirmedProjectDeletionException.class,
                () -> service.delete("arknet", List.of(ELSEWHERE), false));

        assertEquals(Map.of("arkreq:FunctionalRequirement", 3L, "skos:Concept", 2L), ex.resourceCounts());
        assertTrue(ex.getMessage().contains("3 arkreq:FunctionalRequirement"), ex.getMessage());
        assertTrue(ex.getMessage().contains("2 skos:Concept"), ex.getMessage());
        assertTrue(ex.getMessage().contains("confirm=true"), ex.getMessage());
        assertEquals(Optional.of(project), service.findById(project.id()));
        assertFalse(projectDatasets.wasDeleted(project.id()));
    }

    @Test
    void aConfirmedDeletionRemovesAProjectHoldingModelResourcesAndReportsWhatWentWithIt() {
        Project project = service.register("arknet", pathAnchor("/home/fred/DEV/arknet"), null, null, null, null);
        projectDatasets.holding(project.id(), "arkreq:FunctionalRequirement", 3);

        Deletion deletion = service.delete("arknet", List.of(ELSEWHERE), true);

        assertEquals(Map.of("arkreq:FunctionalRequirement", 3L), deletion.removedResources());
        assertEquals(Optional.empty(), service.findById(project.id()));
        assertTrue(projectDatasets.wasDeleted(project.id()));
    }

    /**
     * Registry first, dataset second: a dataset that a concurrent call still holds open survives,
     * but the project is no longer registered - the caller learns both, and how to finish the job.
     */
    @Test
    void aDatasetStillInUseIsKeptAfterTheRegistryEntryIsGoneAndTheCallerIsToldHowToFinish() {
        Project project = service.register("arknet", pathAnchor("/home/fred/DEV/arknet"), null, null, null, null);
        projectDatasets.inUse(project.id());

        ProjectDatasetRetainedException ex = assertThrows(ProjectDatasetRetainedException.class,
                () -> service.delete("arknet", List.of(ELSEWHERE), false));

        assertEquals(Optional.empty(), service.findById(project.id()));
        assertFalse(projectDatasets.wasDeleted(project.id()));
        assertFalse(ex.getMessage().contains("project_adopt"), ex.getMessage());
        assertTrue(ex.getMessage().contains("call project_delete with the dataset id '"
                + project.id().value() + "' again"), ex.getMessage());
    }

    /**
     * A call can carry two anchors - the header and an explicit override - and either one may be
     * the project the session works in; neither may be deleted from it.
     */
    @Test
    void theOwnProjectGuardHoldsWhenOnlyOneOfSeveralCallerAnchorsBelongsToTheTarget() {
        Anchor header = pathAnchor("/home/fred/DEV/arknet-plugin");
        service.register("arknet-plugin", header, null, null, null, null);
        Anchor override = pathAnchor("/home/fred/DEV/arknet");
        service.register("arknet", override, null, null, null, null);

        assertThrows(OwnProjectDeletionException.class,
                () -> service.delete("arknet-plugin", List.of(header, override), true));
    }

    /**
     * A concurrent deletion of the same project that wins between this call's lookup and its own
     * removal leaves nothing to delete; the caller gets the same not-found answer the port
     * declares for an unknown reference, not an id-based exception it never named.
     */
    @Test
    void aProjectDeletedConcurrentlyIsReportedAsUnknown() {
        Project project = service.register("arknet-plugin", pathAnchor("/home/fred/DEV/arknet-plugin"), null, null,
                null, null);
        projectDatasets.beforeCounting(() -> registry.delete(project.id()));

        assertThrows(UnknownProjectException.class,
                () -> service.delete("arknet-plugin", List.of(ELSEWHERE), false));
    }

    /**
     * Any failure of the dataset removal after the registry entry is gone leaves the same state as
     * an open lease - an orphaned dataset - and gets the same remedy.
     */
    @Test
    void anyDatasetRemovalFailureAfterTheRegistryEntryIsGoneIsReportedAsRetained() {
        Project project = service.register("arknet", pathAnchor("/home/fred/DEV/arknet"), null, null, null, null);
        projectDatasets.failing(project.id(), new IllegalStateException("storage locked"));

        ProjectDatasetRetainedException ex = assertThrows(ProjectDatasetRetainedException.class,
                () -> service.delete("arknet", List.of(ELSEWHERE), false));

        assertEquals(Optional.empty(), service.findById(project.id()));
        assertTrue(ex.getMessage().contains("project_delete"), ex.getMessage());
        assertEquals("storage locked", ex.getSuppressed()[0].getMessage());
    }

    /** The retained dataset is exactly what the next call can remove: the advice has to work. */
    @Test
    void aDatasetRetainedAfterTheRegistryEntryWentCanBeRemovedByItsIdAlone() {
        Project project = service.register("arknet", pathAnchor("/home/fred/DEV/arknet"), null, null, null, null);
        projectDatasets.inUse(project.id());
        assertThrows(ProjectDatasetRetainedException.class,
                () -> service.delete("arknet", List.of(ELSEWHERE), false));
        InMemoryProjectDatasets retry = new InMemoryProjectDatasets();
        ProjectService afterwards = new ProjectService(registry, new InMemoryProjectSelfDescription(),
                new InMemoryDatasetInventory().with(project.id()), retry);

        Deletion deletion = afterwards.delete(project.id().value(), List.of(ELSEWHERE), false);

        assertFalse(deletion.registered());
        assertEquals(project.id(), deletion.datasetId());
        assertTrue(retry.wasDeleted(project.id()));
    }

    @Test
    void anUnregisteredDatasetIsDeletedByItsIdAndTheAnswerSaysNoRegistryEntryExisted() {
        ProjectId orphan = new ProjectId("orphan-dataset");
        ProjectService withOrphan = serviceWithOrphan(orphan);

        Deletion deletion = withOrphan.delete("orphan-dataset", List.of(ELSEWHERE), false);

        assertFalse(deletion.registered());
        assertEquals(null, deletion.project());
        assertEquals(orphan, deletion.datasetId());
        assertTrue(projectDatasets.wasDeleted(orphan));
    }

    @Test
    void anUnregisteredDatasetHoldingModelResourcesNeedsConfirmationToo() {
        ProjectId orphan = new ProjectId("orphan-dataset");
        ProjectService withOrphan = serviceWithOrphan(orphan);
        projectDatasets.holding(orphan, "arkreq:FunctionalRequirement", 2);

        UnconfirmedProjectDeletionException ex = assertThrows(UnconfirmedProjectDeletionException.class,
                () -> withOrphan.delete("orphan-dataset", List.of(ELSEWHERE), false));

        assertTrue(ex.getMessage().contains("unregistered dataset 'orphan-dataset'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("2 arkreq:FunctionalRequirement"), ex.getMessage());
        assertFalse(projectDatasets.wasDeleted(orphan));

        Deletion deletion = withOrphan.delete("orphan-dataset", List.of(ELSEWHERE), true);

        assertEquals(Map.of("arkreq:FunctionalRequirement", 2L), deletion.removedResources());
        assertTrue(projectDatasets.wasDeleted(orphan));
    }

    /** A registered project wins over an unregistered dataset of the same name; the guards stay. */
    @Test
    void aRegisteredProjectIsResolvedBeforeAnUnregisteredDatasetAndKeepsItsOwnProjectGuard() {
        Anchor own = pathAnchor("/home/fred/DEV/arknet");
        Project project = service.register("arknet", own, null, null, null, null);
        ProjectService withSameIdAsDataset = new ProjectService(registry, new InMemoryProjectSelfDescription(),
                new InMemoryDatasetInventory().with(project.id()), projectDatasets);

        assertThrows(OwnProjectDeletionException.class,
                () -> withSameIdAsDataset.delete(project.id().value(), List.of(own), true));
        assertEquals(Optional.of(project), service.findById(project.id()));
    }

    @Test
    void anUnknownValueIsStillRejectedWhenOtherDatasetsAreAdoptable() {
        ProjectService withOrphan = serviceWithOrphan(new ProjectId("orphan-dataset"));

        UnknownProjectException ex = assertThrows(UnknownProjectException.class,
                () -> withOrphan.delete("something-else", List.of(ELSEWHERE), true));

        assertTrue(ex.getMessage().contains("unregistered datasets"), ex.getMessage());
        assertFalse(projectDatasets.wasDeleted(new ProjectId("something-else")));
    }

    @Test
    void aUnregisteredDatasetRegisteredWhileItIsBeingDeletedIsLeftAlone() {
        ProjectId orphan = new ProjectId("orphan-dataset");
        // The inventory answers, then a concurrent adopter registers the dataset before the lock is taken.
        ProjectService withOrphan = new ProjectService(registry, new InMemoryProjectSelfDescription(), () -> {
            registry.register(new Project(orphan, "late", List.of(pathAnchor("/home/fred/DEV/late"))), null, null,
                    null, List.of());
            return java.util.Set.of(orphan);
        }, projectDatasets);
        assertThrows(IllegalArgumentException.class,
                () -> withOrphan.delete("orphan-dataset", List.of(ELSEWHERE), true));
        assertFalse(projectDatasets.wasDeleted(orphan));
    }

    /** History alone is content: a project without a single resource but with revisions is not empty. */
    @Test
    void aProjectHoldingOnlyRevisionHistoryNeedsConfirmationAndTheRefusalCountsTheRevisions() {
        Project project = service.register("arknet", pathAnchor("/home/fred/DEV/arknet"), null, null, null, null);
        projectDatasets.withRevisions(project.id(), 7);

        UnconfirmedProjectDeletionException ex = assertThrows(UnconfirmedProjectDeletionException.class,
                () -> service.delete("arknet", List.of(ELSEWHERE), false));

        assertEquals(7L, ex.revisions());
        assertEquals(Map.of(), ex.resourceCounts());
        assertTrue(ex.getMessage().contains("7 revisions"), ex.getMessage());
        assertEquals(Optional.of(project), service.findById(project.id()));
        assertFalse(projectDatasets.wasDeleted(project.id()));
    }

    @Test
    void aConfirmedDeletionReportsTheRevisionsThatWentWithIt() {
        Project project = service.register("arknet", pathAnchor("/home/fred/DEV/arknet"), null, null, null, null);
        projectDatasets.withRevisions(project.id(), 7);

        Deletion deletion = service.delete("arknet", List.of(ELSEWHERE), true);

        assertEquals(7L, deletion.removedRevisions());
        assertTrue(projectDatasets.wasDeleted(project.id()));
    }

    private ProjectService serviceWithOrphan(ProjectId orphan) {
        return new ProjectService(registry, new InMemoryProjectSelfDescription(),
                new InMemoryDatasetInventory().with(orphan), projectDatasets);
    }

    private static Anchor pathAnchor(String value) {
        return new Anchor(value, AnchorType.PATH);
    }
}
