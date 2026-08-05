// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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
        return new Requirement(id, code, title, description, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"), List.of());
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
        repository.compareAndUpdate(PROJECT_A, head, withGermanTitle, "de", "en", null);

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

        Requirement statusChangeOnly = new Requirement(created.id(), code, created.title(), created.description(),
                created.type(), RequirementStatus.ACCEPTED, created.priority(), created.motivatedBy(),
                created.qualityCategory(), created.usesTerms(), created.acceptanceCriteria(), List.of());
        repository.compareAndUpdate(PROJECT_A, head, statusChangeOnly, "en", "en", null);

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
        repository.compareAndUpdate(PROJECT_A, head, withGermanDescription, "en", "de", null);

        Requirement asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        Requirement asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Login", asEnglish.title());
        assertEquals("Login", asGerman.title());
        assertEquals("The system shall authenticate a user.", asEnglish.description());
        assertEquals("Das System soll einen Benutzer authentifizieren.", asGerman.description());
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
                current.value().description(), current.value().type(), current.value().status(),
                current.value().priority(), current.value().motivatedBy(), current.value().qualityCategory(),
                current.value().usesTerms(), current.value().acceptanceCriteria(), List.of());

        repository.compareAndUpdate(PROJECT_A, current.head(), withGermanTitle, "de", current.descriptionLanguage(),
                "de");

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
                current.value().description(), current.value().type(), current.value().status(),
                current.value().priority(), current.value().motivatedBy(), current.value().qualityCategory(),
                current.value().usesTerms(), current.value().acceptanceCriteria(), List.of());

        repository.compareAndUpdate(PROJECT_A, current.head(), withFrenchTitle, "fr", current.descriptionLanguage(),
                "de");

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
     * (ADR-005) title tagged with a dangling BCP-47 extension singleton (Turtle's own language-tag
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
                current.value().description(), current.value().type(), RequirementStatus.ACCEPTED,
                current.value().priority(), current.value().motivatedBy(), current.value().qualityCategory(),
                current.value().usesTerms(), current.value().acceptanceCriteria(), List.of());

        assertDoesNotThrow(() -> repository.compareAndUpdate(PROJECT_A, current.head(), statusChangeOnly,
                current.titleLanguage(), current.descriptionLanguage(), null));

        Requirement reloaded = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, reloaded.status());
        assertEquals("Login", reloaded.title());
    }

    /**
     * Second regression for the same review finding: a store-first (ADR-005) title tagged with a
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
                current.value().description(), current.value().type(), RequirementStatus.ACCEPTED,
                current.value().priority(), current.value().motivatedBy(), current.value().qualityCategory(),
                current.value().usesTerms(), current.value().acceptanceCriteria(), List.of());

        repository.compareAndUpdate(PROJECT_A, current.head(), statusChangeOnly,
                current.titleLanguage(), current.descriptionLanguage(), null);

        assertEquals(1, countTitleLiterals(PROJECT_A, id));
    }

    /**
     * Writes a shape-legal {@code arkreq:FunctionalRequirement} straight into the requirements
     * graph with its {@code dcterms:title} carrying {@code titleLanguageTag} verbatim - {@code
     * req_add}/{@code req_update} always route a language tag through {@link
     * de.hauschel.arknet.kernel.LanguageTag#canonicalize(String)} first, so an ill-formed or
     * merely non-canonical tag on {@code title} is reachable only store-first (ADR-005).
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
     * (ADR-005) state issue #258's sweep normalises lazily, one write at a time.
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

    private RevisionToken currentHead(RequirementCode code) {
        return repository.findCurrentByCode(PROJECT_A, code)
                .map(RequirementRepository.CurrentRequirement::head)
                .orElse(null);
    }
}
