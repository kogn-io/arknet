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
