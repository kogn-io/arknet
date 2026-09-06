// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
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
import io.kogn.rdf.terms.Literal;

import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.application.port.out.RoleRepository;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.RoleId;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Integration tests for the multilingual {@code arknet:name}/{@code arknet:description} behaviour
 * of {@link KognioRdfRoleRepository}, mirroring
 * {@code KognioRdfConstraintRepositoryMultilingualTest} exactly: language-scoped writes on
 * {@link RoleRepository#create}/{@link RoleRepository#compareAndUpdate}, {@link DisplayLocale}
 * -selected reads, the issue #258 untagged-sibling sweep, and the compare-and-set guard itself.
 *
 * <p>Unlike {@code Constraint}'s mandatory {@code statement}, {@code description} is optional, so
 * this class also covers a role that never carries one at all.</p>
 */
class KognioRdfRoleRepositoryMultilingualTest {

    private static final String ROLE_GRAPH = "https://w3id.org/arknet/model/roles";
    private static final String NAME_PROPERTY = "https://w3id.org/arknet/core#name";
    private static final String DESCRIPTION_PROPERTY = "https://w3id.org/arknet/core#description";
    private static final ProjectId PROJECT_A = new ProjectId("a");

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private RoleRepository repository;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        ShaclWriteGate gate = KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT);
        WriteFunnel funnel = new WriteFunnel(datasetLifecycle, gate, WriteFunnel.DEFAULT_WRITE_CONFLICT);
        repository = KognioRdfRoleRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT, funnel);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static RoleId freshId() {
        return new RoleId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static Role role(RoleId id, RoleCode code, String name, String description) {
        return new Role(id, code, name, description, List.of());
    }

    @Test
    void createWritesATaggedNameAndDescriptionSelectableViaDisplayLocale() {
        RoleCode code = new RoleCode("ROLE-1");
        repository.create(PROJECT_A, role(freshId(), code, "Anforderungsingenieur",
                "Schreibt und pflegt Anforderungen."), "de");

        Role asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Anforderungsingenieur", asGerman.name());
        assertEquals("Schreibt und pflegt Anforderungen.", asGerman.description());
    }

    /**
     * The two-call shape this design exists for: {@code role_add} in one language,
     * {@code role_update} in a second, and both survive.
     */
    @Test
    void compareAndUpdateUnderASecondLanguageKeepsBothVariants() {
        RoleCode code = new RoleCode("ROLE-1");
        RoleId id = freshId();
        repository.create(PROJECT_A, role(id, code, "Anforderungsingenieur", "Schreibt Anforderungen."), "de");
        RevisionToken head = currentHead(code);

        repository.compareAndUpdate(PROJECT_A, head,
                role(id, code, "Requirements Engineer", "Writes requirements."), "en", "en", null);

        Role asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Anforderungsingenieur", asGerman.name());
        Role asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        assertEquals("Requirements Engineer", asEnglish.name());
    }

    /** Correcting only the name must leave the description's other language variants intact. */
    @Test
    void compareAndUpdateOfOneFieldPreservesTheOtherFieldsVariants() {
        RoleCode code = new RoleCode("ROLE-1");
        RoleId id = freshId();
        repository.create(PROJECT_A, role(id, code, "Anforderungsingenieur", "Schreibt Anforderungen."), "de");
        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                role(id, code, "Requirements Engineer", "Writes requirements."), "en", "en", null);

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                role(id, code, "Senior Requirements Engineer", "Writes requirements."), "en", "en", null);

        assertEquals("Anforderungsingenieur", repository.findByCode(PROJECT_A, code, "de").orElseThrow().name());
        assertEquals("Senior Requirements Engineer",
                repository.findByCode(PROJECT_A, code, "en").orElseThrow().name());
        assertEquals(2, literalsOf(id, NAME_PROPERTY).size());
        assertEquals(2, literalsOf(id, DESCRIPTION_PROPERTY).size());
    }

    /** Re-writing the same tag replaces that one variant rather than accumulating duplicates. */
    @Test
    void compareAndUpdateUnderTheSameTagDoesNotDuplicateTheLiteral() {
        RoleCode code = new RoleCode("ROLE-1");
        RoleId id = freshId();
        repository.create(PROJECT_A, role(id, code, "Requirements Engineer", "Writes requirements."), "en");

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                role(id, code, "Requirements Engineer", "Writes and maintains requirements."), "en", "en", null);

        List<Literal> descriptions = literalsOf(id, DESCRIPTION_PROPERTY);
        assertEquals(1, descriptions.size());
        assertEquals("Writes and maintains requirements.", descriptions.get(0).getLexicalForm());
    }

    /**
     * Issue #258's lazy sweep: writing under a tag that equals the project's default replaces an
     * existing untagged literal instead of preserving it as a spurious "other" variant.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedLiteralWhenWritingUnderTheProjectDefault() {
        RoleCode code = new RoleCode("ROLE-1");
        RoleId id = freshId();
        repository.create(PROJECT_A, role(id, code, "Requirements Engineer", "Writes requirements."), null);
        assertTrue(literalsOf(id, DESCRIPTION_PROPERTY).get(0).getLanguageTag().isEmpty());

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                role(id, code, "Requirements Engineer", "Writes and maintains requirements."), "en", "en", "en");

        List<Literal> descriptions = literalsOf(id, DESCRIPTION_PROPERTY);
        assertEquals(1, descriptions.size());
        assertEquals("en", descriptions.get(0).getLanguageTag().orElseThrow());
    }

    /** ... whereas a write under a non-default tag leaves the legacy untagged literal alone. */
    @Test
    void compareAndUpdateUnderANonDefaultTagPreservesAnUntaggedLiteral() {
        RoleCode code = new RoleCode("ROLE-1");
        RoleId id = freshId();
        repository.create(PROJECT_A, role(id, code, "Requirements Engineer", "Writes requirements."), null);

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                role(id, code, "Anforderungsingenieur", "Schreibt Anforderungen."), "de", "de", "en");

        assertEquals(2, literalsOf(id, DESCRIPTION_PROPERTY).size());
    }

    @Test
    void compareAndUpdateRejectsAStaleHead() {
        RoleCode code = new RoleCode("ROLE-1");
        RoleId id = freshId();
        repository.create(PROJECT_A, role(id, code, "Requirements Engineer", null), "en");
        RevisionToken stale = currentHead(code);
        repository.compareAndUpdate(PROJECT_A, stale, role(id, code, "Senior Requirements Engineer", null), "en",
                "en", null);

        assertThrows(RoleConcurrentlyModifiedException.class, () -> repository.compareAndUpdate(
                PROJECT_A, stale, role(id, code, "Requirements Engineer", null), "en", "en", null));
    }

    /** {@code findCurrentByCode} hands back the tag each selected literal actually carries. */
    @Test
    void findCurrentByCodeReportsTheTagEachFieldWasSelectedUnder() {
        RoleCode code = new RoleCode("ROLE-1");
        repository.create(PROJECT_A, role(freshId(), code, "Requirements Engineer", "Writes requirements."), "en");

        RoleRepository.CurrentRole current = repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow();

        assertEquals("en", current.nameLanguage());
        assertEquals("en", current.descriptionLanguage());
    }

    /** A role carrying no description at all reports a {@code null} description language, not an error. */
    @Test
    void findCurrentByCodeReportsANullDescriptionLanguageWhenNoneExists() {
        RoleCode code = new RoleCode("ROLE-1");
        repository.create(PROJECT_A, role(freshId(), code, "Architect", null), "en");

        RoleRepository.CurrentRole current = repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow();

        assertEquals("en", current.nameLanguage());
        assertNull(current.descriptionLanguage());
        assertNull(current.value().description());
    }

    /** A store-first role written with untagged literals reads back with a {@code null} tag. */
    @Test
    void findCurrentByCodeReportsANullTagForAnUntaggedLegacyLiteral() {
        RoleCode code = new RoleCode("ROLE-1");
        repository.create(PROJECT_A, role(freshId(), code, "Requirements Engineer", "Writes requirements."), null);

        RoleRepository.CurrentRole current = repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow();

        assertNull(current.nameLanguage());
        assertNull(current.descriptionLanguage());
    }

    /**
     * {@code findAll} selects per subject from the bulk literal reads - a project mixing languages
     * must not multiply a role into one entry per name/description combination.
     */
    @Test
    void findAllSelectsOneVariantPerRole() {
        RoleCode code = new RoleCode("ROLE-1");
        RoleId id = freshId();
        repository.create(PROJECT_A, role(id, code, "Anforderungsingenieur", "Schreibt Anforderungen."), "de");
        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                role(id, code, "Requirements Engineer", "Writes requirements."), "en", "en", null);

        List<Role> all = repository.findAll(PROJECT_A, "de");

        assertEquals(1, all.size());
        assertEquals("Anforderungsingenieur", all.get(0).name());
    }

    // --- the read-modify-write read under the project's language (issue #456) ----------------

    @Test
    void findCurrentByCodeSelectsTheTextFieldsInTheProjectsDefaultLanguage() {
        RoleCode code = new RoleCode("ROLE-1");
        givenBilingualRole(code);

        RoleRepository.CurrentRole current = repository.findCurrentByCode(PROJECT_A, code, "de").orElseThrow();

        assertEquals("Anforderungsingenieur", current.value().name());
        assertEquals(repository.findByCode(PROJECT_A, code, "de").orElseThrow(), current.value(),
                "role_update must read the role role_get shows for the same project");
    }

    @Test
    void findCurrentByCodeCarriesTheLanguageTagsOfTheProjectsDefaultLanguage() {
        RoleCode code = new RoleCode("ROLE-1");
        givenBilingualRole(code);

        RoleRepository.CurrentRole current = repository.findCurrentByCode(PROJECT_A, code, "de").orElseThrow();

        assertEquals("de", current.nameLanguage());
        assertEquals("de", current.descriptionLanguage());
    }

    @Test
    void findCurrentByCodeWithoutAProjectDefaultLanguageStaysOnTheConfiguredPreference() {
        RoleCode code = new RoleCode("ROLE-1");
        givenBilingualRole(code);

        RoleRepository.CurrentRole current = repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow();

        assertEquals("Requirements Engineer", current.value().name());
        assertEquals("en", current.nameLanguage());
    }

    /** A role carrying both its {@code @en} and its {@code @de} name/description variant. */
    private void givenBilingualRole(RoleCode code) {
        RoleId id = freshId();
        repository.create(PROJECT_A, role(id, code, "Requirements Engineer", "Writes requirements."), "en");
        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                role(id, code, "Anforderungsingenieur", "Schreibt Anforderungen."), "de", "de", "de");
    }

    private RevisionToken currentHead(RoleCode code) {
        return repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow().head();
    }

    private List<Literal> literalsOf(RoleId id, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + ROLE_GRAPH + "> { <"
                + id.value().value() + "> <" + predicateIri + "> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            List<Literal> literals = new ArrayList<>();
            handle.sparqlQuery().select(query)
                    .forEach(row -> literals.add((Literal) row.getValue("o").orElseThrow()));
            return literals;
        }
    }
}
