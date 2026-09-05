// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.kogniordf;

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

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.WriteFunnel;
import de.hauschel.arknet.req.application.port.out.ConstraintRepository;
import de.hauschel.arknet.req.application.port.out.RevisionToken;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintConcurrentlyModifiedException;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintType;

/**
 * Integration tests for the multilingual {@code dcterms:title}/{@code arkreq:constraintStatement}
 * behaviour of {@link KognioRdfConstraintRepository} (issue #313): language-scoped writes on
 * {@link ConstraintRepository#create}/{@link ConstraintRepository#compareAndUpdate},
 * {@link DisplayLocale}-selected reads, and the compare-and-set guard itself.
 *
 * <p>Same regression class {@code KognioRdfRequirementRepositoryMultilingualTest} is for its own
 * resource: a full replace-by-identity write must not collapse a multilingual field down to one
 * language variant just because one call did not intend to touch it. What is specific here is the
 * <em>starting point</em> the pre-#313 corpus leaves behind - constraints written as plain,
 * untagged literals, which the issue #258 sweep is what makes repairable.</p>
 */
class KognioRdfConstraintRepositoryMultilingualTest {

    private static final String CONSTRAINTS_GRAPH = "https://w3id.org/arknet/model/constraints";
    private static final String STATEMENT_PROPERTY = "https://w3id.org/arknet/requirements#constraintStatement";
    private static final String TITLE_PROPERTY = VocabDct.NAMESPACE + "title";
    private static final ProjectId PROJECT_A = new ProjectId("a");

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

    private static Constraint constraint(ConstraintId id, ConstraintCode code, String title, String statement) {
        return new Constraint(id, code, title, statement, ConstraintType.TECHNICAL);
    }

    @Test
    void createWritesATaggedTitleAndStatementSelectableViaDisplayLocale() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        repository.create(PROJECT_A, constraint(freshId(), code, "Nur JVM", "Muss auf der JVM laufen."), "de");

        Constraint asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Nur JVM", asGerman.title());
        assertEquals("Muss auf der JVM laufen.", asGerman.statement());
    }

    /**
     * The two-call shape issue #313 exists for: {@code constraint_add} in one language,
     * {@code constraint_update} in a second, and both survive - a constraint that would have been
     * permanently single-language before this change.
     */
    @Test
    void compareAndUpdateUnderASecondLanguageKeepsBothVariants() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        ConstraintId id = freshId();
        repository.create(PROJECT_A, constraint(id, code, "Nur JVM", "Muss auf der JVM laufen."), "de");
        RevisionToken head = currentHead(code);

        repository.compareAndUpdate(PROJECT_A, head,
                constraint(id, code, "JVM only", "Must run on the JVM."), "en", "en", null);

        Constraint asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Nur JVM", asGerman.title());
        assertEquals("Muss auf der JVM laufen.", asGerman.statement());
        Constraint asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        assertEquals("JVM only", asEnglish.title());
        assertEquals("Must run on the JVM.", asEnglish.statement());
    }

    /** Correcting only the title must leave the statement's other language variants intact. */
    @Test
    void compareAndUpdateOfOneFieldPreservesTheOtherFieldsVariants() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        ConstraintId id = freshId();
        repository.create(PROJECT_A, constraint(id, code, "Nur JVM", "Muss auf der JVM laufen."), "de");
        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                constraint(id, code, "JVM only", "Must run on the JVM."), "en", "en", null);

        // Correct the English title only; the German title/statement pass through under "de".
        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                constraint(id, code, "JVM required", "Must run on the JVM."), "en", "en", null);

        assertEquals("Nur JVM", repository.findByCode(PROJECT_A, code, "de").orElseThrow().title());
        assertEquals("JVM required", repository.findByCode(PROJECT_A, code, "en").orElseThrow().title());
        assertEquals(2, literalsOf(id, TITLE_PROPERTY).size());
        assertEquals(2, literalsOf(id, STATEMENT_PROPERTY).size());
    }

    /** Re-writing the same tag replaces that one variant rather than accumulating duplicates. */
    @Test
    void compareAndUpdateUnderTheSameTagDoesNotDuplicateTheLiteral() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        ConstraintId id = freshId();
        repository.create(PROJECT_A, constraint(id, code, "JVM only", "Must run on the JMV."), "en");

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                constraint(id, code, "JVM only", "Must run on the JVM."), "en", "en", null);

        List<Literal> statements = literalsOf(id, STATEMENT_PROPERTY);
        assertEquals(1, statements.size());
        assertEquals("Must run on the JVM.", statements.get(0).getLexicalForm());
    }

    /**
     * Issue #258's lazy sweep, which is what makes the pre-#313 corpus repairable: a constraint
     * written before this change carries plain untagged literals. Writing under a tag that equals
     * the project's default replaces the untagged literal instead of preserving it as a spurious
     * "other" variant.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedLiteralWhenWritingUnderTheProjectDefault() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        ConstraintId id = freshId();
        repository.create(PROJECT_A, constraint(id, code, "JVM only", "Must run on the JMV."), null);
        assertTrue(literalsOf(id, STATEMENT_PROPERTY).get(0).getLanguageTag().isEmpty());

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                constraint(id, code, "JVM only", "Must run on the JVM."), "en", "en", "en");

        List<Literal> statements = literalsOf(id, STATEMENT_PROPERTY);
        assertEquals(1, statements.size());
        assertEquals("en", statements.get(0).getLanguageTag().orElseThrow());
    }

    /** ... whereas a write under a non-default tag leaves the legacy untagged literal alone. */
    @Test
    void compareAndUpdateUnderANonDefaultTagPreservesAnUntaggedLiteral() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        ConstraintId id = freshId();
        repository.create(PROJECT_A, constraint(id, code, "JVM only", "Must run on the JVM."), null);

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                constraint(id, code, "Nur JVM", "Muss auf der JVM laufen."), "de", "de", "en");

        assertEquals(2, literalsOf(id, STATEMENT_PROPERTY).size());
    }

    @Test
    void compareAndUpdateRejectsAStaleHead() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        ConstraintId id = freshId();
        repository.create(PROJECT_A, constraint(id, code, "JVM only", "Must run on the JVM."), "en");
        RevisionToken stale = currentHead(code);
        repository.compareAndUpdate(PROJECT_A, stale,
                constraint(id, code, "JVM required", "Must run on the JVM."), "en", "en", null);

        assertThrows(ConstraintConcurrentlyModifiedException.class, () -> repository.compareAndUpdate(
                PROJECT_A, stale, constraint(id, code, "JVM only", "Must run on the JVM."), "en", "en", null));
    }

    /** {@code findCurrentByCode} hands back the tag each selected literal actually carries. */
    @Test
    void findCurrentByCodeReportsTheTagEachFieldWasSelectedUnder() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        repository.create(PROJECT_A, constraint(freshId(), code, "JVM only", "Must run on the JVM."), "en");

        ConstraintRepository.CurrentConstraint current =
                repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow();

        assertEquals("en", current.titleLanguage());
        assertEquals("en", current.statementLanguage());
    }

    /** A store-first constraint written with untagged literals reads back with a {@code null} tag. */
    @Test
    void findCurrentByCodeReportsANullTagForAnUntaggedLegacyLiteral() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        repository.create(PROJECT_A, constraint(freshId(), code, "JVM only", "Must run on the JVM."), null);

        ConstraintRepository.CurrentConstraint current =
                repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow();

        assertEquals(null, current.titleLanguage());
        assertEquals(null, current.statementLanguage());
    }

    /**
     * {@code findAll} selects per subject from the bulk literal reads - a project mixing languages
     * must not multiply a constraint into one entry per title/statement combination.
     */
    @Test
    void findAllSelectsOneVariantPerConstraint() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        ConstraintId id = freshId();
        repository.create(PROJECT_A, constraint(id, code, "Nur JVM", "Muss auf der JVM laufen."), "de");
        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                constraint(id, code, "JVM only", "Must run on the JVM."), "en", "en", null);

        List<Constraint> all = repository.findAll(PROJECT_A, "de");

        assertEquals(1, all.size());
        assertEquals("Nur JVM", all.get(0).title());
        assertEquals("Muss auf der JVM laufen.", all.get(0).statement());
    }

    /**
     * The SHACL write gate now accepts a language-tagged statement - the very thing
     * {@code sh:datatype xsd:string} rejected before issue #313.
     */
    @Test
    void gateAcceptsALanguageTaggedConstraintStatement() {
        RDF rdf = new SimpleRdf();
        IRI subject = rdf.createIRI("https://w3id.org/arknet/id/" + UUID.randomUUID());
        Graph candidate = rdf.createGraph();
        candidate.add(subject, VocabRdf.TYPE,
                rdf.createIRI("https://w3id.org/arknet/requirements#TechnicalConstraint"));
        candidate.add(subject, VocabDct.IDENTIFIER, rdf.createLiteral("TCON-1"));
        candidate.add(subject, rdf.createIRI(TITLE_PROPERTY), rdf.createLiteral("Nur JVM", "de"));
        candidate.add(subject, rdf.createIRI(TITLE_PROPERTY), rdf.createLiteral("JVM only", "en"));
        candidate.add(subject, rdf.createIRI(STATEMENT_PROPERTY),
                rdf.createLiteral("Muss auf der JVM laufen.", "de"));
        candidate.add(subject, rdf.createIRI(STATEMENT_PROPERTY),
                rdf.createLiteral("Must run on the JVM.", "en"));

        KognioRdfRequirementRepositoryFactory.buildGate(DisplayLocale.DEFAULT).enforce(candidate);
    }

    // --- the read-modify-write read under the project's language (issue #456) ----------------

    /**
     * Issue #456: {@link ConstraintRepository#findCurrentByCode} - the read behind every
     * {@code constraint_update} - used to project a multilingual {@code title}/
     * {@code constraintStatement} through this repository's process-wide configured
     * {@link DisplayLocale} (English by default), while {@link ConstraintRepository#findByCode}
     * projects those very same fields through the calling project's own default language. A field
     * this update leaves alone is echoed straight back to the caller, so one and the same store
     * state answered {@code constraint_update} with the English title and a directly following
     * {@code constraint_get} with the German one.
     */
    @Test
    void findCurrentByCodeSelectsTheTextFieldsInTheProjectsDefaultLanguage() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        givenBilingualConstraint(code);

        ConstraintRepository.CurrentConstraint current =
                repository.findCurrentByCode(PROJECT_A, code, "de").orElseThrow();

        assertEquals("Nur JVM", current.value().title());
        assertEquals("Muss auf der JVM laufen.", current.value().statement());
        assertEquals(repository.findByCode(PROJECT_A, code, "de").orElseThrow(), current.value(),
                "constraint_update must read the constraint constraint_get shows for the same project");
    }

    /**
     * The half of the same defect that steers the <em>write</em> rather than only the reply: the
     * tags this read hands back are the tags {@code ConstraintService} writes an untouched field
     * back under, and the values it hands back are what its no-op check compares a correction
     * against. Read under the process default, a German project's {@code constraint_update} would
     * round-trip its title as the English variant - and would mistake a genuine German correction
     * for a no-op whenever the English variant happens to already carry that text.
     */
    @Test
    void findCurrentByCodeCarriesTheLanguageTagsOfTheProjectsDefaultLanguage() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        givenBilingualConstraint(code);

        ConstraintRepository.CurrentConstraint current =
                repository.findCurrentByCode(PROJECT_A, code, "de").orElseThrow();

        assertEquals("de", current.titleLanguage());
        assertEquals("de", current.statementLanguage());
    }

    /**
     * A project without a configured default language degrades exactly as before: the tag is
     * {@code null}, {@link DisplayLocale#withRequestedOverride} is a no-op for it, and this
     * repository's own configured preference (English here) decides.
     */
    @Test
    void findCurrentByCodeWithoutAProjectDefaultLanguageStaysOnTheConfiguredPreference() {
        ConstraintCode code = new ConstraintCode("TCON-1");
        givenBilingualConstraint(code);

        ConstraintRepository.CurrentConstraint current =
                repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow();

        assertEquals("JVM only", current.value().title());
        assertEquals("en", current.titleLanguage());
    }

    /** A constraint carrying both its {@code @en} and its {@code @de} title/statement variant. */
    private void givenBilingualConstraint(ConstraintCode code) {
        ConstraintId id = freshId();
        repository.create(PROJECT_A, constraint(id, code, "JVM only", "Must run on the JVM."), "en");
        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                constraint(id, code, "Nur JVM", "Muss auf der JVM laufen."), "de", "de", "de");
    }

    private RevisionToken currentHead(ConstraintCode code) {
        return repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow().head();
    }

    /** Every literal actually stored on {@code subject}'s {@code predicateIri}, tags included. */
    private List<Literal> literalsOf(ConstraintId id, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + CONSTRAINTS_GRAPH + "> { <"
                + id.value().value() + "> <" + predicateIri + "> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            List<Literal> literals = new ArrayList<>();
            handle.sparqlQuery().select(query)
                    .forEach(row -> literals.add((Literal) row.getValue("o").orElseThrow()));
            return literals;
        }
    }
}
