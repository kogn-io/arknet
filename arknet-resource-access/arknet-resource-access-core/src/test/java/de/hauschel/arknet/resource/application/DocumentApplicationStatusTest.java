// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.resource.domain.ArkresVocabulary;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;
import de.hauschel.arknet.resource.domain.SubmittedDocument;

/**
 * The status mechanics of the engine, driven by rule data alone: the initial status of a create, a
 * change only along a declared transition, a transition's conditions checked on the changed resource
 * only, the same status as no change, and a delete only in a deletable status. A type without status
 * rule data is untouched. Fixture shapes, no namespace of a bounded context; whether a condition
 * holds is the batch's answer, the real shape evaluation is {@code StatusTransitionsRealStoreTest}.
 */
class DocumentApplicationStatusTest {

    private static final RDF RDF_FACTORY = new SimpleRdf();
    private static final String VOCAB = "https://example.org/vocab#";
    private static final String SHAPES = "https://example.org/shapes#";
    private static final String SHACL = "http://www.w3.org/ns/shacl#";
    private static final String STORE = "https://example.org/store/";

    private static final IRI TICKET = vocab("Ticket");
    private static final IRI MEMO = vocab("Memo");
    private static final IRI NAME = vocab("name");
    private static final IRI STATE = vocab("state");
    private static final IRI NOTE = vocab("note");
    private static final IRI REPLACED_BY = vocab("replacedBy");
    private static final IRI OPEN = vocab("Open");
    private static final IRI DONE = vocab("Done");
    private static final IRI CLOSED = vocab("Closed");
    private static final IRI NEEDS_NOTE = RDF_FACTORY.createIRI(SHAPES + "NeedsNote");
    private static final IRI REPLACEMENT_DONE = RDF_FACTORY.createIRI(SHAPES + "ReplacementDone");
    private static final IRI STATE_SHAPE = RDF_FACTORY.createIRI(SHAPES + "Ticket-state");

    private static final ResourceSchema SCHEMA = ResourceSchema.of(shapes(), RDF_FACTORY.createGraph());

    private final FakeBatch batch = new FakeBatch();
    private final Graph document = RDF_FACTORY.createGraph();

    @Test
    void aCreateWithoutStatusStartsInTheInitialStatus() {
        BlankNode ticket = create(TICKET);
        document.add(ticket, NAME, literal("first"));

        assertDoesNotThrow(this::apply);

        IRI created = batch.created.get(0);
        assertEquals(Set.of(OPEN), values(batch.createdSlices.get(created), created, STATE));
    }

    @Test
    void aCreateWritingTheInitialStatusIsAccepted() {
        BlankNode ticket = create(TICKET);
        document.add(ticket, STATE, OPEN);

        assertDoesNotThrow(this::apply);

        IRI created = batch.created.get(0);
        assertEquals(Set.of(OPEN), values(batch.createdSlices.get(created), created, STATE));
    }

    @Test
    void aCreateWritingAnotherStatusIsRejected() {
        BlankNode ticket = create(TICKET);
        document.add(ticket, STATE, DONE);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(OPEN.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(STATE.getIRIString()), rejected.getMessage());
        assertTrue(batch.created.isEmpty());
    }

    @Test
    void aStatusChangeAlongATransitionChecksItsConditionOnTheChangedResourceAfterTheDocument() {
        IRI neighbour = storedTicket("TK-2", DONE);
        IRI ticket = storedTicket("TK-1", OPEN);
        batch.add(ticket, REPLACED_BY, neighbour);
        BlankNode values = update(ticket);
        document.add(values, STATE, DONE);

        assertDoesNotThrow(this::apply);

        assertEquals(1, batch.checks.size());
        FakeBatch.Check check = batch.checks.get(0);
        assertEquals(NEEDS_NOTE, check.shape());
        assertEquals(ticket, check.focus());
        assertEquals(Set.of(DONE), values(check.data(), ticket, STATE));
        assertEquals(Set.of(DONE), values(check.data(), neighbour, STATE), "the slices it reaches come along");
        assertEquals(Set.of(DONE), values(batch.updated.get(ticket), ticket, STATE));
    }

    @Test
    void aViolatedConditionRejectsTheTransitionWithTheShapesMessage() {
        IRI ticket = storedTicket("TK-1", OPEN);
        batch.failing.put(NEEDS_NOTE, "a done ticket carries a note");
        BlankNode values = update(ticket);
        document.add(values, STATE, DONE);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(NEEDS_NOTE.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("a done ticket carries a note"), rejected.getMessage());
        assertTrue(batch.updated.isEmpty());
    }

    @Test
    void aStatusChangeNoTransitionAllowsIsRejectedNamingTheReachableStatuses() {
        IRI ticket = storedTicket("TK-1", OPEN);
        BlankNode values = update(ticket);
        document.add(values, STATE, CLOSED);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(CLOSED.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(DONE.getIRIString()), rejected.getMessage());
        assertTrue(batch.checks.isEmpty());
        assertTrue(batch.updated.isEmpty());
    }

    @Test
    void writingTheStatusAResourceAlreadyHasIsNoChangeAndChecksNothing() {
        IRI ticket = storedTicket("TK-1", CLOSED);
        BlankNode values = update(ticket);
        document.add(values, STATE, CLOSED);
        document.add(values, NAME, literal("renamed"));

        assertDoesNotThrow(this::apply);

        assertTrue(batch.checks.isEmpty());
        assertEquals(1, batch.updated.size());
    }

    @Test
    void aChangeOfAnotherFieldChecksNoConditionEvenIfItWouldNotHold() {
        IRI ticket = storedTicket("TK-1", DONE);
        batch.failing.put(NEEDS_NOTE, "a done ticket carries a note");
        BlankNode values = update(ticket);
        document.add(values, NAME, literal("renamed"));

        assertDoesNotThrow(this::apply);

        assertTrue(batch.checks.isEmpty());
    }

    @Test
    void aReferencedResourceIsNotCheckedForTheConditionsOfItsOwnStatus() {
        IRI neighbour = storedTicket("TK-2", DONE);
        IRI ticket = storedTicket("TK-1", DONE);
        batch.add(ticket, REPLACED_BY, neighbour);
        batch.failing.put(NEEDS_NOTE, "a done ticket carries a note");
        BlankNode values = update(ticket);
        document.add(values, STATE, CLOSED);

        assertDoesNotThrow(this::apply);

        assertEquals(List.of(REPLACEMENT_DONE), batch.checks.stream().map(FakeBatch.Check::shape).toList());
        assertEquals(List.of(ticket), batch.checks.stream().map(FakeBatch.Check::focus).toList());
    }

    @Test
    void clearingTheStatusIsRejected() {
        IRI ticket = storedTicket("TK-1", OPEN);
        BlankNode activity = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.UPDATE));
        document.add(activity, arkres(ArkresVocabulary.OBJECT), ticket);
        document.add(activity, arkres(ArkresVocabulary.CLEAR), STATE);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains("exactly one value"), rejected.getMessage());
    }

    @Test
    void aResourceStoredWithoutStatusTakesTheInitialStatusFirst() {
        IRI ticket = storedTicket("TK-1", null);
        BlankNode values = update(ticket);
        document.add(values, STATE, DONE);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(OPEN.getIRIString()), rejected.getMessage());
    }

    @Test
    void aDeleteInAStatusTheFieldDoesNotNameAsDeletableIsRejected() {
        IRI ticket = storedTicket("TK-1", CLOSED);
        delete(ticket);

        SubmitRejectedException rejected = assertThrows(SubmitRejectedException.class, this::apply);

        assertTrue(rejected.getMessage().contains(CLOSED.getIRIString()), rejected.getMessage());
        assertTrue(rejected.getMessage().contains(ArkresVocabulary.DELETABLE_IN), rejected.getMessage());
        assertTrue(batch.deleted.isEmpty());
    }

    @Test
    void aDeleteInADeletableStatusIsAccepted() {
        IRI ticket = storedTicket("TK-1", OPEN);
        delete(ticket);

        assertDoesNotThrow(this::apply);

        assertEquals(List.of(ticket), batch.deleted);
    }

    @Test
    void aTypeWithoutStatusRuleDataBehavesAsBefore() {
        BlankNode memo = create(MEMO);
        document.add(memo, NAME, literal("memo"));
        document.add(memo, STATE, CLOSED);
        IRI stored = RDF_FACTORY.createIRI(STORE + "memo-1");
        batch.add(stored, VocabRdf.TYPE, MEMO);
        batch.add(stored, STATE, DONE);
        BlankNode values = update(stored);
        document.add(values, STATE, OPEN);

        assertDoesNotThrow(this::apply);

        IRI created = batch.created.get(0);
        assertEquals(Set.of(CLOSED), values(batch.createdSlices.get(created), created, STATE));
        assertEquals(Set.of(OPEN), values(batch.updated.get(stored), stored, STATE));
        assertTrue(batch.checks.isEmpty());
    }

    private void apply() {
        new DocumentApplication(SubmittedDocument.of(document, SCHEMA), SCHEMA, batch,
                () -> ResourceId.of(STORE + UUID.randomUUID())).apply();
    }

    private BlankNode create(final IRI type) {
        BlankNode activity = RDF_FACTORY.createBlankNode();
        BlankNode resource = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.CREATE));
        document.add(activity, arkres(ArkresVocabulary.OBJECT), resource);
        document.add(resource, VocabRdf.TYPE, type);
        return resource;
    }

    private BlankNode update(final IRI resource) {
        BlankNode activity = RDF_FACTORY.createBlankNode();
        BlankNode values = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.UPDATE));
        document.add(activity, arkres(ArkresVocabulary.OBJECT), resource);
        document.add(activity, arkres(ArkresVocabulary.SET), values);
        return values;
    }

    private void delete(final IRI resource) {
        BlankNode activity = RDF_FACTORY.createBlankNode();
        document.add(activity, VocabRdf.TYPE, arkres(ArkresVocabulary.DELETE));
        document.add(activity, arkres(ArkresVocabulary.OBJECT), resource);
    }

    private IRI storedTicket(final String code, final IRI state) {
        IRI resource = RDF_FACTORY.createIRI(STORE + code);
        batch.add(resource, VocabRdf.TYPE, TICKET);
        batch.add(resource, NAME, literal("stored " + code));
        if (state != null) {
            batch.add(resource, STATE, state);
        }
        return resource;
    }

    private static Set<RDFTerm> values(final ReadableGraph graph, final IRI subject, final IRI property) {
        return graph.stream(subject, property, null).map(Triple::getObject).collect(Collectors.toSet());
    }

    private static Graph shapes() {
        Graph shapes = RDF_FACTORY.createGraph();
        shapes.add(TICKET, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        shapes.add(TICKET, arkres(ArkresVocabulary.CODE_PREFIX), literal("TK-"));
        shapes.add(MEMO, VocabRdf.TYPE, arkres(ArkresVocabulary.RESOURCE_TYPE));
        IRI ticketShape = RDF_FACTORY.createIRI(SHAPES + "Ticket");
        shapes.add(ticketShape, shacl("targetClass"), TICKET);
        for (IRI field : List.of(NAME, NOTE, REPLACED_BY)) {
            IRI property = RDF_FACTORY.createIRI(SHAPES + "Ticket-" + field.getIRIString().substring(VOCAB.length()));
            shapes.add(ticketShape, shacl("property"), property);
            shapes.add(property, shacl("path"), field);
        }
        shapes.add(ticketShape, shacl("property"), STATE_SHAPE);
        shapes.add(STATE_SHAPE, shacl("path"), STATE);
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.INITIAL_STATUS), OPEN);
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.DELETABLE_IN), OPEN);
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.DELETABLE_IN), DONE);
        shapes.add(NEEDS_NOTE, VocabRdf.TYPE, shacl("NodeShape"));
        shapes.add(REPLACEMENT_DONE, VocabRdf.TYPE, shacl("NodeShape"));
        transition(shapes, OPEN, DONE, NEEDS_NOTE);
        transition(shapes, DONE, OPEN, null);
        transition(shapes, DONE, CLOSED, REPLACEMENT_DONE);
        IRI memoShape = RDF_FACTORY.createIRI(SHAPES + "Memo");
        shapes.add(memoShape, shacl("targetClass"), MEMO);
        for (IRI field : List.of(NAME, STATE)) {
            IRI property = RDF_FACTORY.createIRI(SHAPES + "Memo-" + field.getIRIString().substring(VOCAB.length()));
            shapes.add(memoShape, shacl("property"), property);
            shapes.add(property, shacl("path"), field);
        }
        return shapes;
    }

    private static void transition(final Graph shapes, final IRI from, final IRI to, final IRI condition) {
        BlankNode transition = RDF_FACTORY.createBlankNode();
        shapes.add(STATE_SHAPE, arkres(ArkresVocabulary.TRANSITION), transition);
        shapes.add(transition, arkres(ArkresVocabulary.FROM_STATUS), from);
        shapes.add(transition, arkres(ArkresVocabulary.TO_STATUS), to);
        if (condition != null) {
            shapes.add(transition, arkres(ArkresVocabulary.CONDITION), condition);
        }
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

    private static RDFTerm literal(final String text) {
        return RDF_FACTORY.createLiteral(text);
    }
}
