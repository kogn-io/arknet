// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.DuplicateConstraintCodeException;
import de.hauschel.arknet.req.domain.ResourceAlreadyExistsException;

/**
 * Integration test for {@link KognioRdfConstraintRepository} against an in-memory RDF4J-backed
 * kognio-rdf store - mirrors {@link KognioRdfRequirementRepositoryTest}'s shape, but far simpler:
 * a {@link Constraint} is a flat subject with no cross-resource edges and no derived
 * sub-resources. Its compare-and-set update path and its multilingual title/statement (issue #313)
 * are covered separately by {@link KognioRdfConstraintRepositoryMultilingualTest}.
 */
class KognioRdfConstraintRepositoryTest {

    private static final ProjectId PROJECT_A = new ProjectId("a");
    private static final ProjectId PROJECT_B = new ProjectId("b");

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private ConstraintRepository repository;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        WriteFunnel funnel = KognioRdfRequirementRepositoryFactory.buildFunnel(datasetLifecycle, DisplayLocale.DEFAULT);
        repository = KognioRdfConstraintRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT, funnel);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static ConstraintId freshId() {
        return new ConstraintId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    /**
     * Creates a constraint under the tag {@code en} - the language every assertion in this class
     * reads back under {@link DisplayLocale#DEFAULT}. The multilingual behaviour itself
     * (preserving other language variants across a compare-and-set write, the issue #258 sweep)
     * lives in {@link KognioRdfConstraintRepositoryMultilingualTest}.
     */
    private void create(ProjectId project, Constraint constraint) {
        repository.create(project, constraint, "en");
    }

    @Test
    void createsAndFindsTechnicalConstraintByCode() {
        Constraint constraint = new Constraint(freshId(), new ConstraintCode("TCON-1"), "JVM only",
                "Must run on the JVM.", ConstraintType.TECHNICAL);

        create(PROJECT_A, constraint);

        assertEquals(Optional.of(constraint), repository.findByCode(PROJECT_A, new ConstraintCode("TCON-1"), null));
    }

    @Test
    void createsAndFindsBusinessConstraintByCode() {
        Constraint constraint = new Constraint(freshId(), new ConstraintCode("BCON-1"), "Budget cap",
                "Total spend must not exceed the approved budget.", ConstraintType.BUSINESS);

        create(PROJECT_A, constraint);

        assertEquals(Optional.of(constraint), repository.findByCode(PROJECT_A, new ConstraintCode("BCON-1"), null));
    }

    @Test
    void createsAndFindsRegulatoryConstraintByCode() {
        Constraint constraint = new Constraint(freshId(), new ConstraintCode("RCON-1"), "EU data residency",
                "Personal data must stay in the EU.", ConstraintType.REGULATORY);

        create(PROJECT_A, constraint);

        assertEquals(Optional.of(constraint), repository.findByCode(PROJECT_A, new ConstraintCode("RCON-1"), null));
    }

    @Test
    void findByCodeIsEmptyForUnknownCode() {
        assertEquals(Optional.empty(), repository.findByCode(PROJECT_A, new ConstraintCode("TCON-99"), null));
    }

    @Test
    void createRejectsACollidingIdentity() {
        ConstraintId id = freshId();
        Constraint first = new Constraint(id, new ConstraintCode("TCON-1"), "JVM only", "Must run on the JVM.",
                ConstraintType.TECHNICAL);
        Constraint collidingId = new Constraint(id, new ConstraintCode("TCON-2"), "Logout",
                "Must terminate the session.", ConstraintType.TECHNICAL);
        create(PROJECT_A, first);

        assertThrows(ResourceAlreadyExistsException.class, () -> create(PROJECT_A, collidingId));
    }

    @Test
    void createRejectsACollidingCode() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        Constraint first = new Constraint(freshId(), code, "JVM only", "Must run on the JVM.",
                ConstraintType.TECHNICAL);
        Constraint collidingCode = new Constraint(freshId(), code, "PostgreSQL only",
                "Only PostgreSQL is allowed.", ConstraintType.TECHNICAL);
        create(PROJECT_A, first);

        assertThrows(DuplicateConstraintCodeException.class, () -> create(PROJECT_A, collidingCode));
    }

    @Test
    void findAllContainsAllCreatedConstraints() {
        create(PROJECT_A, new Constraint(freshId(), new ConstraintCode("TCON-1"), "JVM only",
                "Must run on the JVM.", ConstraintType.TECHNICAL));
        create(PROJECT_A, new Constraint(freshId(), new ConstraintCode("BCON-1"), "Budget cap",
                "Total spend must not exceed the approved budget.", ConstraintType.BUSINESS));

        List<Constraint> all = repository.findAll(PROJECT_A, null);
        assertEquals(2, all.size());
    }

    @Test
    void findAllIsScopedPerProject() {
        create(PROJECT_A, new Constraint(freshId(), new ConstraintCode("TCON-1"), "JVM only",
                "Must run on the JVM.", ConstraintType.TECHNICAL));
        create(PROJECT_B, new Constraint(freshId(), new ConstraintCode("TCON-1"), "JVM only",
                "Must run on the JVM.", ConstraintType.TECHNICAL));

        assertEquals(1, repository.findAll(PROJECT_A, null).size());
        assertEquals(1, repository.findAll(PROJECT_B, null).size());
    }

    /**
     * What {@link ConstraintRepository#findAllCodes} is for (kogn-io/arknet#360), against the real
     * store: type and {@code dcterms:identifier} are the whole query, so a code survives on a
     * subject {@link ConstraintRepository#findAll} throws away for want of a readable title and
     * statement. The inserted subject carries neither, the barest thing the constraints graph can
     * hold - anything the counting query joins in future beyond those two triples breaks this test
     * instead of silently reissuing {@code TCON-2}.
     */
    @Test
    void findAllCodesKeepsTheCodeOfASubjectFindAllCannotMaterialiseAtAll() {
        Constraint first = new Constraint(freshId(), new ConstraintCode("TCON-1"), "JVM only",
                "Must run on the JVM.", ConstraintType.TECHNICAL);
        create(PROJECT_A, first);
        givenBareCodedSubject(PROJECT_A, freshId(), "TCON-2");

        assertEquals(List.of(first), repository.findAll(PROJECT_A, null));
        assertTrue(repository.findByCode(PROJECT_A, new ConstraintCode("TCON-2"), null).isEmpty());
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new ConstraintCode("TCON-2")),
                repository.findAllCodes(PROJECT_A).toString());
    }

    /**
     * The blank-node variant of the previous test (kogn-io/arknet#360). Nothing in this query
     * restricts {@code ?s} to an IRI, on purpose: {@code WriteFunnel#create} probes for a taken
     * code with a wildcard subject ({@code tx.contains(graph, null, dcterms:identifier, code)}) and
     * would therefore decline a {@code constraint_add} for {@code TCON-2} while an anonymous
     * subject holds it. A counter that did not see it would keep handing the blocked number to
     * every retry of the code assignment. A {@code FILTER(isIRI(?s))} added here later is caught by
     * this test rather than by a bounded context that can no longer add a constraint.
     */
    @Test
    void findAllCodesCountsACodeHeldByABlankNodeSubject() {
        Constraint first = new Constraint(freshId(), new ConstraintCode("TCON-1"), "JVM only",
                "Must run on the JVM.", ConstraintType.TECHNICAL);
        create(PROJECT_A, first);
        givenBareBlankNodeSubject(PROJECT_A, "TCON-2");

        // No findAll assertion alongside, unlike the IRI-subject test above: this adapter's
        // findAll casts ?s to an IRI unguarded, so a blank-node subject makes it throw a
        // ClassCastException rather than skip the row - a separate defect the glossary and
        // requirements adapters already guard against, and not what this test is about.
        assertTrue(repository.findAllCodes(PROJECT_A).contains(new ConstraintCode("TCON-2")),
                repository.findAllCodes(PROJECT_A).toString());
    }

    /**
     * Writes a subject with the constraint type triple and {@code dcterms:identifier} and nothing
     * else. Both shapes demand a title and a statement, so no {@code constraint_add} can produce
     * this - only a store-first (ADR-005) write, the case kogn-io/arknet#360 concerns.
     */
    private void givenBareCodedSubject(ProjectId projectId, ConstraintId id, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/constraints> { "
                + "<" + id.value().value() + "> a <https://w3id.org/arknet/requirements#TechnicalConstraint> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /**
     * The same subject stripped of its identity too - {@code []} mints an anonymous node, which no
     * constraint shape rules out and which {@code constraint_add} cannot produce, since it always
     * goes through a minted {@code ResourceId}. Store-first (ADR-005) data of this shape still owns
     * its code.
     */
    private void givenBareBlankNodeSubject(ProjectId projectId, String code) {
        String insert = "INSERT DATA { GRAPH <https://w3id.org/arknet/model/constraints> { "
                + "[] a <https://w3id.org/arknet/requirements#TechnicalConstraint> ; "
                + "<http://purl.org/dc/terms/identifier> \"" + code + "\" } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(projectId.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    @Test
    void findByIdsResolvesKnownIdentitiesInOneBatch() {
        Constraint first = new Constraint(freshId(), new ConstraintCode("TCON-1"), "JVM only",
                "Must run on the JVM.", ConstraintType.TECHNICAL);
        Constraint second = new Constraint(freshId(), new ConstraintCode("BCON-1"), "Budget cap",
                "Total spend must not exceed the approved budget.", ConstraintType.BUSINESS);
        create(PROJECT_A, first);
        create(PROJECT_A, second);

        List<ResolveConstraints.ResolvedConstraint> resolved = repository.findByIds(
                PROJECT_A, List.of(first.id().value(), second.id().value()));

        assertEquals(2, resolved.size());
        assertTrue(resolved.contains(new ResolveConstraints.ResolvedConstraint(first.id().value(), first.code())));
        assertTrue(resolved.contains(new ResolveConstraints.ResolvedConstraint(second.id().value(), second.code())));
    }

    @Test
    void findByIdsWithNoIdsReturnsAnEmptyList() {
        assertEquals(List.of(), repository.findByIds(PROJECT_A, List.of()));
    }

    @Test
    void findByIdsSilentlyOmitsUnknownIdentities() {
        Constraint known = new Constraint(freshId(), new ConstraintCode("TCON-1"), "JVM only",
                "Must run on the JVM.", ConstraintType.TECHNICAL);
        create(PROJECT_A, known);
        ResourceId unknown = ResourceId.of("https://w3id.org/arknet/id/does-not-exist");

        List<ResolveConstraints.ResolvedConstraint> resolved =
                repository.findByIds(PROJECT_A, List.of(known.id().value(), unknown));

        assertEquals(List.of(new ResolveConstraints.ResolvedConstraint(known.id().value(), known.code())), resolved);
    }

    /**
     * {@code arkreq:constraintStatement} carries {@code sh:minLength 5}. {@link Constraint}'s
     * constructor only rejects a blank statement, so this candidate graph - built directly,
     * bypassing the domain object - proves the gate itself, not just the domain invariant, is
     * what rejects a too-short statement. Mirrors
     * {@code KognioRdfRequirementRepositoryTest#gateRejectsTooShortDescription}-style tests.
     */
    @Test
    void gateRejectsTooShortConstraintStatement() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#TechnicalConstraint"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("TCON-1"));
        candidate.add(subject, rdf.createIRI("http://purl.org/dc/terms/title"), rdf.createLiteral("JVM only"));
        candidate.add(subject, rdf.createIRI("https://w3id.org/arknet/requirements#constraintStatement"),
                rdf.createLiteral("Hi"));

        WriteConstraintViolationException ex = assertThrows(WriteConstraintViolationException.class,
                () -> KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate));

        assertTrue(ex.getMessage().contains("constraintStatement"), ex.getMessage());
    }
}
