// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.MissingDefaultLanguageException;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints.ResolvedConstraint;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintNotFoundException;
import de.hauschel.arknet.req.domain.ConstraintType;

/**
 * Policy tests for {@link ConstraintService}: identity minting, per-subtype code assignment,
 * listing, lookup and the text/language correction path {@code constraint_update} backs (issue
 * #313), exercised against {@link InMemoryConstraintRepository} and a deterministic fake
 * {@link ResourceIdFactory} - mirrors {@code RequirementServiceTest} in shape, minus the
 * status-transition tests that do not apply to a {@link Constraint}.
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

    /** Adds a constraint under the explicit tag {@code de}, the shorthand most tests here want. */
    private Constraint add(ProjectId project, String title, String statement, ConstraintType type) {
        return service.add(project, new NewConstraint(title, statement, type, "de"), null);
    }

    @Test
    void addAssignsFirstCodePerType() {
        Constraint technical = add(WS, "t", "Must run on the JVM", ConstraintType.TECHNICAL);
        Constraint business = add(WS, "b", "Budget cap of 10k", ConstraintType.BUSINESS);
        Constraint regulatory = add(WS, "r", "Personal data must stay in the EU", ConstraintType.REGULATORY);

        assertEquals(new ConstraintCode("TCON-1"), technical.code());
        assertEquals(new ConstraintCode("BCON-1"), business.code());
        assertEquals(new ConstraintCode("RCON-1"), regulatory.code());
    }

    @Test
    void addNumbersRunPerSubtypeIndependently() {
        ConstraintCode tcon1 = add(WS, "a", "s a", ConstraintType.TECHNICAL).code();
        ConstraintCode bcon1 = add(WS, "b", "s b", ConstraintType.BUSINESS).code();
        ConstraintCode tcon2 = add(WS, "c", "s c", ConstraintType.TECHNICAL).code();
        ConstraintCode rcon1 = add(WS, "d", "s d", ConstraintType.REGULATORY).code();

        assertEquals(new ConstraintCode("TCON-1"), tcon1);
        assertEquals(new ConstraintCode("BCON-1"), bcon1);
        assertEquals(new ConstraintCode("TCON-2"), tcon2);
        assertEquals(new ConstraintCode("RCON-1"), rcon1);
    }

    /**
     * Mutation test for {@code nextCode} counting over {@link ConstraintRepository#findAllCodes}
     * instead of {@link ConstraintRepository#findAll} (kogn-io/arknet#360): move the count back to
     * the listing and this fails. {@code TCON-2} is seeded the way a store-first write
     * leaves a constraint whose title or statement no longer reads - out of every listing, still
     * holding its number - and a listing-based count would reissue that number until someone
     * repaired the data by hand.
     */
    @Test
    void addSkipsOverACodeThatIsAssignedButNotCurrentlyMaterialisable() {
        add(WS, "a", "s a", ConstraintType.TECHNICAL);
        repository.seedUnmaterialisableCode(WS, new ConstraintCode("TCON-2"));

        Constraint third = add(WS, "c", "s c", ConstraintType.TECHNICAL);

        assertEquals(new ConstraintCode("TCON-3"), third.code());
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        add(WS, "a", "s a", ConstraintType.TECHNICAL);

        Constraint inOther = add(other, "b", "s b", ConstraintType.TECHNICAL);

        assertEquals(new ConstraintCode("TCON-1"), inOther.code());
        assertEquals(1, service.list(WS, null).size());
        assertEquals(1, service.list(other, null).size());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        Constraint first = add(WS, "a", "s a", ConstraintType.TECHNICAL);
        Constraint second = add(WS, "b", "s b", ConstraintType.TECHNICAL);

        assertNotEquals(first.id(), second.id());
    }

    @Test
    void addPersistsTitleStatementAndType() {
        Constraint added = add(WS, "Must run on the JVM", "The system shall be JVM-based.",
                ConstraintType.TECHNICAL);

        assertEquals("Must run on the JVM", added.title());
        assertEquals("The system shall be JVM-based.", added.statement());
        assertEquals(ConstraintType.TECHNICAL, added.type());
    }

    /** Issue #313: an omitted {@code language} falls back to the project's configured default. */
    @Test
    void addFallsBackToTheProjectDefaultLanguage() {
        Constraint added = service.add(WS,
                new NewConstraint("a", "s a", ConstraintType.TECHNICAL, null), "de");

        assertEquals("de", currentOf(added.code()).titleLanguage());
        assertEquals("de", currentOf(added.code()).statementLanguage());
    }

    /**
     * Issue #313 inherits #258's rule: writing an untagged literal is not a legal fallback, so a
     * call with neither an explicit language nor a project default is rejected outright - before
     * any code is assigned.
     */
    @Test
    void addRejectsAWriteWithNeitherAnExplicitNorADefaultLanguage() {
        assertThrows(MissingDefaultLanguageException.class,
                () -> service.add(WS, new NewConstraint("a", "s a", ConstraintType.TECHNICAL, null), null));
        assertTrue(service.list(WS, null).isEmpty());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        add(WS, "a", "s a", ConstraintType.TECHNICAL);
        add(WS, "b", "s b", ConstraintType.BUSINESS);

        List<Constraint> all = service.list(WS, null);

        assertEquals(2, all.size());
        assertEquals(new ConstraintCode("TCON-1"), all.get(0).code());
        assertEquals(new ConstraintCode("BCON-1"), all.get(1).code());
    }

    @Test
    void listIsEmptyForAProjectWithNoConstraints() {
        assertTrue(service.list(WS, null).isEmpty());
    }

    @Test
    void getReturnsPersistedConstraint() {
        ConstraintCode code = add(WS, "a", "s a", ConstraintType.TECHNICAL).code();

        Constraint found = service.get(WS, code, null).orElseThrow();

        assertEquals(code, found.code());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new ConstraintCode("TCON-99"), null).isPresent());
    }

    @Test
    void resolveExistingReturnsAnEmptyListForNoIds() {
        assertTrue(service.resolveExisting(WS).isEmpty());
    }

    @Test
    void resolveExistingResolvesAPersistedConstraintsIdToItsCode() {
        Constraint added = add(WS, "a", "s a", ConstraintType.TECHNICAL);

        List<ResolvedConstraint> resolved = service.resolveExisting(WS, added.id().value());

        assertEquals(1, resolved.size());
        assertEquals(added.code(), resolved.get(0).code());
    }

    // --- constraint_update (issue #313) ---------------------------------------

    @Test
    void updateCorrectsTitleAndStatement() {
        ConstraintCode code = add(WS, "typo", "Must run on the JMV.", ConstraintType.TECHNICAL).code();

        Constraint updated = service.update(WS, code, "JVM only", "Must run on the JVM.", "de", null);

        assertEquals("JVM only", updated.title());
        assertEquals("Must run on the JVM.", updated.statement());
    }

    @Test
    void updateLeavesAnOmittedFieldUnchanged() {
        ConstraintCode code = add(WS, "JVM only", "Must run on the JMV.", ConstraintType.TECHNICAL).code();

        Constraint updated = service.update(WS, code, null, "Must run on the JVM.", "de", null);

        assertEquals("JVM only", updated.title());
        assertEquals("Must run on the JVM.", updated.statement());
    }

    @Test
    void updateNeverChangesIdentityCodeOrType() {
        Constraint added = add(WS, "JVM only", "Must run on the JVM.", ConstraintType.TECHNICAL);

        Constraint updated = service.update(WS, added.code(), "JVM required", null, "de", null);

        assertEquals(added.id(), updated.id());
        assertEquals(added.code(), updated.code());
        assertEquals(added.type(), updated.type());
    }

    @Test
    void updateRejectsAnUnknownCode() {
        assertThrows(ConstraintNotFoundException.class,
                () -> service.update(WS, new ConstraintCode("TCON-99"), "x", null, "de", null));
    }

    /**
     * The two-call shape issue #313 exists for: {@code constraint_add} under one tag, then
     * {@code constraint_update} under a second - each call carrying exactly one language.
     */
    @Test
    void updateUnderASecondLanguageRetagsTheTouchedFields() {
        ConstraintCode code = add(WS, "Nur JVM", "Muss auf der JVM laufen.", ConstraintType.TECHNICAL).code();

        service.update(WS, code, "JVM only", "Must run on the JVM.", "en", null);

        assertEquals("en", currentOf(code).titleLanguage());
        assertEquals("en", currentOf(code).statementLanguage());
    }

    /** A field this call does not name keeps the exact tag it was read under - never a retag. */
    @Test
    void updateLeavesAnUntouchedFieldsLanguageAlone() {
        ConstraintCode code = add(WS, "Nur JVM", "Muss auf der JVM laufen.", ConstraintType.TECHNICAL).code();

        service.update(WS, code, "JVM only", null, "en", null);

        assertEquals("en", currentOf(code).titleLanguage());
        assertEquals("de", currentOf(code).statementLanguage());
    }

    /**
     * Issue #271's rule, inherited here: naming a field and resending its already-current text
     * with no {@code language} is a no-op, so it never demands a {@code defaultLanguage} the
     * project may not have.
     */
    @Test
    void updateResendingCurrentTextWithoutALanguageIsANoOp() {
        Constraint added = add(WS, "JVM only", "Must run on the JVM.", ConstraintType.TECHNICAL);

        Constraint result = service.update(WS, added.code(), "JVM only", "Must run on the JVM.", null, null);

        assertEquals(added, result);
        assertEquals("de", currentOf(added.code()).titleLanguage());
    }

    /**
     * ... but the same call <em>with</em> an explicit, different language is a genuine write: it
     * adds that language variant even though the {@link Constraint} value is unchanged.
     */
    @Test
    void updateResendingCurrentTextUnderAnExplicitLanguageStillWrites() {
        ConstraintCode code = add(WS, "JVM only", "Must run on the JVM.", ConstraintType.TECHNICAL).code();

        service.update(WS, code, "JVM only", "Must run on the JVM.", "en", null);

        assertEquals("en", currentOf(code).titleLanguage());
    }

    @Test
    void updateFallsBackToTheProjectDefaultLanguage() {
        ConstraintCode code = add(WS, "Nur JVM", "Muss auf der JVM laufen.", ConstraintType.TECHNICAL).code();

        service.update(WS, code, "JVM only", null, null, "en");

        assertEquals("en", currentOf(code).titleLanguage());
    }

    @Test
    void updateRejectsAChangedFieldWithNeitherAnExplicitNorADefaultLanguage() {
        ConstraintCode code = add(WS, "Nur JVM", "Muss auf der JVM laufen.", ConstraintType.TECHNICAL).code();

        assertThrows(MissingDefaultLanguageException.class,
                () -> service.update(WS, code, "JVM only", null, null, null));
    }

    /** A call that changes neither text nor tag returns the stored value without writing. */
    @Test
    void updateWithNothingToChangeIsANoOp() {
        Constraint added = add(WS, "JVM only", "Must run on the JVM.", ConstraintType.TECHNICAL);
        ConstraintRepository.CurrentConstraint before = currentOf(added.code());

        Constraint result = service.update(WS, added.code(), null, null, null, null);

        assertEquals(added, result);
        assertEquals(before.head(), currentOf(added.code()).head());
    }

    private ConstraintRepository.CurrentConstraint currentOf(ConstraintCode code) {
        return repository.findCurrentByCode(WS, code).orElseThrow();
    }

    private static final class FakeResourceIdFactory implements ResourceIdFactory {

        private final AtomicInteger counter = new AtomicInteger();

        @Override
        public ResourceId newId() {
            return ResourceId.of("https://w3id.org/arknet/id/fake-" + counter.incrementAndGet());
        }
    }
}
