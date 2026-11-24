// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.kogniordf;

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
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.persistence.StoreReader;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;

/**
 * The shape rules of the actor bounded context, driven through {@code submit} against a real
 * store: every violating document is rejected and leaves the store as it was.
 */
class ActorRulesRealStoreTest {

    private static final ProjectId PROJECT = new ProjectId("test-project");

    private static final String PREFIXES = SubmitRealStoreTest.PREFIXES;

    private static final String CREATE = "[] a arkres:Create ; arkres:object _:x .\n_:x ";

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

    @Test
    void anActorTypedOnlyAsTheAbstractSuperclassIsNoResourceType() {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + CREATE + "a arkproc:Actor ; arknet:name \"Somebody\"@en .\n"));

        // then
        assertTrue(rejected.getMessage().contains("process#Actor"), rejected.getMessage());
        assertTrue(reader.findByIdentifier(PROJECT, "ACTOR-1").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"arkproc:HumanActor", "arkproc:SystemActor", "arkproc:LegalActor", "arkproc:GroupActor"})
    void everyConcreteActorSubtypeIsAccepted(final String type) {
        // when
        SubmitResult result = submit.submit(PROJECT, PREFIXES + CREATE + "a " + type + " ; arknet:name \"Somebody\"@en .\n");

        // then
        assertEquals("ACTOR-1", result.changes().get(0).code());
    }

    /** Every text made only of whitespace, separators or control characters, as a Turtle string escape. */
    static Stream<Arguments> blankTexts() {
        final Stream.Builder<Arguments> out = Stream.builder();
        for (final String type : new String[] {"arkproc:HumanActor", "arkproc:Role"}) {
            for (final String blank : new String[] {"   ", "\\u2003\\u2003", "\\u3000\\u3000", "\\u00A0\\u00A0",
                "\\u001F\\u001F", "\\t\\t", "\\n\\n"}) {
                out.add(Arguments.of(type, blank));
            }
        }
        return out.build();
    }

    @ParameterizedTest
    @MethodSource("blankTexts")
    void aBlankNameIsRejected(final String type, final String blank) {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + CREATE + "a " + type + " ; arknet:name \"" + blank + "\"@en .\n"));

        // then
        assertTrue(rejected.getMessage().contains("core#name"), rejected.getMessage());
    }

    @ParameterizedTest
    @MethodSource("blankTexts")
    void aBlankDescriptionIsRejected(final String type, final String blank) {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + CREATE + "a " + type + " ; arknet:name \"Somebody\"@en ;"
                        + " arknet:description \"" + blank + "\"@en .\n"));

        // then
        assertTrue(rejected.getMessage().contains("core#description"), rejected.getMessage());
    }

    @ParameterizedTest
    @ValueSource(strings = {"arkproc:HumanActor", "arkproc:Role"})
    void anEmptyDescriptionIsRejected(final String type) {
        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + CREATE + "a " + type + " ; arknet:name \"Somebody\"@en ; arknet:description \"\"@en .\n"));

        // then
        assertTrue(rejected.getMessage().contains("core#description"), rejected.getMessage());
    }

    @Test
    void aRoleFilledByAnActorIsAccepted() {
        // given
        String actor = SubmitRealStoreTest.iriOf(submit.submit(PROJECT,
                PREFIXES + CREATE + "a arkproc:HumanActor ; arknet:name \"Architect\"@en .\n"), "ACTOR-1");

        // when
        SubmitResult result = submit.submit(PROJECT, PREFIXES + CREATE
                + "a arkproc:Role ; arknet:name \"Modeller\"@en ; arkproc:filledBy <" + actor + "> .\n");

        // then
        assertEquals("ROLE-1", result.changes().get(0).code());
    }

    @Test
    void aRoleFilledByAResourceThatIsNoActorIsRejected() {
        // given
        String term = SubmitRealStoreTest.iriOf(submit.submit(PROJECT, PREFIXES + CREATE
                + "a skos:Concept ; skos:prefLabel \"Term\"@en ; skos:definition \"A term.\"@en .\n"), "TERM-1");

        // when
        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, () -> submit.submit(PROJECT,
                PREFIXES + CREATE + "a arkproc:Role ; arknet:name \"Modeller\"@en ; arkproc:filledBy <" + term + "> .\n"));

        // then
        assertTrue(rejected.getMessage().contains("filledBy"), rejected.getMessage());
        assertTrue(reader.findByIdentifier(PROJECT, "ROLE-1").isEmpty());
    }
}
