// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The end-state rules of the Product &amp; Requirements context (BC-1), each driven through
 * {@code submit} against a real store: one document that breaks the rule is rejected and names
 * it, the same document made right is accepted, and a rejected document leaves nothing behind.
 */
class ProductRequirementsRulesRealStoreTest {

    private static final String PREFIXES = SubmitRealStoreTest.PREFIXES;
    private static final String REQ = "https://w3id.org/arknet/requirements#";

    private static final String CREATE_ROLE = "[] a arkres:Create ; arkres:object _:role .\n"
            + "_:role a arkproc:Role ; arknet:name \"Modeller\"@en .\n";

    @TempDir
    Path storageRoot;

    private DatasetLifecycle lifecycle;
    private SubmitService submit;
    private StoreReader reader;

    @BeforeEach
    void setUp() {
        lifecycle = new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), storageRoot);
        submit = new SubmitService(KognioRdfResourceRepositoryFactory.schema(),
                KognioRdfResourceRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT),
                KognioRdfResourceRepositoryFactory.parser(), new UuidResourceIdFactory(),
                KognioRdfResourceRepositoryFactory.prefixes(), KognioRdfResourceRepositoryFactory.handles(lifecycle));
        reader = new StoreReader(lifecycle);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static String functionalRequirement(final String extraFields) {
        return "[] a arkres:Create ; arkres:object _:fr .\n"
                + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ;"
                + " dcterms:description \"Writes it.\"@en ; " + extraFields
                + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Works fine.\"@en ] .\n";
    }

    private static String useCase(final String role, final String... fields) {
        return useCaseTitled("Model a decision", role, fields);
    }

    private static String useCaseTitled(final String title, final String role, final String... fields) {
        StringBuilder document = new StringBuilder("[] a arkres:Create ; arkres:object _:uc .\n")
                .append("_:uc a arkreq:UseCase ; dcterms:title \"").append(title).append("\"@en ;")
                .append(" arkreq:primaryRole <").append(role).append(">");
        for (String field : fields) {
            document.append(" ;\n    ").append(field);
        }
        return document.append(" .\n").toString();
    }

    private static final String GOAL = "arkreq:useCaseGoal \"A decision is recorded.\"@en";
    private static final String STEP = "arkreq:mainStep [ arkreq:position 1 ;"
            + " arkreq:stepText \"Writes the decision.\"@en ]";

    private String storedRole() {
        return SubmitRealStoreTest.iriOf(submit.submit(SubmitRealStoreTest.PROJECT, PREFIXES + CREATE_ROLE), "ROLE-1");
    }

    private String storedRequirement() {
        return SubmitRealStoreTest.iriOf(submit.submit(SubmitRealStoreTest.PROJECT,
                PREFIXES + functionalRequirement("")), "FR-1");
    }

    private SubmitRejectedException rejected(final String document) {
        return assertThrows(SubmitRejectedException.class,
                () -> submit.submit(SubmitRealStoreTest.PROJECT, PREFIXES + document));
    }

    private void assertNothingStored(final String code) {
        assertTrue(reader.findByIdentifier(SubmitRealStoreTest.PROJECT, code).isEmpty(), code);
    }

    // -- requirement status, priority, quality category ---------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Implemented", "Verified", "Rejected", "Deprecated"})
    void aRequirementStatusBeyondProposedAndAcceptedIsRejected(final String status) {
        // when
        SubmitRejectedException rejected = rejected(functionalRequirement("arkreq:status arkreq:" + status + " ;"));

        // then
        assertTrue(rejected.getMessage().contains(REQ + "status"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("Proposed or Accepted"), rejected.getMessage());
        assertNothingStored("FR-1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"Proposed", "Accepted"})
    void aRequirementStatusProposedOrAcceptedIsAccepted(final String status) {
        assertDoesNotThrow(() -> submit.submit(SubmitRealStoreTest.PROJECT,
                PREFIXES + functionalRequirement("arkreq:status arkreq:" + status + " ;")));
    }

    @Test
    void aRequirementPriorityOutsideMoscowIsRejected() {
        // when
        SubmitRejectedException rejected = rejected(functionalRequirement("arkreq:priority arkreq:Urgent ;"));

        // then
        assertTrue(rejected.getMessage().contains(REQ + "priority"), rejected.getMessage());
        assertNothingStored("FR-1");
    }

    @Test
    void aFunctionalRequirementWithAQualityCategoryIsRejected() {
        // when
        SubmitRejectedException rejected = rejected(functionalRequirement("arkreq:qualityCategory \"performance\" ;"));

        // then
        assertTrue(rejected.getMessage().contains(REQ + "qualityCategory"), rejected.getMessage());
        assertNothingStored("FR-1");
    }

    @Test
    void aNonFunctionalRequirementWithAQualityCategoryIsAccepted() {
        assertDoesNotThrow(() -> submit.submit(SubmitRealStoreTest.PROJECT, PREFIXES
                + "[] a arkres:Create ; arkres:object _:nfr .\n"
                + "_:nfr a arkreq:NonFunctionalRequirement ; dcterms:title \"Fast\"@en ;"
                + " dcterms:description \"Within a second.\"@en ; arkreq:qualityCategory \"performance\" ;"
                + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Below one second.\"@en ] .\n"));
    }

    // -- text that is only whitespace -----------------------------------------------------------

    static Stream<Arguments> blankTexts() {
        String blank = "\"      \"@en";
        return Stream.of(
                Arguments.of("FR-1", "requirement title",
                        "[] a arkres:Create ; arkres:object _:fr .\n_:fr a arkreq:FunctionalRequirement ;"
                                + " dcterms:title \"   \"@en ; dcterms:description \"Writes it.\"@en ;"
                                + " arkreq:acceptanceCriterion [ arkreq:position 1 ;"
                                + " arkreq:criterionText \"Works fine.\"@en ] .\n",
                        "http://purl.org/dc/terms/title"),
                Arguments.of("FR-1", "requirement description",
                        "[] a arkres:Create ; arkres:object _:fr .\n_:fr a arkreq:FunctionalRequirement ;"
                                + " dcterms:title \"Write\"@en ; dcterms:description " + blank + " ;"
                                + " arkreq:acceptanceCriterion [ arkreq:position 1 ;"
                                + " arkreq:criterionText \"Works fine.\"@en ] .\n",
                        "http://purl.org/dc/terms/description"),
                Arguments.of("FR-1", "requirement rationale", functionalRequirement("arkreq:rationale " + blank + " ;"),
                        REQ + "rationale"),
                Arguments.of("FR-1", "acceptance criterion text",
                        "[] a arkres:Create ; arkres:object _:fr .\n_:fr a arkreq:FunctionalRequirement ;"
                                + " dcterms:title \"Write\"@en ; dcterms:description \"Writes it.\"@en ;"
                                + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText "
                                + blank + " ] .\n",
                        REQ + "criterionText"),
                Arguments.of("TCON-1", "constraint title",
                        "[] a arkres:Create ; arkres:object _:c .\n_:c a arkreq:TechnicalConstraint ;"
                                + " dcterms:title \"   \"@en ; arkreq:constraintStatement \"Runs on Java 25.\"@en .\n",
                        "http://purl.org/dc/terms/title"),
                Arguments.of("TCON-1", "constraint statement",
                        "[] a arkres:Create ; arkres:object _:c .\n_:c a arkreq:TechnicalConstraint ;"
                                + " dcterms:title \"Java 25\"@en ; arkreq:constraintStatement " + blank + " .\n",
                        REQ + "constraintStatement"));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("blankTexts")
    void aTextOfOnlyWhitespaceIsRejected(final String code, final String field, final String document,
            final String path) {
        // when
        SubmitRejectedException rejected = rejected(document);

        // then
        assertTrue(rejected.getMessage().contains(path), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("non-whitespace"), rejected.getMessage());
        assertNothingStored(code);
    }

    @ParameterizedTest
    @ValueSource(strings = {"\\u2003", "\\u3000", "\\u00A0", "\\u001F", "\\t", "\\n", " "})
    void everyKindOfBlankCharacterCountsAsWhitespace(final String blankCharacter) {
        // given - five of the character, long enough for the length rule, so only the blank rule can reject it
        String blank = "\"" + blankCharacter.repeat(5) + "\"@en";

        // when
        SubmitRejectedException rejected = rejected(functionalRequirement("arkreq:rationale " + blank + " ;"));

        // then
        assertTrue(rejected.getMessage().contains("non-whitespace"), rejected.getMessage());
        assertNothingStored("FR-1");
    }

    @Test
    void aUseCaseTitleGoalOrStepTextOfOnlyWhitespaceIsRejected() {
        // given
        String role = storedRole();
        String[] documents = {
            useCaseTitled("   ", role, GOAL, STEP),
            useCase(role, "arkreq:useCaseGoal \"      \"@en", STEP),
            useCase(role, GOAL, "arkreq:mainStep [ arkreq:position 1 ; arkreq:stepText \"     \"@en ]")
        };
        String[] paths = {"http://purl.org/dc/terms/title", REQ + "useCaseGoal", REQ + "stepText"};

        for (int i = 0; i < documents.length; i++) {
            // when
            SubmitRejectedException rejected = rejected(documents[i]);

            // then
            assertTrue(rejected.getMessage().contains(paths[i]), rejected.getMessage());
            assertTrue(rejected.getMessage().contains("non-whitespace"), rejected.getMessage());
            assertNothingStored("UC1");
        }
    }

    // -- acceptance criteria ---------------------------------------------------------------------

    @Test
    void twoAcceptanceCriteriaWithTheSameTextAreRejected() {
        // when
        SubmitRejectedException rejected = rejected("[] a arkres:Create ; arkres:object _:fr .\n"
                + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ;"
                + " dcterms:description \"Writes it.\"@en ;"
                + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Works fine.\"@en ] ,"
                + " [ arkreq:position 2 ; arkreq:criterionText \"Works fine.\"@en ] .\n");

        // then
        assertTrue(rejected.getMessage().contains("must not carry the same arkreq:criterionText"),
                rejected.getMessage());
        assertNothingStored("FR-1");
    }

    static Stream<Arguments> collidingCriteria() {
        return Stream.of(
                Arguments.of("en and en", "\"Works fine.\"@en", "\"Works fine.\"@en"),
                Arguments.of("en and de", "\"Works fine.\"@en", "\"Works fine.\"@de"),
                Arguments.of("en and untagged", "\"Works fine.\"@en", "\"Works fine.\""),
                Arguments.of("untagged and untagged", "\"Works fine.\"", "\"Works fine.\""),
                Arguments.of("de and de, the first also in en", "\"Other text.\"@en ; arkreq:criterionText"
                        + " \"Works fine.\"@de", "\"Works fine.\"@de"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("collidingCriteria")
    void twoCriteriaSharingAStringUnderAnyTagsAreRejected(final String tags, final String first,
            final String second) {
        // when
        SubmitRejectedException rejected = rejected("[] a arkres:Create ; arkres:object _:fr .\n"
                + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ;"
                + " dcterms:description \"Writes it.\"@en ;"
                + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText " + first + " ] ,"
                + " [ arkreq:position 2 ; arkreq:criterionText " + second + " ] .\n");

        // then
        assertTrue(rejected.getMessage().contains("must not carry the same arkreq:criterionText"),
                rejected.getMessage());
        assertNothingStored("FR-1");
    }

    @Test
    void twoCriteriaWithDifferentTextsAreAcceptedInSeveralLanguages() {
        assertDoesNotThrow(() -> submit.submit(SubmitRealStoreTest.PROJECT, PREFIXES
                + "[] a arkres:Create ; arkres:object _:fr .\n"
                + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"Write\"@en ;"
                + " dcterms:description \"Writes it.\"@en ;"
                + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Works fine.\"@en ;"
                + " arkreq:criterionText \"Funktioniert.\"@de ] ,"
                + " [ arkreq:position 2 ; arkreq:criterionText \"Reads back.\"@en ;"
                + " arkreq:criterionText \"Liest zurueck.\"@de ] .\n"));
    }

    @Test
    void anAcceptanceCriterionAddedWithATextTheRequirementAlreadyHasIsRejected() {
        // given
        String fr = storedRequirement();

        // when
        SubmitRejectedException rejected = rejected("[] a arkres:AddTo ; arkres:object <" + fr + "> ;"
                + " arkres:field arkreq:acceptanceCriterion ;"
                + " arkres:value [ arkreq:criterionText \"Works fine.\"@en ] .\n");

        // then
        assertTrue(rejected.getMessage().contains("must not carry the same arkreq:criterionText"),
                rejected.getMessage());
    }

    @Test
    void twoRequirementsMayShareACriterionText() {
        // given
        storedRequirement();

        // when / then
        assertDoesNotThrow(() -> submit.submit(SubmitRealStoreTest.PROJECT, PREFIXES + functionalRequirement("")));
    }

    // -- use case goal and main flow --------------------------------------------------------------

    @Test
    void aUseCaseWithoutAGoalIsRejected() {
        // given
        String role = storedRole();

        // when
        SubmitRejectedException rejected = rejected(useCase(role, STEP));

        // then
        assertTrue(rejected.getMessage().contains(REQ + "useCaseGoal"), rejected.getMessage());
        assertNothingStored("UC1");
    }

    @Test
    void aUseCaseWithoutAMainStepIsRejected() {
        // given
        String role = storedRole();

        // when
        SubmitRejectedException rejected = rejected(useCase(role, GOAL));

        // then
        assertTrue(rejected.getMessage().contains(REQ + "mainStep"), rejected.getMessage());
        assertNothingStored("UC1");
    }

    @Test
    void aUseCaseWithAGoalAndAMainStepIsAccepted() {
        // given
        String role = storedRole();

        // when / then
        assertDoesNotThrow(() -> submit.submit(SubmitRealStoreTest.PROJECT,
                PREFIXES + useCase(role, GOAL, STEP)));
    }

    // -- references of a use case --------------------------------------------------------------------

    @Test
    void aSupportingRoleThatIsNoRoleIsRejected() {
        // given
        String role = storedRole();
        String term = SubmitRealStoreTest.iriOf(submit.submit(SubmitRealStoreTest.PROJECT, PREFIXES
                + "[] a arkres:Create ; arkres:object _:t .\n_:t a skos:Concept ; skos:prefLabel \"Term\"@en ;"
                + " skos:definition \"A term.\"@en .\n"), "TERM-1");

        // when
        SubmitRejectedException rejected = rejected(useCase(role, GOAL, STEP, "arkreq:supportingRole <" + term + ">"));

        // then
        assertTrue(rejected.getMessage().contains(REQ + "supportingRole"), rejected.getMessage());
        assertNothingStored("UC1");
    }

    @Test
    void aSupportingRoleThatIsARoleIsAccepted() {
        // given
        String role = storedRole();

        // when / then
        assertDoesNotThrow(() -> submit.submit(SubmitRealStoreTest.PROJECT,
                PREFIXES + useCase(role, GOAL, STEP, "arkreq:supportingRole <" + role + ">")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"includesUseCase", "extendsUseCase"})
    void anIncludedOrExtendedResourceThatIsNoUseCaseIsRejected(final String property) {
        // given
        String role = storedRole();
        String fr = storedRequirement();

        // when
        SubmitRejectedException rejected = rejected(
                useCase(role, GOAL, STEP, "arkreq:" + property + " <" + fr + ">"));

        // then
        assertTrue(rejected.getMessage().contains(REQ + property), rejected.getMessage());
        assertNothingStored("UC1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"includesUseCase", "extendsUseCase"})
    void anIncludedOrExtendedUseCaseIsAccepted(final String property) {
        // given
        String role = storedRole();
        String other = SubmitRealStoreTest.iriOf(submit.submit(SubmitRealStoreTest.PROJECT,
                PREFIXES + useCase(role, GOAL, STEP)), "UC1");

        // when / then
        assertDoesNotThrow(() -> submit.submit(SubmitRealStoreTest.PROJECT,
                PREFIXES + useCase(role, GOAL, STEP, "arkreq:" + property + " <" + other + ">")));
    }

    // -- satisfies is the set the main steps realise -------------------------------------------------

    private static String realisingStep(final String requirement) {
        return "arkreq:mainStep [ arkreq:position 1 ; arkreq:stepText \"Writes the decision.\"@en ;"
                + " arkreq:stepRealises <" + requirement + "> ]";
    }

    @Test
    void aUseCaseWhoseStepRealisesARequirementItDoesNotSatisfyIsRejected() {
        // given
        String role = storedRole();
        String fr = storedRequirement();

        // when
        SubmitRejectedException rejected = rejected(useCase(role, GOAL, realisingStep(fr)));

        // then
        assertTrue(rejected.getMessage().contains("exactly the set of requirements"), rejected.getMessage());
        assertNothingStored("UC1");
    }

    @Test
    void aUseCaseSatisfyingARequirementNoStepRealisesIsRejected() {
        // given
        String role = storedRole();
        String fr = storedRequirement();

        // when
        SubmitRejectedException rejected = rejected(useCase(role, GOAL, STEP, "oslc_rm:satisfies <" + fr + ">"));

        // then
        assertTrue(rejected.getMessage().contains("exactly the set of requirements"), rejected.getMessage());
        assertNothingStored("UC1");
    }

    @Test
    void aUseCaseSatisfyingExactlyWhatItsStepsRealiseIsAccepted() {
        // given
        String role = storedRole();
        String fr = storedRequirement();

        // when / then
        assertDoesNotThrow(() -> submit.submit(SubmitRealStoreTest.PROJECT,
                PREFIXES + useCase(role, GOAL, realisingStep(fr), "oslc_rm:satisfies <" + fr + ">")));
    }

    @Test
    void aStepAddedWithoutTheMatchingSatisfiesIsRejectedAndAcceptedWithIt() {
        // given
        String role = storedRole();
        String fr = storedRequirement();
        String uc = SubmitRealStoreTest.iriOf(submit.submit(SubmitRealStoreTest.PROJECT,
                PREFIXES + useCase(role, GOAL, STEP)), "UC1");
        String addStep = "[] a arkres:AddTo ; arkres:object <" + uc + "> ; arkres:field arkreq:mainStep ;"
                + " arkres:value [ arkreq:stepText \"Reads it back.\"@en ; arkreq:stepRealises <" + fr + "> ] .\n";

        // when - only the step
        SubmitRejectedException rejected = rejected(addStep);

        // then
        assertTrue(rejected.getMessage().contains("exactly the set of requirements"), rejected.getMessage());
        assertEquals(1, reader.history(SubmitRealStoreTest.PROJECT, uc).size());

        // when - the step and the edge in one document
        assertDoesNotThrow(() -> submit.submit(SubmitRealStoreTest.PROJECT, PREFIXES + addStep
                + "[] a arkres:AddTo ; arkres:object <" + uc + "> ; arkres:field oslc_rm:satisfies ;"
                + " arkres:value <" + fr + "> .\n"));
    }
}
