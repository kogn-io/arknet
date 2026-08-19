// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.uc.application.port.out.RevisionToken;
import de.hauschel.arknet.uc.application.port.out.UseCaseRepository;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;

/**
 * Integration tests for the multilingual {@code dcterms:title}/{@code arkreq:useCaseGoal}/
 * {@code arkreq:designScope}/{@code arkreq:trigger}/{@code arkreq:useCasePrecondition}/
 * {@code arkreq:useCasePostcondition}/{@code arkreq:stepText} (main-flow and extension steps)
 * behaviour of {@link KognioRdfUseCaseRepository}: language-scoped writes on
 * {@link UseCaseRepository#create}/{@link UseCaseRepository#compareAndUpdate}, and
 * {@link DisplayLocale}-selected reads on {@link UseCaseRepository#findByCode}. Mirrors
 * {@code KognioRdfRequirementRepositoryMultilingualTest}.
 *
 * <p>A step's own subject IRI is re-minted on every {@link UseCaseRepository#compareAndUpdate}
 * write (see {@link KognioRdfUseCaseRepository}'s class-level "opaque value object" note) - the
 * regression this class exists to pin is that an other-language {@code stepText} variant still
 * survives that re-minting, re-attached by <em>position</em> to the freshly-minted step, for both
 * a main-flow step and an extension step.</p>
 */
class KognioRdfUseCaseRepositoryMultilingualTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ActorRef CUSTOMER = new ActorRef(ResourceId.of("https://w3id.org/arknet/model/term/customer"));

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private UseCaseRepository repository;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        repository = KognioRdfUseCaseRepositoryFactory.over(
                lifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
        seedCustomerActor();
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private void seedCustomerActor() {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                        + "<https://w3id.org/arknet/model/term/customer> "
                        + "a <http://www.w3.org/2004/02/skos/core#Concept> , "
                        + "<https://w3id.org/arknet/process#HumanActor> ; "
                        + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Customer\" . } }");
                return null;
            });
        }
    }

    private static UseCaseId freshId() {
        return new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static UseCase useCase(UseCaseId id, UseCaseCode code, String title, String goal, String stepText) {
        return new UseCase(id, code, title, goal, null, null, CUSTOMER, List.of(), null, null,
                List.of(new Step(1, stepText, List.of())), List.of(), List.of(), List.of());
    }

    private static UseCase useCase(UseCaseId id, UseCaseCode code, String title, String goal, List<Step> steps) {
        return new UseCase(id, code, title, goal, null, null, CUSTOMER, List.of(), null, null, steps, List.of(), List.of(), List.of());
    }

    private RevisionToken currentHead(UseCaseCode code) {
        return repository.findCurrentByCode(PROJECT_A, code)
                .map(UseCaseRepository.CurrentUseCase::head)
                .orElse(null);
    }

    /** A use-case repository reading the shared store under an explicit display-language preference. */
    private UseCaseRepository readerFor(Locale requested, Locale systemDefault) {
        return KognioRdfUseCaseRepositoryFactory.over(
                lifecycle, new UuidResourceIdFactory(), new DisplayLocale(requested, systemDefault));
    }

    /**
     * {@code uc_list}'s own default-language resolution (issue #281): {@link
     * UseCaseRepository#findAll} must select a multilingual use case's title in the reader's
     * configured requested language, exactly as {@link UseCaseRepository#findByCode} already does -
     * mirrors {@code KognioRdfTermRepositoryTest#findAllPicksThePrefLabelInTheRequestedLanguage}.
     */
    @Test
    void findAllPicksTheTitleInTheRequestedLanguage() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCase created = useCase(freshId(), code, "Place order", "Order is placed", "Customer selects items");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);
        UseCase withGermanTitle = useCase(created.id(), code, "Bestellung aufgeben", "Order is placed",
                "Customer selects items");
        repository.compareAndUpdate(PROJECT_A, head, withGermanTitle, "de", "en", null, null, null, null,
                Map.of(1, "en"), Map.of(), null, Integer.MAX_VALUE);
        UseCaseRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        List<UseCase> all = germanReader.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertEquals("Bestellung aufgeben", all.get(0).title());
    }

    /**
     * {@code uc_list}'s own default-language resolution (issue #281): a per-call override wins
     * over the repository's own constructor-configured display language for {@link
     * UseCaseRepository#findAll} too - {@code UseCaseMcpTools#list} passes the calling project's
     * own configured default language as this argument, not an explicit tool argument (unlike
     * {@code uc_get}'s {@code displayLocale}), but this repository method itself does not
     * distinguish the two: whatever string it is handed simply overrides the configured {@code
     * requested} tier for this one call. Mirrors
     * {@code KognioRdfTermRepositoryTest#findAllDisplayLocaleArgumentOverridesTheConfiguredDefault}.
     */
    @Test
    void findAllDisplayLocaleArgumentOverridesTheConfiguredDefault() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCase created = useCase(freshId(), code, "Place order", "Order is placed", "Customer selects items");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);
        UseCase withGermanTitle = useCase(created.id(), code, "Bestellung aufgeben", "Order is placed",
                "Customer selects items");
        repository.compareAndUpdate(PROJECT_A, head, withGermanTitle, "de", "en", null, null, null, null,
                Map.of(1, "en"), Map.of(), null, Integer.MAX_VALUE);
        UseCaseRepository englishReader = readerFor(Locale.ENGLISH, Locale.ENGLISH);

        List<UseCase> all = englishReader.findAll(PROJECT_A, "de");

        assertEquals(1, all.size());
        assertEquals("Bestellung aufgeben", all.get(0).title());
    }

    @Test
    void createWritesTaggedTitleGoalAndStepTextSelectableViaDisplayLocale() {
        UseCaseCode code = new UseCaseCode("UC1");
        repository.create(PROJECT_A, useCase(freshId(), code, "Bestellung aufgeben", "Bestellung abschliessen",
                "Kunde waehlt Artikel"), "de");

        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Bestellung aufgeben", asGerman.title());
        assertEquals("Bestellung abschliessen", asGerman.goal());
        assertEquals("Kunde waehlt Artikel", asGerman.steps().get(0).text());
    }

    /**
     * The regression this issue exists to fix: a {@code compareAndUpdate} that corrects only
     * {@code title} under a new language must leave the previously-written language variant of
     * {@code title} completely intact - not delete it, not silently retag it. {@code goal} and the
     * step's text (untouched by this call) must survive under their original tag too.
     */
    @Test
    void compareAndUpdateWithANewLanguageForTitlePreservesEveryOtherFieldsLanguageVariant() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCase created = useCase(freshId(), code, "Place order", "Order is placed", "Customer selects items");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        UseCase withGermanTitle = useCase(created.id(), code, "Bestellung aufgeben", "Order is placed",
                "Customer selects items");
        repository.compareAndUpdate(PROJECT_A, head, withGermanTitle, "de", "en", null, null, null, null,
                Map.of(1, "en"), Map.of(), null, Integer.MAX_VALUE);

        UseCase asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Place order", asEnglish.title());
        assertEquals("Bestellung aufgeben", asGerman.title());
        assertEquals("Order is placed", asEnglish.goal());
        assertEquals("Order is placed", asGerman.goal());
        assertEquals("Customer selects items", asEnglish.steps().get(0).text());
        assertEquals("Customer selects items", asGerman.steps().get(0).text());
    }

    /**
     * The step-specific regression: a step's opaque subject is re-minted on every write, so its
     * other-language text variant must be re-attached to the freshly-minted subject at the same
     * position, not lost along with the deleted old step subject.
     */
    @Test
    void compareAndUpdateWithANewLanguageForStepTextPreservesTheOriginalLanguageVariant() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCase created = useCase(freshId(), code, "Place order", "Order is placed", "Customer selects items");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        UseCase withGermanStepText = useCase(created.id(), code, "Place order", "Order is placed",
                "Kunde waehlt Artikel");
        repository.compareAndUpdate(PROJECT_A, head, withGermanStepText, "en", "en", null, null, null, null,
                Map.of(1, "de"), Map.of(), null, Integer.MAX_VALUE);

        UseCase asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Customer selects items", asEnglish.steps().get(0).text());
        assertEquals("Kunde waehlt Artikel", asGerman.steps().get(0).text());
    }

    /**
     * Multi-step regression for the same step-text preservation behaviour, mirrors
     * {@link #compareAndUpdateWithANewLanguageForStepTextPreservesTheOriginalLanguageVariant} but
     * with three main-flow steps: only the middle step (position 2) is patched under a new
     * language, so {@link KognioRdfUseCaseRepository#otherLanguageStepTexts}'s position-keyed
     * re-attachment across the wholesale delete-and-replace has to pick out exactly that one
     * step's freshly-minted subject, not the other two's.
     */
    @Test
    void compareAndUpdateWithANewLanguageForOneStepPreservesTheOtherStepsOriginalLanguageVariant() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseId id = freshId();
        List<Step> original = List.of(
                new Step(1, "Customer selects items", List.of()),
                new Step(2, "Customer enters payment details", List.of()),
                new Step(3, "Customer confirms order", List.of()));
        repository.create(PROJECT_A, useCase(id, code, "Place order", "Order is placed", original), "en");
        RevisionToken head = currentHead(code);

        List<Step> withGermanSecondStep = List.of(
                new Step(1, "Customer selects items", List.of()),
                new Step(2, "Kunde gibt Zahlungsdetails ein", List.of()),
                new Step(3, "Customer confirms order", List.of()));
        UseCase updated = useCase(id, code, "Place order", "Order is placed", withGermanSecondStep);
        repository.compareAndUpdate(PROJECT_A, head, updated, "en", "en", null, null, null, null,
                Map.of(1, "en", 2, "de", 3, "en"), Map.of(), null, Integer.MAX_VALUE);

        UseCase asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Customer selects items", asEnglish.steps().get(0).text());
        assertEquals("Customer selects items", asGerman.steps().get(0).text());
        assertEquals("Customer enters payment details", asEnglish.steps().get(1).text());
        assertEquals("Kunde gibt Zahlungsdetails ein", asGerman.steps().get(1).text());
        assertEquals("Customer confirms order", asEnglish.steps().get(2).text());
        assertEquals("Customer confirms order", asGerman.steps().get(2).text());
    }

    /**
     * Multi-step counterpart to the title/goal "no duplication under a pass-through-of-the-
     * same-tag" tests ({@link #compareAndUpdatePassingThroughANonCanonicalStoreFirstTagDoesNotDuplicateTheTitle}):
     * rewriting one step's text (position 2) under the language tag it already carries must not
     * leave two {@code arkreq:stepText} literals sitting on that step's freshly-minted subject.
     */
    @Test
    void compareAndUpdateWithTheSameLanguageForOneStepInAMultiStepUseCaseDoesNotDuplicateItsText() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseId id = freshId();
        List<Step> original = List.of(
                new Step(1, "Customer selects items", List.of()),
                new Step(2, "Customer enters payment details", List.of()),
                new Step(3, "Customer confirms order", List.of()));
        repository.create(PROJECT_A, useCase(id, code, "Place order", "Order is placed", original), "en");
        RevisionToken head = currentHead(code);

        List<Step> withRewordedSecondStep = List.of(
                new Step(1, "Customer selects items", List.of()),
                new Step(2, "Customer enters payment details again", List.of()),
                new Step(3, "Customer confirms order", List.of()));
        UseCase updated = useCase(id, code, "Place order", "Order is placed", withRewordedSecondStep);
        repository.compareAndUpdate(PROJECT_A, head, updated, "en", "en", null, null, null, null,
                Map.of(1, "en", 2, "en", 3, "en"), Map.of(), null, Integer.MAX_VALUE);

        assertEquals(1, countStepTextLiterals(id, 2));
    }

    /**
     * Round trip for the four optional scalar fields and extensions gaining {@code sh:uniqueLang}
     * (issue #254): {@code create} tags {@code scope}/{@code trigger}/{@code precondition}/
     * {@code postcondition} and every extension's text with the same shared tag as
     * {@code title}/{@code goal}/{@code steps}, and each is selectable back via
     * {@link DisplayLocale}.
     */
    @Test
    void createWritesTaggedScopeTriggerPreconditionPostconditionAndExtensionsSelectableViaDisplayLocale() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCase created = new UseCase(freshId(), code, "Bestellung aufgeben", "Bestellung abschliessen",
                "Webshop", "Kunde oeffnet den Warenkorb", CUSTOMER, List.of(),
                "Kunde ist eingeloggt", "Bestellung ist erfasst",
                List.of(new Step(1, "Kunde waehlt Artikel", List.of())),
                List.of("2a. Zahlung abgelehnt -> Abbruch"), List.of(), List.of());
        repository.create(PROJECT_A, created, "de");

        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Webshop", asGerman.scope());
        assertEquals("Kunde oeffnet den Warenkorb", asGerman.trigger());
        assertEquals("Kunde ist eingeloggt", asGerman.precondition());
        assertEquals("Bestellung ist erfasst", asGerman.postcondition());
        assertEquals(List.of("2a. Zahlung abgelehnt -> Abbruch"), asGerman.extensions());
    }

    /**
     * The regression this issue exists to fix, for a scalar field other than {@code title}: a
     * {@code compareAndUpdate} that corrects only {@code trigger} under a new language must leave
     * every other field's already-written language variant (here {@code scope}, standing in for
     * {@code precondition}/{@code postcondition}, which share the identical
     * {@code otherLanguageLiterals} code path) completely intact.
     */
    @Test
    void compareAndUpdateWithANewLanguageForTriggerPreservesEveryOtherFieldsLanguageVariant() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCase created = new UseCase(freshId(), code, "Place order", "Order is placed",
                "Webshop", "Customer opens the cart", CUSTOMER, List.of(),
                "Customer is logged in", "Order is recorded",
                List.of(new Step(1, "Customer selects items", List.of())),
                List.of("2a. Payment declined -> abort"), List.of(), List.of());
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        UseCase withGermanTrigger = new UseCase(created.id(), code, "Place order", "Order is placed",
                "Webshop", "Kunde oeffnet den Warenkorb", CUSTOMER, List.of(),
                "Customer is logged in", "Order is recorded",
                List.of(new Step(1, "Customer selects items", List.of())),
                List.of("2a. Payment declined -> abort"), List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, head, withGermanTrigger, "en", "en", "en", "de", "en", "en",
                Map.of(1, "en"), Map.of(1, "en"), null, Integer.MAX_VALUE);

        UseCase asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Customer opens the cart", asEnglish.trigger());
        assertEquals("Kunde oeffnet den Warenkorb", asGerman.trigger());
        assertEquals("Webshop", asEnglish.scope());
        assertEquals("Webshop", asGerman.scope());
    }

    /**
     * The extension-specific regression, mirroring
     * {@link #compareAndUpdateWithANewLanguageForOneStepPreservesTheOtherStepsOriginalLanguageVariant}:
     * an extension's opaque subject is re-minted on every write exactly like a main-flow step's, so
     * only the middle of three extensions is patched under a new language, and
     * {@code otherLanguageStepTexts}'s position-keyed re-attachment (now shared between mainStep and
     * extensionStep via its {@code edgeProperty} parameter) must pick out exactly that one
     * extension's freshly-minted subject, not the other two's.
     */
    @Test
    void compareAndUpdateWithANewLanguageForOneExtensionPreservesTheOtherExtensionsOriginalLanguageVariant() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseId id = freshId();
        List<String> originalExtensions = List.of(
                "2a. Payment declined -> abort",
                "3a. Item out of stock -> notify customer",
                "4a. Address invalid -> request correction");
        UseCase created = new UseCase(id, code, "Place order", "Order is placed", null, null, CUSTOMER, List.of(),
                null, null, List.of(new Step(1, "Customer selects items", List.of())), originalExtensions, List.of(), List.of());
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        List<String> withGermanSecondExtension = List.of(
                "2a. Payment declined -> abort",
                "3a. Artikel nicht vorraetig -> Kunde benachrichtigen",
                "4a. Address invalid -> request correction");
        UseCase updated = new UseCase(id, code, "Place order", "Order is placed", null, null, CUSTOMER, List.of(),
                null, null, List.of(new Step(1, "Customer selects items", List.of())), withGermanSecondExtension, List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, head, updated, "en", "en", null, null, null, null,
                Map.of(1, "en"), Map.of(1, "en", 2, "de", 3, "en"), null, Integer.MAX_VALUE);

        UseCase asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("2a. Payment declined -> abort", asEnglish.extensions().get(0));
        assertEquals("2a. Payment declined -> abort", asGerman.extensions().get(0));
        assertEquals("3a. Item out of stock -> notify customer", asEnglish.extensions().get(1));
        assertEquals("3a. Artikel nicht vorraetig -> Kunde benachrichtigen", asGerman.extensions().get(1));
        assertEquals("4a. Address invalid -> request correction", asEnglish.extensions().get(2));
        assertEquals("4a. Address invalid -> request correction", asGerman.extensions().get(2));
    }

    /**
     * The insert/reorder regression this class's {@code stableExtensionPrefixLength} parameter
     * exists to fix: once an extension is inserted ahead of a position that already carries an
     * other-language variant, that position's meaning shifts, so the old variant must not be
     * re-attached to whatever new content now sits there.
     *
     * <p>1) create with two extensions, English. 2) compareAndUpdate gives position 2 a German
     * variant - a safe in-place translation ({@code stableExtensionPrefixLength=Integer.MAX_VALUE}),
     * so both the English and the German text now sit on position 2. 3) compareAndUpdate inserts a
     * brand-new extension ahead of position 2 - three items where there were two, so the common
     * prefix is only position 1 ({@code stableExtensionPrefixLength=1}): position 2 now denotes the
     * newly-inserted extension, not the one the German variant was ever a translation of. With the
     * boundary honoured, that German variant must not survive on the freshly-minted position-2
     * subject; before the fix it did, silently attaching to unrelated content.</p>
     */
    @Test
    void compareAndUpdateWithAnInsertedExtensionDoesNotMisattachAPriorPositionsOtherLanguageVariant() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseId id = freshId();
        List<String> originalExtensions = List.of("2a. A", "3a. B");
        UseCase created = new UseCase(id, code, "Place order", "Order is placed", null, null, CUSTOMER, List.of(),
                null, null, List.of(new Step(1, "Customer selects items", List.of())), originalExtensions, List.of(), List.of());
        repository.create(PROJECT_A, created, "en");
        RevisionToken headAfterCreate = currentHead(code);

        List<String> withGermanSecondExtension = List.of("2a. A", "3a. B (de)");
        UseCase withGermanVariant = new UseCase(id, code, "Place order", "Order is placed", null, null, CUSTOMER,
                List.of(), null, null, List.of(new Step(1, "Customer selects items", List.of())),
                withGermanSecondExtension, List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, headAfterCreate, withGermanVariant, "en", "en", null, null, null,
                null, Map.of(1, "en"), Map.of(1, "en", 2, "de"), null, Integer.MAX_VALUE);
        RevisionToken headAfterTranslation = currentHead(code);

        List<String> withInsertedExtension = List.of("2a. A", "2b. New", "3a. B");
        UseCase withInsert = new UseCase(id, code, "Place order", "Order is placed", null, null, CUSTOMER, List.of(),
                null, null, List.of(new Step(1, "Customer selects items", List.of())), withInsertedExtension, List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, headAfterTranslation, withInsert, "en", "en", null, null, null, null,
                Map.of(1, "en"), Map.of(1, "en", 2, "en", 3, "en"), null, 1);

        assertEquals(1, countExtensionTextLiterals(id, 2),
                "the stale German variant of the superseded position-2 extension must not survive");
        UseCase asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals(List.of("2a. A", "2b. New", "3a. B"), asEnglish.extensions());
        assertEquals(List.of("2a. A", "2b. New", "3a. B"), asGerman.extensions());
    }

    /**
     * Regression for the review finding on issue #254 (PR #267): a restructure beyond a stable
     * leading prefix must only suspend preservation for the positions it actually destabilises -
     * a position inside that prefix, left completely untouched by the restructuring call, must keep
     * its own, independently-earned other-language variant. Counterpart to
     * {@link #compareAndUpdateWithAnInsertedExtensionDoesNotMisattachAPriorPositionsOtherLanguageVariant}:
     * that test pins the misattachment this one pins the opposite failure mode of - a
     * {@code stableExtensionPrefixLength} of {@code 0} (or, before the fix, the blanket
     * {@code extensionsRestructured=true} dropping preservation for every position) would silently
     * destroy position 1's German variant here even though position 1 never moved and never
     * changed.
     *
     * <p>1) create with three extensions, English. 2) compareAndUpdate gives position 1 its own
     * German variant - a safe in-place translation, so both the English and the German text now sit
     * on position 1. 3) compareAndUpdate inserts a brand-new extension between positions 2 and 3 -
     * four items where there were three, restructuring everything from position 3 onward, but
     * leaving positions 1 and 2 byte-for-byte identical ({@code stableExtensionPrefixLength=2}).
     * Position 1's German variant, earned in step 2 and never touched by step 3, must survive.</p>
     */
    @Test
    void compareAndUpdateWithAnInsertedExtensionPreservesAStablePrefixPositionsOwnOtherLanguageVariant() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseId id = freshId();
        List<String> originalExtensions = List.of("2a. A", "3a. B", "4a. C");
        UseCase created = new UseCase(id, code, "Place order", "Order is placed", null, null, CUSTOMER, List.of(),
                null, null, List.of(new Step(1, "Customer selects items", List.of())), originalExtensions, List.of(), List.of());
        repository.create(PROJECT_A, created, "en");
        RevisionToken headAfterCreate = currentHead(code);

        List<String> withGermanFirstExtension = List.of("2a. A (de)", "3a. B", "4a. C");
        UseCase withGermanVariant = new UseCase(id, code, "Place order", "Order is placed", null, null, CUSTOMER,
                List.of(), null, null, List.of(new Step(1, "Customer selects items", List.of())),
                withGermanFirstExtension, List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, headAfterCreate, withGermanVariant, "en", "en", null, null, null,
                null, Map.of(1, "en"), Map.of(1, "de", 2, "en", 3, "en"), null, Integer.MAX_VALUE);
        RevisionToken headAfterTranslation = currentHead(code);

        List<String> withInsertedExtension = List.of("2a. A (de)", "3a. B", "2c. New", "4a. C");
        UseCase withInsert = new UseCase(id, code, "Place order", "Order is placed", null, null, CUSTOMER, List.of(),
                null, null, List.of(new Step(1, "Customer selects items", List.of())), withInsertedExtension, List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, headAfterTranslation, withInsert, "en", "en", null, null, null, null,
                Map.of(1, "en"), Map.of(1, "de", 2, "en", 3, "en", 4, "en"), null, 2);

        assertEquals(2, countExtensionTextLiterals(id, 1),
                "position 1's own German variant must survive a restructure that never touched it");
        UseCase asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        UseCase asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals(List.of("2a. A", "3a. B", "2c. New", "4a. C"), asEnglish.extensions());
        assertEquals(List.of("2a. A (de)", "3a. B", "2c. New", "4a. C"), asGerman.extensions());
    }

    /**
     * Extension counterpart to {@link #compareAndUpdateWithTheSameLanguageForOneStepInAMultiStepUseCaseDoesNotDuplicateItsText}:
     * rewriting one extension's text (position 2) under the language tag it already carries must
     * not leave two {@code arkreq:stepText} literals sitting on that extension's freshly-minted
     * subject.
     */
    @Test
    void compareAndUpdateWithTheSameLanguageForOneExtensionInAMultiExtensionUseCaseDoesNotDuplicateItsText() {
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseId id = freshId();
        List<String> originalExtensions = List.of(
                "2a. Payment declined -> abort",
                "3a. Item out of stock -> notify customer");
        UseCase created = new UseCase(id, code, "Place order", "Order is placed", null, null, CUSTOMER, List.of(),
                null, null, List.of(new Step(1, "Customer selects items", List.of())), originalExtensions, List.of(), List.of());
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        List<String> withRewordedSecondExtension = List.of(
                "2a. Payment declined -> abort",
                "3a. Item out of stock -> notify customer immediately");
        UseCase updated = new UseCase(id, code, "Place order", "Order is placed", null, null, CUSTOMER, List.of(),
                null, null, List.of(new Step(1, "Customer selects items", List.of())), withRewordedSecondExtension, List.of(), List.of());
        repository.compareAndUpdate(PROJECT_A, head, updated, "en", "en", null, null, null, null,
                Map.of(1, "en"), Map.of(1, "en", 2, "en"), null, Integer.MAX_VALUE);

        assertEquals(1, countExtensionTextLiterals(id, 2));
    }

    /**
     * Issue #258, decision 3: a {@code compareAndUpdate} that writes {@code title} under the tag
     * equal to the project's {@code defaultLanguage} sweeps away a stale untagged sibling of the
     * same predicate instead of preserving it as a spurious "other" language variant.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedTitleWhenTheWrittenTagEqualsTheProjectDefault() {
        UseCaseId id = freshId();
        givenLegacyUseCaseWithUntaggedTitle(PROJECT_A, id, "UC1");
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();
        UseCase withGermanTitle = useCase(id, code, "Bestellung aufgeben", current.value().goal(),
                current.value().steps());

        repository.compareAndUpdate(PROJECT_A, current.head(), withGermanTitle, "de",
                current.goalLanguage(), current.scopeLanguage(), current.triggerLanguage(),
                current.preconditionLanguage(), current.postconditionLanguage(),
                current.stepTextLanguageByPosition(), current.extensionTextLanguageByPosition(), "de", Integer.MAX_VALUE);

        assertEquals(1, countTitleLiterals(PROJECT_A, id));
        UseCase reloaded = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Bestellung aufgeben", reloaded.title());
    }

    /**
     * Regression guard for the same sweep (issue #258): writing {@code title} under an
     * <em>explicit</em>, non-default language must leave an existing untagged variant alone.
     */
    @Test
    void compareAndUpdateKeepsAnUntaggedTitleWhenTheWrittenTagDiffersFromTheProjectDefault() {
        UseCaseId id = freshId();
        givenLegacyUseCaseWithUntaggedTitle(PROJECT_A, id, "UC1");
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();
        UseCase withFrenchTitle = useCase(id, code, "Passer commande", current.value().goal(),
                current.value().steps());

        repository.compareAndUpdate(PROJECT_A, current.head(), withFrenchTitle, "fr",
                current.goalLanguage(), current.scopeLanguage(), current.triggerLanguage(),
                current.preconditionLanguage(), current.postconditionLanguage(),
                current.stepTextLanguageByPosition(), current.extensionTextLanguageByPosition(), "de", Integer.MAX_VALUE);

        assertEquals(2, countTitleLiterals(PROJECT_A, id));
        assertTrue(hasUntaggedTitle(PROJECT_A, id, "Place order"));
        UseCase asFrench = repository.findByCode(PROJECT_A, code, "fr").orElseThrow();
        assertEquals("Passer commande", asFrench.title());
    }

    /**
     * Issue #258, decision 3, {@code otherLanguageStepTexts}'s own independent implementation
     * (distinct from {@code otherLanguageLiterals}, since a step's subject is re-minted on every
     * write - class-level note): a {@code compareAndUpdate} that writes a step's {@code text}
     * under the tag equal to the project's {@code defaultLanguage} sweeps away that position's
     * stale untagged sibling instead of preserving it as a spurious "other" language variant.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedStepTextWhenTheWrittenTagEqualsTheProjectDefault() {
        UseCaseId id = freshId();
        givenLegacyUseCaseWithUntaggedStepText(PROJECT_A, id, "UC1");
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();
        UseCase withGermanStepText = useCase(id, code, current.value().title(), current.value().goal(),
                List.of(new Step(1, "Kunde waehlt Artikel", List.of())));

        repository.compareAndUpdate(PROJECT_A, current.head(), withGermanStepText, current.titleLanguage(),
                current.goalLanguage(), current.scopeLanguage(), current.triggerLanguage(),
                current.preconditionLanguage(), current.postconditionLanguage(),
                Map.of(1, "de"), current.extensionTextLanguageByPosition(), "de", Integer.MAX_VALUE);

        assertEquals(1, countStepTextLiterals(id, 1));
        UseCase reloaded = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Kunde waehlt Artikel", reloaded.steps().get(0).text());
    }

    /**
     * Regression guard for the same sweep (issue #258): writing a step's {@code text} under an
     * <em>explicit</em>, non-default language must leave that position's existing untagged variant
     * alone.
     */
    @Test
    void compareAndUpdateKeepsAnUntaggedStepTextWhenTheWrittenTagDiffersFromTheProjectDefault() {
        UseCaseId id = freshId();
        givenLegacyUseCaseWithUntaggedStepText(PROJECT_A, id, "UC1");
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();
        UseCase withFrenchStepText = useCase(id, code, current.value().title(), current.value().goal(),
                List.of(new Step(1, "Le client choisit des articles", List.of())));

        repository.compareAndUpdate(PROJECT_A, current.head(), withFrenchStepText, current.titleLanguage(),
                current.goalLanguage(), current.scopeLanguage(), current.triggerLanguage(),
                current.preconditionLanguage(), current.postconditionLanguage(),
                Map.of(1, "fr"), current.extensionTextLanguageByPosition(), "de", Integer.MAX_VALUE);

        assertEquals(2, countStepTextLiterals(id, 1));
        UseCase asFrench = repository.findByCode(PROJECT_A, code, "fr").orElseThrow();
        assertEquals("Le client choisit des articles", asFrench.steps().get(0).text());
    }

    /**
     * Issue #258, decision 3, for a scalar field other than {@code title} (mirrors
     * {@link #compareAndUpdateSweepsAnUntaggedTitleWhenTheWrittenTagEqualsTheProjectDefault}): a
     * {@code compareAndUpdate} that writes {@code scope} under the tag equal to the project's
     * {@code defaultLanguage} sweeps away a stale untagged sibling of the same predicate instead of
     * preserving it as a spurious "other" language variant.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedScopeWhenTheWrittenTagEqualsTheProjectDefault() {
        UseCaseId id = freshId();
        givenLegacyUseCaseWithUntaggedScope(PROJECT_A, id, "UC1");
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();
        UseCase withGermanScope = new UseCase(id, code, current.value().title(), current.value().goal(),
                "Webshop (deutsch)", current.value().trigger(), CUSTOMER, List.of(),
                current.value().precondition(), current.value().postcondition(), current.value().steps(),
                current.value().extensions(), List.of(), List.of());

        repository.compareAndUpdate(PROJECT_A, current.head(), withGermanScope, current.titleLanguage(),
                current.goalLanguage(), "de", current.triggerLanguage(), current.preconditionLanguage(),
                current.postconditionLanguage(), current.stepTextLanguageByPosition(),
                current.extensionTextLanguageByPosition(), "de", Integer.MAX_VALUE);

        assertEquals(1, countScopeLiterals(PROJECT_A, id));
        UseCase reloaded = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Webshop (deutsch)", reloaded.scope());
    }

    /**
     * Regression guard for the same sweep (issue #258): writing {@code scope} under an
     * <em>explicit</em>, non-default language must leave an existing untagged variant alone.
     */
    @Test
    void compareAndUpdateKeepsAnUntaggedScopeWhenTheWrittenTagDiffersFromTheProjectDefault() {
        UseCaseId id = freshId();
        givenLegacyUseCaseWithUntaggedScope(PROJECT_A, id, "UC1");
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();
        UseCase withFrenchScope = new UseCase(id, code, current.value().title(), current.value().goal(),
                "Boutique en ligne", current.value().trigger(), CUSTOMER, List.of(),
                current.value().precondition(), current.value().postcondition(), current.value().steps(),
                current.value().extensions(), List.of(), List.of());

        repository.compareAndUpdate(PROJECT_A, current.head(), withFrenchScope, current.titleLanguage(),
                current.goalLanguage(), "fr", current.triggerLanguage(), current.preconditionLanguage(),
                current.postconditionLanguage(), current.stepTextLanguageByPosition(),
                current.extensionTextLanguageByPosition(), "de", Integer.MAX_VALUE);

        assertEquals(2, countScopeLiterals(PROJECT_A, id));
        assertTrue(hasUntaggedScope(PROJECT_A, id, "Webshop"));
        UseCase asFrench = repository.findByCode(PROJECT_A, code, "fr").orElseThrow();
        assertEquals("Boutique en ligne", asFrench.scope());
    }

    /**
     * Issue #258, decision 3, {@code otherLanguageStepTexts}'s extension-step call (mirrors
     * {@link #compareAndUpdateSweepsAnUntaggedStepTextWhenTheWrittenTagEqualsTheProjectDefault} but
     * for {@code arkreq:extensionStep} rather than {@code arkreq:mainStep}): a
     * {@code compareAndUpdate} that writes an extension's {@code text} under the tag equal to the
     * project's {@code defaultLanguage} sweeps away that position's stale untagged sibling instead
     * of preserving it as a spurious "other" language variant.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedExtensionTextWhenTheWrittenTagEqualsTheProjectDefault() {
        UseCaseId id = freshId();
        givenLegacyUseCaseWithUntaggedExtensionText(PROJECT_A, id, "UC1");
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();
        UseCase withGermanExtension = new UseCase(id, code, current.value().title(), current.value().goal(),
                current.value().scope(), current.value().trigger(), CUSTOMER, List.of(),
                current.value().precondition(), current.value().postcondition(), current.value().steps(),
                List.of("2a. Zahlung abgelehnt -> Abbruch"), List.of(), List.of());

        repository.compareAndUpdate(PROJECT_A, current.head(), withGermanExtension, current.titleLanguage(),
                current.goalLanguage(), current.scopeLanguage(), current.triggerLanguage(),
                current.preconditionLanguage(), current.postconditionLanguage(),
                current.stepTextLanguageByPosition(), Map.of(1, "de"), "de", Integer.MAX_VALUE);

        assertEquals(1, countExtensionTextLiterals(id, 1));
        UseCase reloaded = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("2a. Zahlung abgelehnt -> Abbruch", reloaded.extensions().get(0));
    }

    /**
     * Regression guard for the same sweep (issue #258): writing an extension's {@code text} under
     * an <em>explicit</em>, non-default language must leave that position's existing untagged
     * variant alone.
     */
    @Test
    void compareAndUpdateKeepsAnUntaggedExtensionTextWhenTheWrittenTagDiffersFromTheProjectDefault() {
        UseCaseId id = freshId();
        givenLegacyUseCaseWithUntaggedExtensionText(PROJECT_A, id, "UC1");
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();
        UseCase withFrenchExtension = new UseCase(id, code, current.value().title(), current.value().goal(),
                current.value().scope(), current.value().trigger(), CUSTOMER, List.of(),
                current.value().precondition(), current.value().postcondition(), current.value().steps(),
                List.of("2a. Paiement refuse -> annulation"), List.of(), List.of());

        repository.compareAndUpdate(PROJECT_A, current.head(), withFrenchExtension, current.titleLanguage(),
                current.goalLanguage(), current.scopeLanguage(), current.triggerLanguage(),
                current.preconditionLanguage(), current.postconditionLanguage(),
                current.stepTextLanguageByPosition(), Map.of(1, "fr"), "de", Integer.MAX_VALUE);

        assertEquals(2, countExtensionTextLiterals(id, 1));
        UseCase asFrench = repository.findByCode(PROJECT_A, code, "fr").orElseThrow();
        assertEquals("2a. Paiement refuse -> annulation", asFrench.extensions().get(0));
    }

    /**
     * Regression for the review finding on issue #229 (PR #238), mirrors
     * {@code KognioRdfRequirementRepositoryMultilingualTest
     * #compareAndUpdatePassesThroughAStoreFirstIllFormedTitleLanguageTagWithoutCrashing}: a
     * store-first (ADR-005) title tagged with a dangling BCP-47 extension singleton is rejected
     * both by {@link de.hauschel.arknet.kernel.LanguageTag} and, identically, by RDF4J's own
     * literal validation reached from the SHACL gate - before the fix this blocked every future
     * correction of the use case, even a call, like this one, that leaves {@code title} untouched.
     */
    @Test
    void compareAndUpdatePassesThroughAStoreFirstIllFormedTitleLanguageTagWithoutCrashing() {
        UseCaseId id = freshId();
        givenLegacyUseCaseWithTitleLanguageTag(PROJECT_A, id, "UC1", "en-a");
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();

        assertDoesNotThrow(() -> repository.compareAndUpdate(PROJECT_A, current.head(), current.value(),
                current.titleLanguage(), current.goalLanguage(), current.scopeLanguage(), current.triggerLanguage(),
                current.preconditionLanguage(), current.postconditionLanguage(),
                current.stepTextLanguageByPosition(), current.extensionTextLanguageByPosition(), null, Integer.MAX_VALUE));

        UseCase reloaded = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        assertEquals("Place order", reloaded.title());
    }

    /**
     * Second regression for the same review finding, mirrors
     * {@code KognioRdfRequirementRepositoryMultilingualTest
     * #compareAndUpdatePassingThroughANonCanonicalStoreFirstTagDoesNotDuplicateTheTitle}: a
     * store-first (ADR-005) title tagged with a valid but non-canonical tag (e.g. {@code "de-de"},
     * canonicalizing to {@code "de-DE"}) must not be duplicated once a pass-through
     * {@code compareAndUpdate} rewrites it under its canonicalized form.
     */
    @Test
    void compareAndUpdatePassingThroughANonCanonicalStoreFirstTagDoesNotDuplicateTheTitle() {
        UseCaseId id = freshId();
        givenLegacyUseCaseWithTitleLanguageTag(PROJECT_A, id, "UC1", "de-de");
        UseCaseCode code = new UseCaseCode("UC1");
        UseCaseRepository.CurrentUseCase current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();

        repository.compareAndUpdate(PROJECT_A, current.head(), current.value(),
                current.titleLanguage(), current.goalLanguage(), current.scopeLanguage(), current.triggerLanguage(),
                current.preconditionLanguage(), current.postconditionLanguage(),
                current.stepTextLanguageByPosition(), current.extensionTextLanguageByPosition(), null, Integer.MAX_VALUE);

        assertEquals(1, countTitleLiterals(PROJECT_A, id));
    }

    /**
     * Writes a shape-legal {@code arkreq:UseCase} straight into the use-cases graph with its
     * {@code dcterms:title} carrying {@code titleLanguageTag} verbatim - {@code uc_add}/
     * {@code uc_update} always route a language tag through {@link
     * de.hauschel.arknet.kernel.LanguageTag#canonicalize(String)} first, so an ill-formed or merely
     * non-canonical tag on {@code title} is reachable only store-first (ADR-005).
     */
    private void givenLegacyUseCaseWithTitleLanguageTag(ProjectId projectId, UseCaseId id, String code,
            String titleLanguageTag) {
        String stepIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#UseCase> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Place order\"@" + titleLanguageTag + " ; "
                + "<https://w3id.org/arknet/requirements#useCaseGoal> \"Order is placed\" ; "
                + "<https://w3id.org/arknet/requirements#primaryActor> <" + CUSTOMER.value().value() + "> ; "
                + "<https://w3id.org/arknet/requirements#mainStep> <" + stepIri + "> . "
                + "<" + stepIri + "> a <https://w3id.org/arknet/requirements#Step> ; "
                + "<https://w3id.org/arknet/requirements#position> \"1\"^^<http://www.w3.org/2001/XMLSchema#integer> ; "
                + "<https://w3id.org/arknet/requirements#stepText> \"Customer selects items\" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Writes a shape-legal {@code arkreq:UseCase} straight into the use-cases graph with its
     * {@code dcterms:title} carrying no language tag at all - the store-first (ADR-005) state
     * issue #258's sweep normalises lazily, one write at a time.
     */
    private void givenLegacyUseCaseWithUntaggedTitle(ProjectId projectId, UseCaseId id, String code) {
        String stepIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#UseCase> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Place order\" ; "
                + "<https://w3id.org/arknet/requirements#useCaseGoal> \"Order is placed\" ; "
                + "<https://w3id.org/arknet/requirements#primaryActor> <" + CUSTOMER.value().value() + "> ; "
                + "<https://w3id.org/arknet/requirements#mainStep> <" + stepIri + "> . "
                + "<" + stepIri + "> a <https://w3id.org/arknet/requirements#Step> ; "
                + "<https://w3id.org/arknet/requirements#position> \"1\"^^<http://www.w3.org/2001/XMLSchema#integer> ; "
                + "<https://w3id.org/arknet/requirements#stepText> \"Customer selects items\" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Writes a shape-legal {@code arkreq:UseCase} straight into the use-cases graph with its main
     * step's {@code arkreq:stepText} (position 1) carrying no language tag at all - the store-first
     * (ADR-005) state issue #258's sweep normalises lazily, one write at a time, mirrors {@link
     * #givenLegacyUseCaseWithUntaggedTitle} for {@code otherLanguageStepTexts}'s own independent
     * implementation.
     */
    private void givenLegacyUseCaseWithUntaggedStepText(ProjectId projectId, UseCaseId id, String code) {
        String stepIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#UseCase> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Place order\" ; "
                + "<https://w3id.org/arknet/requirements#useCaseGoal> \"Order is placed\" ; "
                + "<https://w3id.org/arknet/requirements#primaryActor> <" + CUSTOMER.value().value() + "> ; "
                + "<https://w3id.org/arknet/requirements#mainStep> <" + stepIri + "> . "
                + "<" + stepIri + "> a <https://w3id.org/arknet/requirements#Step> ; "
                + "<https://w3id.org/arknet/requirements#position> \"1\"^^<http://www.w3.org/2001/XMLSchema#integer> ; "
                + "<https://w3id.org/arknet/requirements#stepText> \"Customer selects items\" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * {@link #givenLegacyUseCaseWithUntaggedTitle} for {@code arkreq:designScope}: writes a
     * shape-legal {@code arkreq:UseCase} whose {@code designScope} carries no language tag at all.
     */
    private void givenLegacyUseCaseWithUntaggedScope(ProjectId projectId, UseCaseId id, String code) {
        String stepIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#UseCase> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Place order\" ; "
                + "<https://w3id.org/arknet/requirements#useCaseGoal> \"Order is placed\" ; "
                + "<https://w3id.org/arknet/requirements#designScope> \"Webshop\" ; "
                + "<https://w3id.org/arknet/requirements#primaryActor> <" + CUSTOMER.value().value() + "> ; "
                + "<https://w3id.org/arknet/requirements#mainStep> <" + stepIri + "> . "
                + "<" + stepIri + "> a <https://w3id.org/arknet/requirements#Step> ; "
                + "<https://w3id.org/arknet/requirements#position> \"1\"^^<http://www.w3.org/2001/XMLSchema#integer> ; "
                + "<https://w3id.org/arknet/requirements#stepText> \"Customer selects items\" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * {@link #givenLegacyUseCaseWithUntaggedStepText} for an extension step: writes a shape-legal
     * {@code arkreq:UseCase} whose sole {@code arkreq:extensionStep} (position 1) carries no
     * language tag at all.
     */
    private void givenLegacyUseCaseWithUntaggedExtensionText(ProjectId projectId, UseCaseId id, String code) {
        String stepIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String extensionStepIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#UseCase> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Place order\" ; "
                + "<https://w3id.org/arknet/requirements#useCaseGoal> \"Order is placed\" ; "
                + "<https://w3id.org/arknet/requirements#primaryActor> <" + CUSTOMER.value().value() + "> ; "
                + "<https://w3id.org/arknet/requirements#mainStep> <" + stepIri + "> ; "
                + "<https://w3id.org/arknet/requirements#extensionStep> <" + extensionStepIri + "> . "
                + "<" + stepIri + "> a <https://w3id.org/arknet/requirements#Step> ; "
                + "<https://w3id.org/arknet/requirements#position> \"1\"^^<http://www.w3.org/2001/XMLSchema#integer> ; "
                + "<https://w3id.org/arknet/requirements#stepText> \"Customer selects items\" . "
                + "<" + extensionStepIri + "> a <https://w3id.org/arknet/requirements#Step> ; "
                + "<https://w3id.org/arknet/requirements#position> \"1\"^^<http://www.w3.org/2001/XMLSchema#integer> ; "
                + "<https://w3id.org/arknet/requirements#stepText> \"2a. Payment declined -> abort\" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /** Whether {@code id}'s {@code dcterms:title} still carries an untagged {@code text} literal. */
    private boolean hasUntaggedTitle(ProjectId projectId, UseCaseId id, String text) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> <http://purl.org/dc/terms/title> \"" + text + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    /** {@link #hasUntaggedTitle} for {@code arkreq:designScope}. */
    private boolean hasUntaggedScope(ProjectId projectId, UseCaseId id, String text) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> <https://w3id.org/arknet/requirements#designScope> \""
                + text + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    private long countTitleLiterals(ProjectId projectId, UseCaseId id) {
        String query = "SELECT ?o WHERE { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> <http://purl.org/dc/terms/title> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query).count();
        }
    }

    /** {@link #countTitleLiterals} for {@code arkreq:designScope}. */
    private long countScopeLiterals(ProjectId projectId, UseCaseId id) {
        String query = "SELECT ?o WHERE { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> <https://w3id.org/arknet/requirements#designScope> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query).count();
        }
    }

    /**
     * Counts {@code arkreq:stepText} literals on whichever step subject currently sits at
     * {@code position} - a step's own subject is re-minted on every write (class-level note), so
     * this looks the current subject up via {@code arkreq:mainStep}/{@code arkreq:position} rather
     * than addressing a step by IRI.
     */
    private long countStepTextLiterals(UseCaseId id, int position) {
        String query = "SELECT ?text WHERE { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> <https://w3id.org/arknet/requirements#mainStep> ?step . "
                + "?step <https://w3id.org/arknet/requirements#position> \"" + position
                + "\"^^<http://www.w3.org/2001/XMLSchema#integer> ; "
                + "<https://w3id.org/arknet/requirements#stepText> ?text } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query).count();
        }
    }

    /** {@link #countStepTextLiterals} for an extension step (via {@code arkreq:extensionStep}). */
    private long countExtensionTextLiterals(UseCaseId id, int position) {
        String query = "SELECT ?text WHERE { GRAPH <https://w3id.org/arknet/model/use-cases> { "
                + "<" + id.value().value() + "> <https://w3id.org/arknet/requirements#extensionStep> ?step . "
                + "?step <https://w3id.org/arknet/requirements#position> \"" + position
                + "\"^^<http://www.w3.org/2001/XMLSchema#integer> ; "
                + "<https://w3id.org/arknet/requirements#stepText> ?text } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query).count();
        }
    }
}
