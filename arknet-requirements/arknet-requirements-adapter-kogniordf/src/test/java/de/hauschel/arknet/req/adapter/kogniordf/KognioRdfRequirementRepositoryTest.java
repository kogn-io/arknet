// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
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
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.DuplicateRequirementCodeException;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementNotFoundException;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.req.domain.UnsupportedRequirementStatusException;

/**
 * Integration test for {@link KognioRdfRequirementRepository} against an in-memory
 * RDF4J-backed kognio-rdf store.
 */
class KognioRdfRequirementRepositoryTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");

    /**
     * The store's on-disk home, managed by JUnit rather than {@code Files.createTempDirectory},
     * which left its directories behind - harmless while the store is {@code IN_MEMORY}, but
     * still an inode left in {@code /tmp} for every test run. Deleted after {@link #tearDown()}
     * has shut the store down.
     */
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

    /** Fresh, valid opaque identity - every test picks its own so ids never collide. */
    private static RequirementId freshId() {
        return new RequirementId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    /**
     * Test convenience for call sites that only need "replace this by identity" and do not
     * exercise the compare-and-set guard itself: reads {@code updated}'s current
     * head via {@link RequirementRepository#findCurrentByCode} and immediately applies
     * {@code updated} through it - there is no unconditional {@code update} left on the port.
     */
    private void replaceViaCompareAndUpdate(ProjectId projectId, Requirement updated) {
        RevisionToken head = repository.findCurrentByCode(projectId, updated.code())
                .map(RequirementRepository.CurrentRequirement::head)
                .orElse(null);
        repository.compareAndUpdate(projectId, head, updated);
    }

    @Test
    void createsAndFindsFunctionalRequirementByCode() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));

        repository.create(PROJECT_A, requirement);
        Optional<Requirement> found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"));

        assertEquals(Optional.of(requirement), found);
        assertEquals("The system shall authenticate a user.", found.orElseThrow().description());
    }

    @Test
    void findAllContainsAllCreatedRequirements() {
        Requirement first = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));

        repository.create(PROJECT_A, first);
        assertEquals(1, repository.findAll(PROJECT_A).size());

        Requirement second = new Requirement(
                freshId(), new RequirementCode("FR-2"), "Logout", "The system shall end a user session.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));
        repository.create(PROJECT_A, second);

        List<Requirement> all = repository.findAll(PROJECT_A);
        assertEquals(2, all.size());
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    @Test
    void createRejectsAnAlreadyExistingIdentityAndPersistsNothingElse() {
        RequirementId id = freshId();
        Requirement requirement = new Requirement(id, new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));
        repository.create(PROJECT_A, requirement);

        Requirement collidingId = new Requirement(id, new RequirementCode("FR-2"), "Logout",
                "The system shall end a user session.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));

        assertThrows(ResourceAlreadyExistsException.class,
                () -> repository.create(PROJECT_A, collidingId));
        assertEquals(1, repository.findAll(PROJECT_A).size());
        assertEquals(Optional.of(requirement), repository.findByCode(PROJECT_A, new RequirementCode("FR-1")));
    }

    /**
     * Identity collision and code collision are distinct failure modes: two different, freshly
     * minted identities both claiming {@code FR-1} must be rejected by code, not by identity.
     */
    @Test
    void createRejectsADuplicateCodeUnderADifferentIdentityAndPersistsNothingElse() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement first = new Requirement(freshId(), code, "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));
        repository.create(PROJECT_A, first);

        Requirement collidingCode = new Requirement(freshId(), code, "Logout",
                "The system shall end a user session.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));

        assertThrows(DuplicateRequirementCodeException.class,
                () -> repository.create(PROJECT_A, collidingCode));
        assertEquals(1, repository.findAll(PROJECT_A).size());
        assertEquals(Optional.of(first), repository.findByCode(PROJECT_A, code));
    }

    @Test
    void updateReplacesByIdentityInsteadOfDuplicating() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        Requirement proposed = new Requirement(id, code, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));
        Requirement accepted = new Requirement(id, code, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.ACCEPTED, null, null, null, null, List.of("Login succeeds with valid credentials"));

        repository.create(PROJECT_A, proposed);
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        assertEquals(Optional.of(accepted), repository.findByCode(PROJECT_A, code));
        assertEquals(1, repository.findAll(PROJECT_A).size());
        assertEquals(accepted, repository.findAll(PROJECT_A).get(0));
    }

    /** The opaque identity is preserved across an update - only the requirement's state changes. */
    @Test
    void updatePreservesTheOpaqueIdentity() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, new Requirement(id, code, "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials")));

        replaceViaCompareAndUpdate(PROJECT_A, new Requirement(id, code, "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.ACCEPTED, null, null, null, null, List.of("Login succeeds with valid credentials")));

        assertEquals(id, repository.findByCode(PROJECT_A, code).orElseThrow().id());
    }

    // ---- compareAndUpdate: CAS guard against lost updates, head-based ----

    @Test
    void compareAndUpdateAppliesWhenExpectedHeadMatchesTheStoredHead() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        Requirement proposed = new Requirement(id, code, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null,
                List.of("Login succeeds with valid credentials"));
        repository.create(PROJECT_A, proposed);
        RevisionToken head = repository.findCurrentByCode(PROJECT_A, code).orElseThrow().head();
        Requirement accepted = new Requirement(id, code, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.ACCEPTED, null, null, null, null,
                List.of("Login succeeds with valid credentials"));

        repository.compareAndUpdate(PROJECT_A, head, accepted);

        assertEquals(Optional.of(accepted), repository.findByCode(PROJECT_A, code));
    }

    /**
     * The core of this fix, degenerated from a full-snapshot comparison to a head
     * comparison: a stale {@code expectedHead} (no longer matching the head another
     * writer already advanced) must be rejected without mutating the store - the caller re-reads
     * and retries instead of silently overwriting the concurrent change.
     */
    @Test
    void compareAndUpdateThrowsAndPersistsNothingWhenExpectedHeadIsStale() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        Requirement original = new Requirement(id, code, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null,
                List.of("Login succeeds with valid credentials"));
        repository.create(PROJECT_A, original);
        RevisionToken staleHead = repository.findCurrentByCode(PROJECT_A, code).orElseThrow().head();
        // Simulates a concurrent writer that already committed a change since staleHead was read.
        Requirement concurrentlyAccepted = new Requirement(id, code, "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL, RequirementStatus.ACCEPTED,
                null, null, null, null, List.of("Login succeeds with valid credentials"));
        replaceViaCompareAndUpdate(PROJECT_A, concurrentlyAccepted);

        Requirement staleAttempt = new Requirement(id, code, "Login renamed",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                null, null, null, null, List.of("Login succeeds with valid credentials"));

        assertThrows(RequirementConcurrentlyModifiedException.class,
                () -> repository.compareAndUpdate(PROJECT_A, staleHead, staleAttempt));
        assertEquals(Optional.of(concurrentlyAccepted), repository.findByCode(PROJECT_A, code));
    }

    @Test
    void compareAndUpdateThrowsWhenTheIdentityDoesNotExistAtAll() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        Requirement neverCreated = new Requirement(id, code, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null,
                List.of("Login succeeds with valid credentials"));

        assertThrows(RequirementNotFoundException.class,
                () -> repository.compareAndUpdate(PROJECT_A, null, neverCreated));
        assertTrue(repository.findAll(PROJECT_A).isEmpty());
        assertEquals(Optional.empty(), repository.findCurrentByCode(PROJECT_A, code));
    }

    /**
     * Regression guard for the replace-by-identity write path, exercised via {@code
     * compareAndUpdate}: linked terms and acceptance criteria must still survive the CAS write
     * path.
     */
    @Test
    void compareAndUpdatePreservesLinkedTermsAndAcceptanceCriteria() {
        givenTerm(PROJECT_A, "TERM-1");
        Requirement created = requirementUsing(termRef("TERM-1"));
        repository.create(PROJECT_A, created);
        RevisionToken head = repository.findCurrentByCode(PROJECT_A, created.code()).orElseThrow().head();

        Requirement accepted = new Requirement(created.id(), created.code(), created.title(), created.description(),
                created.type(), RequirementStatus.ACCEPTED, created.priority(), created.motivatedBy(),
                created.qualityCategory(), created.usesTerms(), created.acceptanceCriteria());
        repository.compareAndUpdate(PROJECT_A, head, accepted);

        Requirement found = repository.findByCode(PROJECT_A, created.code()).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, found.status());
        assertEquals(List.of(termRef("TERM-1")), found.usesTerms());
        assertEquals(List.of("Login succeeds with valid credentials"), found.acceptanceCriteria());
    }

    @Test
    void findByCodeReturnsEmptyForUnknownCode() {
        assertEquals(Optional.empty(), repository.findByCode(PROJECT_A, new RequirementCode("FR-99")));
    }

    @Test
    void projectsAreIsolated() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));

        repository.create(PROJECT_A, requirement);

        assertTrue(repository.findAll(PROJECT_B).isEmpty());
    }

    @Test
    void createsAndFindsNonFunctionalRequirement() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("NFR-1"), "Response time < 200ms",
                "95% of requests shall complete in under 200ms.",
                RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));

        repository.create(PROJECT_A, requirement);
        Optional<Requirement> found = repository.findByCode(PROJECT_A, new RequirementCode("NFR-1"));

        assertEquals(Optional.of(requirement), found);
        assertEquals(RequirementType.NON_FUNCTIONAL, found.get().type());
    }

    @Test
    void createsAndFindsPriorityMotivatedByAndQualityCategory() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("NFR-1"), "Response time < 200ms",
                "95% of requests shall complete in under 200ms.",
                RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED,
                Priority.MUST_HAVE, "https://w3id.org/arknet/model/goal/fast-ux", "performance", null, List.of("Login succeeds with valid credentials"));

        repository.create(PROJECT_A, requirement);
        Optional<Requirement> found = repository.findByCode(PROJECT_A, new RequirementCode("NFR-1"));

        assertEquals(Optional.of(requirement), found);
        assertEquals(Priority.MUST_HAVE, found.orElseThrow().priority());
        assertEquals("https://w3id.org/arknet/model/goal/fast-ux", found.orElseThrow().motivatedBy());
        assertEquals("performance", found.orElseThrow().qualityCategory());
        assertTrue(repository.findAll(PROJECT_A).contains(requirement));
    }

    @Test
    void createdWithoutOptionalFieldsAreFoundWithNullOptionalFields() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));

        repository.create(PROJECT_A, requirement);
        Optional<Requirement> found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"));
        Requirement foundViaFindAll = repository.findAll(PROJECT_A).get(0);

        assertEquals(Optional.of(requirement), found);
        assertEquals(requirement, foundViaFindAll);
        assertNull(found.orElseThrow().priority());
        assertNull(found.orElseThrow().motivatedBy());
        assertNull(found.orElseThrow().qualityCategory());
    }

    /**
     * Regression test for the RDFS gotcha: {@code RequirementShape} targets the abstract
     * {@code arkreq:Requirement}, while the adapter types instances as the concrete
     * {@code arkreq:FunctionalRequirement}. The write-gate must reason the subclass axioms
     * from {@code arknet-requirements.ttl} into the validated data graph, otherwise the
     * shape silently never fires and this test would pass with an invalid requirement saved.
     */
    @Test
    void createRejectsRequirementViolatingShaclShapes() {
        Requirement tooShortDescription = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "Hi", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));

        assertThrows(WriteConstraintViolationException.class,
                () -> repository.create(PROJECT_A, tooShortDescription));
        assertTrue(repository.findAll(PROJECT_A).isEmpty());
    }

    /**
     * {@code arkreq:acceptanceCriterion} is mandatory ({@code sh:minCount 1}). The
     * {@link Requirement} constructor already rejects an empty list, so this candidate graph -
     * unlike the domain object - can omit the triple entirely to prove the gate itself, not just
     * the domain invariant, is the thing rejecting a store-first requirement without a testable
     * "Done when ...". Mirrors {@link #gateRejectsUsesTermPointingAtSomethingThatIsNotAConcept}.
     */
    @Test
    void gateRejectsRequirementWithoutAcceptanceCriterion() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#FunctionalRequirement"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("FR-1"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Login"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/description"),
                rdf.createLiteral("The system shall authenticate a user."));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#status"),
                rdf.createIRI("https://w3id.org/arknet/requirements#Proposed"));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));

        assertTrue(ex.getMessage().contains("acceptanceCriterion"), ex.getMessage());
    }

    // ---- acceptanceCriterion: testable "Done when ..." criteria -------------------------

    @Test
    void createsAndFindsSeveralAcceptanceCriteria() {
        List<String> criteria = List.of("Login succeeds with valid credentials", "Login is rate-limited");
        Requirement requirement = new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                null, null, null, null, criteria);

        repository.create(PROJECT_A, requirement);

        assertEquals(List.copyOf(criteria).stream().sorted().toList(),
                repository.findByCode(PROJECT_A, new RequirementCode("FR-1"))
                        .orElseThrow().acceptanceCriteria().stream().sorted().toList());
        assertEquals(requirement.acceptanceCriteria().stream().sorted().toList(),
                repository.findAll(PROJECT_A).get(0).acceptanceCriteria().stream().sorted().toList());
    }

    /**
     * Regression guard for the replace-by-identity write path, same rationale as
     * {@link #usesTermEdgesSurviveAReplacingUpdate}: {@code update} wipes the subject's triples
     * before re-writing them, so a status change must carry the acceptance criteria along.
     */
    @Test
    void acceptanceCriteriaSurviveAReplacingUpdate() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementUsing();
        repository.create(PROJECT_A, created);

        Requirement reloaded = repository.findByCode(PROJECT_A, code).orElseThrow();
        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(),
                reloaded.motivatedBy(), reloaded.qualityCategory(), reloaded.usesTerms(),
                reloaded.acceptanceCriteria());
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        Requirement found = repository.findByCode(PROJECT_A, code).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, found.status());
        assertEquals(reloaded.acceptanceCriteria(), found.acceptanceCriteria());
    }

    /**
     * Regression guard: a requirement written by an older {@code req_add} has no
     * {@code arkreq:acceptanceCriterion} triple at all - SHACL only gates writes, so this state
     * is reachable simply by not having re-saved the requirement since the field became
     * mandatory, not just via an exotic store-first bypass. Before the fix, {@code findByCode}
     * fed the resulting empty list straight into {@link Requirement}'s constructor, which rejects
     * an empty list unconditionally, so this crashed {@code req_get} instead of returning the
     * requirement with a placeholder.
     */
    @Test
    void findByCodeSubstitutesAPlaceholderForARequirementPredatingAcceptanceCriterion() {
        RequirementId id = freshId();
        givenLegacyRequirementWithoutAcceptanceCriterion(PROJECT_A, id, "FR-1");

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1")).orElseThrow();

        assertEquals(1, found.acceptanceCriteria().size());
        assertTrue(found.acceptanceCriteria().get(0).contains("Altdatensatz"), found.acceptanceCriteria().toString());
    }

    /** Same regression as above, exercised via the batch {@link RequirementRepository#findAll}. */
    @Test
    void findAllSubstitutesAPlaceholderForARequirementPredatingAcceptanceCriterion() {
        RequirementId id = freshId();
        givenLegacyRequirementWithoutAcceptanceCriterion(PROJECT_A, id, "FR-1");

        List<Requirement> all = repository.findAll(PROJECT_A);

        assertEquals(1, all.size());
        assertEquals(1, all.get(0).acceptanceCriteria().size());
        assertTrue(all.get(0).acceptanceCriteria().get(0).contains("Altdatensatz"));
    }

    /**
     * Regression for issue #157: {@link #findByCode}/{@link #findAll} surface the legacy
     * placeholder to keep the read path from crashing, but that placeholder is a read-time
     * stand-in, not a store fact - {@link RequirementRepository.CurrentRequirement} must say so
     * explicitly, otherwise nothing stops a caller's read-modify-write round trip
     * ({@code accept}/{@code update}/{@code linkTerm}) from writing it back as a genuine,
     * persisted {@code arkreq:acceptanceCriterion} literal on the very next
     * {@link #compareAndUpdate}.
     */
    @Test
    void findCurrentByCodeSignalsSynthesizedAcceptanceCriteriaForARequirementPredatingAcceptanceCriterion() {
        RequirementId id = freshId();
        givenLegacyRequirementWithoutAcceptanceCriterion(PROJECT_A, id, "FR-1");

        RequirementRepository.CurrentRequirement current =
                repository.findCurrentByCode(PROJECT_A, new RequirementCode("FR-1")).orElseThrow();

        assertTrue(current.acceptanceCriteriaIsSynthesized());
        assertTrue(current.value().acceptanceCriteria().get(0).contains("Altdatensatz"));
    }

    /**
     * Counterpart to {@link
     * #findCurrentByCodeSignalsSynthesizedAcceptanceCriteriaForARequirementPredatingAcceptanceCriterion}:
     * a requirement written with a real {@code arkreq:acceptanceCriterion} triple must not be
     * flagged as synthesized, or the guard this signal backs would reject writes it should let
     * through.
     */
    @Test
    void findCurrentByCodeDoesNotSignalSynthesizedAcceptanceCriteriaForARegularRequirement() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, new Requirement(id, code, "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, null,
                List.of("Login succeeds with valid credentials")));

        RequirementRepository.CurrentRequirement current =
                repository.findCurrentByCode(PROJECT_A, code).orElseThrow();

        assertFalse(current.acceptanceCriteriaIsSynthesized());
        assertEquals(List.of("Login succeeds with valid credentials"), current.value().acceptanceCriteria());
    }

    /**
     * Writes a shape-legal {@code arkreq:FunctionalRequirement} straight into the requirements
     * graph without an {@code arkreq:acceptanceCriterion} triple - exactly what a {@code req_add}
     * call made before the field became mandatory would have produced, since the shape only gates writes made
     * after the SHACL property was added.
     */
    private void givenLegacyRequirementWithoutAcceptanceCriterion(ProjectId projectId, RequirementId id,
            String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Store-first regression test: two {@code arkreq:acceptanceCriterion} literals
     * with the same lexical form but different language tags read back as duplicate strings,
     * because {@link #readAcceptanceCriteria} reads them via {@code literalOf(...).getLexicalForm()}
     * - which discards the language tag. {@link Requirement}'s constructor rejects duplicate
     * acceptance criteria unconditionally, so before the fix this crashed {@code findByCode}
     * instead of returning the requirement, exactly the way the all-empty case used to.
     */
    @Test
    void findByCodeDeduplicatesAcceptanceCriteriaDifferingOnlyByLanguageTag() {
        RequirementId id = freshId();
        givenRequirementWithDuplicateAcceptanceCriterionByLanguageTag(PROJECT_A, id, "FR-1");

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1")).orElseThrow();

        assertEquals(List.of("Login succeeds with valid credentials"), found.acceptanceCriteria());
    }

    /**
     * Same defect as {@link #findByCodeDeduplicatesAcceptanceCriteriaDifferingOnlyByLanguageTag},
     * exercised via the batch {@link RequirementRepository#findAll} - a whitespace-only literal
     * alongside a valid one is the "blank entry" half of the reachable state, rather than the
     * "duplicate entry" half.
     */
    @Test
    void findAllFiltersOutABlankAcceptanceCriterionAlongsideAValidOne() {
        RequirementId id = freshId();
        givenRequirementWithBlankAndValidAcceptanceCriterion(PROJECT_A, id, "FR-1");

        List<Requirement> all = repository.findAll(PROJECT_A);

        assertEquals(1, all.size());
        assertEquals(List.of("Login succeeds with valid credentials"), all.get(0).acceptanceCriteria());
    }

    /**
     * Writes a shape-legal {@code arkreq:FunctionalRequirement} straight into the requirements
     * graph with two {@code arkreq:acceptanceCriterion} literals sharing one lexical form but
     * carrying different language tags - {@code RequirementShape} places no constraint ruling
     * this out, and {@code req_add} cannot produce it (it writes plain literals, no language
     * tag), so this is reachable only store-first (ADR-005).
     */
    private void givenRequirementWithDuplicateAcceptanceCriterionByLanguageTag(ProjectId projectId,
            RequirementId id, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "\"Login succeeds with valid credentials\"@en , \"Login succeeds with valid credentials\"@de "
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
     * graph with one valid {@code arkreq:acceptanceCriterion} literal and one whitespace-only
     * one - {@code RequirementShape} places no {@code sh:pattern}/blank-rejection on the
     * property, and {@link Requirement}'s constructor is the only place that rejects blanks, so
     * this is reachable only store-first (ADR-005).
     */
    private void givenRequirementWithBlankAndValidAcceptanceCriterion(ProjectId projectId, RequirementId id,
            String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "\"Login succeeds with valid credentials\" , \"   \" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- row multiplication on priority/qualityCategory ----------------------------------

    /**
     * Store-first regression test: {@code rshapes:Requirement-priority}'s {@code sh:maxCount 1} is
     * {@code sh:Warning}-severity only, so it never blocks a write - a subject with two
     * {@code arkreq:priority} triples is therefore reachable, even though {@code req_add}/
     * {@code req_set_status} never write more than one. Before the fix, {@link #findAll} mapped
     * every SPARQL row straight to a {@link Requirement} without grouping by subject, so the
     * cross-product row multiplication surfaced the same {@code FR-1} twice in the result list -
     * this pins that it now surfaces exactly once.
     */
    @Test
    void findAllReturnsExactlyOneRequirementForASubjectWithTwoPriorities() {
        RequirementId id = freshId();
        givenRequirementWithTwoPriorities(PROJECT_A, id, "FR-1");

        List<Requirement> all = repository.findAll(PROJECT_A);

        assertEquals(1, all.size());
        assertEquals(new RequirementCode("FR-1"), all.get(0).code());
        // First-seen wins: MUST_HAVE is the first arkreq:priority triple
        // givenRequirementWithTwoPriorities inserts, and RDF4J's MemoryStore preserves insertion
        // order for equal-subject/-predicate statements, so this is deterministic, not incidental.
        assertEquals(Priority.MUST_HAVE, all.get(0).priority());
    }

    /** The chosen priority is deterministic across repeated reads against the same store state. */
    @Test
    void findAllPicksTheSamePriorityOnRepeatedReads() {
        RequirementId id = freshId();
        givenRequirementWithTwoPriorities(PROJECT_A, id, "FR-1");

        Priority first = repository.findAll(PROJECT_A).get(0).priority();
        Priority second = repository.findAll(PROJECT_A).get(0).priority();

        assertEquals(first, second);
    }

    /**
     * {@code findByCode} deliberately stays untouched by this fix (its single-row
     * {@code findFirst()} is already internally consistent): this regression guard proves it still
     * works for a requirement carrying an additional, unrelated {@code rdf:type} triple - the
     * (store-first-only) case {@link #findByCode}'s now-explicit type {@code FILTER} was hardened
     * against, so it must not reject or crash on a subject that is legitimately typed as a
     * {@code FunctionalRequirement} plus something else.
     */
    @Test
    void findByCodeStillWorksForARequirementWithAnAdditionalUnrelatedType() {
        RequirementId id = freshId();
        givenRequirementWithAnAdditionalType(PROJECT_A, id, "FR-1",
                "https://w3id.org/arknet/architecture#Stakeholder");

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1")).orElseThrow();

        assertEquals(RequirementType.FUNCTIONAL, found.type());
        assertEquals("Login", found.title());
    }

    /**
     * Writes an {@code arkreq:FunctionalRequirement} straight into the requirements graph with two
     * {@code arkreq:priority} triples - shape-legal (the property's {@code sh:maxCount 1} is
     * {@code sh:Warning}-severity, so the write-gate never rejects it), but unreachable via
     * {@code req_add}/{@code req_set_status}, which only ever write one.
     */
    private void givenRequirementWithTwoPriorities(ProjectId projectId, RequirementId id, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "\"Login succeeds with valid credentials\" ; "
                + "<https://w3id.org/arknet/requirements#priority> <https://w3id.org/arknet/requirements#MustHave> ; "
                + "<https://w3id.org/arknet/requirements#priority> <https://w3id.org/arknet/requirements#ShouldHave> "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Writes an {@code arkreq:FunctionalRequirement} straight into the requirements graph with one
     * additional, unrelated {@code rdf:type} triple - RDF-legal (nothing forbids a subject from
     * carrying several types) and store-first (ADR-005) reachable, but unreachable via
     * {@code req_add}, which types a requirement exactly once.
     */
    private void givenRequirementWithAnAdditionalType(
            ProjectId projectId, RequirementId id, String code, String additionalTypeIri) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "a <" + additionalTypeIri + "> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "\"Login succeeds with valid credentials\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- status: SHACL-legal but MVP-unsupported (issue #160) ----------------------------

    /**
     * Store-first regression test for issue #160: {@code requirements-shapes.ttl}'s
     * {@code Requirement-status} shape SHACL-legally allows six status individuals via
     * {@code sh:in}, but {@link RequirementStatus} implements only two
     * ({@code PROPOSED}/{@code ACCEPTED}) - {@code arkreq:Rejected} is unreachable via
     * {@code req_add}/{@code req_set_status}, but a store-first (ADR-005) edit can legally write
     * it. Before the fix, {@code statusFromIri} threw a raw, uncaught {@link IllegalStateException}
     * that named neither the requirement nor which method it broke; the dedicated exception must
     * name both.
     */
    @Test
    void findByCodeThrowsUnsupportedStatusExceptionNamingTheRequirementForAShaclLegalStatus() {
        RequirementId id = freshId();
        givenRequirementWithStatus(PROJECT_A, id, "FR-1", "https://w3id.org/arknet/requirements#Rejected");

        UnsupportedRequirementStatusException thrown = assertThrows(
                UnsupportedRequirementStatusException.class,
                () -> repository.findByCode(PROJECT_A, new RequirementCode("FR-1")));

        assertEquals(PROJECT_A, thrown.projectId());
        assertEquals(new RequirementCode("FR-1"), thrown.requirementCode());
        assertEquals("https://w3id.org/arknet/requirements#Rejected", thrown.statusIri());
        assertTrue(thrown.getMessage().contains("FR-1"),
                "message must name the affected requirement, not just the raw status IRI");
    }

    /** {@link RequirementRepository#findCurrentByCode} must fail the same way, not just findByCode. */
    @Test
    void findCurrentByCodeThrowsUnsupportedStatusExceptionForAShaclLegalStatus() {
        RequirementId id = freshId();
        givenRequirementWithStatus(PROJECT_A, id, "FR-1", "https://w3id.org/arknet/requirements#Deprecated");

        assertThrows(UnsupportedRequirementStatusException.class,
                () -> repository.findCurrentByCode(PROJECT_A, new RequirementCode("FR-1")));
    }

    /**
     * Before the fix this was the crash the issue described as project-wide: {@link #findAll}
     * called {@code statusFromIri} once per row inside a {@code computeIfAbsent} lambda, so one
     * requirement with an unsupported status aborted the whole listing with an uninformative
     * {@link IllegalStateException} - unreadable requirements alongside perfectly good ones. The
     * fix keeps the abort (a SHACL-legal value must fail visibly, not vanish - see the class-level
     * Javadoc), but with a dedicated, named exception instead of a raw one.
     */
    @Test
    void findAllThrowsUnsupportedStatusExceptionEvenWithOtherValidRequirementsPresent() {
        repository.create(PROJECT_A, new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(),
                List.of("Login succeeds with valid credentials")));
        givenRequirementWithStatus(PROJECT_A, freshId(), "FR-2", "https://w3id.org/arknet/requirements#Verified");

        UnsupportedRequirementStatusException thrown = assertThrows(
                UnsupportedRequirementStatusException.class, () -> repository.findAll(PROJECT_A));

        assertEquals(new RequirementCode("FR-2"), thrown.requirementCode());
    }

    /**
     * Writes an {@code arkreq:FunctionalRequirement} straight into the requirements graph with an
     * arbitrary {@code arkreq:status} object - used to reach the four SHACL-legal status
     * individuals ({@code Rejected}/{@code Implemented}/{@code Verified}/{@code Deprecated})
     * {@link RequirementStatus} does not implement, unreachable via {@code req_add}/
     * {@code req_set_status}.
     */
    private void givenRequirementWithStatus(ProjectId projectId, RequirementId id, String code, String statusIri) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <" + statusIri + "> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "\"Login succeeds with valid credentials\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- priority/motivatedBy/qualityCategory: SHACL-legal but type-mismatched (issue #163) ----

    /**
     * Store-first regression test for issue #163: {@code requirements-shapes.ttl}'s
     * {@code Requirement-priority} shape declares no {@code sh:nodeKind}, so a store-first
     * (ADR-005) edit can legally write {@code arkreq:priority} as a literal instead of an IRI.
     * Before the fix {@code priorityOf}'s unguarded {@code (IRI) value} cast threw an uncaught
     * {@link ClassCastException}; the fix reads the mismatched value as "not set" instead.
     */
    @Test
    void findByCodeReadsATypeMismatchedPriorityAsNotSetInsteadOfThrowing() {
        RequirementId id = freshId();
        givenRequirementWithLiteralInsteadOfIri(PROJECT_A, id, "FR-1", "priority", "not-an-iri");

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1")).orElseThrow();

        assertNull(found.priority());
    }

    /** {@code motivatedBy}'s equivalent of {@link #findByCodeReadsATypeMismatchedPriorityAsNotSetInsteadOfThrowing}. */
    @Test
    void findByCodeReadsATypeMismatchedMotivatedByAsNotSetInsteadOfThrowing() {
        RequirementId id = freshId();
        givenRequirementWithLiteralInsteadOfIri(PROJECT_A, id, "FR-1", "motivatedBy", "not-an-iri");

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1")).orElseThrow();

        assertNull(found.motivatedBy());
    }

    /**
     * {@code qualityCategory}'s expected RDF term kind is the opposite of {@code priority}/
     * {@code motivatedBy}'s (a {@link Literal}, not an {@link IRI}) - this test writes an IRI
     * where a literal is expected, exercising {@code qualityCategoryOf}'s guard instead.
     */
    @Test
    void findByCodeReadsATypeMismatchedQualityCategoryAsNotSetInsteadOfThrowing() {
        RequirementId id = freshId();
        givenRequirementWithIriInsteadOfLiteral(
                PROJECT_A, id, "FR-1", "qualityCategory", "https://w3id.org/arknet/id/not-a-literal");

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1")).orElseThrow();

        assertNull(found.qualityCategory());
    }

    /**
     * Before the fix this was the crash the issue described as project-wide: a single
     * type-mismatched {@code priority} aborted {@link #findAll}'s whole stream with an uncaught
     * {@link ClassCastException}, taking every other, perfectly good requirement in the project
     * down with it. The fix keeps the batch intact - the offending requirement is still returned,
     * just with {@code priority() == null}.
     */
    @Test
    void findAllDoesNotAbortTheWholeBatchForATypeMismatchedPriority() {
        repository.create(PROJECT_A, new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(),
                List.of("Login succeeds with valid credentials")));
        givenRequirementWithLiteralInsteadOfIri(PROJECT_A, freshId(), "FR-2", "priority", "not-an-iri");

        List<Requirement> all = repository.findAll(PROJECT_A);

        assertEquals(2, all.size());
        Requirement brokenPriority = all.stream()
                .filter(requirement -> requirement.code().equals(new RequirementCode("FR-2")))
                .findFirst()
                .orElseThrow();
        assertNull(brokenPriority.priority());
    }

    /**
     * Writes an {@code arkreq:FunctionalRequirement} straight into the requirements graph with
     * {@code property} bound to a literal instead of the IRI its {@code priorityOf}/
     * {@code motivatedByOf} decoder expects - shape-legal ({@code requirements-shapes.ttl}
     * declares no {@code sh:nodeKind} for either property, so the write-gate never rejects it),
     * but unreachable via {@code req_add}/{@code req_update}, which only ever write an IRI.
     */
    private void givenRequirementWithLiteralInsteadOfIri(
            ProjectId projectId, RequirementId id, String code, String property, String literalValue) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "\"Login succeeds with valid credentials\" ; "
                + "<https://w3id.org/arknet/requirements#" + property + "> \"" + literalValue + "\" "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * {@link #givenRequirementWithLiteralInsteadOfIri} with the mismatch inverted: {@code property}
     * bound to an IRI instead of the literal {@code qualityCategoryOf} expects.
     */
    private void givenRequirementWithIriInsteadOfLiteral(
            ProjectId projectId, RequirementId id, String code, String property, String iriValue) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "\"Login succeeds with valid credentials\" ; "
                + "<https://w3id.org/arknet/requirements#" + property + "> <" + iriValue + "> "
                + "} }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- findByIds: batch resolution for ResolveRequirements -----------------------------

    @Test
    void findByIdsResolvesKnownIdentitiesInOneQuery() {
        Requirement first = new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));
        Requirement second = new Requirement(freshId(), new RequirementCode("FR-2"), "Logout",
                "The system shall end a user session.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));
        repository.create(PROJECT_A, first);
        repository.create(PROJECT_A, second);

        List<ResolveRequirements.ResolvedRequirement> resolved = repository.findByIds(
                PROJECT_A, List.of(first.id().value(), second.id().value()));

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(new ResolveRequirements.ResolvedRequirement(first.id().value(), first.code())));
        assertTrue(
                resolved.contains(new ResolveRequirements.ResolvedRequirement(second.id().value(), second.code())));
    }

    /** An id absent from the project is simply absent from the result, never an error. */
    @Test
    void findByIdsSilentlyOmitsUnknownIdentities() {
        Requirement known = new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));
        repository.create(PROJECT_A, known);
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/does-not-exist");

        List<ResolveRequirements.ResolvedRequirement> resolved =
                repository.findByIds(PROJECT_A, List.of(known.id().value(), unknown));

        assertEquals(List.of(new ResolveRequirements.ResolvedRequirement(known.id().value(), known.code())),
                resolved);
    }

    @Test
    void findByIdsWithEmptyIdsReturnsAnEmptyListWithoutQuerying() {
        assertEquals(List.of(), repository.findByIds(PROJECT_A, List.of()));
    }

    @Test
    void findByIdsIsScopedPerProject() {
        Requirement inProjectA = new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials"));
        repository.create(PROJECT_A, inProjectA);

        assertEquals(List.of(), repository.findByIds(PROJECT_B, List.of(inProjectA.id().value())));
    }

    /**
     * Store-first regression test: {@code RequirementShape} places no {@code sh:maxCount} on
     * {@code dcterms:identifier}, so a subject with two identifier triples is shape-legal even
     * though {@code req_add} never writes more than one. {@code findByIds}' mandatory
     * {@code identifier} join must not multiply such a subject into two
     * {@link ResolveRequirements.ResolvedRequirement}s carrying the same id - a caller keying its
     * own results by identity would otherwise throw on the duplicate key.
     */
    @Test
    void findByIdsReturnsExactlyOneResolvedRequirementForASubjectWithSeveralIdentifiers() {
        RequirementId id = freshId();
        givenRequirementWithTwoIdentifiers(PROJECT_A, id, "FR-1", "FR-1-ALT");

        List<ResolveRequirements.ResolvedRequirement> resolved =
                repository.findByIds(PROJECT_A, List.of(id.value()));

        assertEquals(1, resolved.size());
        assertEquals(id.value(), resolved.get(0).id());
    }

    /**
     * Writes an {@code arkreq:FunctionalRequirement} straight into the requirements graph with
     * two {@code dcterms:identifier} triples - shape-legal ({@code RequirementShape} places no
     * {@code sh:maxCount} on the property), but unreachable via {@code req_add}. No other fields
     * are set: {@code findByIds}' query only selects {@code ?s}/{@code ?identifier}.
     */
    private void givenRequirementWithTwoIdentifiers(ProjectId projectId, RequirementId id, String first,
            String second) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + first + "\" ; "
                + "<http://purl.org/dc/terms/identifier> \"" + second + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    // ---- usesTerm: the cross-BC requirement -> glossary-term edge ------------------------

    @Test
    void createsAndFindsUsesTermEdge() {
        givenTerm(PROJECT_A, "TERM-1");
        Requirement requirement = requirementUsing(termRef("TERM-1"));

        repository.create(PROJECT_A, requirement);

        assertEquals(Optional.of(requirement),
                repository.findByCode(PROJECT_A, new RequirementCode("FR-1")));
        assertEquals(List.of(termRef("TERM-1")),
                repository.findAll(PROJECT_A).get(0).usesTerms());
    }

    @Test
    void createsAndFindsSeveralUsesTermEdges() {
        givenTerm(PROJECT_A, "TERM-1");
        givenTerm(PROJECT_A, "TERM-2");

        repository.create(PROJECT_A, requirementUsing(termRef("TERM-1"), termRef("TERM-2")));

        List<TermRef> found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"))
                .orElseThrow().usesTerms();
        assertEquals(2, found.size());
        assertTrue(found.containsAll(List.of(termRef("TERM-1"), termRef("TERM-2"))));
    }

    /**
     * Term references arrive pre-resolved. This adapter no longer looks the term up
     * (that strict, identifier-based resolution now lives in {@code KognioRdfTermLookup}, called
     * once by the application service when a term is linked) - it trusts the identity it is
     * handed, the same way it trusts {@code motivatedBy} without re-resolving it. A target that
     * does not exist at all in the store is therefore persisted just the same as one that does;
     * see {@code KognioRdfTermLookupTest} for the strict-resolution behaviour this used to be
     * (and still is, just one layer up).
     */
    @Test
    void createPersistsAUsesTermEdgeEvenWhenItsTargetDoesNotExistInTheStore() {
        TermRef doesNotExist = termRef("TERM-99");

        repository.create(PROJECT_A, requirementUsing(doesNotExist));

        assertEquals(List.of(doesNotExist),
                repository.findByCode(PROJECT_A, new RequirementCode("FR-1")).orElseThrow().usesTerms());
    }

    /**
     * Regression guard for the replace-by-identity write path: {@code update} wipes the
     * subject's triples before re-writing them, so a read-modify-write round trip must carry
     * the usesTerm edges along instead of silently dropping them.
     */
    @Test
    void usesTermEdgesSurviveAReplacingUpdate() {
        givenTerm(PROJECT_A, "TERM-1");
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, requirementUsing(termRef("TERM-1")));

        Requirement reloaded = repository.findByCode(PROJECT_A, code).orElseThrow();
        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(),
                reloaded.motivatedBy(), reloaded.qualityCategory(), reloaded.usesTerms(), List.of("Login succeeds with valid credentials"));
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        Requirement found = repository.findByCode(PROJECT_A, code).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, found.status());
        assertEquals(List.of(termRef("TERM-1")), found.usesTerms());
    }

    @Test
    void unlinkingATermRemovesTheEdge() {
        givenTerm(PROJECT_A, "TERM-1");
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementUsing(termRef("TERM-1"));
        repository.create(PROJECT_A, created);

        replaceViaCompareAndUpdate(PROJECT_A, new Requirement(created.id(), created.code(), created.title(),
                created.description(), created.type(), created.status(), created.priority(),
                created.motivatedBy(), created.qualityCategory(), List.of(), List.of("Login succeeds with valid credentials")));

        assertEquals(List.of(), repository.findByCode(PROJECT_A, code).orElseThrow().usesTerms());
    }

    /**
     * Proves the {@code sh:class skos:Concept} constraint on {@code arkreq:usesTerm} actually
     * fires rather than silently passing: RDFS reasoning is on and the ontology declares
     * {@code arkreq:usesTerm rdfs:range skos:Concept}, which - had range inference applied -
     * would type every link target as a concept and make the shape vacuous.
     *
     * <p>It does not, so the adapter must feed the resolved terms' type triples into the
     * validation graph; otherwise every legitimate link would be rejected. This test pins
     * that contract at gate level, where {@link #createsAndFindsUsesTermEdge} pins the other
     * side of it.</p>
     */
    @Test
    void gateRejectsUsesTermPointingAtSomethingThatIsNotAConcept() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#FunctionalRequirement"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("FR-1"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Login"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/description"),
                rdf.createLiteral("The system shall authenticate a user."));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#status"),
                rdf.createIRI("https://w3id.org/arknet/requirements#Proposed"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#usesTerm"),
                rdf.createIRI("https://example.org/not-a-concept"));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));

        assertTrue(ex.getMessage().contains("usesTerm"), ex.getMessage());
    }

    /**
     * {@code rshapes:Requirement-title} is a shape ({@code dcterms:title} had none
     * before). {@link Requirement#title()} is single-valued, so a second title is unreachable via
     * {@link RequirementRepository#create} - this exercises the gate directly against a synthetic
     * candidate graph, the way a store-first (ADR-005) write could still produce two triples.
     */
    @Test
    void gateRejectsRequirementWithTwoTitles() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#FunctionalRequirement"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("FR-1"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Login"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Sign in"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/description"),
                rdf.createLiteral("The system shall authenticate a user."));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#status"),
                rdf.createIRI("https://w3id.org/arknet/requirements#Proposed"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#acceptanceCriterion"),
                rdf.createLiteral("Login succeeds with valid credentials"));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));

        assertTrue(ex.getMessage().contains("title"), ex.getMessage());
    }

    /**
     * {@code rshapes:Requirement-description} carries {@code sh:maxCount 1},
     * mirroring {@link #gateRejectsRequirementWithTwoTitles}. {@link Requirement#description()}
     * is single-valued, so a second description is unreachable via
     * {@link RequirementRepository#create}.
     */
    @Test
    void gateRejectsRequirementWithTwoDescriptions() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#FunctionalRequirement"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("FR-1"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Login"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/description"),
                rdf.createLiteral("The system shall authenticate a user."));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/description"),
                rdf.createLiteral("Das System soll einen Benutzer authentifizieren."));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#status"),
                rdf.createIRI("https://w3id.org/arknet/requirements#Proposed"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#acceptanceCriterion"),
                rdf.createLiteral("Login succeeds with valid credentials"));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));

        assertTrue(ex.getMessage().contains("description"), ex.getMessage());
    }

    /**
     * {@code rshapes:Requirement-motivatedBy-count} is a {@code sh:Violation}
     * shape carrying only the {@code sh:maxCount 1} - split out from the pre-existing
     * {@code rshapes:Requirement-motivatedBy} (which stays a {@code sh:Warning} best-practice
     * check on {@code sh:class arkreq:Goal}, unchanged). A {@code sh:Warning}-severity
     * {@code maxCount} would never fire {@link WriteConstraintViolationException} - a SHACL
     * report only "does not conform" on a {@code sh:Violation} result - so multiplicity had to
     * become its own, separately-severed property shape rather than a field added to the
     * existing one. {@link Requirement#motivatedBy()} is single-valued, so a second value is
     * unreachable via {@link RequirementRepository#create}.
     */
    @Test
    void gateRejectsRequirementWithTwoMotivatedBy() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI goal1 = rdf.createIRI("https://w3id.org/arknet/model/goal/fast-ux");
        IRI goal2 = rdf.createIRI("https://w3id.org/arknet/model/goal/secure-login");
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#FunctionalRequirement"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("FR-1"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Login"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/description"),
                rdf.createLiteral("The system shall authenticate a user."));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#status"),
                rdf.createIRI("https://w3id.org/arknet/requirements#Proposed"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#motivatedBy"), goal1);
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#motivatedBy"), goal2);
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#acceptanceCriterion"),
                rdf.createLiteral("Login succeeds with valid credentials"));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));

        assertTrue(ex.getMessage().contains("motivatedBy"), ex.getMessage());
    }

    // ---- usesTerm: reading is identity-based, not join-based ----------------------------

    /**
     * {@link #readUsesTerms} no longer joins into
     * the terms graph at all, so a target's missing {@code dcterms:identifier} can no longer
     * hide the edge. What used to require the {@code write()} preservation mechanism (below) is
     * now handled by the ordinary read-and-replace path, without any special-casing.
     */
    @Test
    void usesTermEdgeToATermWithoutIdentifierIsReadableAndSurvivesAnOrdinaryUpdate() {
        String termIri = givenTermWithoutIdentifier(PROJECT_A);
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementUsing();
        repository.create(PROJECT_A, created);
        givenUsesTermEdge(PROJECT_A, created.id(), termIri);
        TermRef expected = new TermRef(ResourceId.of(termIri));

        Requirement reloaded = repository.findByCode(PROJECT_A, code).orElseThrow();
        assertEquals(List.of(expected), reloaded.usesTerms(),
                "reading no longer joins into the terms graph, so a missing dcterms:identifier "
                        + "on the target no longer hides the edge");

        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(),
                reloaded.motivatedBy(), reloaded.qualityCategory(), reloaded.usesTerms(), List.of("Login succeeds with valid credentials"));
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        assertEquals(List.of(expected), repository.findByCode(PROJECT_A, code).orElseThrow().usesTerms(),
                "the edge is now part of the ordinary record, carried forward by the replacing "
                        + "update without needing the preservation mechanism at all");
    }

    // ---- usesTerm: store-first edges to a non-IRI target the strict read cannot represent ----

    /**
     * Store-first regression test: {@code arkreq:usesTerm} is not range-constrained to
     * {@code IRI} at the RDF level (the SHACL {@code sh:class skos:Concept} constraint accepts
     * a blank node just as readily as an IRI), so a store-first edge can legally target a
     * blank node - {@code [ a skos:Concept ]} written directly into the requirements graph.
     * {@link de.hauschel.arknet.kernel.ResourceId} cannot represent a blank node, so it is
     * exactly the kind of edge the preservation query in {@code write()} must still capture
     * narrowed to this one case. Casting the captured binding to {@link IRI}
     * would throw a {@link ClassCastException} on a blank node, turning the previously silent
     * data loss into a crash on every {@link #update} of the affected requirement -
     * a regression, not a fix.
     */
    @Test
    void storeFirstUsesTermEdgeToABlankNodeSurvivesAReplacingUpdateWithoutCrashing() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementUsing();
        repository.create(PROJECT_A, created);
        givenUsesTermEdgeToFreshBlankNodeConcept(PROJECT_A, created.id());

        Requirement reloaded = repository.findByCode(PROJECT_A, code).orElseThrow();
        assertEquals(List.of(), reloaded.usesTerms(), "blank-node edge must stay invisible to the read");

        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(),
                reloaded.motivatedBy(), reloaded.qualityCategory(), reloaded.usesTerms(), List.of("Login succeeds with valid credentials"));
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        assertTrue(usesTermEdgeTargetsAConceptBlankNode(PROJECT_A, reloaded.id()),
                "blank-node edge must survive the replacing update and still point at its typed node - "
                        + "not merely at some blank node");
    }

    /**
     * Preserving a non-IRI-target edge must not duplicate an ordinary IRI-target one. Both the
     * ordinary rewrite and the preservation query run against the same subject inside the same
     * write transaction; this pins that an IRI-target edge - which the preservation query's
     * {@code FILTER(!isIRI(?term))} must exclude - is still written exactly once.
     */
    @Test
    void ordinaryUsesTermEdgeIsNotDuplicatedByPreservation() {
        givenTerm(PROJECT_A, "TERM-1");
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, requirementUsing(termRef("TERM-1")));

        Requirement reloaded = repository.findByCode(PROJECT_A, code).orElseThrow();
        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(),
                reloaded.motivatedBy(), reloaded.qualityCategory(), reloaded.usesTerms(), List.of("Login succeeds with valid credentials"));
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        String termIri = "https://w3id.org/arknet/model/term/TERM-1";
        assertEquals(1, countUsesTermEdges(PROJECT_A, reloaded.id(), termIri));
    }

    private static Requirement requirementUsing(TermRef... terms) {
        return new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(terms), List.of("Login succeeds with valid credentials"));
    }

    /** Builds the {@link TermRef} a term written by {@link #givenTerm} resolves to. */
    private static TermRef termRef(String termId) {
        return new TermRef(ResourceId.of("https://w3id.org/arknet/model/term/" + termId));
    }

    /**
     * Writes a glossary term straight into the sibling terms graph of the shared project
     * dataset - deliberately via raw SPARQL rather than the ubiquitous-language adapter, so
     * this test does not couple the two bounded contexts. The cross-BC wiring itself is
     * covered by {@code CrossBoundedContextStoreWiringTest} in arknet-mcp.
     */
    private void givenTerm(ProjectId projectId, String termId) {
        String termIri = "https://w3id.org/arknet/model/term/" + termId;
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + termIri + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + termId + "\" ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Anmeldung\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Writes a glossary term into the sibling terms graph <em>without</em> a
     * {@code dcterms:identifier} - unreachable via {@code term_add}/{@link #givenTerm}, but
     * reachable store-first (ADR-005). {@code KognioRdfTermLookup} cannot resolve such a concept
     * by code (no identifier to look up by), so a test wiring an edge to it must do so directly
     * per raw SPARQL as well. Returns the term's IRI for that purpose.
     */
    private String givenTermWithoutIdentifier(ProjectId projectId) {
        String termIri = "https://w3id.org/arknet/model/term/" + UUID.randomUUID();
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/ubiquitous-language> { "
                + "<" + termIri + "> a <http://www.w3.org/2004/02/skos/core#Concept> ; "
                + "<http://www.w3.org/2004/02/skos/core#prefLabel> \"Anmeldung\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
        return termIri;
    }

    /**
     * Writes an {@code arkreq:usesTerm} edge straight into the requirements graph - the
     * store-first path (ADR-005), unmediated by {@code req_link_term}/{@code KognioRdfTermLookup},
     * so it can point at a term the strict lookup would reject by code.
     */
    private void givenUsesTermEdge(ProjectId projectId, RequirementId subjectId, String termIri) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + subjectId.value().value() + "> <https://w3id.org/arknet/requirements#usesTerm> <"
                + termIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Counts an {@code arkreq:usesTerm} edge between one subject and one term directly in the
     * requirements graph, bypassing {@code findByCode}/{@code findAll} entirely - the
     * assertion this supports must not rely on the very read path whose blind spot it is
     * proving safe.
     */
    private long countUsesTermEdges(ProjectId projectId, RequirementId subjectId, String termIri) {
        String select = "SELECT ?term WHERE { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + subjectId.value().value() + "> <https://w3id.org/arknet/requirements#usesTerm> <"
                + termIri + "> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().select(select).count();
        }
    }

    /**
     * Writes an {@code arkreq:usesTerm} edge straight into the requirements graph, targeting a
     * freshly minted anonymous blank node typed as a {@code skos:Concept} - RDF-legal (the
     * property carries no {@code sh:nodeKind} constraint forcing an IRI object) and reachable
     * only store-first, never via {@code req_link_term}/{@code KognioRdfTermLookup}, which
     * resolve a code to an IRI and therefore cannot even address a blank node.
     */
    private void givenUsesTermEdgeToFreshBlankNodeConcept(ProjectId projectId, RequirementId subjectId) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + subjectId.value().value() + "> <https://w3id.org/arknet/requirements#usesTerm> "
                + "[ a <http://www.w3.org/2004/02/skos/core#Concept> ] } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * Checks - via a single raw SPARQL {@code ASK} joining both patterns on the same variable -
     * that the subject's {@code arkreq:usesTerm} edge still targets a node that itself carries
     * {@code a skos:Concept} in the requirements graph. This is the identity check for the
     * blank-node preservation case: {@code DELETE WHERE { <subject> ?p ?o }} only ever removes triples whose
     * subject is the requirement, never the target node's own type triple, so this passing is
     * proof the edge was re-attached to the very same blank node rather than to a dangling or
     * freshly-generated one.
     */
    private boolean usesTermEdgeTargetsAConceptBlankNode(ProjectId projectId, RequirementId subjectId) {
        String ask = "ASK { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + subjectId.value().value() + "> <https://w3id.org/arknet/requirements#usesTerm> ?term . "
                + "?term a <http://www.w3.org/2004/02/skos/core#Concept> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            return handle.sparqlQuery().ask(ask);
        }
    }

    // ---- revision trail (ADR-014): one revision per write, head queryable ----------------

    /**
     * ADR-014 revision basis for this bounded context's funnel write paths: {@code create} and
     * {@code compareAndUpdate} each record exactly one immutable revision, and the head is
     * queryable per resource. Since {@code compareAndUpdate} was resolved into the funnel
     * (ADR-014 decision 4), this is no longer a special path any more than {@code create} is -
     * {@code RequirementService} routes every state change ({@code req_update}, {@code
     * req_set_status}, {@code req_link_term}) through it, so the head now moves on every
     * user-reachable requirement write, not just the initial {@code create}.
     */
    @Test
    void createAndCompareAndUpdateEachRecordExactlyOneRevisionWithAQueryableHead() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, new Requirement(id, code, "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, null, List.of("Login succeeds with valid credentials")));

        assertEquals(1, revisionsOf(id).size(), "create must record exactly one revision");
        RevisionToken headAfterCreate = repository.findCurrentByCode(PROJECT_A, code).orElseThrow().head();

        repository.compareAndUpdate(PROJECT_A, headAfterCreate, new Requirement(id, code, "Login",
                "The system shall authenticate a user.", RequirementType.FUNCTIONAL,
                RequirementStatus.ACCEPTED, null, null, null, null, List.of("Login succeeds with valid credentials")));

        List<String> revisions = revisionsOf(id);
        assertEquals(2, revisions.size(), "compareAndUpdate must record exactly one more revision");
        List<String> heads = headsOf(id);
        assertEquals(1, heads.size(), "the head is rewritten, never duplicated");
        assertTrue(revisions.contains(heads.get(0)), "the head must be one of the resource's revisions");
        assertEquals(heads.get(0), repository.findCurrentByCode(PROJECT_A, code).orElseThrow().head().value(),
                "findCurrentByCode must observe the advanced head");
    }

    private List<String> revisionsOf(RequirementId id) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { "
                + "?v a <" + ArkprovVocabulary.REVISION_TYPE + "> ; "
                + "<" + ArkprovVocabulary.SPECIALIZATION_OF + "> <" + id.value().value() + "> } }");
    }

    private List<String> headsOf(RequirementId id) {
        return selectIris("SELECT ?v WHERE { GRAPH <" + ArkprovVocabulary.PROVENANCE_GRAPH + "> { <"
                + id.value().value() + "> <" + ArkprovVocabulary.HEAD + "> ?v } }");
    }

    private List<String> selectIris(String query) {
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            return handle.sparqlQuery().select(query)
                    .map(row -> ((IRI) row.getValue("v").orElseThrow()).getIRIString())
                    .toList();
        }
    }
}
