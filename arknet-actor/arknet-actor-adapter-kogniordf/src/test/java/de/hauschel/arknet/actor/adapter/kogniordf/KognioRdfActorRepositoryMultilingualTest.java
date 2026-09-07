// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.Literal;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.vocab.VocabDct;
import io.kogn.rdf.terms.vocab.VocabRdf;

import de.hauschel.arknet.actor.application.port.out.ActorRepository;
import de.hauschel.arknet.actor.application.port.out.RevisionToken;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorConcurrentlyModifiedException;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.persistence.WriteFunnel;

/**
 * Integration tests for the multilingual {@code arknet:name}/{@code arknet:description} behaviour
 * of {@link KognioRdfActorRepository} (kogn-io/arknet#520): language-scoped writes on
 * {@link ActorRepository#create}/{@link ActorRepository#compareAndUpdate},
 * {@link DisplayLocale}-selected reads, and the compare-and-set guard itself.
 *
 * <p>Same regression class {@code KognioRdfRoleRepositoryMultilingualTest}/
 * {@code KognioRdfConstraintRepositoryMultilingualTest} are for their own resources: a full
 * replace-by-identity write must not collapse a multilingual field down to one language variant
 * just because one call did not intend to touch it. What is specific here is the <em>starting
 * point</em> the pre-#520 corpus leaves behind - actors written as plain, untagged literals, which
 * the issue #258 sweep is what makes repairable.</p>
 */
class KognioRdfActorRepositoryMultilingualTest {

    private static final String ACTOR_GRAPH = "https://w3id.org/arknet/model/actors";
    private static final String NAME_PROPERTY = "https://w3id.org/arknet/core#name";
    private static final String DESCRIPTION_PROPERTY = "https://w3id.org/arknet/core#description";
    private static final String HUMAN_ACTOR_TYPE = "https://w3id.org/arknet/process#HumanActor";
    private static final ProjectId PROJECT_A = new ProjectId("a");

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private ActorRepository repository;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        WriteFunnel funnel = KognioRdfActorRepositoryFactory.buildFunnel(datasetLifecycle, DisplayLocale.DEFAULT);
        repository = KognioRdfActorRepositoryFactory.over(datasetLifecycle, DisplayLocale.DEFAULT, funnel);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static ActorId freshId() {
        return new ActorId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static Actor actor(ActorId id, ActorCode code, String name, String description) {
        return new Actor(id, code, ActorType.HUMAN, name, description);
    }

    @Test
    void createWritesATaggedNameAndDescriptionSelectableViaDisplayLocale() {
        ActorCode code = new ActorCode("ACTOR-1");
        repository.create(PROJECT_A, actor(freshId(), code, "Sachbearbeiter", "Bearbeitet Antraege."), "de");

        Actor asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Sachbearbeiter", asGerman.name());
        assertEquals("Bearbeitet Antraege.", asGerman.description());
    }

    /**
     * The two-call shape kogn-io/arknet#520 exists for: {@code actor_add} in one language,
     * {@code actor_update} in a second, and both survive - an actor that would have been
     * permanently single-language before this change.
     */
    @Test
    void compareAndUpdateUnderASecondLanguageKeepsBothVariants() {
        ActorCode code = new ActorCode("ACTOR-1");
        ActorId id = freshId();
        repository.create(PROJECT_A, actor(id, code, "Sachbearbeiter", "Bearbeitet Antraege."), "de");
        RevisionToken head = currentHead(code);

        repository.compareAndUpdate(PROJECT_A, head,
                actor(id, code, "Case Worker", "Processes applications."), "en", "en", null);

        Actor asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Sachbearbeiter", asGerman.name());
        assertEquals("Bearbeitet Antraege.", asGerman.description());
        Actor asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        assertEquals("Case Worker", asEnglish.name());
        assertEquals("Processes applications.", asEnglish.description());
    }

    /** Correcting only the name must leave the description's other language variants intact. */
    @Test
    void compareAndUpdateOfOneFieldPreservesTheOtherFieldsVariants() {
        ActorCode code = new ActorCode("ACTOR-1");
        ActorId id = freshId();
        repository.create(PROJECT_A, actor(id, code, "Sachbearbeiter", "Bearbeitet Antraege."), "de");
        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                actor(id, code, "Case Worker", "Processes applications."), "en", "en", null);

        // Correct the English name only; the German name/description pass through under "de".
        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                actor(id, code, "Senior Case Worker", "Processes applications."), "en", "en", null);

        assertEquals("Sachbearbeiter", repository.findByCode(PROJECT_A, code, "de").orElseThrow().name());
        assertEquals("Senior Case Worker", repository.findByCode(PROJECT_A, code, "en").orElseThrow().name());
        assertEquals(2, literalsOf(id, NAME_PROPERTY).size());
        assertEquals(2, literalsOf(id, DESCRIPTION_PROPERTY).size());
    }

    /** Re-writing the same tag replaces that one variant rather than accumulating duplicates. */
    @Test
    void compareAndUpdateUnderTheSameTagDoesNotDuplicateTheLiteral() {
        ActorCode code = new ActorCode("ACTOR-1");
        ActorId id = freshId();
        repository.create(PROJECT_A, actor(id, code, "Case Worker", "Processess applications."), "en");

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                actor(id, code, "Case Worker", "Processes applications."), "en", "en", null);

        List<Literal> descriptions = literalsOf(id, DESCRIPTION_PROPERTY);
        assertEquals(1, descriptions.size());
        assertEquals("Processes applications.", descriptions.get(0).getLexicalForm());
    }

    /**
     * Issue #258's lazy sweep, which is what makes the pre-#520 corpus repairable: an actor
     * written before this change carries plain untagged literals. Writing under a tag that equals
     * the project's default replaces the untagged literal instead of preserving it as a spurious
     * "other" variant.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedLiteralWhenWritingUnderTheProjectDefault() {
        ActorCode code = new ActorCode("ACTOR-1");
        ActorId id = freshId();
        repository.create(PROJECT_A, actor(id, code, "Case Worker", "Processess applications."), null);
        assertTrue(literalsOf(id, DESCRIPTION_PROPERTY).get(0).getLanguageTag().isEmpty());

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                actor(id, code, "Case Worker", "Processes applications."), "en", "en", "en");

        List<Literal> descriptions = literalsOf(id, DESCRIPTION_PROPERTY);
        assertEquals(1, descriptions.size());
        assertEquals("en", descriptions.get(0).getLanguageTag().orElseThrow());
    }

    /** ... whereas a write under a non-default tag leaves the legacy untagged literal alone. */
    @Test
    void compareAndUpdateUnderANonDefaultTagPreservesAnUntaggedLiteral() {
        ActorCode code = new ActorCode("ACTOR-1");
        ActorId id = freshId();
        repository.create(PROJECT_A, actor(id, code, "Case Worker", "Processes applications."), null);

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                actor(id, code, "Sachbearbeiter", "Bearbeitet Antraege."), "de", "de", "en");

        assertEquals(2, literalsOf(id, DESCRIPTION_PROPERTY).size());
    }

    @Test
    void compareAndUpdateRejectsAStaleHead() {
        ActorCode code = new ActorCode("ACTOR-1");
        ActorId id = freshId();
        repository.create(PROJECT_A, actor(id, code, "Case Worker", "Processes applications."), "en");
        RevisionToken stale = currentHead(code);
        repository.compareAndUpdate(PROJECT_A, stale,
                actor(id, code, "Senior Case Worker", "Processes applications."), "en", "en", null);

        assertThrows(ActorConcurrentlyModifiedException.class, () -> repository.compareAndUpdate(
                PROJECT_A, stale, actor(id, code, "Case Worker", "Processes applications."), "en", "en", null));
    }

    /** {@code findCurrentByCode} hands back the tag each selected literal actually carries. */
    @Test
    void findCurrentByCodeReportsTheTagEachFieldWasSelectedUnder() {
        ActorCode code = new ActorCode("ACTOR-1");
        repository.create(PROJECT_A, actor(freshId(), code, "Case Worker", "Processes applications."), "en");

        ActorRepository.CurrentActor current = repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow();

        assertEquals("en", current.nameLanguage());
        assertEquals("en", current.descriptionLanguage());
    }

    /** A store-first actor written with untagged literals reads back with a {@code null} tag. */
    @Test
    void findCurrentByCodeReportsANullTagForAnUntaggedLegacyLiteral() {
        ActorCode code = new ActorCode("ACTOR-1");
        repository.create(PROJECT_A, actor(freshId(), code, "Case Worker", "Processes applications."), null);

        ActorRepository.CurrentActor current = repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow();

        assertEquals(null, current.nameLanguage());
        assertEquals(null, current.descriptionLanguage());
    }

    /**
     * {@code findAll} selects per subject from the bulk literal reads - a project mixing languages
     * must not multiply an actor into one entry per name/description combination.
     */
    @Test
    void findAllSelectsOneVariantPerActor() {
        ActorCode code = new ActorCode("ACTOR-1");
        ActorId id = freshId();
        repository.create(PROJECT_A, actor(id, code, "Sachbearbeiter", "Bearbeitet Antraege."), "de");
        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                actor(id, code, "Case Worker", "Processes applications."), "en", "en", null);

        List<Actor> all = repository.findAll(PROJECT_A, "de");

        assertEquals(1, all.size());
        assertEquals("Sachbearbeiter", all.get(0).name());
        assertEquals("Bearbeitet Antraege.", all.get(0).description());
    }

    /** {@code findAllDisplayFallback} marks a field that fell back and leaves an on-target one alone. */
    @Test
    void findAllDisplayFallbackMarksOnlyTheFallenBackField() {
        ActorCode code = new ActorCode("ACTOR-1");
        repository.create(PROJECT_A, actor(freshId(), code, "Case Worker", "Processes applications."), "en");

        var fallbacks = repository.findAllDisplayFallback(PROJECT_A, "de");

        assertEquals("en", fallbacks.get(code).nameTag());
        assertEquals("en", fallbacks.get(code).descriptionTag());
    }

    @Test
    void findAllDisplayFallbackIsEmptyWhenTheRequestedLanguageIsShown() {
        ActorCode code = new ActorCode("ACTOR-1");
        repository.create(PROJECT_A, actor(freshId(), code, "Sachbearbeiter", "Bearbeitet Antraege."), "de");

        var fallbacks = repository.findAllDisplayFallback(PROJECT_A, "de");

        assertTrue(fallbacks.isEmpty(), fallbacks.toString());
    }

    /**
     * The SHACL write gate now accepts a language-tagged name/description - the very thing
     * {@code sh:datatype xsd:string} rejected before kogn-io/arknet#520 - while {@code sh:uniqueLang}
     * still rejects two literals sharing the same tag.
     */
    @Test
    void gateAcceptsALanguageTaggedNameAndRejectsADuplicateTag() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph accepted = rdf.createGraph();
        accepted.add(subject, VocabRdf.TYPE, rdf.createIRI(HUMAN_ACTOR_TYPE));
        accepted.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("ACTOR-1"));
        accepted.add(subject, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral("Sachbearbeiter", "de"));
        accepted.add(subject, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral("Case Worker", "en"));

        KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(accepted);

        Graph rejected = rdf.createGraph();
        rejected.add(subject, VocabRdf.TYPE, rdf.createIRI(HUMAN_ACTOR_TYPE));
        rejected.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("ACTOR-1"));
        rejected.add(subject, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral("Sachbearbeiter", "de"));
        rejected.add(subject, rdf.createIRI(NAME_PROPERTY), rdf.createLiteral("Sachbearbeiter II", "de"));

        assertThrows(de.hauschel.arknet.persistence.WriteConstraintViolationException.class,
                () -> KognioRdfActorRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(rejected));
    }

    private RevisionToken currentHead(ActorCode code) {
        return repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow().head();
    }

    /** Every literal actually stored on {@code subject}'s {@code predicateIri}, tags included. */
    private List<Literal> literalsOf(ActorId id, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + ACTOR_GRAPH + "> { <"
                + id.value().value() + "> <" + predicateIri + "> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            List<Literal> literals = new ArrayList<>();
            handle.sparqlQuery().select(query)
                    .forEach(row -> literals.add((Literal) row.getValue("o").orElseThrow()));
            return literals;
        }
    }
}
