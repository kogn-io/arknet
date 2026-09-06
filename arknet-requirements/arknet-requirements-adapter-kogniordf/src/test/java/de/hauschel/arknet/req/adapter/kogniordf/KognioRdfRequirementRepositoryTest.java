// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.ArkprovVocabulary;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintRef;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.ConstraintType;
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
    private ConstraintRepository constraints;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = KognioRdfRequirementRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT);
        WriteFunnel funnel = KognioRdfRequirementRepositoryFactory.buildFunnel(datasetLifecycle, DisplayLocale.DEFAULT);
        constraints = KognioRdfConstraintRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT, funnel);
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
        RevisionToken head = repository.findCurrentByCode(projectId, updated.code(), null)
                .map(RequirementRepository.CurrentRequirement::head)
                .orElse(null);
        repository.compareAndUpdate(projectId, head, updated, null, null, null, noAcceptanceCriteriaLanguages(updated), null);
    }

    /**
     * An untagged (all-{@code null}) {@code acceptanceCriteriaLanguageByPosition}, covering every
     * position {@code updated} carries - the fixture-level equivalent of what {@code req_update}'s
     * own language resolution would produce for a call that never supplies a {@code language}.
     */
    private static Map<Integer, String> noAcceptanceCriteriaLanguages(Requirement updated) {
        Map<Integer, String> languages = new LinkedHashMap<>();
        updated.acceptanceCriteria().forEach(criterion -> languages.put(criterion.position(), null));
        return languages;
    }

    @Test
    void createsAndFindsFunctionalRequirementByCode() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        repository.create(PROJECT_A, requirement, null);
        Optional<Requirement> found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null);

        assertEquals(Optional.of(requirement), found);
        assertEquals("The system shall authenticate a user.", found.orElseThrow().description());
    }

    @Test
    void findAllContainsAllCreatedRequirements() {
        Requirement first = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        repository.create(PROJECT_A, first, null);
        assertEquals(1, repository.findAll(PROJECT_A, null).size());

        Requirement second = new Requirement(
                freshId(), new RequirementCode("FR-2"), "Logout", "The system shall end a user session.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, second, null);

        List<Requirement> all = repository.findAll(PROJECT_A, null);
        assertEquals(2, all.size());
        assertTrue(all.contains(first));
        assertTrue(all.contains(second));
    }

    /**
     * What {@link RequirementRepository#findAllCodes} is for (kogn-io/arknet#360), pinned against
     * the real store: the query joins the requirement type and {@code dcterms:identifier} and
     * nothing further, so a number stays taken even by a subject the listing cannot build a
     * {@link Requirement} out of at all. The inserted subject is deliberately as bare as the graph
     * permits - no title, no description, no status - since every one of those is a join the
     * listing makes and this read must not. Add any field to
     * {@code KognioRdfRequirementRepository#findAllCodes}'s query later and this test fails, rather
     * than the counter quietly handing {@code FR-2} out for a second time.
     */
    @Test
    void findAllCodesKeepsTheCodeOfASubjectFindAllCannotMaterialiseAtAll() {
        Requirement first = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, first, null);
        givenBareCodedSubject(PROJECT_A, freshId(), "FR-2");

        assertEquals(List.of(first), repository.findAll(PROJECT_A, null));
        assertTrue(repository.findByCode(PROJECT_A, new RequirementCode("FR-2"), null).isEmpty());
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new RequirementCode("FR-2")),
                repository.findAllCodes(PROJECT_A).toString());
    }

    /**
     * The node-kind case of the same read (kogn-io/arknet#360). No {@code FILTER(isIRI(?s))} guards
     * this query, so an {@code FR-2} held by an anonymous subject is counted - deliberately,
     * because the write path is equally indifferent: {@code WriteFunnel#create} looks for the code
     * with {@code tx.contains(graph, null, dcterms:identifier, code)}, a wildcard subject, and would
     * refuse a {@code req_add} for {@code FR-2}. Overlooking such a code would make the retry loop
     * behind the code assignment propose the same rejected number for ever. Put the filter back to
     * match the neighbouring reads and this test reports the miss.
     */
    @Test
    void findAllCodesCountsACodeHeldByABlankNodeSubject() {
        Requirement first = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, first, null);
        givenBareBlankNodeSubject(PROJECT_A, "FR-2");

        assertEquals(List.of(first), repository.findAll(PROJECT_A, null));
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new RequirementCode("FR-2")),
                repository.findAllCodes(PROJECT_A).toString());
    }

    /**
     * Writes the leanest subject that still counts as a coded requirement: the type triple and
     * {@code dcterms:identifier}, nothing else. Shape-illegal, and therefore unreachable through
     * {@code req_add} - only a store-first write can produce it, which is exactly the
     * situation kogn-io/arknet#360 is about.
     */
    private void givenBareCodedSubject(ProjectId projectId, RequirementId id, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * A blank-node subject that is a complete requirement in everything but its node kind
     * (kogn-io/arknet#401) - the shape a store-first import that never minted IRIs produces. The
     * bare fixture above never reaches {@code findAll}'s {@link io.kogn.rdf.terms.IRI} cast,
     * because the listing query joins {@code arkreq:status} and this one carries it, and because
     * the bulk per-predicate reads behind the listing join {@code dcterms:title} alone, with no
     * type join in front. Before the guard either route threw a {@code ClassCastException} out of
     * the whole call, so one anonymous subject cost the project its entire {@code req_list} - not
     * the one skipped resource the listing documents for a subject it cannot materialise.
     */
    @Test
    void findAllSkipsAFullyPopulatedBlankNodeSubjectInsteadOfCrashingTheWholeListing() {
        Requirement first = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, first, null);
        givenPopulatedBlankNodeSubject(PROJECT_A, "FR-2");

        assertEquals(List.of(first), repository.findAll(PROJECT_A, null));
        assertTrue(repository.findByCode(PROJECT_A, new RequirementCode("FR-2"), null).isEmpty());
    }

    /** The fixture behind {@link #findAllSkipsAFullyPopulatedBlankNodeSubjectInsteadOfCrashingTheWholeListing}. */
    private void givenPopulatedBlankNodeSubject(ProjectId projectId, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "[] a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<https://w3id.org/arknet/requirements#status> "
                + "<https://w3id.org/arknet/requirements#Proposed> ; "
                + "<http://purl.org/dc/terms/title> \"Anonymous\" ; "
                + "<http://purl.org/dc/terms/description> \"No identity.\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * The same lean subject without an identity of its own: {@code []} is a fresh blank node,
     * which {@code rshapes:RequirementShape} does not forbid and no {@code req_add} can mint. Only
     * a store-first write reaches this shape, and the code on it is taken all the same.
     */
    private void givenBareBlankNodeSubject(ProjectId projectId, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "[] a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    @Test
    void createRejectsAnAlreadyExistingIdentityAndPersistsNothingElse() {
        RequirementId id = freshId();
        Requirement requirement = new Requirement(id, new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, requirement, null);

        Requirement collidingId = new Requirement(id, new RequirementCode("FR-2"), "Logout",
                "The system shall end a user session.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        assertThrows(ResourceAlreadyExistsException.class,
                () -> repository.create(PROJECT_A, collidingId, null));
        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        assertEquals(Optional.of(requirement), repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null));
    }

    /**
     * Identity collision and code collision are distinct failure modes: two different, freshly
     * minted identities both claiming {@code FR-1} must be rejected by code, not by identity.
     */
    @Test
    void createRejectsADuplicateCodeUnderADifferentIdentityAndPersistsNothingElse() {
        RequirementCode code = new RequirementCode("FR-1");
        Requirement first = new Requirement(freshId(), code, "Login",
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, first, null);

        Requirement collidingCode = new Requirement(freshId(), code, "Logout",
                "The system shall end a user session.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        assertThrows(DuplicateRequirementCodeException.class,
                () -> repository.create(PROJECT_A, collidingCode, null));
        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        assertEquals(Optional.of(first), repository.findByCode(PROJECT_A, code, null));
    }

    @Test
    void updateReplacesByIdentityInsteadOfDuplicating() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        Requirement proposed = new Requirement(id, code, "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        Requirement accepted = new Requirement(id, code, "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.ACCEPTED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        repository.create(PROJECT_A, proposed, null);
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        assertEquals(Optional.of(accepted), repository.findByCode(PROJECT_A, code, null));
        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        assertEquals(accepted, repository.findAll(PROJECT_A, null).get(0));
    }

    /** The opaque identity is preserved across an update - only the requirement's state changes. */
    @Test
    void updatePreservesTheOpaqueIdentity() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, new Requirement(id, code, "Login",
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of()), null);

        replaceViaCompareAndUpdate(PROJECT_A, new Requirement(id, code, "Login",
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.ACCEPTED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of()));

        assertEquals(id, repository.findByCode(PROJECT_A, code, null).orElseThrow().id());
    }

    // ---- compareAndUpdate: CAS guard against lost updates, head-based ----

    @Test
    void compareAndUpdateAppliesWhenExpectedHeadMatchesTheStoredHead() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        Requirement proposed = new Requirement(id, code, "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, proposed, null);
        RevisionToken head = repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow().head();
        Requirement accepted = new Requirement(id, code, "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.ACCEPTED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        repository.compareAndUpdate(
                PROJECT_A, head, accepted, null, null, null, noAcceptanceCriteriaLanguages(accepted), null);

        assertEquals(Optional.of(accepted), repository.findByCode(PROJECT_A, code, null));
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
        Requirement original = new Requirement(id, code, "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, original, null);
        RevisionToken staleHead = repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow().head();
        // Simulates a concurrent writer that already committed a change since staleHead was read.
        Requirement concurrentlyAccepted = new Requirement(id, code, "Login",
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL, RequirementStatus.ACCEPTED,
                null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        replaceViaCompareAndUpdate(PROJECT_A, concurrentlyAccepted);

        Requirement staleAttempt = new Requirement(id, code, "Login renamed",
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        assertThrows(RequirementConcurrentlyModifiedException.class,
                () -> repository.compareAndUpdate(
                        PROJECT_A, staleHead, staleAttempt, null, null, null, noAcceptanceCriteriaLanguages(staleAttempt), null));
        assertEquals(Optional.of(concurrentlyAccepted), repository.findByCode(PROJECT_A, code, null));
    }

    @Test
    void compareAndUpdateThrowsWhenTheIdentityDoesNotExistAtAll() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        Requirement neverCreated = new Requirement(id, code, "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        assertThrows(RequirementNotFoundException.class,
                () -> repository.compareAndUpdate(
                        PROJECT_A, null, neverCreated, null, null, null, noAcceptanceCriteriaLanguages(neverCreated), null));
        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
        assertEquals(Optional.empty(), repository.findCurrentByCode(PROJECT_A, code, null));
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
        repository.create(PROJECT_A, created, null);
        RevisionToken head = repository.findCurrentByCode(PROJECT_A, created.code(), null).orElseThrow().head();

        Requirement accepted = new Requirement(created.id(), created.code(), created.title(), created.description(), null,
                created.type(), RequirementStatus.ACCEPTED, created.priority(),
                created.qualityCategory(), created.usesTerms(), created.acceptanceCriteria(), List.of());
        repository.compareAndUpdate(
                PROJECT_A, head, accepted, null, null, null, noAcceptanceCriteriaLanguages(accepted), null);

        Requirement found = repository.findByCode(PROJECT_A, created.code(), null).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, found.status());
        assertEquals(List.of(termRef("TERM-1")), found.usesTerms());
        assertEquals(List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), found.acceptanceCriteria());
    }

    @Test
    void findByCodeReturnsEmptyForUnknownCode() {
        assertEquals(Optional.empty(), repository.findByCode(PROJECT_A, new RequirementCode("FR-99"), null));
    }

    @Test
    void projectsAreIsolated() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        repository.create(PROJECT_A, requirement, null);

        assertTrue(repository.findAll(PROJECT_B, null).isEmpty());
    }

    @Test
    void createsAndFindsNonFunctionalRequirement() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("NFR-1"), "Response time < 200ms",
                "95% of requests shall complete in under 200ms.", null,
                RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        repository.create(PROJECT_A, requirement, null);
        Optional<Requirement> found = repository.findByCode(PROJECT_A, new RequirementCode("NFR-1"), null);

        assertEquals(Optional.of(requirement), found);
        assertEquals(RequirementType.NON_FUNCTIONAL, found.get().type());
    }

    @Test
    void createsAndFindsPriorityAndQualityCategory() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("NFR-1"), "Response time < 200ms",
                "95% of requests shall complete in under 200ms.", null,
                RequirementType.NON_FUNCTIONAL, RequirementStatus.PROPOSED,
                Priority.MUST_HAVE, "performance", null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        repository.create(PROJECT_A, requirement, null);
        Optional<Requirement> found = repository.findByCode(PROJECT_A, new RequirementCode("NFR-1"), null);

        assertEquals(Optional.of(requirement), found);
        assertEquals(Priority.MUST_HAVE, found.orElseThrow().priority());
        assertEquals("performance", found.orElseThrow().qualityCategory());
        assertTrue(repository.findAll(PROJECT_A, null).contains(requirement));
    }

    @Test
    void createdWithoutOptionalFieldsAreFoundWithNullOptionalFields() {
        Requirement requirement = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        repository.create(PROJECT_A, requirement, null);
        Optional<Requirement> found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null);
        Requirement foundViaFindAll = repository.findAll(PROJECT_A, null).get(0);

        assertEquals(Optional.of(requirement), found);
        assertEquals(requirement, foundViaFindAll);
        assertNull(found.orElseThrow().priority());
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
                freshId(), new RequirementCode("FR-1"), "Login", "Hi", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        assertThrows(WriteConstraintViolationException.class,
                () -> repository.create(PROJECT_A, tooShortDescription, null));
        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
    }

    /**
     * {@code arkreq:rationale} carries {@code sh:minLength 5} but deliberately no
     * {@code sh:minCount} (issue #321) - so a too-short reason is rejected by the gate, while a
     * requirement carrying no reason at all passes. The {@link Requirement} constructor rejects a
     * blank rationale but not a short one, so this candidate has to come from a domain object to
     * prove the shape itself fires.
     */
    @Test
    void createRejectsATooShortRationale() {
        Requirement tooShortRationale = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.", "why",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> repository.create(PROJECT_A, tooShortRationale, null));

        assertTrue(ex.getMessage().contains("rationale"), ex.getMessage());
        assertTrue(repository.findAll(PROJECT_A, null).isEmpty());
    }

    /**
     * The complement of {@link #createRejectsATooShortRationale}: {@code rshapes:Requirement-rationale}
     * carries no {@code sh:minCount}, so a requirement whose reason nobody recorded is legal -
     * this is the one shape decision issue #321 makes deliberately, and the one a later
     * "make it mandatory" change would have to break this test to reverse.
     */
    @Test
    void gateAcceptsARequirementWithoutARationale() {
        Requirement withoutRationale = new Requirement(
                freshId(), new RequirementCode("FR-1"), "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());

        repository.create(PROJECT_A, withoutRationale, null);

        assertNull(repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null).orElseThrow().rationale());
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

    /**
     * {@code arkreq:acceptanceCriterion} is an object property since issue #266 ({@code sh:class
     * arkreq:AcceptanceCriterion}, mirroring {@code arkreq:mainStep}'s {@code sh:class arkreq:Step}):
     * pointing it at a node the candidate graph never types as an {@code arkreq:AcceptanceCriterion}
     * must be rejected, the same way {@link #gateRejectsUsesTermPointingAtSomethingThatIsNotAConcept}
     * pins {@code usesTerm}'s own {@code sh:class}.
     */
    @Test
    void gateRejectsAcceptanceCriterionPointingAtSomethingThatIsNotAnAcceptanceCriterion() {
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
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#acceptanceCriterion"),
                rdf.createIRI("https://example.org/not-an-acceptance-criterion"));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));

        assertTrue(ex.getMessage().contains("acceptanceCriterion"), ex.getMessage());
    }

    /**
     * {@code rshapes:AcceptanceCriterionShape} requires exactly one {@code arkreq:position} and at
     * least one {@code arkreq:criterionText} - a candidate {@code arkreq:AcceptanceCriterion} node
     * missing {@code criterionText} entirely must be rejected. Mirrors
     * {@link #gateRejectsRequirementWithoutAcceptanceCriterion} one level deeper: the requirement
     * itself has a criterion edge, but the criterion resource at the far end is incomplete.
     */
    @Test
    void gateRejectsAcceptanceCriterionWithoutCriterionText() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        IRI criterion = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#FunctionalRequirement"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("FR-1"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Login"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/description"),
                rdf.createLiteral("The system shall authenticate a user."));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#status"),
                rdf.createIRI("https://w3id.org/arknet/requirements#Proposed"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#acceptanceCriterion"), criterion);
        candidate.add(criterion, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#AcceptanceCriterion"));
        candidate.add(criterion, rdf.createIRI("https://w3id.org/arknet/requirements#position"),
                rdf.createLiteral("1", VocabXsd.INTEGER));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));

        assertTrue(ex.getMessage().contains("criterionText"), ex.getMessage());
    }

    // ---- acceptanceCriterion: testable "Done when ..." criteria -------------------------

    @Test
    void createsAndFindsSeveralAcceptanceCriteria() {
        List<AcceptanceCriterion> criteria = List.of(
                new AcceptanceCriterion(1, "Login succeeds with valid credentials"),
                new AcceptanceCriterion(2, "Login is rate-limited"));
        Requirement requirement = new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                null, null, null, criteria, List.of());

        repository.create(PROJECT_A, requirement, null);

        assertEquals(criteria, repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null)
                .orElseThrow().acceptanceCriteria());
        assertEquals(requirement.acceptanceCriteria(), repository.findAll(PROJECT_A, null).get(0).acceptanceCriteria());
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
        repository.create(PROJECT_A, created, null);

        Requirement reloaded = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), null, reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(), reloaded.qualityCategory(), reloaded.usesTerms(),
                reloaded.acceptanceCriteria(), List.of());
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        Requirement found = repository.findByCode(PROJECT_A, code, null).orElseThrow();
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

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null).orElseThrow();

        assertEquals(1, found.acceptanceCriteria().size());
        assertTrue(found.acceptanceCriteria().get(0).text().contains("Altdatensatz"), found.acceptanceCriteria().toString());
    }

    /** Same regression as above, exercised via the batch {@link RequirementRepository#findAll}. */
    @Test
    void findAllSubstitutesAPlaceholderForARequirementPredatingAcceptanceCriterion() {
        RequirementId id = freshId();
        givenLegacyRequirementWithoutAcceptanceCriterion(PROJECT_A, id, "FR-1");

        List<Requirement> all = repository.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertEquals(1, all.get(0).acceptanceCriteria().size());
        assertTrue(all.get(0).acceptanceCriteria().get(0).text().contains("Altdatensatz"));
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
                repository.findCurrentByCode(PROJECT_A, new RequirementCode("FR-1"), null).orElseThrow();

        assertTrue(current.acceptanceCriteriaIsSynthesized());
        assertTrue(current.value().acceptanceCriteria().get(0).text().contains("Altdatensatz"));
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
        repository.create(PROJECT_A, new Requirement(id, code, "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of()), null);

        RequirementRepository.CurrentRequirement current =
                repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow();

        assertFalse(current.acceptanceCriteriaIsSynthesized());
        assertEquals(List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), current.value().acceptanceCriteria());
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
     * Store-first regression test (issue #266): two {@code arkreq:AcceptanceCriterion} resources
     * whose {@code arkreq:position} skips a number (1, 3 - no 2) are RDF-legal (nothing in SHACL
     * forbids the gap) but constructor-illegal for {@link Requirement}, which rejects
     * non-consecutive positions unconditionally. Before this would crash {@code findByCode}
     * instead of returning the requirement, exactly the way the all-empty case used to -
     * {@link #acceptanceCriteriaOrLegacyPlaceholder} now substitutes the same read-time placeholder
     * for this case too.
     */
    @Test
    void findByCodeSubstitutesAPlaceholderForARequirementWithAGapInAcceptanceCriterionPositions() {
        RequirementId id = freshId();
        givenRequirementWithAcceptanceCriterionPositionGap(PROJECT_A, id, "FR-1");

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null).orElseThrow();

        assertEquals(1, found.acceptanceCriteria().size());
        assertTrue(found.acceptanceCriteria().get(0).text().contains("Altdatensatz"),
                found.acceptanceCriteria().toString());
    }

    /**
     * Same defect as {@link #findByCodeSubstitutesAPlaceholderForARequirementWithAGapInAcceptanceCriterionPositions},
     * exercised via the batch {@link RequirementRepository#findAll} - here the gap is a knock-on
     * effect of {@link #toAcceptanceCriteria} filtering out a blank {@code arkreq:criterionText}
     * candidate in the middle of an otherwise-consecutive sequence (positions 1, 2, 3 with position
     * 2's text blank), leaving positions 1 and 3 - a gap the very same guard catches.
     */
    @Test
    void findAllSubstitutesAPlaceholderWhenABlankAcceptanceCriterionTextLeavesAGap() {
        RequirementId id = freshId();
        givenRequirementWithABlankAcceptanceCriterionTextInTheMiddle(PROJECT_A, id, "FR-1");

        List<Requirement> all = repository.findAll(PROJECT_A, null);

        assertEquals(1, all.size());
        assertEquals(1, all.get(0).acceptanceCriteria().size());
        assertTrue(all.get(0).acceptanceCriteria().get(0).text().contains("Altdatensatz"));
    }

    /**
     * Writes a shape-legal {@code arkreq:FunctionalRequirement} straight into the requirements
     * graph with two {@code arkreq:AcceptanceCriterion} resources whose positions skip a number -
     * {@code AcceptanceCriterionShape} places no uniqueness/consecutiveness constraint on
     * {@code arkreq:position} across sibling criteria, and {@code req_update} cannot produce a gap
     * (append-only + in-place patch, issue #266), so this is reachable only store-first.
     */
    private void givenRequirementWithAcceptanceCriterionPositionGap(ProjectId projectId, RequirementId id,
            String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "<" + id.value().value() + "-c1> , <" + id.value().value() + "-c3> . "
                + "<" + id.value().value() + "-c1> <https://w3id.org/arknet/requirements#position> 1 ; "
                + "<https://w3id.org/arknet/requirements#criterionText> \"Login succeeds with valid credentials\" . "
                + "<" + id.value().value() + "-c3> <https://w3id.org/arknet/requirements#position> 3 ; "
                + "<https://w3id.org/arknet/requirements#criterionText> \"Third criterion, position 2 is missing\" "
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
     * graph with three {@code arkreq:AcceptanceCriterion} resources at consecutive positions
     * (1, 2, 3), the middle one's {@code arkreq:criterionText} whitespace-only -
     * {@code AcceptanceCriterion-text} places no {@code sh:pattern}/blank-rejection on the
     * property (only {@code sh:minLength}, which a whitespace-only literal already satisfies), and
     * {@link Requirement}'s constructor is the only place that rejects a blank criterion text, so
     * this is reachable only store-first.
     */
    private void givenRequirementWithABlankAcceptanceCriterionTextInTheMiddle(ProjectId projectId, RequirementId id,
            String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/requirements> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#FunctionalRequirement> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" ; "
                + "<http://purl.org/dc/terms/title> \"Login\" ; "
                + "<http://purl.org/dc/terms/description> \"The system shall authenticate a user.\" ; "
                + "<https://w3id.org/arknet/requirements#status> <https://w3id.org/arknet/requirements#Proposed> ; "
                + "<https://w3id.org/arknet/requirements#acceptanceCriterion> "
                + "<" + id.value().value() + "-c1> , <" + id.value().value() + "-c2> , <" + id.value().value()
                + "-c3> . "
                + "<" + id.value().value() + "-c1> <https://w3id.org/arknet/requirements#position> 1 ; "
                + "<https://w3id.org/arknet/requirements#criterionText> \"Login succeeds with valid credentials\" . "
                + "<" + id.value().value() + "-c2> <https://w3id.org/arknet/requirements#position> 2 ; "
                + "<https://w3id.org/arknet/requirements#criterionText> \"   \" . "
                + "<" + id.value().value() + "-c3> <https://w3id.org/arknet/requirements#position> 3 ; "
                + "<https://w3id.org/arknet/requirements#criterionText> \"Third criterion\" "
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

        List<Requirement> all = repository.findAll(PROJECT_A, null);

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

        Priority first = repository.findAll(PROJECT_A, null).get(0).priority();
        Priority second = repository.findAll(PROJECT_A, null).get(0).priority();

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

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null).orElseThrow();

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
     * carrying several types) and store-first reachable, but unreachable via
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
     * {@code req_add}/{@code req_set_status}, but a store-first edit can legally write
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
                () -> repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null));

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
                () -> repository.findCurrentByCode(PROJECT_A, new RequirementCode("FR-1"), null));
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
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, List.of(),
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of()), null);
        givenRequirementWithStatus(PROJECT_A, freshId(), "FR-2", "https://w3id.org/arknet/requirements#Verified");

        UnsupportedRequirementStatusException thrown = assertThrows(
                UnsupportedRequirementStatusException.class, () -> repository.findAll(PROJECT_A, null));

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

    // ---- priority/qualityCategory: SHACL-legal but type-mismatched (issue #163) ----

    /**
     * Store-first regression test for issue #163: {@code requirements-shapes.ttl}'s
     * {@code Requirement-priority} shape declares no {@code sh:nodeKind}, so a store-first
     * edit can legally write {@code arkreq:priority} as a literal instead of an IRI.
     * Before the fix {@code priorityOf}'s unguarded {@code (IRI) value} cast threw an uncaught
     * {@link ClassCastException}; the fix reads the mismatched value as "not set" instead.
     */
    @Test
    void findByCodeReadsATypeMismatchedPriorityAsNotSetInsteadOfThrowing() {
        RequirementId id = freshId();
        givenRequirementWithLiteralInsteadOfIri(PROJECT_A, id, "FR-1", "priority", "not-an-iri");

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null).orElseThrow();

        assertNull(found.priority());
    }

    /**
     * {@code qualityCategory}'s expected RDF term kind is the opposite of {@code priority}'s
     * (a {@link Literal}, not an {@link IRI}) - this test writes an IRI
     * where a literal is expected, exercising {@code qualityCategoryOf}'s guard instead.
     */
    @Test
    void findByCodeReadsATypeMismatchedQualityCategoryAsNotSetInsteadOfThrowing() {
        RequirementId id = freshId();
        givenRequirementWithIriInsteadOfLiteral(
                PROJECT_A, id, "FR-1", "qualityCategory", "https://w3id.org/arknet/id/not-a-literal");

        Requirement found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null).orElseThrow();

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
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, List.of(),
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of()), null);
        givenRequirementWithLiteralInsteadOfIri(PROJECT_A, freshId(), "FR-2", "priority", "not-an-iri");

        List<Requirement> all = repository.findAll(PROJECT_A, null);

        assertEquals(2, all.size());
        Requirement brokenPriority = all.stream()
                .filter(requirement -> requirement.code().equals(new RequirementCode("FR-2")))
                .findFirst()
                .orElseThrow();
        assertNull(brokenPriority.priority());
    }

    /**
     * Writes an {@code arkreq:FunctionalRequirement} straight into the requirements graph with
     * {@code property} bound to a literal instead of the IRI its {@code priorityOf}
     * decoder expects - shape-legal ({@code requirements-shapes.ttl}
     * declares no {@code sh:nodeKind} for the property, so the write-gate never rejects it),
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
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        Requirement second = new Requirement(freshId(), new RequirementCode("FR-2"), "Logout",
                "The system shall end a user session.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, first, null);
        repository.create(PROJECT_A, second, null);

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
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, known, null);
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
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.create(PROJECT_A, inProjectA, null);

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

        repository.create(PROJECT_A, requirement, null);

        assertEquals(Optional.of(requirement),
                repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null));
        assertEquals(List.of(termRef("TERM-1")),
                repository.findAll(PROJECT_A, null).get(0).usesTerms());
    }

    @Test
    void createsAndFindsSeveralUsesTermEdges() {
        givenTerm(PROJECT_A, "TERM-1");
        givenTerm(PROJECT_A, "TERM-2");

        repository.create(PROJECT_A, requirementUsing(termRef("TERM-1"), termRef("TERM-2")), null);

        List<TermRef> found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null)
                .orElseThrow().usesTerms();
        assertEquals(2, found.size());
        assertTrue(found.containsAll(List.of(termRef("TERM-1"), termRef("TERM-2"))));
    }

    /**
     * Term references arrive pre-resolved. This adapter no longer looks the term up
     * (that strict, identifier-based resolution now lives in {@code KognioRdfTermLookup}, called
     * once by the application service when a term is linked) - it trusts the identity it is
     * handed. A target that
     * does not exist at all in the store is therefore persisted just the same as one that does;
     * see {@code KognioRdfTermLookupTest} for the strict-resolution behaviour this used to be
     * (and still is, just one layer up).
     */
    @Test
    void createPersistsAUsesTermEdgeEvenWhenItsTargetDoesNotExistInTheStore() {
        TermRef doesNotExist = termRef("TERM-99");

        repository.create(PROJECT_A, requirementUsing(doesNotExist), null);

        assertEquals(List.of(doesNotExist),
                repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null).orElseThrow().usesTerms());
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
        repository.create(PROJECT_A, requirementUsing(termRef("TERM-1")), null);

        Requirement reloaded = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), null, reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(), reloaded.qualityCategory(), reloaded.usesTerms(), List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        Requirement found = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, found.status());
        assertEquals(List.of(termRef("TERM-1")), found.usesTerms());
    }

    @Test
    void unlinkingATermRemovesTheEdge() {
        givenTerm(PROJECT_A, "TERM-1");
        RequirementCode code = new RequirementCode("FR-1");
        Requirement created = requirementUsing(termRef("TERM-1"));
        repository.create(PROJECT_A, created, null);

        replaceViaCompareAndUpdate(PROJECT_A, new Requirement(created.id(), created.code(), created.title(),
                created.description(), null, created.type(), created.status(), created.priority(), created.qualityCategory(), List.of(), List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of()));

        assertEquals(List.of(), repository.findByCode(PROJECT_A, code, null).orElseThrow().usesTerms());
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
     * {@code rshapes:Requirement-title} carries {@code sh:uniqueLang true} (formerly
     * {@code sh:maxCount 1}): two language-tagged titles sharing the exact same non-empty tag are
     * rejected, but {@link Requirement#title()} stays single-valued at the domain level - a second
     * title is unreachable via {@link RequirementRepository#create}, so this exercises the gate
     * directly against a synthetic candidate graph, the way a store-first write could
     * still produce two same-tagged triples. Two plain, <em>untagged</em> titles are deliberately
     * <strong>not</strong> covered here: {@code sh:uniqueLang} per the SHACL spec only ever
     * compares literals that carry a non-empty language tag, so two untagged titles are SHACL-legal
     * (mirroring {@code skos:prefLabel}/{@code skos:definition} in the sibling
     * ubiquitous-language adapter, which has the same property).
     */
    @Test
    void gateRejectsRequirementWithTwoTitlesSharingTheSameLanguageTag() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#FunctionalRequirement"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("FR-1"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Login", "en"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Sign in", "en"));
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
     * {@code rshapes:Requirement-description} carries {@code sh:uniqueLang true},
     * mirroring {@link #gateRejectsRequirementWithTwoTitlesSharingTheSameLanguageTag}. {@link
     * Requirement#description()} is single-valued, so a second description is unreachable via
     * {@link RequirementRepository#create}.
     */
    @Test
    void gateRejectsRequirementWithTwoDescriptionsSharingTheSameLanguageTag() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#FunctionalRequirement"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("FR-1"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("Login"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/description"),
                rdf.createLiteral("The system shall authenticate a user.", "en"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/description"),
                rdf.createLiteral("The system must authenticate a user.", "en"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#status"),
                rdf.createIRI("https://w3id.org/arknet/requirements#Proposed"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#acceptanceCriterion"),
                rdf.createLiteral("Login succeeds with valid credentials"));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));

        assertTrue(ex.getMessage().contains("description"), ex.getMessage());
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
        repository.create(PROJECT_A, created, null);
        givenUsesTermEdge(PROJECT_A, created.id(), termIri);
        TermRef expected = new TermRef(ResourceId.of(termIri));

        Requirement reloaded = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        assertEquals(List.of(expected), reloaded.usesTerms(),
                "reading no longer joins into the terms graph, so a missing dcterms:identifier "
                        + "on the target no longer hides the edge");

        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), null, reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(), reloaded.qualityCategory(), reloaded.usesTerms(), List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        assertEquals(List.of(expected), repository.findByCode(PROJECT_A, code, null).orElseThrow().usesTerms(),
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
        repository.create(PROJECT_A, created, null);
        givenUsesTermEdgeToFreshBlankNodeConcept(PROJECT_A, created.id());

        Requirement reloaded = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        assertEquals(List.of(), reloaded.usesTerms(), "blank-node edge must stay invisible to the read");

        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), null, reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(), reloaded.qualityCategory(), reloaded.usesTerms(), List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
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
        repository.create(PROJECT_A, requirementUsing(termRef("TERM-1")), null);

        Requirement reloaded = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), null, reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(), reloaded.qualityCategory(), reloaded.usesTerms(), List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        String termIri = "https://w3id.org/arknet/model/term/TERM-1";
        assertEquals(1, countUsesTermEdges(PROJECT_A, reloaded.id(), termIri));
    }

    private static Requirement requirementUsing(TermRef... terms) {
        return new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, List.of(terms), List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
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
     * reachable store-first. {@code KognioRdfTermLookup} cannot resolve such a concept
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
     * store-first path, unmediated by {@code req_link_term}/{@code KognioRdfTermLookup},
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

    // ---- constrainedBy: the (same-module) requirement -> constraint edge -----------------

    @Test
    void createsAndFindsConstrainedByEdge() {
        ConstraintRef ref = givenConstraint(PROJECT_A, "TCON-1", ConstraintType.TECHNICAL);
        Requirement requirement = requirementConstrainedBy(ref);

        repository.create(PROJECT_A, requirement, null);

        assertEquals(Optional.of(requirement),
                repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null));
        assertEquals(List.of(ref), repository.findAll(PROJECT_A, null).get(0).constrainedBy());
    }

    @Test
    void createsAndFindsSeveralConstrainedByEdges() {
        ConstraintRef technical = givenConstraint(PROJECT_A, "TCON-1", ConstraintType.TECHNICAL);
        ConstraintRef business = givenConstraint(PROJECT_A, "BCON-1", ConstraintType.BUSINESS);

        repository.create(PROJECT_A, requirementConstrainedBy(technical, business), null);

        List<ConstraintRef> found = repository.findByCode(PROJECT_A, new RequirementCode("FR-1"), null)
                .orElseThrow().constrainedBy();
        assertEquals(2, found.size());
        assertTrue(found.containsAll(List.of(technical, business)));
    }

    /**
     * Regression guard for the replace-by-identity write path: {@code update} wipes the
     * subject's triples before re-writing them, so a read-modify-write round trip must carry
     * the constrainedBy edges along instead of silently dropping them - mirrors
     * {@link #usesTermEdgesSurviveAReplacingUpdate}.
     */
    @Test
    void constrainedByEdgesSurviveAReplacingUpdate() {
        ConstraintRef ref = givenConstraint(PROJECT_A, "TCON-1", ConstraintType.TECHNICAL);
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, requirementConstrainedBy(ref), null);

        Requirement reloaded = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        Requirement accepted = new Requirement(reloaded.id(), reloaded.code(), reloaded.title(),
                reloaded.description(), null, reloaded.type(), RequirementStatus.ACCEPTED, reloaded.priority(), reloaded.qualityCategory(), reloaded.usesTerms(),
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), reloaded.constrainedBy());
        replaceViaCompareAndUpdate(PROJECT_A, accepted);

        Requirement found = repository.findByCode(PROJECT_A, code, null).orElseThrow();
        assertEquals(RequirementStatus.ACCEPTED, found.status());
        assertEquals(List.of(ref), found.constrainedBy());
    }

    /**
     * Proves the {@code sh:class arkreq:Constraint} constraint on {@code oslc_rm:constrainedBy}
     * actually fires - mirrors {@link #gateRejectsUsesTermPointingAtSomethingThatIsNotAConcept}.
     */
    @Test
    void gateRejectsConstrainedByPointingAtSomethingThatIsNotAConstraint() {
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
        candidate.add(subject, rdf.createIRI("http://open-services.net/ns/rm#constrainedBy"),
                rdf.createIRI("https://example.org/not-a-constraint"));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));

        assertTrue(ex.getMessage().contains("constrainedBy"), ex.getMessage());
    }

    private static Requirement requirementConstrainedBy(ConstraintRef... constraints) {
        return new Requirement(freshId(), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, List.of(),
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of(constraints));
    }

    /**
     * Creates a real constraint via {@link KognioRdfConstraintRepository} (sharing this test's
     * {@link #lifecycle}) and returns the {@link ConstraintRef} pointing at it - unlike
     * {@link #termRef}/{@code givenTerm}, a {@code constrainedBy} edge is re-verified at write
     * time (see {@code KognioRdfRequirementRepository#constraintAssertedContext}'s javadoc), so
     * these tests need a genuinely persisted constraint rather than a fabricated identity.
     */
    private ConstraintRef givenConstraint(ProjectId projectId, String code, ConstraintType type) {
        Constraint constraint = new Constraint(
                new ConstraintId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID())),
                new ConstraintCode(code), "A constraint", "A real, SHACL-conforming constraint statement.", type);
        constraints.create(projectId, constraint, "en");
        return new ConstraintRef(constraint.id().value());
    }

    // ---- revision trail: one revision per write, head queryable ----------------

    /**
     * Revision basis for this bounded context's funnel write paths: {@code create} and
     * {@code compareAndUpdate} each record exactly one immutable revision, and the head is
     * queryable per resource. Since {@code compareAndUpdate} was resolved into the funnel,
     * this is no longer a special path any more than {@code create} is -
     * {@code RequirementService} routes every state change ({@code req_update}, {@code
     * req_set_status}, {@code req_link_term}) through it, so the head now moves on every
     * user-reachable requirement write, not just the initial {@code create}.
     */
    @Test
    void createAndCompareAndUpdateEachRecordExactlyOneRevisionWithAQueryableHead() {
        RequirementId id = freshId();
        RequirementCode code = new RequirementCode("FR-1");
        repository.create(PROJECT_A, new Requirement(id, code, "Login",
                "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, null, null, null, List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of()), null);

        assertEquals(1, revisionsOf(id).size(), "create must record exactly one revision");
        RevisionToken headAfterCreate = repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow().head();

        Requirement accepted = new Requirement(id, code, "Login", "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.ACCEPTED, null, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        repository.compareAndUpdate(
                PROJECT_A, headAfterCreate, accepted, null, null, null, noAcceptanceCriteriaLanguages(accepted), null);

        List<String> revisions = revisionsOf(id);
        assertEquals(2, revisions.size(), "compareAndUpdate must record exactly one more revision");
        List<String> heads = headsOf(id);
        assertEquals(1, heads.size(), "the head is rewritten, never duplicated");
        assertTrue(revisions.contains(heads.get(0)), "the head must be one of the resource's revisions");
        assertEquals(heads.get(0), repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow().head().value(),
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
