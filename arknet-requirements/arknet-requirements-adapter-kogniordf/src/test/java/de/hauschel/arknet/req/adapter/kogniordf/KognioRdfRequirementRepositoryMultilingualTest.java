// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));
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
        repository.compareAndUpdate(PROJECT_A, head, withGermanTitle, "de", "en");

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
                created.qualityCategory(), created.usesTerms(), created.acceptanceCriteria());
        repository.compareAndUpdate(PROJECT_A, head, statusChangeOnly, "en", "en");

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
        repository.compareAndUpdate(PROJECT_A, head, withGermanDescription, "en", "de");

        Requirement asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        Requirement asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Login", asEnglish.title());
        assertEquals("Login", asGerman.title());
        assertEquals("The system shall authenticate a user.", asEnglish.description());
        assertEquals("Das System soll einen Benutzer authentifizieren.", asGerman.description());
    }

    @Test
    void createRejectsAnIllFormedLanguageTag() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirement(freshId(), code, "Login", "The system shall authenticate a user.");

        assertThrows(InvalidLanguageTagException.class, () -> repository.create(PROJECT_A, created, "de_DE"));
    }

    private RevisionToken currentHead(RequirementCode code) {
        return repository.findCurrentByCode(PROJECT_A, code)
                .map(RequirementRepository.CurrentRequirement::head)
                .orElse(null);
    }
}
