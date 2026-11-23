// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

import static de.hauschel.arknet.resource.adapter.kogniordf.SubmitRealStoreTest.PREFIXES;
import static de.hauschel.arknet.resource.adapter.kogniordf.SubmitRealStoreTest.PROJECT;
import static de.hauschel.arknet.resource.adapter.kogniordf.SubmitRealStoreTest.iriOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
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
import io.kogn.rdf.rdf4j.dataset.GraphImportRdf4j;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;
import io.kogn.rdf.rdf4j.shacl.ShaclValidationRdf4j;
import io.kogn.rdf.shacl.ValidationOptions;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.ShaclWriteGate;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.persistence.WriteConstraintViolationException;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The end-state rules of the Domain Modelling context (glossary terms, bounded contexts, context
 * relationships) as its shapes carry them: one violating document per rule, rejected through
 * {@code submit} against a real store, the rejection naming the rule, and the store left as it was.
 */
class DomainModellingRulesRealStoreTest {

    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String DDD = "https://w3id.org/arknet/ddd#";
    private static final String CORE = "https://w3id.org/arknet/core#";

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

    static Stream<Arguments> termsBreakingAFieldRule() {
        return Stream.of(
                Arguments.of("skos:prefLabel \"Resource\"@en", SKOS + "definition"),
                Arguments.of("skos:prefLabel \"Resource\"@en ; skos:definition \"   \"@en", SKOS + "definition"),
                Arguments.of("skos:prefLabel \"Resource\"@en ; skos:definition \"\"@en", SKOS + "definition"),
                Arguments.of("skos:prefLabel \"  \"@en ; skos:definition \"Kept under an identity.\"@en",
                        SKOS + "prefLabel"));
    }

    @ParameterizedTest
    @MethodSource("termsBreakingAFieldRule")
    void aTermWithoutADefinitionOrWithABlankTextIsRejected(final String fields, final String path) {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Create ; arkres:object _:t .\n_:t a skos:Concept ; " + fields + " .\n"));

        // then
        assertRejectedOn(rejected, path);
        assertEquals(List.of(), reader.findByIdentifier(PROJECT, "TERM-1"));
    }

    /**
     * Every text {@link String#isBlank()} calls blank is rejected (the read path drops such a
     * resource), and the no-break space beyond it: em space, ideographic space, no-break space, a
     * control character, tab plus line break - written as Turtle escapes.
     */
    @ParameterizedTest
    @ValueSource(strings = {"\\u2003\\u2003", "\\u3000\\u3000", "\\u00A0\\u00A0", "\\u001F\\u001F",
            "\\t\\n"})
    void aTermDefinitionAndABoundedContextNameOfOnlyBlankCharactersAreRejected(final String blank) {
        // when
        SubmitRejectedException term = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Create ; arkres:object _:t .\n_:t a skos:Concept ;"
                        + " skos:prefLabel \"Resource\"@en ; skos:definition \"" + blank + "\"@en .\n"));
        SubmitRejectedException bc = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Create ; arkres:object _:bc .\n_:bc a arkddd:BoundedContext ;"
                        + " arknet:name \"" + blank + "\"@en ; arkddd:domainVision \"Keeps the model consistent.\"@en .\n"));

        // then
        assertRejectedOn(term, SKOS + "definition");
        assertRejectedOn(bc, CORE + "name");
        assertEquals(List.of(), reader.findByIdentifier(PROJECT, "TERM-1"));
        assertEquals(List.of(), reader.findByIdentifier(PROJECT, "BC-1"));
    }

    @Test
    void aTermLabelledWithAnotherWordInAnotherLanguageIsRejected() {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Create ; arkres:object _:t .\n_:t a skos:Concept ;"
                        + " skos:prefLabel \"Resource\"@en , \"Ressource\"@de ; skos:definition \"Kept.\"@en .\n"));

        // then
        assertMessage(rejected, "one word under every language tag");
        assertEquals(List.of(), reader.findByIdentifier(PROJECT, "TERM-1"));
    }

    @Test
    void aTermLabelledWithTheSameWordInSeveralLanguagesIsAccepted() {
        assertDoesNotThrow(() -> submit.submit(PROJECT, PREFIXES + "[] a arkres:Create ; arkres:object _:t .\n"
                + "_:t a skos:Concept ; skos:prefLabel \"Resource\"@en , \"Resource\"@de ;"
                + " skos:definition \"Kept.\"@en , \"Gehalten.\"@de .\n"));
    }

    static Stream<Arguments> selfReferences() {
        return Stream.of(Arguments.of("skos:broader", "its own broader term"),
                Arguments.of("skos:related", "related to itself"));
    }

    @ParameterizedTest
    @MethodSource("selfReferences")
    void aTermReferencingItselfIsRejected(final String field, final String rule) {
        // given
        String term = storedTerm("Resource");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Update ; arkres:object <" + term + "> ; arkres:set [ " + field + " <" + term
                        + "> ] .\n"));

        // then
        assertMessage(rejected, rule);
        assertEquals(1, reader.history(PROJECT, term).size());
    }

    @Test
    void aBroaderChainIsAcceptedButClosingItIntoACycleIsRejected() {
        // given - Resource -> Thing -> Entity
        String resource = storedTerm("Resource");
        String thing = iriOf(submit.submit(PROJECT, PREFIXES + "[] a arkres:Create ; arkres:object _:t .\n"
                + "_:t a skos:Concept ; skos:prefLabel \"Thing\"@en ; skos:definition \"Anything.\"@en ;"
                + " skos:broader <" + resource + "> .\n"), "TERM-2");
        String entity = iriOf(submit.submit(PROJECT, PREFIXES + "[] a arkres:Create ; arkres:object _:t .\n"
                + "_:t a skos:Concept ; skos:prefLabel \"Entity\"@en ; skos:definition \"Has an identity.\"@en ;"
                + " skos:broader <" + thing + "> .\n"), "TERM-3");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Update ; arkres:object <" + resource + "> ; arkres:set [ skos:broader <"
                        + entity + "> ] .\n"));

        // then
        assertMessage(rejected, "cycle");
        assertEquals(1, reader.history(PROJECT, resource).size());
    }

    static Stream<Arguments> boundedContextsBreakingAFieldRule() {
        return Stream.of(
                Arguments.of("arknet:name \"   \"@en ; arkddd:domainVision \"Keeps the model consistent.\"@en",
                        CORE + "name"),
                Arguments.of("arknet:name \"Modelling\"@en ; arkddd:domainVision \"            \"@en",
                        DDD + "domainVision"),
                Arguments.of("arknet:name \"Modelling\"@en ; arkddd:domainVision \"Keeps the model consistent.\"@en ;"
                        + " arkddd:ownedBy \"  \"", DDD + "ownedBy"),
                Arguments.of("arknet:name \"Modelling\"@en ; arkddd:domainVision \"Keeps the model consistent.\"@en ;"
                        + " arkddd:ownedBy \"Core team\"@en", DDD + "ownedBy"),
                Arguments.of("arknet:name \"Modelling\"@en ; arkddd:domainVision \"Keeps the model consistent.\"@en ;"
                        + " arkddd:ownedBy <https://example.org/team>", DDD + "ownedBy"));
    }

    @ParameterizedTest
    @MethodSource("boundedContextsBreakingAFieldRule")
    void aBoundedContextWithABlankOrMistypedTextIsRejected(final String fields, final String path) {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Create ; arkres:object _:bc .\n_:bc a arkddd:BoundedContext ; " + fields
                        + " .\n"));

        // then
        assertRejectedOn(rejected, path);
        assertEquals(List.of(), reader.findByIdentifier(PROJECT, "BC-1"));
    }

    @Test
    void aBoundedContextOwnedByATeamIsAccepted() {
        assertDoesNotThrow(() -> submit.submit(PROJECT, PREFIXES + "[] a arkres:Create ; arkres:object _:bc .\n"
                + "_:bc a arkddd:BoundedContext ; arknet:name \"Modelling\"@en ;"
                + " arkddd:domainVision \"Keeps the model consistent.\"@en ; arkddd:ownedBy \"Core team\" .\n"));
    }

    @Test
    void aContextRelationshipFromAContextToItselfIsRejected() {
        // given
        String bc = iriOf(submit.submit(PROJECT, PREFIXES + "[] a arkres:Create ; arkres:object _:bc .\n"
                + "_:bc a arkddd:BoundedContext ; arknet:name \"Modelling\"@en ;"
                + " arkddd:domainVision \"Keeps the model consistent.\"@en .\n"), "BC-1");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + "[] a arkres:Create ; arkres:object _:rel .\n_:rel a arkddd:ContextRelationship ;"
                        + " arkddd:upstream <" + bc + "> ; arkddd:downstream <" + bc + "> ;"
                        + " arkddd:relationshipType arkddd:Partnership .\n"));

        // then
        assertRejectedOn(rejected, DDD + "upstream");
        assertEquals(1, reader.history(PROJECT, bc).size());
    }

    /**
     * A subdomain is no resource type, so no document writes one; the rule is shown against the
     * shapes {@code submit} validates with.
     */
    @Test
    void aSubdomainOfAnUnknownTypeIsRejectedByTheShapes() {
        // given
        ShaclWriteGate gate = new ShaclWriteGate(new ShaclValidationRdf4j(), KognioRdfResourceRepositoryFactory.shapes(),
                new SimpleRdf().createGraph(), new ValidationOptions(true), DisplayLocale.DEFAULT);
        Graph known = turtle("<https://example.org/s> a <" + DDD + "Subdomain> ; <" + DDD + "subdomainType> <" + DDD
                + "CoreDomain> .");
        Graph unknown = turtle("<https://example.org/s> a <" + DDD + "Subdomain> ; <" + DDD + "subdomainType> <" + DDD
                + "CentralDomain> .");

        // when / then
        assertDoesNotThrow(() -> gate.enforce(known));
        WriteConstraintViolationException rejected = assertThrows(WriteConstraintViolationException.class,
                () -> gate.enforce(unknown));
        assertTrue(rejected.getMessage().contains(DDD + "subdomainType"), rejected.getMessage());
    }

    private String storedTerm(final String label) {
        return iriOf(submit.submit(PROJECT, PREFIXES + "[] a arkres:Create ; arkres:object _:t .\n"
                + "_:t a skos:Concept ; skos:prefLabel \"" + label + "\"@en ; skos:definition \"Kept.\"@en .\n"),
                "TERM-1");
    }

    private static Graph turtle(final String document) {
        return new GraphImportRdf4j().readTurtle(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
    }

    private static void assertRejectedOn(final SubmitRejectedException rejected, final String path) {
        String message = rejected.getMessage();
        assertTrue(message.matches("(?s).*path=<?" + java.util.regex.Pattern.quote(path) + ".*"), message);
    }

    private static void assertMessage(final SubmitRejectedException rejected, final String rule) {
        assertTrue(rejected.getMessage().contains(rule), rejected.getMessage());
    }
}
