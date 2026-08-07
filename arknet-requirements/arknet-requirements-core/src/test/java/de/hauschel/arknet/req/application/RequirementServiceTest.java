// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.MissingDefaultLanguageException;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RequirementSchemaSource;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.AcceptanceCriterionTextPatch;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintNotFoundException;
import de.hauschel.arknet.req.domain.ConstraintRef;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.MissingAcceptanceCriteriaException;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementSchemaTerm;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Policy tests for {@link RequirementService}: identity minting, code assignment, listing,
 * lookup, status-transition and term-linking rules, exercised against an in-memory fake
 * repository and a deterministic fake {@link ResourceIdFactory}.
 */
class RequirementServiceTest {

    private static final ProjectId WS = new ProjectId("test-project");
    /**
     * A project default language for tests that do not themselves exercise issue #258's
     * language-resolution policy - passed explicitly so a {@code null} {@code language} argument
     * in a fixture (e.g. {@link #newFunctionalRequirement()}) still resolves instead of throwing.
     */
    private static final String DEFAULT_LANGUAGE = "en";
    private static final ResourceId TERM_1 =
            ResourceId.of("https://w3id.org/arknet/id/term-1");
    private static final ResourceId TERM_2 =
            ResourceId.of("https://w3id.org/arknet/id/term-2");

    private InMemoryRequirementRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private InMemoryTermLookup termLookup;
    private InMemoryConstraintRepository constraintRepository;
    private FakeRequirementSchemaSource schemaSource;
    private RequirementService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryRequirementRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        termLookup = new InMemoryTermLookup();
        termLookup.register("TERM-1", TERM_1);
        termLookup.register("TERM-2", TERM_2);
        constraintRepository = new InMemoryConstraintRepository();
        schemaSource = new FakeRequirementSchemaSource();
        service = new RequirementService(repository, resourceIdFactory, termLookup, constraintRepository, schemaSource);
    }

    private Constraint givenConstraint(String title, String statement, ConstraintType type) {
        ConstraintService constraintService = new ConstraintService(constraintRepository, resourceIdFactory);
        return constraintService.add(WS, new NewConstraint(title, statement, type, DEFAULT_LANGUAGE), null);
    }

    @Test
    void addAssignsFirstFunctionalCode() {
        Requirement added = service.add(WS, new NewRequirement("User can log in",
                "The system shall let a registered user authenticate.", RequirementType.FUNCTIONAL,
                null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE);

        assertEquals(new RequirementCode("FR-1"), added.code());
    }

    @Test
    void addSetsProposedStatusByDefault() {
        Requirement added = service.add(WS, new NewRequirement("User can log in",
                "The system shall let a registered user authenticate.", RequirementType.FUNCTIONAL,
                null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE);

        assertEquals(RequirementStatus.PROPOSED, added.status());
    }

    /**
     * Every field {@code add} was given comes back unchanged on the returned {@link Requirement}
     * and is what a subsequent read from the repository sees too - one fact (faithful roundtrip
     * of the supplied fields), asserted from both ends.
     */
    @Test
    void addPersistsAllSuppliedFields() {
        Requirement added = service.add(WS, new NewRequirement("User can log in",
                "The system shall let a registered user authenticate.", RequirementType.FUNCTIONAL,
                null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE);

        assertEquals("User can log in", added.title());
        assertEquals("The system shall let a registered user authenticate.", added.description());
        assertEquals(RequirementType.FUNCTIONAL, added.type());
        assertEquals(List.of(new AcceptanceCriterion(1, "Done when it works")), added.acceptanceCriteria());
        assertEquals(added, repository.findByCode(WS, added.code(), null).orElseThrow());
    }

    /**
     * Issue #258, decision 2: a write without an explicit {@code language} falls back to the
     * target project's configured {@code defaultLanguage} instead of writing an untagged literal.
     */
    @Test
    void addWithoutLanguageFallsBackToTheProjectsDefaultLanguage() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), "de").code();

        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.titleLanguage());
        assertEquals("de", current.descriptionLanguage());
    }

    /**
     * Issue #258, decision 1: a write without an explicit {@code language}, targeting a project
     * with no configured default either, is rejected instead of silently writing an untagged
     * literal - and nothing is persisted.
     */
    @Test
    void addWithoutLanguageAndWithoutAProjectDefaultIsRejected() {
        assertThrows(MissingDefaultLanguageException.class,
                () -> service.add(WS, newFunctionalRequirement(), null));

        assertEquals(List.of(), service.list(WS, null));
    }

    /** Mirrors {@link #addWithoutLanguageFallsBackToTheProjectsDefaultLanguage}, for {@code update}. */
    @Test
    void updateWithoutLanguageFallsBackToTheProjectsDefaultLanguage() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        service.update(WS, code, "New title", null, null, null, null, null, "de");

        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.titleLanguage());
    }

    /** Mirrors {@link #addWithoutLanguageAndWithoutAProjectDefaultIsRejected}, for {@code update}. */
    @Test
    void updateWithoutLanguageAndWithoutAProjectDefaultIsRejected() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        assertThrows(MissingDefaultLanguageException.class,
                () -> service.update(WS, code, "New title", null, null, null, null, null, null));

        assertEquals("User can log in", service.get(WS, code, null).orElseThrow().title());
    }

    /**
     * A field named by the caller ({@code title != null}) but resent with its own
     * already-current text, no {@code language} argument, and no project default must still be a
     * genuine no-op - naming a field alone (as opposed to actually changing it) must not force a
     * write-language resolution the project cannot satisfy. Complements {@link
     * #updateWithoutLanguageAndWithoutAProjectDefaultIsRejected}, which covers the same missing-
     * language/-default combination for an actually-changed title.
     */
    @Test
    void updateResendingUnchangedTitleWithoutLanguageOrDefaultIsATrueNoOpAndDoesNotWrite() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        RequirementRepository.CurrentRequirement before = repository.findCurrentByCode(WS, code).orElseThrow();

        Requirement updated = service.update(WS, code, "User can log in", null, null, null, null, null, null);

        RequirementRepository.CurrentRequirement after = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals(before.head(), after.head());
        assertEquals("User can log in", updated.title());
    }

    /** Mirrors {@link #updateResendingUnchangedTitleWithoutLanguageOrDefaultIsATrueNoOpAndDoesNotWrite}, for an acceptance-criterion patch. */
    @Test
    void updateResendingUnchangedAcceptanceCriterionTextWithoutLanguageOrDefaultIsATrueNoOpAndDoesNotWrite() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        RequirementRepository.CurrentRequirement before = repository.findCurrentByCode(WS, code).orElseThrow();

        service.update(WS, code, null, null, null,
                List.of(new AcceptanceCriterionTextPatch(1, "Done when it works")), null, null, null);

        RequirementRepository.CurrentRequirement after = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals(before.head(), after.head());
    }

    /**
     * Regression for issue #271: a caller writing {@code title} under a language it does not yet
     * carry must actually retag it, even when the supplied text is byte-for-byte identical to
     * what is already stored - text equality alone is not "no change" once a language is
     * explicitly named, since the whole point of the call is to tag (and let the out-adapter
     * sweep) an untagged/mis-tagged literal.
     */
    @Test
    void updateWithSameTitleTextButANewLanguageStillWritesUnderThatLanguage() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        service.update(WS, code, "User can log in", null, null, null, null, "de", null);

        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.titleLanguage());
    }

    /**
     * The flip side of {@link #updateWithSameTitleTextButANewLanguageStillWritesUnderThatLanguage}:
     * once text AND language both already match what is stored, the call is a genuine no-op and
     * must not reach the repository at all - the revision token proves no write happened.
     */
    @Test
    void updateWithIdenticalTitleTextAndLanguageIsATrueNoOpAndDoesNotWrite() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        RequirementRepository.CurrentRequirement before = repository.findCurrentByCode(WS, code).orElseThrow();

        service.update(WS, code, "User can log in", null, null, null, null, DEFAULT_LANGUAGE, null);

        RequirementRepository.CurrentRequirement after = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals(before.head(), after.head());
    }

    /** Mirrors {@link #updateWithSameTitleTextButANewLanguageStillWritesUnderThatLanguage}, for an acceptance-criterion patch. */
    @Test
    void updateAcceptanceCriteriaPatchWithSameTextButANewLanguageStillWritesUnderThatLanguage() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        service.update(WS, code, null, null, null,
                List.of(new AcceptanceCriterionTextPatch(1, "Done when it works")), null, "de", null);

        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.acceptanceCriteriaLanguageByPosition().get(1));
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        Requirement first = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE);
        Requirement second = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE);

        assertNotEquals(first.id(), second.id());
        assertEquals(2, resourceIdFactory.mintedCount());
    }

    @Test
    void addAssignsNfrPrefixForNonFunctional() {
        Requirement added = service.add(WS, new NewRequirement("Page loads < 200ms",
                "95% of page loads shall complete in under 200ms.", RequirementType.NON_FUNCTIONAL,
                null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE);

        assertEquals(new RequirementCode("NFR-1"), added.code());
    }

    @Test
    void addCarriesPriorityMotivatedByAndQualityCategoryThrough() {
        Requirement added = service.add(WS, new NewRequirement("Page loads < 200ms",
                "95% of page loads shall complete in under 200ms.", RequirementType.NON_FUNCTIONAL,
                Priority.MUST_HAVE, "https://w3id.org/arknet/model/goal/fast-ux", "performance", List.of("Done when it works"), null), DEFAULT_LANGUAGE);

        assertEquals(Priority.MUST_HAVE, added.priority());
        assertEquals("https://w3id.org/arknet/model/goal/fast-ux", added.motivatedBy());
        assertEquals("performance", added.qualityCategory());
        assertEquals(added, repository.findByCode(WS, added.code(), null).orElseThrow());
    }

    @Test
    void addNumbersRunPerTypeIndependently() {
        RequirementCode fr1 = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();
        RequirementCode nfr1 = service.add(WS,
                new NewRequirement("b", "desc b", RequirementType.NON_FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();
        RequirementCode fr2 = service.add(WS,
                new NewRequirement("c", "desc c", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();

        assertEquals(new RequirementCode("FR-1"), fr1);
        assertEquals(new RequirementCode("NFR-1"), nfr1);
        assertEquals(new RequirementCode("FR-2"), fr2);
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE);

        Requirement inOther = service.add(other,
                new NewRequirement("b", "desc b", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE);

        assertEquals(new RequirementCode("FR-1"), inOther.code());
        assertTrue(service.list(other, null).stream().allMatch(r -> r.title().equals("b")));
        assertEquals(1, service.list(WS, null).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE);
        service.add(WS, new NewRequirement("b", "desc b", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE);

        List<Requirement> all = service.list(WS, null);

        assertEquals(2, all.size());
        assertEquals("a", all.get(0).title());
        assertEquals("b", all.get(1).title());
    }

    @Test
    void getReturnsPersistedRequirement() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();

        assertTrue(service.get(WS, code, null).isPresent());
        assertEquals("a", service.get(WS, code, null).orElseThrow().title());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new RequirementCode("FR-99"), null).isPresent());
    }

    @Test
    void acceptTransitionsProposedToAccepted() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();

        Requirement accepted = service.accept(WS, code);

        assertEquals(RequirementStatus.ACCEPTED, accepted.status());
        assertEquals("desc a", accepted.description());
        assertEquals(RequirementStatus.ACCEPTED, repository.findByCode(WS, code, null).orElseThrow().status());
    }

    @Test
    void acceptPreservesPriorityMotivatedByAndQualityCategory() {
        RequirementCode code = service.add(WS, new NewRequirement("a", "desc a", RequirementType.NON_FUNCTIONAL,
                Priority.COULD_HAVE, "https://w3id.org/arknet/model/goal/g", "security", List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();

        Requirement accepted = service.accept(WS, code);

        assertEquals(Priority.COULD_HAVE, accepted.priority());
        assertEquals("https://w3id.org/arknet/model/goal/g", accepted.motivatedBy());
        assertEquals("security", accepted.qualityCategory());
    }

    @Test
    void acceptIsIdempotentWhenAlreadyAccepted() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();
        service.accept(WS, code);

        Requirement result = service.accept(WS, code);

        assertEquals(RequirementStatus.ACCEPTED, result.status());
    }

    @Test
    void acceptThrowsWhenRequirementUnknown() {
        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.accept(WS, new RequirementCode("FR-42")));

        assertSame(WS, ex.projectId());
        assertEquals(new RequirementCode("FR-42"), ex.requirementCode());
    }

    /**
     * Issue #291 / ADR-019 point 4: the reverse transition. Before this fix, an accepted
     * requirement could never be reset - the status was a one-way freeze rather than the
     * unbinding maturity signal ADR-019 requires.
     */
    @Test
    void proposeTransitionsAcceptedToProposed() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();
        service.accept(WS, code);

        Requirement proposed = service.propose(WS, code);

        assertEquals(RequirementStatus.PROPOSED, proposed.status());
        assertEquals("desc a", proposed.description());
        assertEquals(RequirementStatus.PROPOSED, repository.findByCode(WS, code, null).orElseThrow().status());
    }

    @Test
    void proposeIsIdempotentWhenAlreadyProposed() {
        RequirementCode code = service.add(WS,
                new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();

        Requirement result = service.propose(WS, code);

        assertEquals(RequirementStatus.PROPOSED, result.status());
    }

    @Test
    void proposeThrowsWhenRequirementUnknown() {
        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.propose(WS, new RequirementCode("FR-42")));

        assertSame(WS, ex.projectId());
        assertEquals(new RequirementCode("FR-42"), ex.requirementCode());
    }

    /**
     * Same replace-by-identity regression as {@link #acceptPreservesLinkedTerms}/
     * {@link #acceptPreservesLinkedConstraints}/{@link #acceptPreservesAcceptanceCriteria}, for
     * the reverse transition.
     */
    @Test
    void proposePreservesLinkedTermsConstraintsAndAcceptanceCriteria() {
        Constraint constraint = givenConstraint("EU data residency", "Personal data must stay in the EU.",
                ConstraintType.REGULATORY);
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        service.linkConstraint(WS, code, constraint.code().value());
        service.linkTerm(WS, code, "TERM-1");
        service.accept(WS, code);

        Requirement proposed = service.propose(WS, code);

        assertEquals(RequirementStatus.PROPOSED, proposed.status());
        assertEquals(List.of(new ConstraintRef(constraint.id().value())), proposed.constrainedBy());
        assertEquals(List.of(new TermRef(TERM_1)), proposed.usesTerms());
        assertEquals(List.of(new AcceptanceCriterion(1, "Done when it works")), proposed.acceptanceCriteria());
    }

    @Test
    void updateChangesTitleDescriptionAndAppendsANewAcceptanceCriterion() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        Requirement updated = service.update(WS, code, "New title", "New description",
                List.of("New done-when criterion"), null, null, null, DEFAULT_LANGUAGE);

        assertEquals("New title", updated.title());
        assertEquals("New description", updated.description());
        assertEquals(List.of(new AcceptanceCriterion(1, "Done when it works"),
                new AcceptanceCriterion(2, "New done-when criterion")), updated.acceptanceCriteria());
        assertEquals(updated, service.get(WS, code, null).orElseThrow());
    }

    /**
     * {@code req_update} only appends or in-place patches acceptance criteria (issue #266) - it
     * cannot restate/replace the whole list wholesale the way {@code title}/{@code description}
     * can be corrected. Correcting an existing criterion's wording goes through
     * {@code acceptanceCriteriaTextPatches} instead.
     */
    @Test
    void updateCorrectsAnExistingAcceptanceCriterionByPosition() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        Requirement updated = service.update(WS, code, null, null, null,
                List.of(new AcceptanceCriterionTextPatch(1, "Corrected done-when criterion")), null, null,
                DEFAULT_LANGUAGE);

        assertEquals(List.of(new AcceptanceCriterion(1, "Corrected done-when criterion")),
                updated.acceptanceCriteria());
        assertEquals(updated, service.get(WS, code, null).orElseThrow());
    }

    @Test
    void updateAcceptanceCriteriaTextPatchRejectsAnUnknownPosition() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        assertThrows(de.hauschel.arknet.req.domain.AcceptanceCriterionPositionNotFoundException.class,
                () -> service.update(WS, code, null, null, null,
                        List.of(new AcceptanceCriterionTextPatch(9, "no such criterion")), null, null,
                        DEFAULT_LANGUAGE));
    }

    @Test
    void updateWithNullFieldsLeavesThemUnchanged() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        Requirement updated =
                service.update(WS, code, null, "New description", null, null, null, null, DEFAULT_LANGUAGE);

        assertEquals("User can log in", updated.title());
        assertEquals("New description", updated.description());
        assertEquals(List.of(new AcceptanceCriterion(1, "Done when it works")), updated.acceptanceCriteria());
    }

    @Test
    void updateWithAllNullFieldsIsANoOp() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        Requirement before = service.get(WS, code, null).orElseThrow();

        Requirement result = service.update(WS, code, null, null, null, null, null, null, DEFAULT_LANGUAGE);

        assertEquals(before, result);
    }

    @Test
    void updatePreservesStatusLinkedTermsAndOtherFields() {
        RequirementCode code = service.add(WS, new NewRequirement("a", "desc a", RequirementType.NON_FUNCTIONAL,
                Priority.COULD_HAVE, "https://w3id.org/arknet/model/goal/g", "security",
                List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();
        service.accept(WS, code);
        service.linkTerm(WS, code, "TERM-1");

        Requirement updated =
                service.update(WS, code, "New title", null, null, null, null, null, DEFAULT_LANGUAGE);

        assertEquals(RequirementStatus.ACCEPTED, updated.status());
        assertEquals(List.of(new TermRef(TERM_1)), updated.usesTerms());
        assertEquals(Priority.COULD_HAVE, updated.priority());
        assertEquals("https://w3id.org/arknet/model/goal/g", updated.motivatedBy());
        assertEquals("security", updated.qualityCategory());
    }

    /**
     * The concrete case - a register audited as uniformly {@code MUST_HAVE} is
     * corrected down to {@code SHOULD_HAVE}, keeping the requirement's code and thus every
     * {@code usesTerm}/{@code realises} reference into it intact.
     */
    @Test
    void updateChangesThePriorityWithoutTouchingAnyOtherField() {
        RequirementCode code = service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL,
                Priority.MUST_HAVE, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();

        Requirement updated =
                service.update(WS, code, null, null, null, null, Priority.SHOULD_HAVE, null, DEFAULT_LANGUAGE);

        assertEquals(Priority.SHOULD_HAVE, updated.priority());
        assertEquals("a", updated.title());
        assertEquals("desc a", updated.description());
        assertEquals(List.of(new AcceptanceCriterion(1, "Done when it works")), updated.acceptanceCriteria());
        assertEquals(updated, service.get(WS, code, null).orElseThrow());
    }

    /** A requirement added without a priority can have one set after the fact. */
    @Test
    void updateCanSetAPriorityThatWasNeverSet() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        assertNull(service.get(WS, code, null).orElseThrow().priority());

        Requirement updated =
                service.update(WS, code, null, null, null, null, Priority.COULD_HAVE, null, DEFAULT_LANGUAGE);

        assertEquals(Priority.COULD_HAVE, updated.priority());
    }

    /**
     * {@code null} means "unchanged", not "remove": correcting only the title must not silently
     * strip an already-set priority (removing one is deliberately out of this port's scope).
     */
    @Test
    void updateWithANullPriorityDoesNotClearAnAlreadySetOne() {
        RequirementCode code = service.add(WS, new NewRequirement("a", "desc a", RequirementType.FUNCTIONAL,
                Priority.MUST_HAVE, null, null, List.of("Done when it works"), null), DEFAULT_LANGUAGE).code();

        Requirement updated =
                service.update(WS, code, "New title", null, null, null, null, null, DEFAULT_LANGUAGE);

        assertEquals(Priority.MUST_HAVE, updated.priority());
    }

    @Test
    void updateRejectsABlankTitleViaTheDomainInvariant() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        assertThrows(IllegalArgumentException.class,
                () -> service.update(WS, code, " ", null, null, null, null, null, DEFAULT_LANGUAGE));
    }

    @Test
    void updateThrowsWhenRequirementUnknown() {
        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.update(
                        WS, new RequirementCode("FR-42"), "New title", null, null, null, null, null, DEFAULT_LANGUAGE));

        assertSame(WS, ex.projectId());
        assertEquals(new RequirementCode("FR-42"), ex.requirementCode());
    }

    @Test
    void addStartsWithoutLinkedTerms() {
        Requirement added = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE);

        assertEquals(List.of(), added.usesTerms());
    }

    @Test
    void linkTermAddsTheTermToTheRequirement() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        Requirement linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1)), linked.usesTerms());
        assertEquals(List.of(new TermRef(TERM_1)), service.get(WS, code, null).orElseThrow().usesTerms());
    }

    @Test
    void linkTermAppendsToAlreadyLinkedTerms() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        service.linkTerm(WS, code, "TERM-1");

        Requirement linked = service.linkTerm(WS, code, "TERM-2");

        assertEquals(List.of(new TermRef(TERM_1), new TermRef(TERM_2)), linked.usesTerms());
    }

    @Test
    void linkingTheSameTermTwiceIsANoOp() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        service.linkTerm(WS, code, "TERM-1");

        Requirement linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1)), linked.usesTerms());
    }

    @Test
    void linkTermThrowsWhenRequirementUnknown() {
        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.linkTerm(WS, new RequirementCode("FR-42"), "TERM-1"));

        assertSame(WS, ex.projectId());
        assertEquals(new RequirementCode("FR-42"), ex.requirementCode());
    }

    /**
     * Resolution of the human-typed term code happens here, via {@link InMemoryTermLookup}
     * - not in the out-adapter's write path any more. A lookup failure must
     * propagate unchanged and leave the requirement untouched.
     */
    @Test
    void linkTermPropagatesTheLookupFailureForAnUnknownTermCodeAndLinksNothing() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        assertThrows(NoSuchElementException.class, () -> service.linkTerm(WS, code, "TERM-99"));

        assertEquals(List.of(), service.get(WS, code, null).orElseThrow().usesTerms());
    }

    @Test
    void addStartsWithoutLinkedConstraints() {
        Requirement added = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE);

        assertEquals(List.of(), added.constrainedBy());
    }

    @Test
    void linkConstraintAddsTheConstraintToTheRequirement() {
        Constraint constraint = givenConstraint("EU data residency", "Personal data must stay in the EU.",
                ConstraintType.REGULATORY);
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        Requirement linked = service.linkConstraint(WS, code, constraint.code().value());

        assertEquals(List.of(new ConstraintRef(constraint.id().value())), linked.constrainedBy());
        assertEquals(List.of(new ConstraintRef(constraint.id().value())),
                service.get(WS, code, null).orElseThrow().constrainedBy());
    }

    @Test
    void linkingTheSameConstraintTwiceIsANoOp() {
        Constraint constraint = givenConstraint("EU data residency", "Personal data must stay in the EU.",
                ConstraintType.REGULATORY);
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        service.linkConstraint(WS, code, constraint.code().value());

        Requirement linked = service.linkConstraint(WS, code, constraint.code().value());

        assertEquals(List.of(new ConstraintRef(constraint.id().value())), linked.constrainedBy());
    }

    @Test
    void linkConstraintThrowsWhenRequirementUnknown() {
        Constraint constraint = givenConstraint("EU data residency", "Personal data must stay in the EU.",
                ConstraintType.REGULATORY);

        RequirementNotFoundException ex = assertThrows(RequirementNotFoundException.class,
                () -> service.linkConstraint(WS, new RequirementCode("FR-42"), constraint.code().value()));

        assertSame(WS, ex.projectId());
        assertEquals(new RequirementCode("FR-42"), ex.requirementCode());
    }

    /**
     * Resolution of the human-typed constraint code happens here, via
     * {@link InMemoryConstraintRepository} - a lookup failure must propagate unchanged and leave
     * the requirement untouched.
     */
    @Test
    void linkConstraintThrowsWhenConstraintCodeUnknownAndLinksNothing() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        assertThrows(ConstraintNotFoundException.class, () -> service.linkConstraint(WS, code, "TCON-99"));

        assertEquals(List.of(), service.get(WS, code, null).orElseThrow().constrainedBy());
    }

    /**
     * Regression guard for the replace-by-identity write path: the out-adapter persists a
     * requirement by wiping and re-writing its triples, so a status change must carry the
     * linked constraints along rather than silently dropping them.
     */
    @Test
    void acceptPreservesLinkedConstraints() {
        Constraint constraint = givenConstraint("EU data residency", "Personal data must stay in the EU.",
                ConstraintType.REGULATORY);
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        service.linkConstraint(WS, code, constraint.code().value());

        Requirement accepted = service.accept(WS, code);

        assertEquals(List.of(new ConstraintRef(constraint.id().value())), accepted.constrainedBy());
    }

    /**
     * Regression guard for the replace-by-identity write path: the out-adapter persists a
     * requirement by wiping and re-writing its triples, so a status change must carry the
     * linked terms along rather than silently dropping them.
     */
    @Test
    void acceptPreservesLinkedTerms() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();
        service.linkTerm(WS, code, "TERM-1");

        Requirement accepted = service.accept(WS, code);

        assertEquals(RequirementStatus.ACCEPTED, accepted.status());
        assertEquals(List.of(new TermRef(TERM_1)), accepted.usesTerms());
        assertEquals(List.of(new TermRef(TERM_1)), service.get(WS, code, null).orElseThrow().usesTerms());
    }

    /**
     * Same regression as {@link #acceptPreservesLinkedTerms}, for the mandatory
     * acceptance criteria: accepting a requirement must not drop them either.
     */
    @Test
    void acceptPreservesAcceptanceCriteria() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        Requirement accepted = service.accept(WS, code);

        assertEquals(List.of(new AcceptanceCriterion(1, "Done when it works")), accepted.acceptanceCriteria());
        assertEquals(List.of(new AcceptanceCriterion(1, "Done when it works")),
                service.get(WS, code, null).orElseThrow().acceptanceCriteria());
    }

    /**
     * Same regression, exercised via {@code linkTerm}'s own replace-by-identity write.
     */
    @Test
    void linkTermPreservesAcceptanceCriteria() {
        RequirementCode code = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE).code();

        Requirement linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new AcceptanceCriterion(1, "Done when it works")), linked.acceptanceCriteria());
    }

    /**
     * Regression for issue #157: a requirement that predates the mandatory acceptance-criterion
     * invariant reads back with the read-time legacy placeholder standing in for its
     * {@code acceptanceCriteria} (see {@link
     * de.hauschel.arknet.req.application.port.out.RequirementRepository.CurrentRequirement#acceptanceCriteriaIsSynthesized()}).
     * {@code accept} never supplies a replacement, so writing it through must reject instead of
     * turning that placeholder into a real, persisted literal - and must leave the requirement's
     * status untouched, exactly as if the write had never been attempted.
     */
    @Test
    void acceptRejectsALegacyRequirementInsteadOfPersistingThePlaceholder() {
        RequirementCode code = givenLegacyRequirement();

        MissingAcceptanceCriteriaException ex = assertThrows(MissingAcceptanceCriteriaException.class,
                () -> service.accept(WS, code));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.requirementCode());
        assertEquals(RequirementStatus.PROPOSED, service.get(WS, code, null).orElseThrow().status());
    }

    /**
     * Same regression as {@link #acceptRejectsALegacyRequirementInsteadOfPersistingThePlaceholder},
     * for the reverse transition: a legacy requirement accepted store-first (bypassing the
     * mandatory acceptance-criterion invariant) must not silently persist the read-time
     * placeholder when reset back to {@code PROPOSED} either.
     */
    @Test
    void proposeRejectsALegacyRequirementInsteadOfPersistingThePlaceholder() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement legacyAccepted = new Requirement(
                new RequirementId(resourceIdFactory.newId()), code, "legacy title",
                "A requirement predating the acceptance-criterion invariant.", RequirementType.FUNCTIONAL,
                RequirementStatus.ACCEPTED, null, null, null, List.of(),
                List.of(new AcceptanceCriterion(1, "(legacy placeholder - no acceptance criterion on record)")),
                List.of());
        repository.createLegacy(WS, legacyAccepted);

        MissingAcceptanceCriteriaException ex = assertThrows(MissingAcceptanceCriteriaException.class,
                () -> service.propose(WS, code));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.requirementCode());
        assertEquals(RequirementStatus.ACCEPTED, service.get(WS, code, null).orElseThrow().status());
    }

    /** Same regression as {@link #acceptRejectsALegacyRequirementInsteadOfPersistingThePlaceholder}, via {@code linkTerm}. */
    @Test
    void linkTermRejectsALegacyRequirementInsteadOfPersistingThePlaceholder() {
        RequirementCode code = givenLegacyRequirement();

        MissingAcceptanceCriteriaException ex = assertThrows(MissingAcceptanceCriteriaException.class,
                () -> service.linkTerm(WS, code, "TERM-1"));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.requirementCode());
        assertEquals(List.of(), service.get(WS, code, null).orElseThrow().usesTerms());
    }

    /**
     * Same regression as {@link #acceptRejectsALegacyRequirementInsteadOfPersistingThePlaceholder},
     * via {@code update} - but only when the caller leaves {@code acceptanceCriteria} {@code null}
     * ("unchanged"), the same argument that used to carry the placeholder straight into the store.
     */
    @Test
    void updateRejectsALegacyRequirementWhenAcceptanceCriteriaAreLeftUnchanged() {
        RequirementCode code = givenLegacyRequirement();

        MissingAcceptanceCriteriaException ex = assertThrows(MissingAcceptanceCriteriaException.class,
                () -> service.update(WS, code, "New title", null, null, null, null, null, DEFAULT_LANGUAGE));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.requirementCode());
        assertEquals("legacy title", service.get(WS, code, null).orElseThrow().title());
    }

    /**
     * Same regression as {@link #updateRejectsALegacyRequirementWhenAcceptanceCriteriaAreLeftUnchanged},
     * but with no {@code defaultLanguage} at all: the acceptance-criteria guard must still take
     * precedence over {@link de.hauschel.arknet.kernel.MissingDefaultLanguageException}, which
     * {@code title}'s own language resolution would otherwise throw first.
     */
    @Test
    void updateRejectsALegacyRequirementBeforeComplainingAboutAMissingDefaultLanguage() {
        RequirementCode code = givenLegacyRequirement();

        MissingAcceptanceCriteriaException ex = assertThrows(MissingAcceptanceCriteriaException.class,
                () -> service.update(WS, code, "New title", null, null, null, null, null, null));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.requirementCode());
        assertEquals("legacy title", service.get(WS, code, null).orElseThrow().title());
    }

    /**
     * The escape hatch: a caller closing the gap by patching the placeholder's own position (the
     * only way {@code update} can touch an already-existing criterion, issue #266) with real text
     * must succeed - the guard only blocks a write that would carry the placeholder forward
     * unchanged, not one that explicitly overwrites it.
     */
    @Test
    void updateAcceptsALegacyRequirementWhenThePlaceholderPositionIsPatchedWithRealText() {
        RequirementCode code = givenLegacyRequirement();

        Requirement updated = service.update(WS, code, null, null, null,
                List.of(new AcceptanceCriterionTextPatch(1, "Real done-when criterion")), null, null,
                DEFAULT_LANGUAGE);

        assertEquals(List.of(new AcceptanceCriterion(1, "Real done-when criterion")), updated.acceptanceCriteria());
        assertEquals(List.of(new AcceptanceCriterion(1, "Real done-when criterion")),
                service.get(WS, code, null).orElseThrow().acceptanceCriteria());
    }

    /**
     * Merely appending a new criterion after the placeholder does not close the gap: the
     * placeholder itself would still be persisted at position 1, exactly the state the guard
     * exists to prevent.
     */
    @Test
    void updateStillRejectsALegacyRequirementWhenOnlyAppendingWithoutPatchingThePlaceholder() {
        RequirementCode code = givenLegacyRequirement();

        MissingAcceptanceCriteriaException ex = assertThrows(MissingAcceptanceCriteriaException.class,
                () -> service.update(WS, code, null, null, List.of("A new, additional criterion"), null, null, null,
                        DEFAULT_LANGUAGE));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.requirementCode());
    }

    /**
     * Once real acceptance criteria have been supplied, the requirement is no longer legacy: a
     * subsequent {@code accept} (which supplies no replacement of its own) must succeed instead of
     * throwing again.
     */
    @Test
    void acceptSucceedsOnceALegacyRequirementsAcceptanceCriteriaHaveBeenSupplied() {
        RequirementCode code = givenLegacyRequirement();
        service.update(WS, code, null, null, null,
                List.of(new AcceptanceCriterionTextPatch(1, "Real done-when criterion")), null, null,
                DEFAULT_LANGUAGE);

        Requirement accepted = service.accept(WS, code);

        assertEquals(RequirementStatus.ACCEPTED, accepted.status());
        assertEquals(List.of(new AcceptanceCriterion(1, "Real done-when criterion")), accepted.acceptanceCriteria());
    }

    /**
     * Stores a requirement the way {@code repository.createLegacy} models a pre-invariant
     * requirement: some non-blank text stands in for {@code acceptanceCriteria} ({@link
     * Requirement}'s constructor rejects an empty list unconditionally, so the domain object
     * itself can never represent "no criteria at all"), but {@link
     * InMemoryRequirementRepository#createLegacy} marks the identity so {@link
     * RequirementRepository.CurrentRequirement#acceptanceCriteriaIsSynthesized()} reports
     * {@code true} for it - mirroring the real adapter's structural signal.
     */
    private RequirementCode givenLegacyRequirement() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement legacy = new Requirement(
                new RequirementId(resourceIdFactory.newId()), code, "legacy title",
                "A requirement predating the acceptance-criterion invariant.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(),
                List.of(new AcceptanceCriterion(1, "(legacy placeholder - no acceptance criterion on record)")),
                List.of());
        repository.createLegacy(WS, legacy);
        return code;
    }

    /**
     * A sibling bounded context's driving adapter resolves opaque requirement
     * identities back to their identity and business code (e.g. to render {@code FR-N} for
     * display) - in one batch, not per-id.
     */
    @Test
    void resolveExistingResolvesKnownIdentitiesInOneBatch() {
        Requirement first = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE);
        Requirement second = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE);

        List<ResolveRequirements.ResolvedRequirement> resolved =
                service.resolveExisting(WS, first.id().value(), second.id().value());

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(new ResolveRequirements.ResolvedRequirement(first.id().value(), first.code())));
        assertTrue(
                resolved.contains(new ResolveRequirements.ResolvedRequirement(second.id().value(), second.code())));
    }

    /**
     * The port never rejects an unresolvable id - it simply omits it from the result, so the
     * caller (not this port) decides what "missing" means for its own display.
     */
    @Test
    void resolveExistingSilentlyOmitsUnknownIdentities() {
        Requirement known = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE);
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/does-not-exist");

        List<ResolveRequirements.ResolvedRequirement> resolved =
                service.resolveExisting(WS, known.id().value(), unknown);

        assertEquals(List.of(new ResolveRequirements.ResolvedRequirement(known.id().value(), known.code())),
                resolved);
    }

    @Test
    void resolveExistingWithNoIdsReturnsAnEmptyList() {
        assertEquals(List.of(), service.resolveExisting(WS));
    }

    @Test
    void resolveExistingIsScopedPerProject() {
        Requirement inWs = service.add(WS, newFunctionalRequirement(), DEFAULT_LANGUAGE);
        ProjectId other = new ProjectId("other");

        assertEquals(List.of(), service.resolveExisting(other, inWs.id().value()));
    }

    /**
     * {@code schema()} is pure delegation to the {@link RequirementSchemaSource}
     * driven port - the service adds no policy of its own, only the seam between the driving
     * and driven port.
     */
    @Test
    void schemaDelegatesToTheSchemaSource() {
        List<RequirementSchemaTerm> result = service.schema();

        assertEquals(schemaSource.terms(), result);
        assertEquals(1, schemaSource.callCount());
    }

    private static NewRequirement newFunctionalRequirement() {
        return new NewRequirement("User can log in", "The system shall let a registered user authenticate.",
                RequirementType.FUNCTIONAL, null, null, null, List.of("Done when it works"), null);
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

    /**
     * Fake {@link RequirementSchemaSource}: hands back a fixed, canned list of terms and counts
     * its own invocations, so the test can pin "schema() is exactly one delegating call" without
     * a mocking framework.
     */
    private static final class FakeRequirementSchemaSource implements RequirementSchemaSource {

        private final List<RequirementSchemaTerm> terms =
                List.of(new RequirementSchemaTerm("Priority", "Priorisierung nach MoSCoW.",
                        List.of("MUST_HAVE", "SHOULD_HAVE", "COULD_HAVE", "WONT_HAVE")));
        private int calls;

        @Override
        public List<RequirementSchemaTerm> schema() {
            calls++;
            return terms;
        }

        List<RequirementSchemaTerm> terms() {
            return terms;
        }

        int callCount() {
            return calls;
        }
    }
}
