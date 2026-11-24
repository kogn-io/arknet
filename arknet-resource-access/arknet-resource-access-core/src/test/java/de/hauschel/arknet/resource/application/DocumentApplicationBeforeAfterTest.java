// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.RDFTerm;
import io.kogn.rdf.terms.ReadableGraph;
import io.kogn.rdf.terms.SimpleRdf;
import io.kogn.rdf.terms.Triple;
import io.kogn.rdf.terms.vocab.VocabRdf;
import io.kogn.rdf.terms.vocab.VocabXsd;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.resource.domain.ArkresVocabulary;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;
import de.hauschel.arknet.resource.domain.SubmittedDocument;

/**
 * The rules that compare a resource before and after a document, driven by rule data alone: a
 * field group that only grows outside its editable statuses (new languages over the union of the
 * group, new children after the last), an immutable field, and a default value written at one
 * transition only, and only into a field the document does not name. Fixture shapes, no namespace
 * of a bounded context; the day is fixed.
 */
class DocumentApplicationBeforeAfterTest {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String VOCAB = "https://example.org/vocab#";
    private static final String SHAPES = "https://example.org/shapes#";
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final String STORE = "https://example.org/store/";
    private static final LocalDate TODAY = LocalDate.of(2026, 11, 24);

    private static final IRI PAPER = vocab("Paper");
    private static final IRI MEMO = vocab("Memo");
    private static final IRI SECTION = vocab("Section");
    private static final IRI STATE = vocab("state");
    private static final IRI TITLE = vocab("title");
    private static final IRI SUMMARY = vocab("summary");
    private static final IRI SECTIONS = vocab("section");
    private static final IRI HEADING = vocab("heading");
    private static final IRI KIND = vocab("kind");
    private static final IRI POSITION = vocab("position");
    private static final IRI CATEGORY = vocab("category");
    private static final IRI PUBLISHED_ON = vocab("publishedOn");
    private static final IRI DRAFT = vocab("Draft");
    private static final IRI PUBLISHED = vocab("Published");
    private static final IRI WITHDRAWN = vocab("Withdrawn");
    private static final IRI ESSAY = vocab("Essay");
    private static final IRI REVIEW = vocab("Review");
    private static final IRI BODY = vocab("Body");
    private static final IRI PUBLISHABLE = RDF_FACTORY.createIRI(SHAPES + "Publishable");
    private static final IRI STATE_SHAPE = RDF_FACTORY.createIRI(SHAPES + "Paper-state");
    private static final RDFTerm OLD_DAY = date("2020-01-01");

    private static final ResourceSchema SCHEMA = ResourceSchema.of(shapes(), RDF_FACTORY.createGraph());

    private final FakeBatch batch = new FakeBatch();
    private final Graph document = RDF_FACTORY.createGraph();

    @Test
    void inAnEditableStatusTheGroupChangesFreely() {
        IRI paper = storedPaper(DRAFT);
        BlankNode values = update(paper);
        document.add(values, TITLE, text("Rewritten", "en"));
        document.add(values, SUMMARY, RDF_FACTORY.createLiteral("no language"));

        assertDoesNotThrow(this::apply);

        assertEquals(Set.of(text("Rewritten", "en"), text("Titel", "de")), values(batch.updated.get(paper), paper,
                TITLE));
    }

    @Test
    void aResourceStoredWithoutStatusCountsAsInTheInitialStatus() {
        IRI paper = storedPaper(null);
        document.add(update(paper), TITLE, text("Rewritten", "en"));

        assertDoesNotThrow(this::apply);
    }

    @Test
    void outsideTheEditableStatusesAValueInALanguageTheGroupCarriesIsRejected() {
        IRI paper = storedPaper(PUBLISHED);
        document.add(update(paper), TITLE, text("Rewritten", "en"));

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(ArkresVocabulary.FIELD_GROUP), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(TITLE.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(PUBLISHED.getIRIString()), rejected.getMessage());
        assertTrue(batch.updated.isEmpty());
    }

    @Test
    void outsideTheEditableStatusesALanguageNewToTheWholeGroupIsAdded() {
        IRI paper = storedPaper(PUBLISHED);
        BlankNode values = update(paper);
        document.add(values, TITLE, text("Titre", "fr"));
        document.add(values, SUMMARY, text("Resume", "fr"));

        assertDoesNotThrow(this::apply);

        assertEquals(Set.of(text("Title", "en"), text("Titel", "de"), text("Titre", "fr")),
                values(batch.updated.get(paper), paper, TITLE));
    }

    @Test
    void aLanguageAnotherFieldOfTheGroupCarriesIsNotNewToTheGroup() {
        IRI paper = storedPaper(PUBLISHED);
        document.add(update(paper), SUMMARY, text("Zusammenfassung", "de"));

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains("[de, en]"), rejected.getMessage());
    }

    @Test
    void outsideTheEditableStatusesAValueWithoutLanguageAndAClearAreRejected() {
        IRI paper = storedPaper(PUBLISHED);
        document.add(update(paper), SUMMARY, RDF_FACTORY.createLiteral("no language"));

        assertThrows(SubmitRejectedException.class, this::apply);

        document.clear();
        clear(paper, TITLE);
        SubmitRejectedException cleared = assertThrows(SubmitRejectedException.class, this::apply);
        assertTrue(cleared.getMessage().contains("removes"), cleared.getMessage());
    }

    @Test
    void outsideTheEditableStatusesAListTakesANewChildAfterTheLast() {
        IRI paper = storedPaper(PUBLISHED);
        BlankNode child = addSection(paper);
        document.add(child, HEADING, text("Appendix", "en"));

        assertDoesNotThrow(this::apply);

        assertEquals(2, values(batch.updated.get(paper), paper, SECTIONS).size());
    }

    @Test
    void outsideTheEditableStatusesAListKeepsEveryChildAndItsPosition() {
        IRI paper = storedPaper(PUBLISHED);
        removeSection(paper, 1);

        SubmitRejectedException removed = assertThrows(SubmitRejectedException.class, this::apply);
        assertTrue(removed.getMessage().contains("removes the child " + paper.getIRIString() + "#Section-1"),
                removed.getMessage());

        document.clear();
        BlankNode inserted = addSection(paper);
        document.add(inserted, HEADING, text("Preface", "en"));
        document.add(inserted, POSITION, RDF_FACTORY.createLiteral("1", VocabXsd.INTEGER));
        SubmitRejectedException moved = assertThrows(SubmitRejectedException.class, this::apply);
        assertTrue(moved.getMessage().contains(POSITION.getIRIString()), moved.getMessage());
    }

    @Test
    void aTransitionOutOfAnEditableStatusMayCarryAnEditOfTheGroup() {
        IRI paper = storedPaper(DRAFT);
        BlankNode values = update(paper);
        document.add(values, STATE, PUBLISHED);
        document.add(values, TITLE, text("Final", "en"));

        assertDoesNotThrow(this::apply);
    }

    @Test
    void anImmutableFieldKeepsItsValueInEveryStatus() {
        IRI paper = storedPaper(DRAFT);
        document.add(update(paper), CATEGORY, REVIEW);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(ArkresVocabulary.IMMUTABLE), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(CATEGORY.getIRIString()), rejected.getMessage());

        document.clear();
        clear(paper, CATEGORY);
        assertThrows(SubmitRejectedException.class, this::apply);

        document.clear();
        document.add(update(paper), CATEGORY, ESSAY);
        assertDoesNotThrow(this::apply, "writing the value it holds is no change");
    }

    @Test
    void anImmutableFieldStoredWithoutValueTakesNone() {
        IRI paper = storedPaper(DRAFT);
        batch.store().stream(paper, CATEGORY, null).toList().forEach(batch.store()::remove);
        document.add(update(paper), CATEGORY, ESSAY);

        assertThrows(SubmitRejectedException.class, this::apply);
    }

    @Test
    void theDeclaringTransitionWritesTheDefaultDayReplacingTheStoredOneAndItsConditionSeesIt() {
        IRI paper = storedPaper(DRAFT);
        batch.add(paper, PUBLISHED_ON, OLD_DAY);
        document.add(update(paper), STATE, PUBLISHED);

        assertDoesNotThrow(this::apply);

        assertEquals(Set.of(date("2026-11-24")), values(batch.updated.get(paper), paper, PUBLISHED_ON));
        assertEquals(Set.of(date("2026-11-24")), values(batch.checks.get(0).data(), paper, PUBLISHED_ON));
    }

    @Test
    void aDayTheDocumentWritesItselfWins() {
        IRI paper = storedPaper(DRAFT);
        BlankNode values = update(paper);
        document.add(values, STATE, PUBLISHED);
        document.add(values, PUBLISHED_ON, OLD_DAY);

        assertDoesNotThrow(this::apply);

        assertEquals(Set.of(OLD_DAY), values(batch.updated.get(paper), paper, PUBLISHED_ON));
    }

    @Test
    void anotherTransitionIntoTheSameStatusKeepsTheDay() {
        IRI paper = storedPaper(WITHDRAWN);
        batch.add(paper, PUBLISHED_ON, OLD_DAY);
        document.add(update(paper), STATE, PUBLISHED);

        assertDoesNotThrow(this::apply);

        assertEquals(Set.of(OLD_DAY), values(batch.updated.get(paper), paper, PUBLISHED_ON));
    }

    @Test
    void writingTheCurrentStatusAgainWritesNoDefault() {
        IRI paper = storedPaper(PUBLISHED);
        document.add(update(paper), STATE, PUBLISHED);

        assertDoesNotThrow(this::apply);

        assertTrue(values(batch.updated.get(paper), paper, PUBLISHED_ON).isEmpty());
    }

    @Test
    void aTypeWithoutBeforeAfterRuleDataBehavesAsBefore() {
        IRI memo = RDF_FACTORY.createIRI(STORE + "memo-1");
        batch.add(memo, VocabRdf.TYPE, MEMO);
        batch.add(memo, TITLE, text("Memo", "en"));
        batch.add(memo, CATEGORY, ESSAY);
        BlankNode values = update(memo);
        document.add(values, TITLE, text("Changed", "en"));
        document.add(values, CATEGORY, REVIEW);

        assertDoesNotThrow(this::apply);

        assertEquals(Set.of(REVIEW), values(batch.updated.get(memo), memo, CATEGORY));
    }

    private void apply() {
        new DocumentApplication(SubmittedDocument.of(document, SCHEMA), SCHEMA, batch,
                () -> ResourceId.of(STORE + UUID.randomUUID()), TODAY).apply();
    }

    private BlankNode update(final IRI resource) {
        BlankNode activity = RDF_FACTORY.createBlankNode();
        BlankNode values = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.UPDATE));
        document.add(activity, arkres(ArkresVocabulary.OBJECT), resource);
        document.add(activity, arkres(ArkresVocabulary.SET), values);
        return values;
    }

    private void clear(final IRI resource, final IRI field) {
        BlankNode activity = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.UPDATE));
        document.add(activity, arkres(ArkresVocabulary.OBJECT), resource);
        document.add(activity, arkres(ArkresVocabulary.CLEAR), field);
    }

    private BlankNode addSection(final IRI resource) {
        BlankNode activity = RDF_FACTORY.createBlankNode();
        BlankNode child = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.ADD_TO));
        document.add(activity, arkres(ArkresVocabulary.OBJECT), resource);
        document.add(activity, arkres(ArkresVocabulary.FIELD), SECTIONS);
        document.add(activity, arkres(ArkresVocabulary.VALUE), child);
        document.add(child, KIND, BODY);
        return child;
    }

    private void removeSection(final IRI resource, final int position) {
        BlankNode activity = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.REMOVE_FROM));
        document.add(activity, arkres(ArkresVocabulary.OBJECT), resource);
        document.add(activity, arkres(ArkresVocabulary.FIELD), SECTIONS);
        document.add(activity, arkres(ArkresVocabulary.POSITION),
                RDF_FACTORY.createLiteral(Integer.toString(position), VocabXsd.INTEGER));
    }

    /** A paper with a title in en and de, a summary in en, category Essay and one section. */
    private IRI storedPaper(final IRI state) {
        IRI paper = RDF_FACTORY.createIRI(STORE + "paper-" + UUID.randomUUID());
        batch.add(paper, VocabRdf.TYPE, PAPER);
        batch.add(paper, TITLE, text("Title", "en"));
        batch.add(paper, TITLE, text("Titel", "de"));
        batch.add(paper, SUMMARY, text("Summary", "en"));
        batch.add(paper, CATEGORY, ESSAY);
        if (state != null) {
            batch.add(paper, STATE, state);
        }
        IRI section = RDF_FACTORY.createIRI(paper.getIRIString() + "#Section-1");
        batch.add(paper, SECTIONS, section);
        batch.add(section, VocabRdf.TYPE, SECTION);
        batch.add(section, HEADING, text("Introduction", "en"));
        batch.add(section, KIND, BODY);
        batch.add(section, POSITION, RDF_FACTORY.createLiteral("1", VocabXsd.INTEGER));
        return paper;
    }

    private static Set<RDFTerm> values(final ReadableGraph graph, final IRI subject, final IRI property) {
        return graph.stream(subject, property, null).map(Triple::getObject).collect(Collectors.toSet());
    }

    /**
     * A paper: status Draft/Published/Withdrawn; title, summary and the owned sections only grow
     * outside Draft; an immutable category; the publication day as default of Draft to Published,
     * whose condition is answered by the batch, and none for Withdrawn to Published. A memo carries
     * the same fields without any rule data.
     */
    private static Graph shapes() {
        Graph shapes = RDF_FACTORY.createGraph();
        shapes.add(PAPER, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        shapes.add(PAPER, arkres(ArkresVocabulary.CODE_PREFIX), RDF_FACTORY.createLiteral("PA-"));
        shapes.add(MEMO, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        IRI paperShape = RDF_FACTORY.createIRI(SHAPES + "Paper");
        shapes.add(paperShape, shacl("targetClass"), PAPER);
        for (IRI field : List.of(TITLE, SUMMARY, PUBLISHED_ON)) {
            property(shapes, paperShape, "Paper", field);
        }
        property(shapes, paperShape, "Paper", CATEGORY);
        shapes.add(RDF_FACTORY.createIRI(SHAPES + "Paper-category"), arkres(ArkresVocabulary.IMMUTABLE),
                RDF_FACTORY.createLiteral("true", VocabXsd.BOOLEAN));
        IRI sections = property(shapes, paperShape, "Paper", SECTIONS);
        shapes.add(sections, shacl("class"), SECTION);
        shapes.add(sections, arkres(ArkresVocabulary.CHILD_KIND), RDF_FACTORY.createLiteral("Section"));
        shapes.add(sections, arkres(ArkresVocabulary.ORDERED_BY), POSITION);
        IRI sectionShape = RDF_FACTORY.createIRI(SHAPES + "Section");
        shapes.add(sectionShape, shacl("targetClass"), SECTION);
        property(shapes, sectionShape, "Section", HEADING);
        property(shapes, sectionShape, "Section", KIND);
        property(shapes, sectionShape, "Section", POSITION);
        shapes.add(paperShape, shacl("property"), STATE_SHAPE);
        shapes.add(STATE_SHAPE, shacl("path"), STATE);
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.INITIAL_STATUS), DRAFT);
        BlankNode group = RDF_FACTORY.createBlankNode();
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.FIELD_GROUP), group);
        for (IRI field : List.of(TITLE, SUMMARY, SECTIONS)) {
            shapes.add(group, arkres(ArkresVocabulary.GROUP_FIELD), field);
        }
        shapes.add(group, arkres(ArkresVocabulary.EDITABLE_IN), DRAFT);
        shapes.add(PUBLISHABLE, VocabRdf.TYPE, shacl("NodeShape"));
        BlankNode publish = transition(shapes, DRAFT, PUBLISHED);
        shapes.add(publish, arkres(ArkresVocabulary.CONDITION), PUBLISHABLE);
        BlankNode day = RDF_FACTORY.createBlankNode();
        shapes.add(publish, arkres(ArkresVocabulary.DEFAULT), day);
        shapes.add(day, arkres(ArkresVocabulary.DEFAULT_FIELD), PUBLISHED_ON);
        shapes.add(day, arkres(ArkresVocabulary.DEFAULT_VALUE), arkres(ArkresVocabulary.TODAY));
        transition(shapes, PUBLISHED, WITHDRAWN);
        transition(shapes, WITHDRAWN, PUBLISHED);
        IRI memoShape = RDF_FACTORY.createIRI(SHAPES + "Memo");
        shapes.add(memoShape, shacl("targetClass"), MEMO);
        property(shapes, memoShape, "Memo", TITLE);
        property(shapes, memoShape, "Memo", CATEGORY);
        return shapes;
    }

    private static IRI property(final Graph shapes, final IRI node, final String owner, final IRI field) {
        IRI property = RDF_FACTORY.createIRI(SHAPES + owner + "-" + field.getIRIString().substring(VOCAB.length()));
        shapes.add(node, shacl("property"), property);
        shapes.add(property, shacl("path"), field);
        return property;
    }

    private static BlankNode transition(final Graph shapes, final IRI from, final IRI to) {
        BlankNode transition = RDF_FACTORY.createBlankNode();
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.TRANSITION), transition);
        shapes.add(transition, arkres(ArkresVocabulary.FROM_STATUS), from);
        shapes.add(transition, arkres(ArkresVocabulary.TO_STATUS), to);
        return transition;
    }

    private static IRI vocab(final String localName) {
        return RDF_FACTORY.createIRI(VOCAB + localName);
    }

    private static IRI arkres(final String iri) {
        return RDF_FACTORY.createIRI(iri);
    }

    private static IRI shacl(final String localName) {
        return RDF_FACTORY.createIRI(SHACL + localName);
    }

    private static RDFTerm text(final String value, final String language) {
        return RDF_FACTORY.createLiteral(value, language);
    }

    private static RDFTerm date(final String value) {
        return RDF_FACTORY.createLiteral(value, VocabXsd.DATE);
    }
}
