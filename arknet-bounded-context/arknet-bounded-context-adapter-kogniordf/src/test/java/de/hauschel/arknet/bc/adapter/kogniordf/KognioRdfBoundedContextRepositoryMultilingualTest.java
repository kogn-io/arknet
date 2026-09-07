// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.kogniordf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
import io.kogn.rdf.terms.Literal;

import de.hauschel.arknet.bc.application.port.out.BoundedContextRepository;
import de.hauschel.arknet.bc.application.port.out.RevisionToken;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextDisplayFallback;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;

/**
 * Integration tests for the multilingual {@code arknet:name}/{@code arkddd:domainVision} behaviour
 * of {@link KognioRdfBoundedContextRepository} (kogn-io/arknet#520): language-scoped writes on
 * {@link BoundedContextRepository#create}/{@link BoundedContextRepository#compareAndUpdate},
 * {@link DisplayLocale}-selected reads, {@link BoundedContextRepository#findAllDisplayFallback},
 * and the issue #258 lazy sweep of a pre-#520 untagged legacy literal - mirrors
 * {@code KognioRdfConstraintRepositoryMultilingualTest}/{@code KognioRdfRoleRepositoryMultilingualTest}
 * exactly, adapted for the one difference this resource has from both: {@code name} AND
 * {@code domainVision} are BOTH mandatory (see {@link KognioRdfBoundedContextRepository}'s
 * class-level javadoc), so a store-first subject missing either is skipped rather than shown with
 * one field {@code null}.
 */
class KognioRdfBoundedContextRepositoryMultilingualTest {

    private static final String BOUNDED_CONTEXT_TYPE = "https://w3id.org/arknet/ddd#BoundedContext";
    private static final String BOUNDED_CONTEXT_GRAPH = "https://w3id.org/arknet/model/bounded-context";
    private static final String NAME_PROPERTY = "https://w3id.org/arknet/core#name";
    private static final String DOMAIN_VISION_PROPERTY = "https://w3id.org/arknet/ddd#domainVision";
    private static final String IDENTIFIER_PROPERTY = "http://purl.org/dc/terms/identifier";
    private static final String HAS_AGGREGATE_PROPERTY = "https://w3id.org/arknet/ddd#hasAggregate";
    private static final String UBIQUITOUS_LANGUAGE_TERM_PROPERTY =
            "https://w3id.org/arknet/ddd#ubiquitousLanguageTerm";
    private static final ProjectId PROJECT_A = new ProjectId("a");

    @TempDir
    Path storageRoot;

    private DatasetLifecycleRdf4j lifecycle;
    private BoundedContextRepository repository;

    @BeforeEach
    void setUp() {
        DatasetLifecycle datasetLifecycle = new DatasetLifecycleRdf4j(
                new DatasetStoreConfig(DatasetStoreConfig.Persistence.IN_MEMORY, false), storageRoot);
        lifecycle = (DatasetLifecycleRdf4j) datasetLifecycle;
        repository = KognioRdfBoundedContextRepositoryFactory.over(
                datasetLifecycle, new UuidResourceIdFactory(), DisplayLocale.DEFAULT);
    }

    @AfterEach
    void tearDown() {
        lifecycle.shutDownAll();
    }

    private static BoundedContextId freshId() {
        return new BoundedContextId(ResourceId.of("https://w3id.org/arknet/id/" + UUID.randomUUID()));
    }

    private static BoundedContext boundedContext(BoundedContextId id, BoundedContextCode code, String name,
            String domainVision) {
        return new BoundedContext(id, code, name, domainVision, null, null, List.of());
    }

    private RevisionToken currentHead(BoundedContextCode code) {
        return repository.findCurrentByCode(PROJECT_A, code, null).orElseThrow().head();
    }

    @Test
    void createWritesATaggedNameAndDomainVisionSelectableViaDisplayLocale() {
        BoundedContextCode code = new BoundedContextCode("BC-1");
        repository.create(PROJECT_A, boundedContext(freshId(), code, "Auftragsverwaltung",
                "Verantwortet den Lebenszyklus einer Kundenbestellung."), "de");

        BoundedContext asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Auftragsverwaltung", asGerman.name());
        assertEquals("Verantwortet den Lebenszyklus einer Kundenbestellung.", asGerman.domainVision());
    }

    /**
     * The two-call shape issue #520 exists for: {@code bc_add} in one language, a later
     * {@code compareAndUpdate} in a second, and both survive - a bounded context that would have
     * been permanently single-language before this change.
     */
    @Test
    void compareAndUpdateUnderASecondLanguageKeepsBothVariants() {
        BoundedContextCode code = new BoundedContextCode("BC-1");
        BoundedContextId id = freshId();
        repository.create(PROJECT_A, boundedContext(id, code, "Auftragsverwaltung",
                "Verantwortet den Lebenszyklus einer Kundenbestellung."), "de");
        RevisionToken head = currentHead(code);

        repository.compareAndUpdate(PROJECT_A, head,
                boundedContext(id, code, "OrderManagement",
                        "Owns the lifecycle of a customer order."), "en", "en", null);

        BoundedContext asGerman = repository.findByCode(PROJECT_A, code, "de").orElseThrow();
        assertEquals("Auftragsverwaltung", asGerman.name());
        assertEquals("Verantwortet den Lebenszyklus einer Kundenbestellung.", asGerman.domainVision());
        BoundedContext asEnglish = repository.findByCode(PROJECT_A, code, "en").orElseThrow();
        assertEquals("OrderManagement", asEnglish.name());
        assertEquals("Owns the lifecycle of a customer order.", asEnglish.domainVision());
    }

    /**
     * Capture-before-delete/reattach must run per field, independently: a {@code compareAndUpdate}
     * that changes only {@code domainVision}'s language (adding a German variant, passing
     * {@code name}'s already-observed {@code en} tag straight through unchanged) must leave the
     * English {@code name} AND the English {@code domainVision} both in place, next to the new
     * German {@code domainVision} - the subject ends up with two {@code domainVision} literals
     * (en+de) and exactly one {@code name} literal (en), not a collapsed or duplicated set.
     */
    @Test
    void compareAndUpdateOfOnlyDomainVisionLanguagePreservesNameAndBothDomainVisionVariants() {
        BoundedContextCode code = new BoundedContextCode("BC-1");
        BoundedContextId id = freshId();
        repository.create(PROJECT_A, boundedContext(id, code, "OrderManagement",
                "Owns the lifecycle of a customer order."), "en");

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                boundedContext(id, code, "OrderManagement",
                        "Verantwortet den Lebenszyklus einer Kundenbestellung."), "en", "de", null);

        assertEquals(1, literalsOf(id, NAME_PROPERTY).size(),
                "name must not be duplicated by a passthrough write under its already-observed tag");
        assertEquals("en", literalsOf(id, NAME_PROPERTY).get(0).getLanguageTag().orElseThrow());
        List<Literal> domainVisions = literalsOf(id, DOMAIN_VISION_PROPERTY);
        assertEquals(2, domainVisions.size(),
                "both the original English domainVision and the newly written German one must survive");
        assertTrue(domainVisions.stream().anyMatch(l -> "en".equals(l.getLanguageTag().orElse(null))
                && "Owns the lifecycle of a customer order.".equals(l.getLexicalForm())));
        assertTrue(domainVisions.stream().anyMatch(l -> "de".equals(l.getLanguageTag().orElse(null))
                && "Verantwortet den Lebenszyklus einer Kundenbestellung.".equals(l.getLexicalForm())));
    }

    /** Re-writing the same tag replaces that one variant rather than accumulating duplicates. */
    @Test
    void compareAndUpdateUnderTheSameTagDoesNotDuplicateTheLiteral() {
        BoundedContextCode code = new BoundedContextCode("BC-1");
        BoundedContextId id = freshId();
        repository.create(PROJECT_A, boundedContext(id, code, "OrderManagement",
                "Owns the lifecycle of a customer odrer."), "en");

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                boundedContext(id, code, "OrderManagement",
                        "Owns the lifecycle of a customer order."), "en", "en", null);

        List<Literal> domainVisions = literalsOf(id, DOMAIN_VISION_PROPERTY);
        assertEquals(1, domainVisions.size());
        assertEquals("Owns the lifecycle of a customer order.", domainVisions.get(0).getLexicalForm());
    }

    /**
     * Issue #258's lazy sweep, ported to the bounded-context resource: a context created before
     * kogn-io/arknet#520 (or via a store-first/{@code language=null} write) carries plain, untagged
     * {@code name}/{@code domainVision} literals. A subsequent {@code compareAndUpdate} that writes
     * under the project's own default language must REPLACE that untagged literal rather than
     * preserving it as a spurious "other" variant - exactly one {@code arknet:name} triple remains
     * for the subject afterward, now correctly tagged.
     */
    @Test
    void compareAndUpdateSweepsAnUntaggedNameAndDomainVisionWhenWritingUnderTheProjectDefault() {
        BoundedContextCode code = new BoundedContextCode("BC-1");
        BoundedContextId id = freshId();
        repository.create(PROJECT_A, boundedContext(id, code, "OrderManagement",
                "Owns the lifecycle of a customer order."), null);
        assertTrue(literalsOf(id, NAME_PROPERTY).get(0).getLanguageTag().isEmpty(),
                "precondition: a pre-#520/store-first write carries an untagged literal");
        assertTrue(literalsOf(id, DOMAIN_VISION_PROPERTY).get(0).getLanguageTag().isEmpty());

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                boundedContext(id, code, "OrderManagement",
                        "Owns the lifecycle of a customer order."), "en", "en", "en");

        List<Literal> names = literalsOf(id, NAME_PROPERTY);
        assertEquals(1, names.size(), "the untagged literal must be replaced, not preserved as a sibling");
        assertEquals("en", names.get(0).getLanguageTag().orElseThrow());
        List<Literal> domainVisions = literalsOf(id, DOMAIN_VISION_PROPERTY);
        assertEquals(1, domainVisions.size());
        assertEquals("en", domainVisions.get(0).getLanguageTag().orElseThrow());
    }

    /** ... whereas a write under a non-default tag leaves the legacy untagged literal alone. */
    @Test
    void compareAndUpdateUnderANonDefaultTagPreservesAnUntaggedLiteral() {
        BoundedContextCode code = new BoundedContextCode("BC-1");
        BoundedContextId id = freshId();
        repository.create(PROJECT_A, boundedContext(id, code, "OrderManagement",
                "Owns the lifecycle of a customer order."), null);

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                boundedContext(id, code, "Auftragsverwaltung",
                        "Verantwortet den Lebenszyklus einer Kundenbestellung."), "de", "de", "en");

        assertEquals(2, literalsOf(id, NAME_PROPERTY).size());
        assertEquals(2, literalsOf(id, DOMAIN_VISION_PROPERTY).size());
    }

    /**
     * Combined regression: the pre-existing {@code hasAggregate}/blank-node-
     * {@code ubiquitousLanguageTerm} preservation (see
     * {@code KognioRdfBoundedContextRepositoryTest#updatePreservesAStoreFirstHasAggregateEdge}/
     * {@code #updatePreservesABlankNodeUbiquitousLanguageTermEdge}) must keep working exactly as
     * before when the write that triggers it is a language-only {@code compareAndUpdate} - the two
     * preservation mechanisms (edge capture, literal capture) share one transaction and must not
     * interfere with each other.
     */
    @Test
    void languageOnlyCompareAndUpdateDoesNotDisturbHasAggregateOrBlankNodeTermPreservation() {
        BoundedContextCode code = new BoundedContextCode("BC-1");
        BoundedContextId id = freshId();
        repository.create(PROJECT_A, boundedContext(id, code, "OrderManagement",
                "Owns the lifecycle of a customer order."), "en");

        String aggregateIri = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        insertTriple(id.value().value(), HAS_AGGREGATE_PROPERTY, "<" + aggregateIri + ">");
        insertTriple(id.value().value(), UBIQUITOUS_LANGUAGE_TERM_PROPERTY,
                "[ a <http://www.w3.org/2004/02/skos/core#Concept> ]");

        repository.compareAndUpdate(PROJECT_A, currentHead(code),
                boundedContext(id, code, "OrderManagement",
                        "Verantwortet den Lebenszyklus einer Kundenbestellung."), "en", "de", null);

        String askAggregate = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + id.value().value()
                + "> <" + HAS_AGGREGATE_PROPERTY + "> <" + aggregateIri + "> } }";
        String askBlankTerm = "ASK { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <" + id.value().value()
                + "> <" + UBIQUITOUS_LANGUAGE_TERM_PROPERTY + "> ?term . "
                + "?term a <http://www.w3.org/2004/02/skos/core#Concept> } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            assertTrue(handle.sparqlQuery().ask(askAggregate),
                    "the store-first hasAggregate edge must survive a language-only update");
            assertTrue(handle.sparqlQuery().ask(askBlankTerm),
                    "the store-first blank-node ubiquitousLanguageTerm edge must survive a language-only update");
        }
        assertEquals(2, literalsOf(id, DOMAIN_VISION_PROPERTY).size(),
                "the language write itself must still have gone through as usual");
    }

    /**
     * {@code findAllDisplayFallback}: a bounded context whose {@code name}/{@code domainVision}
     * are both already in the requested display language reports no fallback, while one whose
     * {@code name} is only available in a different language is reported with a non-null
     * {@code nameTag} naming the language actually shown, and a null {@code domainVisionTag}
     * because {@code domainVision} itself is available in the requested language.
     */
    @Test
    void findAllDisplayFallbackReportsOnlyTheContextThatFellBack() {
        BoundedContextCode inLanguage = new BoundedContextCode("BC-1");
        repository.create(PROJECT_A, boundedContext(freshId(), inLanguage, "OrderManagement",
                "Owns the lifecycle of a customer order."), "en");

        BoundedContextCode fellBack = new BoundedContextCode("BC-2");
        BoundedContextId fellBackId = freshId();
        repository.create(PROJECT_A, boundedContext(fellBackId, fellBack, "Inventarverwaltung",
                "Verantwortet den Lagerbestand."), "de");
        // domainVision is also written under "en" so only name falls back, not both fields.
        repository.compareAndUpdate(PROJECT_A, currentHead(fellBack),
                boundedContext(fellBackId, fellBack, "Inventarverwaltung",
                        "Tracks stock levels."), "de", "en", null);

        Map<BoundedContextCode, BoundedContextDisplayFallback> fallbacks =
                repository.findAllDisplayFallback(PROJECT_A, "en");

        assertTrue(!fallbacks.containsKey(inLanguage) || fallbacks.get(inLanguage).isEmpty(),
                "a context already shown in the requested language must not be reported as a fallback");
        BoundedContextDisplayFallback fallback = fallbacks.get(fellBack);
        assertEquals("de", fallback.nameTag());
        assertNull(fallback.domainVisionTag(), "domainVision itself is available in the requested language");
    }

    /**
     * Both {@code name} and {@code domainVision} must have a candidate under the requested locale,
     * or the whole bounded context is treated as absent (see {@code KognioRdfBoundedContextRepository
     * #selectNameVision}'s javadoc) - unlike a single-mandatory-field resource such as
     * {@code Constraint}, a store-first subject with {@code name} present but no
     * {@code domainVision} at all is skipped by both {@code findByCode} and {@code findAll}, even
     * though {@code name} alone would otherwise be readable.
     */
    @Test
    void aStoreFirstSubjectMissingDomainVisionEntirelyIsSkippedDespiteHavingAName() {
        String subject = "https://w3id.org/arknet/id/" + UUID.randomUUID();
        insertRootTriple("<" + subject + ">", "a", "<" + BOUNDED_CONTEXT_TYPE + ">");
        insertTriple(subject, IDENTIFIER_PROPERTY, "\"BC-7\"");
        insertTriple(subject, NAME_PROPERTY, "\"Orphaned\"@en");

        assertTrue(repository.findByCode(PROJECT_A, new BoundedContextCode("BC-7"), "en").isEmpty());
        assertTrue(repository.findAll(PROJECT_A, "en").stream()
                .noneMatch(bc -> bc.code().equals(new BoundedContextCode("BC-7"))));
    }

    /** Inserts one raw triple directly into the bounded-context named graph, bypassing the domain. */
    private void insertTriple(String subjectIri, String predicateIri, String objectTerm) {
        insertRootTriple("<" + subjectIri + ">", "<" + predicateIri + ">", objectTerm);
    }

    private void insertRootTriple(String subjectTerm, String predicateTerm, String objectTerm) {
        String insert = "INSERT DATA { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { " + subjectTerm + " "
                + predicateTerm + " " + objectTerm + " } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert);
                return null;
            });
        }
    }

    /** Every literal actually stored on {@code subject}'s {@code predicateIri}, tags included. */
    private List<Literal> literalsOf(BoundedContextId id, String predicateIri) {
        String query = "SELECT ?o WHERE { GRAPH <" + BOUNDED_CONTEXT_GRAPH + "> { <"
                + id.value().value() + "> <" + predicateIri + "> ?o } }";
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT_A.value()))) {
            List<Literal> literals = new ArrayList<>();
            handle.sparqlQuery().select(query)
                    .forEach(row -> literals.add((Literal) row.getValue("o").orElseThrow()));
            return literals;
        }
    }
}
