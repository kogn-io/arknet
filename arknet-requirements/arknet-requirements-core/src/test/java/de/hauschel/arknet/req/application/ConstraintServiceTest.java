// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints.ResolvedConstraint;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintType;

/**
 * Policy tests for {@link ConstraintService}: identity minting, per-subtype code assignment,
 * listing and lookup, exercised against {@link InMemoryConstraintRepository} and a deterministic
 * fake {@link ResourceIdFactory} - mirrors {@code RequirementServiceTest} in shape, minus the
 * read-modify-write/status-transition tests that do not apply to an immutable {@link Constraint}.
 */
class ConstraintServiceTest {

    private static final ProjectId WS = new ProjectId("test-project");

    private InMemoryConstraintRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private ConstraintService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryConstraintRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        service = new ConstraintService(repository, resourceIdFactory);
    }

    @Test
    void addAssignsFirstCodePerType() {
        Constraint technical = service.add(WS, new NewConstraint("t", "Must run on the JVM", ConstraintType.TECHNICAL));
        Constraint business = service.add(WS, new NewConstraint("b", "Budget cap of 10k", ConstraintType.BUSINESS));
        Constraint regulatory =
                service.add(WS, new NewConstraint("r", "Personal data must stay in the EU", ConstraintType.REGULATORY));

        assertEquals(new ConstraintCode("TCON-1"), technical.code());
        assertEquals(new ConstraintCode("BCON-1"), business.code());
        assertEquals(new ConstraintCode("RCON-1"), regulatory.code());
    }

    @Test
    void addNumbersRunPerSubtypeIndependently() {
        ConstraintCode tcon1 = service.add(WS, new NewConstraint("a", "s a", ConstraintType.TECHNICAL)).code();
        ConstraintCode bcon1 = service.add(WS, new NewConstraint("b", "s b", ConstraintType.BUSINESS)).code();
        ConstraintCode tcon2 = service.add(WS, new NewConstraint("c", "s c", ConstraintType.TECHNICAL)).code();
        ConstraintCode rcon1 = service.add(WS, new NewConstraint("d", "s d", ConstraintType.REGULATORY)).code();

        assertEquals(new ConstraintCode("TCON-1"), tcon1);
        assertEquals(new ConstraintCode("BCON-1"), bcon1);
        assertEquals(new ConstraintCode("TCON-2"), tcon2);
        assertEquals(new ConstraintCode("RCON-1"), rcon1);
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        service.add(WS, new NewConstraint("a", "s a", ConstraintType.TECHNICAL));

        Constraint inOther = service.add(other, new NewConstraint("b", "s b", ConstraintType.TECHNICAL));

        assertEquals(new ConstraintCode("TCON-1"), inOther.code());
        assertEquals(1, service.list(WS).size());
        assertEquals(1, service.list(other).size());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        Constraint first = service.add(WS, new NewConstraint("a", "s a", ConstraintType.TECHNICAL));
        Constraint second = service.add(WS, new NewConstraint("b", "s b", ConstraintType.TECHNICAL));

        assertNotEquals(first.id(), second.id());
    }

    @Test
    void addPersistsTitleStatementAndType() {
        Constraint added = service.add(WS, new NewConstraint("Must run on the JVM", "The system shall be JVM-based.",
                ConstraintType.TECHNICAL));

        assertEquals("Must run on the JVM", added.title());
        assertEquals("The system shall be JVM-based.", added.statement());
        assertEquals(ConstraintType.TECHNICAL, added.type());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewConstraint("a", "s a", ConstraintType.TECHNICAL));
        service.add(WS, new NewConstraint("b", "s b", ConstraintType.BUSINESS));

        List<Constraint> all = service.list(WS);

        assertEquals(2, all.size());
        assertEquals(new ConstraintCode("TCON-1"), all.get(0).code());
        assertEquals(new ConstraintCode("BCON-1"), all.get(1).code());
    }

    @Test
    void listIsEmptyForAProjectWithNoConstraints() {
        assertTrue(service.list(WS).isEmpty());
    }

    @Test
    void getReturnsPersistedConstraint() {
        ConstraintCode code =
                service.add(WS, new NewConstraint("a", "s a", ConstraintType.TECHNICAL)).code();

        Constraint found = service.get(WS, code).orElseThrow();

        assertEquals(code, found.code());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new ConstraintCode("TCON-99")).isPresent());
    }

    @Test
    void resolveExistingReturnsAnEmptyListForNoIds() {
        assertTrue(service.resolveExisting(WS).isEmpty());
    }

    @Test
    void resolveExistingResolvesAPersistedConstraintsIdToItsCode() {
        Constraint added = service.add(WS, new NewConstraint("a", "s a", ConstraintType.TECHNICAL));

        List<ResolvedConstraint> resolved =
                service.resolveExisting(WS, added.id().value());

        assertEquals(1, resolved.size());
        assertEquals(added.code(), resolved.get(0).code());
    }

    private static final class FakeResourceIdFactory implements ResourceIdFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public ResourceId newId() {
            return ResourceId.of("https://w3id.org/arknet/id/fake-" + counter.incrementAndGet());
        }
    }
}
