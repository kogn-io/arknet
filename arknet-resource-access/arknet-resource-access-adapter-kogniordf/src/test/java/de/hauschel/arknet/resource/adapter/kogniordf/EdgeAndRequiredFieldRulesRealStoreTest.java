// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The edge and required-field shapes of every model context, driven through {@code submit} against
 * a real store: an edge that points at a stored resource of another class, or a resource or child
 * without a field its shape requires, is rejected with the field's path (a rule over several fields:
 * with its message) and leaves the store as it was. The typed cores ensure the same by resolving a
 * code to a resource of the expected type and by the constructors of their records.
 *
 * <p>One store serves every case: a rejected document changes nothing, which each case checks.</p>
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class EdgeAndRequiredFieldRulesRealStoreTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");

    private static final String PREFIXES = SubmitRealStoreTest.PREFIXES;

    private static final String REQ = "https://w3id.org/arknet/requirements#";
    private static final String DDD = "https://w3id.org/arknet/ddd#";
    private static final String ARCH = "https://w3id.org/arknet/architecture#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String OSLC_RM = "http://open-services.net/ns/rm#";

    private static final String CREATE = "[] a arkres:Create ; arkres:object _:x .\n_:x ";

    private static final String REQUIREMENT = "a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ;"
            + " dcterms:description \"Writes it.\"@en";
    private static final String CRITERION = " ; arkreq:acceptanceCriterion [ arkreq:position 1 ;"
            + " arkreq:criterionText \"Works fine.\"@en ]";
    private static final String USE_CASE = "a arkreq:UseCase ; dcterms:title \"Model\"@en ;"
            + " arkreq:useCaseGoal \"A decision is recorded.\"@en";
    private static final String STEP = " ; arkreq:mainStep [ arkreq:position 1 ;"
            + " arkreq:stepText \"Writes the decision.\"@en ]";
    private static final String TERM = "a skos:Concept ; skos:prefLabel \"Model\"@en ;"
            + " skos:definition \"What the store holds.\"@en";
    private static final String CONTEXT = "a arkddd:BoundedContext ; arknet:name \"Modelling\"@en ;"
            + " arkddd:domainVision \"Keeps the model consistent.\"@en";
    private static final String RELATIONSHIP = "a arkddd:ContextRelationship";
    private static final String DECISION = "a arkarch:ArchitectureDecisionRecord ; arknet:name \"Use RDF\"@en ;"
            + " arkarch:adrContext \"Needs links.\"@en ; arkarch:adrDecision \"Use RDF.\"@en";

    private DatasetLifecycle lifecycle;
    private SubmitService submit;
    private StoreReader reader;
    private Map<String, String> stored;

    @BeforeAll
    void setUp(@TempDir final Path storageRoot) {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        submit = new SubmitService(KognioRdfResourceRepositoryFactory.schema(),
                KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle),
                Clock.systemDefaultZone());
        reader = new StoreReader(lifecycle);
        SubmitResult fixtures = submit.submit(PROJECT, PREFIXES
                + "[] a arkres:Create ; arkres:object _:term .\n_:term " + TERM + " .\n"
                + "[] a arkres:Create ; arkres:object _:fr .\n_:fr " + REQUIREMENT + CRITERION + " .\n"
                + "[] a arkres:Create ; arkres:object _:role .\n"
                + "_:role a arkproc:Role ; arknet:name \"Architect\"@en .\n"
                + "[] a arkres:Create ; arkres:object _:up .\n_:up " + CONTEXT + " .\n"
                + "[] a arkres:Create ; arkres:object _:down .\n_:down " + CONTEXT + " .\n"
                + "[] a arkres:Create ; arkres:object _:third .\n_:third " + CONTEXT + " .\n"
                + "[] a arkres:Create ; arkres:object _:adr .\n_:adr " + DECISION + " .\n");
        stored = Map.of("<TERM>", "<" + SubmitRealStoreTest.iriOf(fixtures, "TERM-1") + ">",
                "<FR>", "<" + SubmitRealStoreTest.iriOf(fixtures, "FR-1") + ">",
                "<ROLE>", "<" + SubmitRealStoreTest.iriOf(fixtures, "ROLE-1") + ">",
                "<BC1>", "<" + SubmitRealStoreTest.iriOf(fixtures, "BC-1") + ">",
                "<BC2>", "<" + SubmitRealStoreTest.iriOf(fixtures, "BC-2") + ">",
                "<BC3>", "<" + SubmitRealStoreTest.iriOf(fixtures, "BC-3") + ">",
                "<ADR>", "<" + SubmitRealStoreTest.iriOf(fixtures, "ADR-1") + ">");
    }

    @AfterAll
    void tearDown() {
        lifecycle.shutDownAll();
    }

    static Stream<Arguments> violatingDocuments() {
        String useCase = USE_CASE + " ; arkreq:primaryRole <ROLE>";
        String relationship = RELATIONSHIP + " ; arkddd:relationshipType arkddd:Partnership";
        return Stream.of(
                // Product & Requirements
                Arguments.of("requirement without criterion", REQUIREMENT, REQ + "acceptanceCriterion"),
                Arguments.of("requirement uses a requirement as term", REQUIREMENT + CRITERION
                        + " ; arkreq:usesTerm <FR>", REQ + "usesTerm"),
                Arguments.of("requirement constrained by a term", REQUIREMENT + CRITERION
                        + " ; oslc_rm:constrainedBy <TERM>", OSLC_RM + "constrainedBy"),
                Arguments.of("use case with a term as primary role", USE_CASE + " ; arkreq:primaryRole <TERM>" + STEP,
                        REQ + "primaryRole"),
                Arguments.of("use case uses a requirement as term", useCase + STEP + " ; arkreq:usesTerm <FR>",
                        REQ + "usesTerm"),
                Arguments.of("use case constrained by a term", useCase + STEP + " ; oslc_rm:constrainedBy <TERM>",
                        OSLC_RM + "constrainedBy"),
                Arguments.of("step realises a term", useCase + " ; oslc_rm:satisfies <TERM> ; arkreq:mainStep"
                        + " [ arkreq:position 1 ; arkreq:stepText \"Writes it.\"@en ; arkreq:stepRealises <TERM> ]",
                        REQ + "stepRealises"),
                // Domain Modelling
                Arguments.of("term broader than a requirement", TERM + " ; skos:broader <FR>", SKOS + "broader"),
                Arguments.of("term related to a requirement", TERM + " ; skos:related <FR>", SKOS + "related"),
                Arguments.of("context speaks a requirement", CONTEXT + " ; arkddd:ubiquitousLanguageTerm <FR>",
                        DDD + "ubiquitousLanguageTerm"),
                Arguments.of("context delimits a requirement", CONTEXT + " ; arkddd:delimitsTerm <FR>",
                        DDD + "delimitsTerm"),
                Arguments.of("context speaks and delimits one term", CONTEXT
                        + " ; arkddd:ubiquitousLanguageTerm <TERM> ; arkddd:delimitsTerm <TERM>", DDD + "delimitsTerm"),
                Arguments.of("relationship without upstream", relationship + " ; arkddd:downstream <BC2>",
                        DDD + "upstream"),
                Arguments.of("relationship without downstream", relationship + " ; arkddd:upstream <BC1>",
                        DDD + "downstream"),
                Arguments.of("relationship upstream of a term", relationship
                        + " ; arkddd:upstream <TERM> ; arkddd:downstream <BC2>", DDD + "upstream"),
                Arguments.of("relationship of an unknown type", RELATIONSHIP + " ; arkddd:relationshipType"
                        + " arkddd:Unknown ; arkddd:upstream <BC1> ; arkddd:downstream <BC2>",
                        DDD + "relationshipType"),
                // Architecture & Decisions
                Arguments.of("consequence without type", DECISION + " ; arkarch:consequence [ arknet:position 1 ;"
                        + " arkarch:consequenceStatement \"Links are cheap.\"@en ]", ARCH + "consequenceType"),
                Arguments.of("option without outcome", DECISION + " ; arkarch:consideredOption [ arknet:position 1 ;"
                        + " arknet:name \"RDF\"@en ; arkarch:optionRationale \"Links are cheap.\"@en ]",
                        ARCH + "optionOutcome"),
                Arguments.of("decision addresses a term", DECISION + " ; arkarch:addressesRequirement <TERM>",
                        ARCH + "addressesRequirement"),
                Arguments.of("decision affects a term", DECISION + " ; arkarch:affectsContext <TERM>",
                        ARCH + "affectsContext"),
                Arguments.of("decision uses a requirement as term", DECISION + " ; arkarch:usesTerm <FR>",
                        ARCH + "usesTerm"),
                Arguments.of("decision related to a term", DECISION + " ; arkarch:relatedTo <TERM>",
                        ARCH + "relatedTo"),
                Arguments.of("proposed decision superseded by a term", DECISION + " ; arkarch:supersededBy <TERM>",
                        ARCH + "supersededBy"),
                Arguments.of("proposed decision superseded by a decision", DECISION + " ; arkarch:supersededBy <ADR>",
                        "An ADR's status must be Superseded whenever its arkarch:supersededBy edge is set."),
                // the required primary role and the single ends of a relationship
                Arguments.of("use case without primary role", USE_CASE + STEP, REQ + "primaryRole"),
                Arguments.of("relationship with two upstream contexts", relationship
                        + " ; arkddd:upstream <BC1> , <BC3> ; arkddd:downstream <BC2>", DDD + "upstream"),
                Arguments.of("relationship with two downstream contexts", relationship
                        + " ; arkddd:upstream <BC1> ; arkddd:downstream <BC2> , <BC3>", DDD + "downstream"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("violatingDocuments")
    void aDocumentBreakingAnEdgeOrRequiredFieldRuleIsRejectedWithTheField(final String rule, final String resource,
            final String expected) {
        // given
        String document = CREATE + resource + " .\n";
        for (Map.Entry<String, String> fixture : stored.entrySet()) {
            document = document.replace(fixture.getKey(), fixture.getValue());
        }
        List<String> notCreated = List.of("TERM-2", "FR-2", "UC1", "BC-4", "ADR-2");
        String submitted = PREFIXES + document;

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class,
                () -> submit.submit(PROJECT, submitted));

        // then
        assertTrue(rejected.getMessage().contains(expected), rule + ": " + rejected.getMessage());
        notCreated.forEach(code -> assertTrue(reader.findByIdentifier(PROJECT, code).isEmpty(), code));
        assertEquals(1, reader.history(PROJECT, stored.get("<TERM>").replaceAll("[<>]", "")).size());
    }
}
