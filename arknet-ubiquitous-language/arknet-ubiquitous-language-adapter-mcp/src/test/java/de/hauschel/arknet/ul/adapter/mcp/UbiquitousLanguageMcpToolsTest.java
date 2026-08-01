// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.ul.application.port.in.AddTerm;
import de.hauschel.arknet.ul.application.port.in.GetTerm;
import de.hauschel.arknet.ul.application.port.in.ListTerms;
import de.hauschel.arknet.ul.application.port.in.UpdateTerm;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Scaffold-level check that the adapter declares exactly the four term tools and
 * guards its in-port dependencies. Behaviour of the handlers is not asserted here.
 */
class UbiquitousLanguageMcpToolsTest {

    /** Fake resolver: every call routes to the same fixed project, ignoring the origin. */
    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Stands in for the registry lookup: every anchor this test sends resolves to {@link #PROJECT}. */
    private static final ProjectResolver PROJECTS = anchor -> PROJECT;

    private final Stub stub = new Stub();
    private final UbiquitousLanguageMcpTools adapter =
            new UbiquitousLanguageMcpTools(stub, stub, stub, stub, PROJECTS);

    @Test
    void declaresTheFourTermTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(4, names.size());
        assertTrue(names.containsAll(List.of("term_add", "term_list", "term_get", "term_update")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(null, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, stub, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, stub, stub, null));
    }

    @Test
    void addPassesThroughActorFacet() {
        String rendered =
                adapter.add(null, "Kunde", "Person, die eine Bestellung aufgibt.", "HUMAN", "Besteller", null);

        assertEquals(new ActorFacet(ActorKind.HUMAN, "Besteller"), stub.lastCommand.actorFacet());
        assertTrue(rendered.contains("[actor:HUMAN role=Besteller]"), rendered);
    }

    @Test
    void addWithoutActorKindLeavesFacetNull() {
        adapter.add(null, "Gutschrift", "def a", null, null, null);

        assertNull(stub.lastCommand.actorFacet());
    }

    @Test
    void addRejectsInvalidActorKind() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "Gutschrift", "def a", "NOT_A_KIND", null, null));
    }

    /** {@code term_update} passes every given field through to the in-port. */
    @Test
    void updatePassesAllGivenFieldsThroughToTheInPort() {
        String rendered = adapter.update(null, "TERM-1", "Erstattung", "Neue Definition", "HUMAN", "Kunde", null);

        assertEquals(new TermCode("TERM-1"), stub.lastUpdatedTerm);
        assertEquals("Erstattung", stub.lastUpdatePrefLabel);
        assertEquals("Neue Definition", stub.lastUpdateDefinition);
        assertEquals(new ActorFacet(ActorKind.HUMAN, "Kunde"), stub.lastUpdateActorFacet);
        assertTrue(rendered.contains("Erstattung"), rendered);
        assertTrue(rendered.contains("[actor:HUMAN role=Kunde]"), rendered);
    }

    /**
     * An omitted field must reach {@link UpdateTerm} as {@code null} - so the port (not this
     * adapter) decides that "unchanged" means "leave the existing value" rather than the adapter
     * silently substituting a blank or empty value.
     */
    @Test
    void updateWithOmittedFieldsPassesNullThroughForEachOfThem() {
        adapter.update(null, "TERM-1", null, null, null, null, null);

        assertEquals(new TermCode("TERM-1"), stub.lastUpdatedTerm);
        assertNull(stub.lastUpdatePrefLabel);
        assertNull(stub.lastUpdateDefinition);
        assertNull(stub.lastUpdateActorFacet);
    }

    @Test
    void updateRejectsInvalidActorKind() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.update(null, "TERM-1", null, null, "NOT_A_KIND", null, null));
    }

    @Test
    void listRendersNoTermsFallbackWhenEmpty() {
        stub.termsForList = List.of();

        String rendered = adapter.list(null, null);

        assertEquals("(no terms)", rendered);
    }

    @Test
    void listJoinsMultipleTermsWithNewlines() {
        Term first = new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/1")),
                new TermCode("TERM-1"), "Gutschrift", "def a", null);
        Term second = new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/2")),
                new TermCode("TERM-2"), "Bestellung", "def b", null);
        stub.termsForList = List.of(first, second);

        String rendered = adapter.list(null, null);

        assertEquals("TERM-1 Gutschrift - def a\nTERM-2 Bestellung - def b", rendered);
    }

    @Test
    void getRendersTheTermWhenFound() {
        stub.termForGet = Optional.of(new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/1")),
                new TermCode("TERM-1"), "Gutschrift", "def a", null));

        String rendered = adapter.get(null, "TERM-1", null);

        assertEquals("TERM-1 Gutschrift - def a", rendered);
    }

    @Test
    void getRendersNotFoundMessageWhenAbsent() {
        stub.termForGet = Optional.empty();

        String rendered = adapter.get(null, "TERM-99", null);

        assertEquals("Term not found: TERM-99", rendered);
    }

    /** Structural stub implementing the four driving in-ports. */
    private static final class Stub implements AddTerm, ListTerms, GetTerm, UpdateTerm {

        private NewTerm lastCommand;
        private TermCode lastUpdatedTerm;
        private String lastUpdatePrefLabel;
        private String lastUpdateDefinition;
        private ActorFacet lastUpdateActorFacet;
        private List<Term> termsForList = List.of();
        private Optional<Term> termForGet = Optional.empty();

        @Override
        public Term add(ProjectId projectId, NewTerm command) {
            lastCommand = command;
            return new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/stub")),
                    new TermCode("TERM-1"), command.prefLabel(), command.definition(),
                    command.actorFacet());
        }

        @Override
        public List<Term> list(ProjectId projectId) {
            return termsForList;
        }

        @Override
        public Optional<Term> get(ProjectId projectId, TermCode code) {
            return termForGet;
        }

        @Override
        public Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
                ActorFacet actorFacet) {
            lastUpdatedTerm = code;
            lastUpdatePrefLabel = prefLabel;
            lastUpdateDefinition = definition;
            lastUpdateActorFacet = actorFacet;
            return new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/stub")), code,
                    prefLabel != null ? prefLabel : "p", definition != null ? definition : "d", actorFacet);
        }
    }
}
