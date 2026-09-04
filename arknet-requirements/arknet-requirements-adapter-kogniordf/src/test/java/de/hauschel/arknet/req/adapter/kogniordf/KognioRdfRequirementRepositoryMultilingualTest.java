// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.InvalidLanguageTagException;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;

/**
 * Integration tests for the multilingual {@code dcterms:title}/{@code dcterms:description}
 * behaviour of {@link KognioRdfRequirementRepository}: language-scoped writes on
 * {@link RequirementRepository#create}/{@link RequirementRepository#compareAndUpdate}, and
 * {@link DisplayLocale}-selected reads on {@link RequirementRepository#findByCode}.
 *
 * <p>This is the regression class for the bug issue #229 exists to avoid: a full replace-by-
 * identity write (unlike {@code KognioRdfTermRepository#update}'s per-predicate patch) must not
 * collapse a multilingual {@code title}/{@code description} down to one language variant just
 * because one call did not intend to touch that field.</p>
 */
class KognioRdfRequirementRepositoryMultilingualTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private RequirementRepository repository;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = KognioRdfRequirementRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static RequirementId freshId() {
        return new RequirementId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static Requirement requirement(RequirementId id, RequirementCode code, String title,
            String description) {
        return requirement(id, code, title, description, null);
    }

    /** {@link #requirement(RequirementId, RequirementCode, String, String)} carrying a rationale. */
    private static Requirement requirement(RequirementId id, RequirementCode code, String title,
            String description, String rationale) {
        return new Requirement(id, code, title, description, rationale, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
    }

    @Test
    void createWritesATaggedTitleAndDescriptionSelectableViaDisplayLocale() {
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, requirement(freshId(), code, "Anmeldung", "Das System soll authentifizieren."),
                "de");

        Requirement asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Anmeldung", asGerman.title());
        assertEquals("Das System soll authentifizieren.", asGerman.description());
    }

    /**
     * The regression this issue exists to fix: a {@code compareAndUpdate} that corrects only
     * {@code title} under a new language must leave the previously-written language variant of
     * {@code title} completely intact - not delete it, not silently retag it.
     */
    @Test
    void compareAndUpdateWithANewLanguageForTitlePreservesTheOriginalLanguageVariant() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirement(freshId(), code, "Login", "The system shall authenticate a user.");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        Requirement withGermanTitle = requirement(created.id(), code, "Anmeldung",
                "The system shall authenticate a user.");
        repository.compareAndUpdate(
                PROJECT_A, head, withGermanTitle, "de", "en", null, noAcceptanceCriteriaLanguages(withGermanTitle), null);

        Requirement asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        Requirement asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Login", asEnglish.title());
        assertEquals("Anmeldung", asGerman.title());
        // description was never touched by this call - both reads must still see the one
        // English-tagged variant, not a duplicate or a dangling second value.
        assertEquals("The system shall authenticate a user.", asEnglish.description());
        assertEquals("The system shall authenticate a user.", asGerman.description());
    }

    /**
     * A {@code compareAndUpdate} that passes through the *same* language tag a field was already
     * carrying (the shape every non-language-aware read-modify-write flow, e.g. {@code
     * req_set_status}/{@code req_link_term}, actually takes) must leave that single variant
     * exactly as it was - a scoped no-op, not an accidental duplicate.
     */
    @Test
    void compareAndUpdateReusingTheSameLanguageDoesNotDuplicateTheVariant() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirement(freshId(), code, "Login", "The system shall authenticate a user.");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        Requirement statusChangeOnly = new Requirement(created.id(), code, created.title(), created.description(), null,
                created.type(), RequirementStatus.ACCEPTED, created.priority(), created.motivatedBy(),
                created.qualityCategory(), created.usesTerms(), created.acceptanceCriteria(), List.of());
        repository.compareAndUpdate(
                PROJECT_A, head, statusChangeOnly, "en", "en", null, noAcceptanceCriteriaLanguages(statusChangeOnly), null);

        Requirement reloaded = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        assertEquals("Login", reloaded.title());
        assertEquals("The system shall authenticate a user.", reloaded.description());
        assertEquals(RequirementStatus.ACCEPTED, reloaded.status());
    }

    /** Correcting {@code description} under a new language must not touch {@code title}'s variant. */
    @Test
    void compareAndUpdateWithANewLanguageForDescriptionPreservesTitleUntouched() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirement(freshId(), code, "Login", "The system shall authenticate a user.");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        Requirement withGermanDescription = requirement(created.id(), code, "Login",
                "Das System soll einen Benutzer authentifizieren.");
        repository.compareAndUpdate(PROJECT_A, head, withGermanDescription, "en", "de", null,
                noAcceptanceCriteriaLanguages(withGermanDescription), null);

        Requirement asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        Requirement asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Login", asEnglish.title());
        assertEquals("Login", asGerman.title());
        assertEquals("The system shall authenticate a user.", asEnglish.description());
        assertEquals("Das System soll einen Benutzer authentifizieren.", asGerman.description());
    }

    /**
     * Issue #266: each acceptance criterion carries its own language tag, keyed by position - a
     * multi-criteria requirement's positions must round-trip together, mirroring
     * {@code KognioRdfUseCaseRepositoryMultilingualTest}'s step-text coverage.
     */
    @Test
    void createWritesTaggedAcceptanceCriteriaSelectableViaDisplayLocale() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementWithCriteria(freshId(), code, "Login",
                "The system shall authenticate a user.",
                List.of("Anmeldung gelingt mit gueltigen Zugangsdaten",
                        "Anmeldung schlaegt bei falschem Passwort fehl"));
        repository.create(PROJECT_A, created, "de");

        Requirement asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals(created.acceptanceCriteria(), asGerman.acceptanceCriteria());
    }

    /**
     * The regression this issue exists to fix, for acceptance criteria: a {@code compareAndUpdate}
     * that corrects only <em>one</em> criterion's text under a new language must leave every other
     * criterion's own language variant (including that same criterion's own prior variant) exactly
     * as it was - not delete it, not silently retag it, not duplicate it. Mirrors
     * {@code KognioRdfUseCaseRepositoryMultilingualTest#compareAndUpdateWithANewLanguageForOneStepPreservesTheOtherStepsOriginalLanguageVariant}.
     */
    @Test
    void compareAndUpdateWithANewLanguageForOneAcceptanceCriterionPreservesTheOtherCriterionsLanguageVariant() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementWithCriteria(freshId(), code, "Login",
                "The system shall authenticate a user.",
                List.of("Login succeeds with valid credentials", "Login is rate-limited"));
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        List<AcceptanceCriterion> updatedCriteria = List.of(
                new AcceptanceCriterion(1, "Anmeldung gelingt mit gueltigen Zugangsdaten"),
                created.acceptanceCriteria().get(1));
        Requirement withGermanFirstCriterion = new Requirement(created.id(), code, created.title(),
                created.description(), null, created.type(), created.status(), created.priority(),
                created.motivatedBy(), created.qualityCategory(), created.usesTerms(), updatedCriteria,
                created.constrainedBy());
        Map<Integer, String> languages = new LinkedHashMap<>();
        languages.put(1, "de");
        languages.put(2, "en");
        repository.compareAndUpdate(PROJECT_A, head, withGermanFirstCriterion, "en", "en", null, languages, null);

        Requirement asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        Requirement asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Login succeeds with valid credentials", asEnglish.acceptanceCriteria().get(0).text());
        assertEquals("Anmeldung gelingt mit gueltigen Zugangsdaten", asGerman.acceptanceCriteria().get(0).text());
        // Position 2 was never touched by this call - both reads must still see the one
        // English-tagged variant, not a duplicate or a dangling second value.
        assertEquals("Login is rate-limited", asEnglish.acceptanceCriteria().get(1).text());
        assertEquals("Login is rate-limited", asGerman.acceptanceCriteria().get(1).text());
    }

    /**
     * Issue #258, decision 3, for acceptance criteria: a {@code compareAndUpdate} that writes a
     * criterion's text under the tag equal to the project's {@code defaultLanguage} sweeps away a
     * stale untagged sibling of that same position instead of preserving it as a spurious "other"
     * language variant.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedAcceptanceCriterionTextWhenTheWrittenTagEqualsTheProjectDefault() {
        RequirementId id = freshId();
        givenLegacyRequirementWithUntaggedAcceptanceCriterionText(PROJECT_A, id, "FR-1");
        RequirementCode code = new RequirementCode("FR-1");
        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(PROJECT_A, code)
                .orElseThrow();
        List<AcceptanceCriterion> updatedCriteria =
                List.of(new AcceptanceCriterion(1, "Anmeldung gelingt mit gueltigen Zugangsdaten"));
        Requirement withGermanCriterion = new Requirement(current.value().id(), code, current.value().title(),
                current.value().description(), null, current.value().type(), current.value().status(),
                current.value().priority(), current.value().motivatedBy(), current.value().qualityCategory(),
                current.value().usesTerms(), updatedCriteria, current.value().constrainedBy());

        repository.compareAndUpdate(PROJECT_A, current.head(), withGermanCriterion, current.titleLanguage(),
                current.descriptionLanguage(), null, Map.of(1, "de"), "de");

        assertEquals(1, countAcceptanceCriterionTextLiterals(PROJECT_A, id));
        Requirement reloaded = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Anmeldung gelingt mit gueltigen Zugangsdaten", reloaded.acceptanceCriteria().get(0).text());
    }

    /**
     * {@code req_list}'s own default-language resolution (issue #281): {@link
     * RequirementRepository#findAll} must select a multilingual requirement's title/description in
     * the reader's configured requested language, exactly as {@link RequirementRepository#findByCode}
     * already does - mirrors {@code KognioRdfTermRepositoryTest#findAllPicksThePrefLabelInTheRequestedLanguage}.
     */
    @Test
    void findAllPicksTheTitleInTheRequestedLanguage() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirement(freshId(), code, "Login", "The system shall authenticate a user.");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);
        Requirement withGermanTitle = requirement(created.id(), code, "Anmeldung",
                "The system shall authenticate a user.");
        repository.compareAndUpdate(
                PROJECT_A, head, withGermanTitle, "de", "en", null, noAcceptanceCriteriaLanguages(withGermanTitle), null);
        RequirementRepository germanReader = readerFor(Locale.GERMAN, Locale.ENGLISH);

        List<Requirement> all = germanReader.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertEquals("Anmeldung", all.get(0).title());
    }

    /**
     * {@code req_list}'s own default-language resolution (issue #281): a per-call override wins
     * over the repository's own constructor-configured display language for {@link
     * RequirementRepository#findAll} too - {@code RequirementMcpTools#list} passes the calling
     * project's own configured default language as this argument, not an explicit tool argument
     * (unlike {@code req_get}'s {@code displayLocale}), but this repository method itself does not
     * distinguish the two: whatever string it is handed simply overrides the configured {@code
     * requested} tier for this one call. Mirrors
     * {@code KognioRdfTermRepositoryTest#findAllDisplayLocaleArgumentOverridesTheConfiguredDefault}.
     */
    @Test
    void findAllDisplayLocaleArgumentOverridesTheConfiguredDefault() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirement(freshId(), code, "Login", "The system shall authenticate a user.");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);
        Requirement withGermanTitle = requirement(created.id(), code, "Anmeldung",
                "The system shall authenticate a user.");
        repository.compareAndUpdate(
                PROJECT_A, head, withGermanTitle, "de", "en", null, noAcceptanceCriteriaLanguages(withGermanTitle), null);
        RequirementRepository englishReader = readerFor(Locale.ENGLISH, Locale.ENGLISH);

        List<Requirement> all = englishReader.findAll(PROJECT_A, "de");

        assertEquals(1, all.size());
        assertEquals("Anmeldung", all.get(0).title());
    }

    /** A requirement repository reading the shared store under an explicit display-language preference. */
    private RequirementRepository readerFor(Locale requested, Locale systemDefault) {
        return KognioRdfRequirementRepositoryFactory.over(lifecycle, new DisplayLocale(requested, systemDefault));
    }

    private static Requirement requirementWithCriteria(RequirementId id, RequirementCode code, String title,
            String description, List<String> criteriaTexts) {
        List<AcceptanceCriterion> criteria = new ArrayList<>();
        int position = 1;
        for (String text : criteriaTexts) {
            criteria.add(new AcceptanceCriterion(position++, text));
        }
        return new Requirement(id, code, title, description, null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, criteria, List.of());
    }

    /**
     * Writes a shape-legal {@code arkreq:FunctionalRequirement} straight into the requirements
     * graph with a single {@code arkreq:AcceptanceCriterion} resource whose {@code
     * arkreq:criterionText} carries no language tag at all - the store-first state
     * issue #258's sweep normalises lazily, mirrors {@link #givenLegacyRequirementWithUntaggedTitle}.
     */
    private void givenLegacyRequirementWithUntaggedAcceptanceCriterionText(ProjectId projectId, RequirementId id,
            String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> <" + id.value().value() + "-c1> . "
                + "<" + id.value().value() + "-c1> <https://w3id.org/arknet/requirements#position> 1 ; "
                + "<https://w3id.org/arknet/requirements#criterionText> \"Login succeeds with valid credentials\" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /** Counts every {@code arkreq:criterionText} literal at position 1 of {@code id}'s sole criterion. */
    private long countAcceptanceCriterionTextLiterals(ProjectId projectId, RequirementId id) {
        String query = "SELECT ?o WHERE { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> <https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "?criterion . ?criterion <https://w3id.org/arknet/requirements#criterionText> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query).count();
        }
    }

    /**
     * Issue #258, decision 3: a {@code compareAndUpdate} that writes {@code title} under the tag
     * equal to the project's {@code defaultLanguage} sweeps away a stale untagged sibling of the
     * same predicate instead of preserving it as a spurious "other" language variant - the write
     * under the resolved default tag <em>is</em>, by construction, what an untagged literal from
     * before this project had (or a caller ever supplied) a language would now resolve to.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedTitleWhenTheWrittenTagEqualsTheProjectDefault() {
        RequirementId id = freshId();
        givenLegacyRequirementWithUntaggedTitle(PROJECT_A, id, "FR-1");
        RequirementCode code = new RequirementCode("FR-1");
        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(PROJECT_A, code)
                .orElseThrow();
        Requirement withGermanTitle = new Requirement(current.value().id(), code, "Anmeldung",
                current.value().description(), null, current.value().type(), current.value().status(),
                current.value().priority(), current.value().motivatedBy(), current.value().qualityCategory(),
                current.value().usesTerms(), current.value().acceptanceCriteria(), List.of());

        repository.compareAndUpdate(PROJECT_A, current.head(), withGermanTitle, "de", current.descriptionLanguage(), null,
                noAcceptanceCriteriaLanguages(withGermanTitle), "de");

        assertEquals(1, countTitleLiterals(PROJECT_A, id));
        Requirement reloaded = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Anmeldung", reloaded.title());
    }

    /**
     * Regression guard for the same sweep (issue #258): writing {@code title} under an
     * <em>explicit</em>, non-default language must leave an existing untagged variant alone - the
     * sweep only ever fires when the written tag equals the project's default, never for any other
     * tag, even against a project that does have a default configured.
     */
    @Test
    void compareAndUpdateKeepsAnUntaggedTitleWhenTheWrittenTagDiffersFromTheProjectDefault() {
        RequirementId id = freshId();
        givenLegacyRequirementWithUntaggedTitle(PROJECT_A, id, "FR-1");
        RequirementCode code = new RequirementCode("FR-1");
        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(PROJECT_A, code)
                .orElseThrow();
        Requirement withFrenchTitle = new Requirement(current.value().id(), code, "Connexion",
                current.value().description(), null, current.value().type(), current.value().status(),
                current.value().priority(), current.value().motivatedBy(), current.value().qualityCategory(),
                current.value().usesTerms(), current.value().acceptanceCriteria(), List.of());

        repository.compareAndUpdate(PROJECT_A, current.head(), withFrenchTitle, "fr", current.descriptionLanguage(), null,
                noAcceptanceCriteriaLanguages(withFrenchTitle), "de");

        assertEquals(2, countTitleLiterals(PROJECT_A, id));
        assertTrue(hasUntaggedTitle(PROJECT_A, id, "Login"));
        Requirement asFrench = repository.findByCode(PROJECT_A, code, "fr").orElseThrow();
        assertEquals("Connexion", asFrench.title());
    }

    @Test
    void createRejectsAnIllFormedLanguageTag() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirement(freshId(), code, "Login", "The system shall authenticate a user.");

        assertThrows(InvalidLanguageTagException.class, () -> repository.create(PROJECT_A, created, "de_DE"));
    }

    /**
     * Regression for the review finding on issue #229 (PR #238): {@code compareAndUpdate}
     * unconditionally re-canonicalized {@code titleLanguage}/{@code descriptionLanguage}, even
     * though {@code RequirementService#updateWithOptimisticRetry} passes those through verbatim
     * from {@code current.titleLanguage()}/{@code current.descriptionLanguage()} - the raw tag as
     * read off the store - for every field a given call does not intend to touch. A store-first
     * title tagged with a dangling BCP-47 extension singleton (Turtle's own language-tag
     * grammar places no structural constraint on subtags, so this is legal Turtle) is rejected not
     * only by {@link de.hauschel.arknet.kernel.LanguageTag} but, identically, by RDF4J's own
     * literal validation reached from the SHACL gate - so before the fix this blocked every future
     * correction of the requirement, even one, like this one, that only changes {@code status} and
     * never touches {@code title} at all; the fix falls back to an untagged literal rather than the
     * unusable raw tag, which is the one literal form no tag validation ever rejects.
     */
    @Test
    void compareAndUpdatePassesThroughAStoreFirstIllFormedTitleLanguageTagWithoutCrashing() {
        RequirementId id = freshId();
        givenLegacyRequirementWithTitleLanguageTag(PROJECT_A, id, "FR-1", "en-a");
        RequirementCode code = new RequirementCode("FR-1");
        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(PROJECT_A, code)
                .orElseThrow();
        Requirement statusChangeOnly = new Requirement(current.value().id(), code, current.value().title(),
                current.value().description(), null, current.value().type(), RequirementStatus.ACCEPTED,
                current.value().priority(), current.value().motivatedBy(), current.value().qualityCategory(),
                current.value().usesTerms(), current.value().acceptanceCriteria(), List.of());

        assertDoesNotThrow(() -> repository.compareAndUpdate(PROJECT_A, current.head(), statusChangeOnly,
                current.titleLanguage(), current.descriptionLanguage(), null,
                noAcceptanceCriteriaLanguages(statusChangeOnly), null));

        Requirement reloaded = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, reloaded.status());
        assertEquals("Login", reloaded.title());
    }

    /**
     * Second regression for the same review finding: a store-first title tagged with a
     * <em>valid but non-canonical</em> tag (e.g. {@code "de-de"}, canonicalizing to {@code
     * "de-DE"}) must not be duplicated once a pass-through {@code compareAndUpdate} rewrites it
     * under its canonicalized form. Before the fix, the preservation query compared the raw
     * stored tag {@code "de-de"} against the freshly canonicalized {@code "de-DE"} being written,
     * mistook them for two different languages, and re-attached the "old" one alongside the new
     * literal - two {@code dcterms:title} literals with the same text under case-differing,
     * semantically identical tags.
     */
    @Test
    void compareAndUpdatePassingThroughANonCanonicalStoreFirstTagDoesNotDuplicateTheTitle() {
        RequirementId id = freshId();
        givenLegacyRequirementWithTitleLanguageTag(PROJECT_A, id, "FR-1", "de-de");
        RequirementCode code = new RequirementCode("FR-1");
        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(PROJECT_A, code)
                .orElseThrow();
        Requirement statusChangeOnly = new Requirement(current.value().id(), code, current.value().title(),
                current.value().description(), null, current.value().type(), RequirementStatus.ACCEPTED,
                current.value().priority(), current.value().motivatedBy(), current.value().qualityCategory(),
                current.value().usesTerms(), current.value().acceptanceCriteria(), List.of());

        repository.compareAndUpdate(PROJECT_A, current.head(), statusChangeOnly,
                current.titleLanguage(), current.descriptionLanguage(), null,
                noAcceptanceCriteriaLanguages(statusChangeOnly), null);

        assertEquals(1, countTitleLiterals(PROJECT_A, id));
    }

    /**
     * Writes a shape-legal {@code arkreq:FunctionalRequirement} straight into the requirements
     * graph with its {@code dcterms:title} carrying {@code titleLanguageTag} verbatim - {@code
     * req_add}/{@code req_update} always route a language tag through {@link
     * de.hauschel.arknet.kernel.LanguageTag#canonicalize(String)} first, so an ill-formed or
     * merely non-canonical tag on {@code title} is reachable only store-first.
     */
    private void givenLegacyRequirementWithTitleLanguageTag(ProjectId projectId, RequirementId id, String code,
            String titleLanguageTag) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\"@" + titleLanguageTag + " ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "\"Login succeeds with valid credentials\" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Writes a shape-legal {@code arkreq:FunctionalRequirement} straight into the requirements
     * graph with its {@code dcterms:title} carrying no language tag at all - the store-first
     * state issue #258's sweep normalises lazily, one write at a time.
     */
    private void givenLegacyRequirementWithUntaggedTitle(ProjectId projectId, RequirementId id, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "\"Login succeeds with valid credentials\" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    private long countTitleLiterals(ProjectId projectId, RequirementId id) {
        String query = "SELECT ?o WHERE { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> <http://purl.org/dc/terms/title> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query).count();
        }
    }

    /** Whether {@code id}'s {@code dcterms:title} still carries an untagged {@code text} literal. */
    private boolean hasUntaggedTitle(ProjectId projectId, RequirementId id, String text) {
        String query = "ASK { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> <http://purl.org/dc/terms/title> \"" + text + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().ask(query);
        }
    }

    // --- rationale (issue #321) --------------------------------------------------------------

    /** {@code arkreq:rationale} is written language-tagged and selected by {@link DisplayLocale}. */
    @Test
    void createWritesATaggedRationaleSelectableViaDisplayLocale() {
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, requirement(freshId(), code, "Anmeldung", "Das System soll authentifizieren.",
                "damit der Support kein Passwort mehr von Hand zuruecksetzt"), "de");

        Requirement asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("damit der Support kein Passwort mehr von Hand zuruecksetzt", asGerman.rationale());
    }

    /**
     * Optional at the store too: no {@code arkreq:rationale} triple is written for a {@code null}
     * rationale, and the requirement stays perfectly readable - unlike an absent title/description,
     * an absent rationale is the ordinary case, not the store-first anomaly {@code findByCode}
     * skips a requirement for.
     */
    @Test
    void createWithoutARationaleWritesNoTripleAndStaysReadable() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, requirement(id, code, "Login", "The system shall authenticate a user."), "en");

        assertEquals(0, countRationaleLiterals(PROJECT_A, id));
        Requirement reloaded = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        assertNull(reloaded.rationale());
        assertEquals("Login", reloaded.title());
        assertNull(repository.findAll(PROJECT_A, "en").getFirst().rationale());
    }

    /** Mirrors the title case: correcting the rationale under a new tag keeps the old variant. */
    @Test
    void compareAndUpdateWithANewLanguageForRationalePreservesTheOriginalLanguageVariant() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirement(freshId(), code, "Login", "The system shall authenticate a user.",
                "so that support stops resetting passwords by hand");
        repository.create(PROJECT_A, created, "en");
        RevisionToken head = currentHead(code);

        Requirement withGermanRationale = requirement(created.id(), code, created.title(), created.description(),
                "damit der Support kein Passwort mehr von Hand zuruecksetzt");
        repository.compareAndUpdate(PROJECT_A, head, withGermanRationale, "en", "en", "de",
                noAcceptanceCriteriaLanguages(withGermanRationale), null);

        assertEquals("so that support stops resetting passwords by hand",
                repository.findByCode(PROJECT_A, code, "en").orElseThrow().rationale());
        assertEquals("damit der Support kein Passwort mehr von Hand zuruecksetzt",
                repository.findByCode(PROJECT_A, code, "de").orElseThrow().rationale());
    }

    /**
     * The one thing {@code rationale} does that {@code title}/{@code description} cannot: a write
     * may legitimately carry no literal for it. Since {@code null} means "leave it alone" at every
     * port above this one and never "remove the recorded reason", such a write must preserve
     * <em>every</em> existing variant - not just the other-language ones, and not sweep an
     * untagged one either (issue #321). A {@code req_set_status} on a requirement whose rationale
     * this adapter's own read did not carry forward is exactly this shape.
     */
    @Test
    void compareAndUpdateWithoutARationalePreservesEveryExistingVariant() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirement(id, code, "Login", "The system shall authenticate a user.",
                "so that support stops resetting passwords by hand");
        repository.create(PROJECT_A, created, "en");
        Requirement withGermanRationale = requirement(id, code, created.title(), created.description(),
                "damit der Support kein Passwort mehr von Hand zuruecksetzt");
        repository.compareAndUpdate(PROJECT_A, currentHead(code), withGermanRationale, "en", "en", "de",
                noAcceptanceCriteriaLanguages(withGermanRationale), null);

        Requirement statusChangeOnly = new Requirement(id, code, created.title(), created.description(), null,
                created.type(), RequirementStatus.ACCEPTED, null, null, null, null, created.acceptanceCriteria(),
                List.of());
        repository.compareAndUpdate(PROJECT_A, currentHead(code), statusChangeOnly, "en", "en", "en",
                noAcceptanceCriteriaLanguages(statusChangeOnly), "en");

        assertEquals(2, countRationaleLiterals(PROJECT_A, id));
        assertEquals("so that support stops resetting passwords by hand",
                repository.findByCode(PROJECT_A, code, "en").orElseThrow().rationale());
        assertEquals("damit der Support kein Passwort mehr von Hand zuruecksetzt",
                repository.findByCode(PROJECT_A, code, "de").orElseThrow().rationale());
        assertEquals(RequirementStatus.ACCEPTED, repository.findByCode(PROJECT_A, code, "en").orElseThrow().status());
    }

    /**
     * Issue #258's sweep applies to this predicate too - but only when a rationale is actually
     * being written. Complements {@link #compareAndUpdateWithoutARationalePreservesEveryExistingVariant},
     * which covers the case where nothing is written and the untagged literal therefore survives.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedRationaleWhenTheWrittenTagEqualsTheProjectDefault() {
        RequirementId id = freshId();
        givenLegacyRequirementWithUntaggedRationale(PROJECT_A, id, "FR-1");
        RequirementCode code = new RequirementCode("FR-1");
        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();
        Requirement withGermanRationale = requirement(id, code, current.value().title(),
                current.value().description(), "damit der Support kein Passwort mehr von Hand zuruecksetzt");

        repository.compareAndUpdate(PROJECT_A, current.head(), withGermanRationale, current.titleLanguage(),
                current.descriptionLanguage(), "de", noAcceptanceCriteriaLanguages(withGermanRationale), "de");

        assertEquals(1, countRationaleLiterals(PROJECT_A, id));
        assertEquals("damit der Support kein Passwort mehr von Hand zuruecksetzt",
                repository.findByCode(PROJECT_A, code, "de").orElseThrow().rationale());
    }

    /** {@code findCurrentByCode} hands the read tag back for the pass-through round trip. */
    @Test
    void findCurrentByCodeCarriesTheRationalesLanguageTag() {
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, requirement(freshId(), code, "Anmeldung", "Das System soll authentifizieren.",
                "damit der Support kein Passwort mehr von Hand zuruecksetzt"), "de");

        assertEquals("de", repository.findCurrentByCode(PROJECT_A, code).orElseThrow().rationaleLanguage());
    }

    /** No rationale, no tag - and the requirement still reads back with its head (issue #321). */
    @Test
    void findCurrentByCodeReportsANullRationaleLanguageWhenNoneIsRecorded() {
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, requirement(freshId(), code, "Login", "The system shall authenticate a user."),
                "en");

        RequirementRepository.CurrentRequirement current = repository.findCurrentByCode(PROJECT_A, code).orElseThrow();
        assertNull(current.rationaleLanguage());
        assertNull(current.value().rationale());
    }

    /**
     * A store-first {@code arkreq:rationale} carrying no language tag at all - the state issue
     * #258's sweep normalises lazily, mirroring {@link #givenLegacyRequirementWithUntaggedTitle}.
     */
    private void givenLegacyRequirementWithUntaggedRationale(ProjectId projectId, RequirementId id, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\"@en ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\"@en ; "
                + "<https://w3id.org/arknet/requirements#rationale> \"so that support stops resetting passwords\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "\"Login succeeds with valid credentials\" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    private long countRationaleLiterals(ProjectId projectId, RequirementId id) {
        String query = "SELECT ?o WHERE { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> <https://w3id.org/arknet/requirements#rationale> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(query).count();
        }
    }

    private RevisionToken currentHead(RequirementCode code) {
        return repository.findCurrentByCode(PROJECT_A, code)
                .map(RequirementRepository.CurrentRequirement::head)
                .orElse(null);
    }

    /**
     * An untagged (all-{@code null}) {@code acceptanceCriteriaLanguageByPosition}, covering every
     * position {@code updated} carries - these tests never exercise per-criterion language
     * resolution, only {@code title}/{@code description}.
     */
    private static Map<Integer, String> noAcceptanceCriteriaLanguages(Requirement updated) {
        Map<Integer, String> languages = new LinkedHashMap<>();
        updated.acceptanceCriteria().forEach(criterion -> languages.put(criterion.position(), null));
        return languages;
    }
}
