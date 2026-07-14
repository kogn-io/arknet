package de.hauschel.arknet.req.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.WorkspaceId;

/**
 * Policy tests for {@link RequirementService}: identity assignment, listing,
 * lookup and status-transition rules, exercised against an in-memory fake
 * repository.
 */
class RequirementServiceTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;

    private InMemoryRequirementRepository repository;
    private RequirementService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRequirementRepository();
        service = new RequirementService(repository);
    }

    @Test
    void addAssignsFirstFunctionalIdAndProposedStatus() {
        Requirement added = service.add(WS, new NewRequirement("User can log in",
                "The system shall let a registered user authenticate.", RequirementType.FUNCTIONAL));

        assertEquals(new RequirementId("FR-1"), added.id());
        assertEquals("User can log in", added.title());
        assertEquals("The system shall let a registered user authenticate.", added.description());
        assertEquals(RequirementType.FUNCTIONAL, added.type());
        assertEquals(RequirementStatus.PROPOSED, added.status());
        assertEquals(added, repository.findById(WS, added.id()).orElseThrow());
    }

    @Test
    void addAssignsNfrPrefixForNonFunctional() {
        Requirement added = service.add(WS, new NewRequirement("Page loads < 200ms",
                "95% of page loads shall complete in under 200ms.", RequirementType.NON_FUNCTIONAL));

        assertEquals(new RequirementId("NFR-1"), added.id());
    }

    @Test
    void addNumbersRunPerTypeIndependently() {
        RequirementId fr1 = service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL)).id();
        RequirementId nfr1 = service.add(WS, new NewRequirement("b", "desc b", RequirementType.NON_FUNCTIONAL)).id();
        RequirementId fr2 = service.add(WS, new NewRequirement("c", "desc c", RequirementType.FUNCTIONAL)).id();

        assertEquals(new RequirementId("FR-1"), fr1);
        assertEquals(new RequirementId("NFR-1"), nfr1);
        assertEquals(new RequirementId("FR-2"), fr2);
    }

    @Test
    void addIsScopedPerWorkspace() {
        WorkspaceId other = new WorkspaceId("other");
        service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL));

        Requirement inOther = service.add(other, new NewRequirement("b", "desc b", RequirementType.FUNCTIONAL));

        assertEquals(new RequirementId("FR-1"), inOther.id());
        assertTrue(service.list(other).stream().allMatch(r -> r.title().equals("b")));
        assertEquals(1, service.list(WS).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL));
        service.add(WS, new NewRequirement("b", "desc b", RequirementType.FUNCTIONAL));

        List<Requirement> all = service.list(WS);

        assertEquals(2, all.size());
        assertEquals("a", all.get(0).title());
        assertEquals("b", all.get(1).title());
    }

    @Test
    void getReturnsPersistedRequirement() {
        RequirementId id = service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL)).id();

        assertTrue(service.get(WS, id).isPresent());
        assertEquals("a", service.get(WS, id).orElseThrow().title());
    }

    @Test
    void getIsEmptyForUnknownId() {
        assertFalse(service.get(WS, new RequirementId("FR-99")).isPresent());
    }

    @Test
    void setStatusAcceptsProposedToAccepted() {
        RequirementId id = service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL)).id();

        Requirement accepted = service.setStatus(WS, id, RequirementStatus.ACCEPTED);

        assertEquals(RequirementStatus.ACCEPTED, accepted.status());
        assertEquals("desc a", accepted.description());
        assertEquals(RequirementStatus.ACCEPTED, repository.findById(WS, id).orElseThrow().status());
    }

    @Test
    void setStatusToSameStatusIsIdempotent() {
        RequirementId id = service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL)).id();

        Requirement result = service.setStatus(WS, id, RequirementStatus.PROPOSED);

        assertEquals(RequirementStatus.PROPOSED, result.status());
    }

    @Test
    void setStatusRejectsRevertingAcceptedToProposed() {
        RequirementId id = service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL)).id();
        service.setStatus(WS, id, RequirementStatus.ACCEPTED);

        assertThrows(IllegalStateException.class,
                () -> service.setStatus(WS, id, RequirementStatus.PROPOSED));
    }

    @Test
    void setStatusThrowsWhenRequirementUnknown() {
        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.setStatus(WS, new RequirementId("FR-42"), RequirementStatus.ACCEPTED));

        assertSame(WS, ex.workspaceId());
        assertEquals(new RequirementId("FR-42"), ex.requirementId());
    }
}
