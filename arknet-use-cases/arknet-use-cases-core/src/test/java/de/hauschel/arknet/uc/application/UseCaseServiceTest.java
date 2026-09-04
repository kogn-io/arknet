// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application;

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

import de.hauschel.arknet.kernel.MissingDefaultLanguageException;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase.StepRealisesPatch;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase.UseCaseCorrection;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.ConstraintRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.StepPositionNotFoundException;
import de.hauschel.arknet.uc.domain.StepTextPatch;
import de.hauschel.arknet.uc.domain.TermRef;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseNotFoundException;

/**
 * Policy tests for {@link UseCaseService}: opaque identity minting, code assignment, listing,
 * lookup and reference resolution, exercised against an in-memory fake repository, deterministic
 * fake {@link ResourceIdFactory} and fake {@code ActorLookup}/{@code RequirementLookup}.
 */
class UseCaseServiceTest {

    private static final ProjectId WS = new ProjectId("test-project");
    /**
     * A project default language for tests that do not themselves exercise issue #258's
     * language-resolution policy - passed explicitly so a {@code null} {@code language} argument
     * in a fixture (e.g. {@link #newUseCase(String)}) still resolves instead of throwing.
     */
    private static final String DEFAULT_LANGUAGE = "en";

    private static final ResourceId CUSTOMER_ID = ResourceId.of("https://w3id.org/arknet/id/actor-customer");
    private static final ResourceId PAYMENT_PROVIDER_ID =
            ResourceId.of("https://w3id.org/arknet/id/actor-payment-provider");
    private static final ResourceId WAREHOUSE_ID = ResourceId.of("https://w3id.org/arknet/id/actor-warehouse");
    private static final ResourceId FR5_ID = ResourceId.of("https://w3id.org/arknet/id/req-fr5");
    private static final ResourceId TERM_1_ID = ResourceId.of("https://w3id.org/arknet/id/term-1");
    private static final ResourceId TERM_2_ID = ResourceId.of("https://w3id.org/arknet/id/term-2");
    private static final ResourceId TCON_1_ID = ResourceId.of("https://w3id.org/arknet/id/constraint-1");

    private InMemoryUseCaseRepository repository;
    private FakeResourceIdFactory resourceIdFactory;
    private InMemoryRequirementLookup requirementLookup;
    private InMemoryActorLookup actorLookup;
    private InMemoryTermLookup termLookup;
    private InMemoryConstraintLookup constraintLookup;
    private UseCaseService service;

    @BeforeEach
    void setUp() {
        repository = new InMemoryUseCaseRepository();
        resourceIdFactory = new FakeResourceIdFactory();
        requirementLookup = new InMemoryRequirementLookup();
        actorLookup = new InMemoryActorLookup();
        termLookup = new InMemoryTermLookup();
        constraintLookup = new InMemoryConstraintLookup();
        actorLookup.register("Customer", CUSTOMER_ID);
        actorLookup.register("PaymentProvider", PAYMENT_PROVIDER_ID);
        actorLookup.register("Warehouse", WAREHOUSE_ID);
        requirementLookup.register("FR5", FR5_ID);
        termLookup.register("TERM-1", TERM_1_ID);
        termLookup.register("TERM-2", TERM_2_ID);
        constraintLookup.register("TCON-1", TCON_1_ID);
        service = new UseCaseService(
                repository, resourceIdFactory, requirementLookup, actorLookup, termLookup, constraintLookup);
    }

    private static NewUseCase newUseCase(String title) {
        return new NewUseCase(title, "goal of " + title, null, null, "Customer",
                List.of(), null, null, List.of(new NewStep(1, "do something", List.of())), List.of(), null);
    }

    @Test
    void addAssignsFirstCode() {
        UseCase added = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE);

        assertEquals(new UseCaseCode("UC1"), added.code());
        assertEquals("Place order", added.title());
        assertEquals(added, repository.findByCode(WS, added.code(), null).orElseThrow());
    }

    /**
     * Issue #258, decision 2: a write without an explicit {@code language} falls back to the
     * target project's configured {@code defaultLanguage} instead of writing an untagged literal.
     */
    @Test
    void addWithoutLanguageFallsBackToTheProjectsDefaultLanguage() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), "de").code();

        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.titleLanguage());
        assertEquals("de", current.goalLanguage());
    }

    /**
     * Issue #258, decision 1: a write without an explicit {@code language}, targeting a project
     * with no configured default either, is rejected instead of silently writing an untagged
     * literal - and nothing is persisted.
     */
    @Test
    void addWithoutLanguageAndWithoutAProjectDefaultIsRejected() {
        assertThrows(MissingDefaultLanguageException.class, () -> service.add(WS, newUseCase("Place order"), null));

        assertEquals(List.of(), service.list(WS, null));
    }

    /** Mirrors {@link #addWithoutLanguageFallsBackToTheProjectsDefaultLanguage}, for {@code update}. */
    @Test
    void updateWithoutLanguageFallsBackToTheProjectsDefaultLanguage() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        service.update(WS, code, UseCaseCorrection.builder().title("New title").build(), "de");

        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.titleLanguage());
    }

    /** Mirrors {@link #addWithoutLanguageAndWithoutAProjectDefaultIsRejected}, for {@code update}. */
    @Test
    void updateWithoutLanguageAndWithoutAProjectDefaultIsRejected() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        assertThrows(MissingDefaultLanguageException.class, () -> service.update(WS, code, UseCaseCorrection.builder()
                .title("New title")
                .build(), null));

        assertEquals("Place order", service.get(WS, code, null).orElseThrow().title());
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
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        service.update(WS, code, UseCaseCorrection.builder().title("Place order").language("de").build(), null);

        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.titleLanguage());
    }

    /**
     * The flip side of {@link #updateWithSameTitleTextButANewLanguageStillWritesUnderThatLanguage}:
     * once text AND language both already match what is stored, the call is a genuine no-op and
     * must not reach the repository at all - the revision token proves no write happened.
     */
    @Test
    void updateWithIdenticalTitleTextAndLanguageIsATrueNoOpAndDoesNotWrite() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        UseCaseRepository.CurrentUseCase before = repository.findCurrentByCode(WS, code).orElseThrow();

        service.update(WS, code, UseCaseCorrection.builder()
                .title("Place order")
                .language(DEFAULT_LANGUAGE)
                .build(), null);

        UseCaseRepository.CurrentUseCase after = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals(before.head(), after.head());
    }

    /** Mirrors {@link #updateWithSameTitleTextButANewLanguageStillWritesUnderThatLanguage}, for a step-text patch. */
    @Test
    void updateStepTextPatchWithSameTextButANewLanguageStillWritesUnderThatLanguage() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        service.update(WS, code, UseCaseCorrection.builder()
                .stepTextPatches(List.of(new StepTextPatch(1, "do something")))
                .language("de")
                .build(), null);

        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.stepTextLanguageByPosition().get(1));
    }

    /** Mirrors {@link #updateWithSameTitleTextButANewLanguageStillWritesUnderThatLanguage}, for extensions. */
    @Test
    void updateExtensionsWithSameTextButANewLanguageStillWritesUnderThatLanguage() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        service.update(WS, code, UseCaseCorrection.builder()
                .extensions(List.of("Payment declined"))
                .language(DEFAULT_LANGUAGE)
                .build(), null);

        service.update(WS, code, UseCaseCorrection.builder()
                .extensions(List.of("Payment declined"))
                .language("de")
                .build(), null);

        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals("de", current.extensionTextLanguageByPosition().get(1));
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
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        UseCaseRepository.CurrentUseCase before = repository.findCurrentByCode(WS, code).orElseThrow();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder().title("Place order").build(), null);

        UseCaseRepository.CurrentUseCase after = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals(before.head(), after.head());
        assertEquals("Place order", updated.title());
    }

    /**
     * Mirrors {@link #updateResendingUnchangedTitleWithoutLanguageOrDefaultIsATrueNoOpAndDoesNotWrite}, for a
     * step-text patch.
     */
    @Test
    void updateResendingUnchangedStepTextWithoutLanguageOrDefaultIsATrueNoOpAndDoesNotWrite() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        UseCaseRepository.CurrentUseCase before = repository.findCurrentByCode(WS, code).orElseThrow();

        service.update(WS, code, UseCaseCorrection.builder()
                .stepTextPatches(List.of(new StepTextPatch(1, "do something")))
                .build(), null);

        UseCaseRepository.CurrentUseCase after = repository.findCurrentByCode(WS, code).orElseThrow();
        assertEquals(before.head(), after.head());
    }

    @Test
    void addMintsAFreshOpaqueIdentityViaTheFactory() {
        UseCase first = service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE);
        UseCase second = service.add(WS, newUseCase("b"), DEFAULT_LANGUAGE);

        assertNotEquals(first.id(), second.id());
        assertEquals(2, resourceIdFactory.mintedCount());
    }

    @Test
    void addResolvesActorAndRequirementReferencesViaTheLookupPorts() {
        NewUseCase command = new NewUseCase("Place order", "Customer places an order", "Webshop",
                "Customer opens the cart", "Customer",
                List.of("PaymentProvider"), "Customer is logged in", "Order is recorded",
                List.of(new NewStep(1, "select items", List.of("FR5")),
                        new NewStep(2, "confirm", List.of())),
                List.of("2a. Payment declined -> abort"), null);

        UseCase added = service.add(WS, command, DEFAULT_LANGUAGE);

        assertEquals("Webshop", added.scope());
        assertEquals("Customer opens the cart", added.trigger());
        assertEquals(new ActorRef(CUSTOMER_ID), added.primaryActor());
        assertEquals(List.of(new ActorRef(PAYMENT_PROVIDER_ID)), added.supportingActors());
        assertEquals("Customer is logged in", added.precondition());
        assertEquals("Order is recorded", added.postcondition());
        assertEquals(2, added.steps().size());
        assertEquals(List.of(new RequirementRef(FR5_ID)), added.steps().get(0).realises());
        assertEquals(List.of("2a. Payment declined -> abort"), added.extensions());
    }

    @Test
    void addPropagatesAnUnknownActorReferenceFromTheLookupPort() {
        NewUseCase command = new NewUseCase("Broken", "goal", null, null, "Unknown",
                List.of(), null, null, List.of(new NewStep(1, "do something", List.of())), List.of(), null);

        assertThrows(NoSuchElementException.class, () -> service.add(WS, command, DEFAULT_LANGUAGE));
        assertTrue(service.list(WS, null).isEmpty());
    }

    @Test
    void addPropagatesAnUnknownRequirementReferenceFromTheLookupPort() {
        NewUseCase command = new NewUseCase("Broken", "goal", null, null, "Customer",
                List.of(), null, null,
                List.of(new NewStep(1, "do something", List.of("FR-UNKNOWN"))), List.of(), null);

        assertThrows(NoSuchElementException.class, () -> service.add(WS, command, DEFAULT_LANGUAGE));
        assertTrue(service.list(WS, null).isEmpty());
    }

    @Test
    void addNumbersRunningPerProject() {
        assertEquals(new UseCaseCode("UC1"), service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE).code());
        assertEquals(new UseCaseCode("UC2"), service.add(WS, newUseCase("b"), DEFAULT_LANGUAGE).code());
        assertEquals(new UseCaseCode("UC3"), service.add(WS, newUseCase("c"), DEFAULT_LANGUAGE).code());
    }

    /**
     * Mutation-tests {@code nextCode}'s reliance on {@link UseCaseRepository#findAllCodes} rather
     * than {@link UseCaseRepository#findAll} (kogn-io/arknet#360): turn {@code nextCode} back to
     * deriving its maximum from {@code findAll} and this goes red - the seeded {@code UC2} holds
     * the project's highest number but is invisible to {@code findAll}, exactly as the out-adapter's
     * skip of a store-first use case without a title/goal literal or with an empty main
     * flow makes it, so {@code add} would recompute {@code UC2} and collide with a code that is
     * still very much assigned.
     */
    @Test
    void addSkipsOverACodeThatIsAssignedButNotCurrentlyMaterialisable() {
        service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE);
        repository.seedUnmaterialisableCode(WS, new UseCaseCode("UC2"));

        UseCase third = service.add(WS, newUseCase("c"), DEFAULT_LANGUAGE);

        assertEquals(new UseCaseCode("UC3"), third.code());
    }

    @Test
    void addIsScopedPerProject() {
        ProjectId other = new ProjectId("other");
        service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE);

        UseCase inOther = service.add(other, newUseCase("b"), DEFAULT_LANGUAGE);

        assertEquals(new UseCaseCode("UC1"), inOther.code());
        assertEquals(1, service.list(other, null).size());
        assertEquals(1, service.list(WS, null).size());
    }

    @Test
    void listReturnsAllInInsertionOrder() {
        service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE);
        service.add(WS, newUseCase("b"), DEFAULT_LANGUAGE);

        List<UseCase> all = service.list(WS, null);

        assertEquals(2, all.size());
        assertEquals("a", all.get(0).title());
        assertEquals("b", all.get(1).title());
    }

    @Test
    void getReturnsPersistedUseCase() {
        UseCaseCode code = service.add(WS, newUseCase("a"), DEFAULT_LANGUAGE).code();

        assertTrue(service.get(WS, code, null).isPresent());
        assertEquals("a", service.get(WS, code, null).orElseThrow().title());
    }

    @Test
    void getIsEmptyForUnknownCode() {
        assertFalse(service.get(WS, new UseCaseCode("UC99"), null).isPresent());
    }

    @Test
    void addGetListRoundtrip() {
        UseCase added = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE);

        UseCase fetched = service.get(WS, added.code(), null).orElseThrow();

        assertEquals(added, fetched);
        assertTrue(service.list(WS, null).contains(added));
    }

    @Test
    void updateChangesGoalLevelFields() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .title("New title")
                .goal("New goal")
                .scope("New scope")
                .trigger("New trigger")
                .precondition("New precondition")
                .postcondition("New postcondition")
                .build(), DEFAULT_LANGUAGE);

        assertEquals("New title", updated.title());
        assertEquals("New goal", updated.goal());
        assertEquals("New scope", updated.scope());
        assertEquals("New trigger", updated.trigger());
        assertEquals("New precondition", updated.precondition());
        assertEquals("New postcondition", updated.postcondition());
        assertEquals(updated, service.get(WS, code, null).orElseThrow());
    }

    @Test
    void updateWithNullFieldsLeavesThemUnchanged() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .goal("New goal")
                .build(), DEFAULT_LANGUAGE);

        assertEquals("Place order", updated.title());
        assertEquals("New goal", updated.goal());
    }

    @Test
    void updateWithEverythingOmittedIsANoOp() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        UseCase before = service.get(WS, code, null).orElseThrow();

        UseCase result = service.update(WS, code, UseCaseCorrection.builder().build(), DEFAULT_LANGUAGE);

        assertEquals(before, result);
    }

    /**
     * Issue #343: a use case's primary actor is correctable in place, resolved through the very
     * same {@code ActorLookup} {@link UseCaseService#add} resolves against - without the
     * delete-and-recreate round trip that would mint a new {@link UseCaseCode} and break every
     * inbound reference to the use case.
     */
    @Test
    void updateReplacesThePrimaryActor() {
        UseCase added = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE);

        UseCase updated = service.update(WS, added.code(), UseCaseCorrection.builder()
                .primaryActor("PaymentProvider")
                .build(), DEFAULT_LANGUAGE);

        assertEquals(new ActorRef(PAYMENT_PROVIDER_ID), updated.primaryActor());
        assertEquals(added.id(), updated.id());
        assertEquals(added.code(), updated.code());
        assertEquals(updated, service.get(WS, added.code(), null).orElseThrow());
    }

    /**
     * The counterpart of {@link #updateReplacesThePrimaryActor}: neither actor argument given
     * leaves both references exactly as they were - the tri-state's "leave it" arm (issue #343).
     */
    @Test
    void updateWithoutActorArgumentsLeavesBothReferencesUnchanged() {
        UseCaseCode code = service.add(WS, useCaseWithSupportingActor(), DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .title("New title")
                .build(), DEFAULT_LANGUAGE);

        assertEquals(new ActorRef(CUSTOMER_ID), updated.primaryActor());
        assertEquals(List.of(new ActorRef(PAYMENT_PROVIDER_ID)), updated.supportingActors());
    }

    /**
     * {@code supportingActors} is a wholesale replace, not a merge (issue #343) - mirroring
     * {@code extensions} and a step's {@code realises} set.
     */
    @Test
    void updateReplacesSupportingActorsWholesale() {
        UseCaseCode code = service.add(WS, useCaseWithSupportingActor(), DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .supportingActors(List.of("Warehouse"))
                .build(), DEFAULT_LANGUAGE);

        assertEquals(List.of(new ActorRef(WAREHOUSE_ID)), updated.supportingActors());
    }

    /**
     * The third arm of {@code supportingActors}' tri-state (issue #343): an empty list is the
     * explicit, unambiguous clear, distinct from omitting the argument altogether. Unlike
     * {@code primaryActor}, which carries {@code sh:minCount 1} and therefore has no clear at
     * all, supporting actors may legally drop to none.
     */
    @Test
    void updateWithAnEmptySupportingActorListClearsThem() {
        UseCaseCode code = service.add(WS, useCaseWithSupportingActor(), DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .supportingActors(List.of())
                .build(), DEFAULT_LANGUAGE);

        assertEquals(List.of(), updated.supportingActors());
    }

    /**
     * An unresolvable actor name is rejected before anything is written, exactly as in
     * {@link UseCaseService#add} - so a correction naming a typo'd actor leaves the use case
     * untouched rather than half-applying the fields it could resolve (issue #343).
     */
    @Test
    void updateWithAnUnknownPrimaryActorIsRejectedAndWritesNothing() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        UseCase before = service.get(WS, code, null).orElseThrow();

        assertThrows(NoSuchElementException.class, () -> service.update(WS, code, UseCaseCorrection.builder()
                .title("New title")
                .primaryActor("Ghost")
                .build(), DEFAULT_LANGUAGE));

        assertEquals(before, service.get(WS, code, null).orElseThrow());
    }

    /** Mirrors {@link #updateWithAnUnknownPrimaryActorIsRejectedAndWritesNothing} for a supporting actor. */
    @Test
    void updateWithAnUnknownSupportingActorIsRejectedAndWritesNothing() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        UseCase before = service.get(WS, code, null).orElseThrow();

        assertThrows(NoSuchElementException.class, () -> service.update(WS, code, UseCaseCorrection.builder()
                .title("New title")
                .supportingActors(List.of("Customer", "Ghost"))
                .build(), DEFAULT_LANGUAGE));

        assertEquals(before, service.get(WS, code, null).orElseThrow());
    }

    /**
     * Issue #343: neither {@code primaryActor} nor {@code supportingActors} carries a
     * language-tagged literal, so an actor-only correction feeds no touched signal into
     * {@code updateWithOptimisticRetry} and therefore never resolves a write language - the call
     * goes through even when the caller supplies neither {@code language} nor
     * {@code defaultLanguage}. This is exactly what lets the actor migration land in a project
     * with no configured default language at all; complements {@link
     * #updateResendingUnchangedTitleWithoutLanguageOrDefaultIsATrueNoOpAndDoesNotWrite}, whose
     * title field is language-tagged and therefore does need one once actually changed.
     */
    @Test
    void updateWithOnlyActorFieldsAndNeitherLanguageNorDefaultLanguageGoesThrough() {
        UseCaseCode code = service.add(WS, useCaseWithSupportingActor(), DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .primaryActor("PaymentProvider")
                .supportingActors(List.of("Warehouse"))
                .build(), null);

        assertEquals(new ActorRef(PAYMENT_PROVIDER_ID), updated.primaryActor());
        assertEquals(List.of(new ActorRef(WAREHOUSE_ID)), updated.supportingActors());
    }

    /** A use case that starts out with one supporting actor, for the actor-correction tests above. */
    private static NewUseCase useCaseWithSupportingActor() {
        return new NewUseCase("Place order", "goal of Place order", null, null, "Customer",
                List.of("PaymentProvider"), null, null, List.of(new NewStep(1, "do something", List.of())),
                List.of(), null);
    }

    @Test
    void updateReplacesExtensionsWholesale() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .extensions(List.of("2a. Payment declined -> abort"))
                .build(), DEFAULT_LANGUAGE);

        assertEquals(List.of("2a. Payment declined -> abort"), updated.extensions());
    }

    /**
     * {@code stableExtensionPrefixLength} (passed on to
     * {@link UseCaseRepository#compareAndUpdate}, see its own javadoc) must cover every position
     * for an update that only edits content in place - here, translating the single position whose
     * content changed. The real out-adapter relies on this to know a prior other-language variant
     * at that position is still safe to preserve; the real out-adapter's {@code
     * KognioRdfUseCaseRepositoryMultilingualTest
     * #compareAndUpdateWithAnInsertedExtensionDoesNotMisattachAPriorPositionsOtherLanguageVariant}
     * covers the store-level consequence, this covers the service's own computation of the value.
     */
    @Test
    void updateThatOnlyTranslatesTheTrailingExtensionIsNotFlaggedAsRestructured() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        service.update(WS, code, UseCaseCorrection.builder()
                .extensions(List.of("2a. A", "3a. B"))
                .build(), DEFAULT_LANGUAGE);

        service.update(WS, code, UseCaseCorrection.builder()
                .extensions(List.of("2a. A", "3a. B (de)"))
                .language("de")
                .build(), DEFAULT_LANGUAGE);

        assertEquals(2, repository.lastStableExtensionPrefixLength());
    }

    /**
     * Counterpart to {@link #updateThatOnlyTranslatesTheTrailingExtensionIsNotFlaggedAsRestructured}:
     * inserting a new extension ahead of an existing position shifts everything after it, so more
     * than one position diverges once the longest common leading prefix is factored out - the value
     * must come out as exactly that prefix's length (1: only position 1, "2a. A", still matches),
     * telling the out-adapter position-based preservation is unsafe beyond it for this call.
     */
    @Test
    void updateThatInsertsAnExtensionIsFlaggedAsRestructured() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        service.update(WS, code, UseCaseCorrection.builder()
                .extensions(List.of("2a. A", "3a. B"))
                .build(), DEFAULT_LANGUAGE);

        service.update(WS, code, UseCaseCorrection.builder()
                .extensions(List.of("2a. A", "2b. New", "3a. B"))
                .build(), DEFAULT_LANGUAGE);

        assertEquals(1, repository.lastStableExtensionPrefixLength());
    }

    /**
     * Review finding on issue #254 (PR #267): a same-length extensions replace that edits a
     * middle position must not starve a later, untouched position's stability just because a
     * leading-prefix scan stopped at the first mismatch. Three extensions, all English; call 1
     * translates only the trailing position (a plain in-place edit); call 2 then translates only
     * the middle position, leaving the trailing position - already carrying its own German
     * variant from call 1 - completely untouched. The trailing position must still come out
     * stable, or the real out-adapter would silently drop its call-1 translation.
     */
    @Test
    void updateThatTranslatesAMiddleExtensionLeavesATrailingExtensionStable() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        service.update(WS, code, UseCaseCorrection.builder()
                .extensions(List.of("2a. A", "3a. B", "4a. C"))
                .build(), DEFAULT_LANGUAGE);
        service.update(WS, code, UseCaseCorrection.builder()
                .extensions(List.of("2a. A", "3a. B", "4a. C (de)"))
                .language("de")
                .build(), DEFAULT_LANGUAGE);

        service.update(WS, code, UseCaseCorrection.builder()
                .extensions(List.of("2a. A", "3a. B (de)", "4a. C (de)"))
                .language("de")
                .build(), DEFAULT_LANGUAGE);

        assertEquals(3, repository.lastStableExtensionPrefixLength());
    }

    @Test
    void updatePreservesPrimaryActorSupportingActorsAndSteps() {
        NewUseCase command = new NewUseCase("Place order", "Customer places an order", "Webshop",
                "Customer opens the cart", "Customer", List.of("PaymentProvider"),
                "Customer is logged in", "Order is recorded",
                List.of(new NewStep(1, "select items", List.of("FR5"))), List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();
        UseCase before = service.get(WS, code, null).orElseThrow();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .title("New title")
                .build(), DEFAULT_LANGUAGE);

        assertEquals(before.primaryActor(), updated.primaryActor());
        assertEquals(before.supportingActors(), updated.supportingActors());
        assertEquals(before.steps(), updated.steps());
    }

    @Test
    void updateThrowsWhenUseCaseUnknown() {
        UseCaseNotFoundException ex = assertThrows(UseCaseNotFoundException.class,
                () -> service.update(WS, new UseCaseCode("UC99"), UseCaseCorrection.builder()
                        .title("New title")
                        .build(), DEFAULT_LANGUAGE));

        assertSame(WS, ex.projectId());
        assertEquals(new UseCaseCode("UC99"), ex.useCaseCode());
    }

    @Test
    void updateCorrectsAnExistingStepsTextWithoutTouchingItsRealisesOrOtherSteps() {
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null,
                List.of(new NewStep(1, "select items", List.of("FR5")),
                        new NewStep(2, "confirm", List.of())),
                List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .stepTextPatches(List.of(new StepTextPatch(1, "select the desired items")))
                .build(), DEFAULT_LANGUAGE);

        assertEquals("select the desired items", updated.steps().get(0).text());
        assertEquals(List.of(new RequirementRef(FR5_ID)), updated.steps().get(0).realises());
        assertEquals("confirm", updated.steps().get(1).text());
    }

    @Test
    void updateCanPatchSeveralStepsAtOnce() {
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null,
                List.of(new NewStep(1, "select items", List.of()),
                        new NewStep(2, "confirm", List.of())),
                List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .stepTextPatches(List.of(new StepTextPatch(1, "select the desired items"),
                        new StepTextPatch(2, "confirm and pay")))
                .build(), DEFAULT_LANGUAGE);

        assertEquals("select the desired items", updated.steps().get(0).text());
        assertEquals("confirm and pay", updated.steps().get(1).text());
    }

    @Test
    void updateRejectsAStepTextPatchForAnUnknownPosition() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        StepPositionNotFoundException ex = assertThrows(StepPositionNotFoundException.class,
                () -> service.update(WS, code, UseCaseCorrection.builder()
                        .stepTextPatches(List.of(new StepTextPatch(99, "does not exist")))
                        .build(), DEFAULT_LANGUAGE));

        assertSame(WS, ex.projectId());
        assertEquals(code, ex.useCaseCode());
        assertEquals(99, ex.position());
    }

    @Test
    void stepTextPatchRejectsNullTextInsteadOfSilentlyIgnoringThePosition() {
        assertThrows(NullPointerException.class, () -> new StepTextPatch(1, null));
    }

    @Test
    void stepTextPatchRejectsBlankText() {
        assertThrows(IllegalArgumentException.class, () -> new StepTextPatch(1, "  "));
    }

    @Test
    void updateWithAnUnknownStepPositionPatchLeavesTheUseCaseUntouched() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        UseCase before = service.get(WS, code, null).orElseThrow();

        assertThrows(StepPositionNotFoundException.class,
                () -> service.update(WS, code, UseCaseCorrection.builder()
                        .title("attempted title change")
                        .stepTextPatches(List.of(new StepTextPatch(99, "does not exist")))
                        .build(), DEFAULT_LANGUAGE));

        assertEquals(before, service.get(WS, code, null).orElseThrow());
    }

    @Test
    void updateWithStepRealisesPatchesResolvesEachCodeAndReplacesTheNamedStepsRealises() {
        ResourceId fr7Id = ResourceId.of("https://w3id.org/arknet/id/req-fr7");
        requirementLookup.register("FR7", fr7Id);
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null,
                List.of(new NewStep(1, "select items", List.of("FR5")),
                        new NewStep(2, "confirm", List.of())),
                List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .stepRealisesPatches(List.of(new StepRealisesPatch(1, List.of("FR7"))))
                .build(), DEFAULT_LANGUAGE);

        assertEquals("select items", updated.steps().get(0).text());
        assertEquals(List.of(new RequirementRef(fr7Id)), updated.steps().get(0).realises());
        assertEquals("confirm", updated.steps().get(1).text());
        assertEquals(List.of(), updated.steps().get(1).realises());
    }

    @Test
    void updateWithStepRealisesPatchesPropagatesAnUnknownRequirementReferenceFromTheLookupPort() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        assertThrows(NoSuchElementException.class,
                () -> service.update(WS, code, UseCaseCorrection.builder()
                        .stepRealisesPatches(List.of(new StepRealisesPatch(1, List.of("FR-UNKNOWN"))))
                        .build(), DEFAULT_LANGUAGE));
    }

    @Test
    void updateWithStepRealisesPatchesClearsAnExistingRealisesSetWhenGivenAnEmptyList() {
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null, List.of(new NewStep(1, "select items", List.of("FR5"))), List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .stepRealisesPatches(List.of(new StepRealisesPatch(1, List.of())))
                .build(), DEFAULT_LANGUAGE);

        assertTrue(updated.steps().get(0).realises().isEmpty());
    }

    @Test
    void updateAppliesStepTextPatchesAndStepRealisesPatchesIndependently() {
        ResourceId fr7Id = ResourceId.of("https://w3id.org/arknet/id/req-fr7");
        requirementLookup.register("FR7", fr7Id);
        NewUseCase command = new NewUseCase("Place order", "goal", null, null, "Customer", List.of(),
                null, null,
                List.of(new NewStep(1, "select items", List.of("FR5")),
                        new NewStep(2, "confirm", List.of())),
                List.of(), null);
        UseCaseCode code = service.add(WS, command, DEFAULT_LANGUAGE).code();

        UseCase updated = service.update(WS, code, UseCaseCorrection.builder()
                .stepTextPatches(List.of(new StepTextPatch(2, "confirm and pay")))
                .stepRealisesPatches(List.of(new StepRealisesPatch(1, List.of("FR7"))))
                .build(), DEFAULT_LANGUAGE);

        assertEquals("select items", updated.steps().get(0).text());
        assertEquals(List.of(new RequirementRef(fr7Id)), updated.steps().get(0).realises());
        assertEquals("confirm and pay", updated.steps().get(1).text());
        assertEquals(List.of(), updated.steps().get(1).realises());
    }

    @Test
    void addStartsWithoutLinkedTermsOrConstraints() {
        UseCase added = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE);

        assertEquals(List.of(), added.usesTerms());
        assertEquals(List.of(), added.constrainedBy());
    }

    @Test
    void linkTermAddsTheTermToTheUseCase() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        UseCase linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1_ID)), linked.usesTerms());
        assertEquals(List.of(new TermRef(TERM_1_ID)), service.get(WS, code, null).orElseThrow().usesTerms());
    }

    @Test
    void linkTermAppendsToAlreadyLinkedTerms() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        service.linkTerm(WS, code, "TERM-1");

        UseCase linked = service.linkTerm(WS, code, "TERM-2");

        assertEquals(List.of(new TermRef(TERM_1_ID), new TermRef(TERM_2_ID)), linked.usesTerms());
    }

    @Test
    void linkingTheSameTermTwiceIsANoOp() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        service.linkTerm(WS, code, "TERM-1");

        UseCase linked = service.linkTerm(WS, code, "TERM-1");

        assertEquals(List.of(new TermRef(TERM_1_ID)), linked.usesTerms());
    }

    @Test
    void linkTermThrowsWhenUseCaseUnknown() {
        UseCaseNotFoundException ex = assertThrows(UseCaseNotFoundException.class,
                () -> service.linkTerm(WS, new UseCaseCode("UC99"), "TERM-1"));

        assertSame(WS, ex.projectId());
        assertEquals(new UseCaseCode("UC99"), ex.useCaseCode());
    }

    /**
     * Resolution of the human-typed term code happens here, via {@link InMemoryTermLookup} - a
     * lookup failure must propagate unchanged and leave the use case untouched.
     */
    @Test
    void linkTermPropagatesTheLookupFailureForAnUnknownTermCodeAndLinksNothing() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        assertThrows(NoSuchElementException.class, () -> service.linkTerm(WS, code, "TERM-99"));

        assertEquals(List.of(), service.get(WS, code, null).orElseThrow().usesTerms());
    }

    @Test
    void linkConstraintAddsTheConstraintToTheUseCase() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        UseCase linked = service.linkConstraint(WS, code, "TCON-1");

        assertEquals(List.of(new ConstraintRef(TCON_1_ID)), linked.constrainedBy());
        assertEquals(List.of(new ConstraintRef(TCON_1_ID)), service.get(WS, code, null).orElseThrow().constrainedBy());
    }

    @Test
    void linkingTheSameConstraintTwiceIsANoOp() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();
        service.linkConstraint(WS, code, "TCON-1");

        UseCase linked = service.linkConstraint(WS, code, "TCON-1");

        assertEquals(List.of(new ConstraintRef(TCON_1_ID)), linked.constrainedBy());
    }

    @Test
    void linkConstraintThrowsWhenUseCaseUnknown() {
        UseCaseNotFoundException ex = assertThrows(UseCaseNotFoundException.class,
                () -> service.linkConstraint(WS, new UseCaseCode("UC99"), "TCON-1"));

        assertSame(WS, ex.projectId());
        assertEquals(new UseCaseCode("UC99"), ex.useCaseCode());
    }

    /**
     * Resolution of the human-typed constraint code happens here, via
     * {@link InMemoryConstraintLookup} - a lookup failure must propagate unchanged and leave the
     * use case untouched.
     */
    @Test
    void linkConstraintPropagatesTheLookupFailureForAnUnknownConstraintCodeAndLinksNothing() {
        UseCaseCode code = service.add(WS, newUseCase("Place order"), DEFAULT_LANGUAGE).code();

        assertThrows(NoSuchElementException.class, () -> service.linkConstraint(WS, code, "TCON-99"));

        assertEquals(List.of(), service.get(WS, code, null).orElseThrow().constrainedBy());
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
