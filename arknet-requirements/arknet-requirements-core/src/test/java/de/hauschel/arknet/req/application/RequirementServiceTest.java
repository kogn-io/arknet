package de.hauschel.arknet.req.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Policy tests for {@link RequirementService}: identity minting, code assignment, listing,
 * lookup, status-transition and term-linking rules, exercised against an in-memory fake
 * repository and a deterministic fake {@link ResourceIdFactory}.
 */
class RequirementServiceTest {

    private static final WorkspaceId WS = WorkspaceId.DEFAULT;
    private static final ResourceId TERM_1 =
            ResourceId.of("https://w3id.org/arknet/id/term-1");
    private static final ResourceId TERM_2 =
            ResourceId.of("https://w3id.org/arknet/id/term-2");

    private InMemoryRequirementRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private InMemoryTermLookup termLookup;
    private RequirementService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRequirementRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        termLookup = new InMemoryTermLookup();
        termLookup.register("TERM-1", TERM_1);
        termLookup.register("TERM-2", TERM_2);
        service = new RequirementService(repository, resourceIdFactory, termLookup);
    }

    @Test
    void addAssignsFirstFunctionalCodeAndProposedStatus() {
        Requirement added = service.add(WS, new NewRequirement("User can log in",
                "The system shall let a registered user authenticate.", RequirementType.FUNCTIONAL,
                null, null, null));

        assertEquals(new RequirementCode("FR-1"), added.code());
        assertEquals("User can log in", added.title());
        assertEquals("The system shall let a registered user authenticate.", added.description());
        assertEquals(RequirementType.FUNCTIONAL, added.type());
        assertEquals(RequirementStatus.PROPOSED, added.status());
        assertEquals(added, repository.findByCode(WS, added.code()).orElseThrow());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        Requirement first = service.add(WS, newFunctionalRequirement());
        Requirement second = service.add(WS, newFunctionalRequirement());

        assertNotEquals(first.id(), second.id());
        assertEquals(2, resourceIdFactory.mintedCount());
    }

    @Test
    void addAssignsNfrPrefixForNonFunctional() {
        Requirement added = service.add(WS, new NewRequirement("Page loads < 200ms",
                "95% of page loads shall complete in under 200ms.", RequirementType.NON_FUNCTIONAL,
                null, null, null));

        assertEquals(new RequirementCode("NFR-1"), added.code());
    }

    @Test
    void addCarriesPriorityMotivatedByAndQualityCategoryThrough() {
        Requirement added = service.add(WS, new NewRequirement("Page loads < 200ms",
                "95% of page loads shall complete in under 200ms.", RequirementType.NON_FUNCTIONAL,
                Priority.MUST_HAVE, "https://w3id.org/arknet/model/goal/fast-ux", "performance"));

        assertEquals(Priority.MUST_HAVE, added.priority());
        assertEquals("https://w3id.org/arknet/model/goal/fast-ux", added.motivatedBy());
        assertEquals("performance", added.qualityCategory());
        assertEquals(added, repository.findByCode(WS, added.code()).orElseThrow());
    }

    @Test
    void addNumbersRunPerTypeIndependently() {
        RequirementCode fr1 = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null)).code();
        RequirementCode nfr1 = service.add(WS,
                new NewRequirement("b", "desc b", RequirementType.NON_FUNCTIONAL, null, null, null)).code();
        RequirementCode fr2 = service.add(WS,
                new NewRequirement("c", "desc c", RequirementType.FUNCTIONAL, null, null, null)).code();

        assertEquals(new RequirementCode("FR-1"), fr1);
        assertEquals(new RequirementCode("NFR-1"), nfr1);
        assertEquals(new RequirementCode("FR-2"), fr2);
    }

    @Test
    void addIsScopedPerWorkspace() {
        WorkspaceId other = new WorkspaceId("other");
        service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null));

        Requirement inOther = service.add(other,
                new NewRequirement("b", "desc b", RequirementType.FUNCTIONAL, null, null, null));

        assertEquals(new RequirementCode("FR-1"), inOther.code());
        assertTrue(service.list(other).stream().allMatch(r -> r.title().equals("b")));
        assertEquals(1, service.list(WS).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null));
        service.add(WS, new NewRequirement("b", "desc b", RequirementType.FUNCTIONAL, null, null, null));

        List<Requirement> all = service.list(WS);

        assertEquals(2, all.size());
        assertEquals("a", all.get(0).title());
        assertEquals("b", all.get(1).title());
    }

    @Test
    void getReturnsPersistedRequirement() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null)).code();

        assertTrue(service.get(WS, code).isPresent());
        assertEquals("a", service.get(WS, code).orElseThrow().title());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new RequirementCode("FR-99")).isPresent());
    }

    @Test
    void setStatusAcceptsProposedToAccepted() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null)).code();

        Requirement accepted = service.setStatus(WS, code, RequirementStatus.ACCEPTED);

        assertEquals(RequirementStatus.ACCEPTED, accepted.status());
        assertEquals("desc a", accepted.description());
        assertEquals(RequirementStatus.ACCEPTED, repository.findByCode(WS, code).orElseThrow().status());
    }

    @Test
    void setStatusPreservesPriorityMotivatedByAndQualityCategory() {
        RequirementCode code = service.add(WS, new NewRequirement("a", "desc a", RequirementType.NON_FUNCTIONAL,
                Priority.COULD_HAVE, "https://w3id.org/arknet/model/goal/g", "security")).code();

        Requirement accepted = service.setStatus(WS, code, RequirementStatus.ACCEPTED);

        assertEquals(Priority.COULD_HAVE, accepted.priority());
        assertEquals("https://w3id.org/arknet/model/goal/g", accepted.motivatedBy());
        assertEquals("security", accepted.qualityCategory());
    }

    @Test
    void setStatusToSameStatusIsIdempotent() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null)).code();

        Requirement result = service.setStatus(WS, code, RequirementStatus.PROPOSED);

        assertEquals(RequirementStatus.PROPOSED, result.status());
    }

    @Test
    void setStatusRejectsRevertingAcceptedToProposed() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null)).code();
        service.setStatus(WS, code, RequirementStatus.ACCEPTED);

        assertThrows(IllegalStateException.class,
                () -> service.setStatus(WS, code, RequirementStatus.PROPOSED));
    }

    @Test
    void setStatusThrowsWhenRequirementUnknown() {
        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.setStatus(WS, new RequirementCode("FR-42"), RequirementStatus.ACCEPTED));

        assertSame(WS, ex.workspaceId());
        assertEquals(new RequirementCode("FR-42"), ex.requirementCode());
    }

    @Test
    void addStartsWithoutLinkedTerms() {
        Requirement added = service.add(WS, newFunctionalRequirement());

        assertEquals(List.of(), added.usesTerms());
    }

    @Test
    void linkTermAddsTheTermToTheRequirement() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();

        Requirement linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1)), linked.usesTerms());
        assertEquals(List.of(new TermRef(TERM_1)), service.get(WS, code).orElseThrow().usesTerms());
    }

    @Test
    void linkTermAppendsToAlreadyLinkedTerms() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();
        service.linkTerm(WS, code, "TERM-1");

        Requirement linked = service.linkTerm(WS, code, "TERM-2");

        assertEquals(List.of(new TermRef(TERM_1), new TermRef(TERM_2)), linked.usesTerms());
    }

    @Test
    void linkingTheSameTermTwiceIsANoOp() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();
        service.linkTerm(WS, code, "TERM-1");

        Requirement linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1)), linked.usesTerms());
    }

    @Test
    void linkTermThrowsWhenRequirementUnknown() {
        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.linkTerm(WS, new RequirementCode("FR-42"), "TERM-1"));

        assertSame(WS, ex.workspaceId());
        assertEquals(new RequirementCode("FR-42"), ex.requirementCode());
    }

    /**
     * Resolution of the human-typed term code happens here, via {@link InMemoryTermLookup}
     * (issue #77) - not in the out-adapter's write path any more. A lookup failure must
     * propagate unchanged and leave the requirement untouched.
     */
    @Test
    void linkTermPropagatesTheLookupFailureForAnUnknownTermCodeAndLinksNothing() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();

        assertThrows(NoSuchElementException.class, () -> service.linkTerm(WS, code, "TERM-99"));

        assertEquals(List.of(), service.get(WS, code).orElseThrow().usesTerms());
    }

    /**
     * Regression guard for the replace-by-identity write path: the out-adapter persists a
     * requirement by wiping and re-writing its triples, so a status change must carry the
     * linked terms along rather than silently dropping them.
     */
    @Test
    void setStatusPreservesLinkedTerms() {
        RequirementCode code = service.add(WS, newFunctionalRequirement()).code();
        service.linkTerm(WS, code, "TERM-1");

        Requirement accepted = service.setStatus(WS, code, RequirementStatus.ACCEPTED);

        assertEquals(RequirementStatus.ACCEPTED, accepted.status());
        assertEquals(List.of(new TermRef(TERM_1)), accepted.usesTerms());
        assertEquals(List.of(new TermRef(TERM_1)), service.get(WS, code).orElseThrow().usesTerms());
    }

    private static NewRequirement newFunctionalRequirement() {
        return new NewRequirement("User can log in", "The system shall let a registered user authenticate.",
                RequirementType.FUNCTIONAL, null, null, null);
    }

    /** Deterministic fake minting sequential opaque ids, so tests never depend on randomness. */
    private static final class FakeResourceIdFactory implements ResourceIdFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public ResourceId newId() {
            return ResourceId.of("https://w3id.org/arknet/id/fake-" + counter.incrementAndGet());
        }

        int mintedCount() {
            return counter.get();
        }
    }
}
