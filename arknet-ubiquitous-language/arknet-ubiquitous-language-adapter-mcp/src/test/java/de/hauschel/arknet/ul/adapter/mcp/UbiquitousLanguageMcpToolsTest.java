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
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.kernel.WorkspaceResolver;
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

    /** Fake resolver: every call routes to the same fixed workspace, ignoring the origin. */
    private static final WorkspaceResolver WORKSPACES = originDir -> WorkspaceId.DEFAULT;

    private final Stub stub = new Stub();
    private final UbiquitousLanguageMcpTools adapter =
            new UbiquitousLanguageMcpTools(stub, stub, stub, stub, WORKSPACES);

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
                () -> new UbiquitousLanguageMcpTools(null, stub, stub, stub, WORKSPACES));
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, stub, null, WORKSPACES));
    }

    @Test
    void rejectsNullWorkspaceResolver() {
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, stub, stub, null));
    }

    @Test
    void addPassesThroughActorFacet() {
        adapter.add(null, "Kunde", "Person, die eine Bestellung aufgibt.", "HUMAN", "Besteller");

        assertEquals(new ActorFacet(ActorKind.HUMAN, "Besteller"), stub.lastCommand.actorFacet());
    }

    @Test
    void addWithoutActorKindLeavesFacetNull() {
        adapter.add(null, "Gutschrift", "def a", null, null);

        assertNull(stub.lastCommand.actorFacet());
    }

    @Test
    void addRejectsInvalidActorKind() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "Gutschrift", "def a", "NOT_A_KIND", null));
    }

    /** Issue #163: {@code term_update} passes every given field through to the in-port. */
    @Test
    void updatePassesAllGivenFieldsThroughToTheInPort() {
        String rendered = adapter.update(null, "TERM-1", "Erstattung", "Neue Definition", "HUMAN", "Kunde");

        assertEquals(new TermCode("TERM-1"), stub.lastUpdatedTerm);
        assertEquals("Erstattung", stub.lastUpdatePrefLabel);
        assertEquals("Neue Definition", stub.lastUpdateDefinition);
        assertEquals(new ActorFacet(ActorKind.HUMAN, "Kunde"), stub.lastUpdateActorFacet);
        assertTrue(rendered.contains("Erstattung"), rendered);
    }

    /**
     * An omitted field must reach {@link UpdateTerm} as {@code null} - so the port (not this
     * adapter) decides that "unchanged" means "leave the existing value" rather than the adapter
     * silently substituting a blank or empty value.
     */
    @Test
    void updateWithOmittedFieldsPassesNullThroughForEachOfThem() {
        adapter.update(null, "TERM-1", null, null, null, null);

        assertEquals(new TermCode("TERM-1"), stub.lastUpdatedTerm);
        assertNull(stub.lastUpdatePrefLabel);
        assertNull(stub.lastUpdateDefinition);
        assertNull(stub.lastUpdateActorFacet);
    }

    @Test
    void updateRejectsInvalidActorKind() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.update(null, "TERM-1", null, null, "NOT_A_KIND", null));
    }

    /** Structural stub implementing the four driving in-ports. */
    private static final class Stub implements AddTerm, ListTerms, GetTerm, UpdateTerm {

        private NewTerm lastCommand;
        private TermCode lastUpdatedTerm;
        private String lastUpdatePrefLabel;
        private String lastUpdateDefinition;
        private ActorFacet lastUpdateActorFacet;

        @Override
        public Term add(WorkspaceId workspaceId, NewTerm command) {
            lastCommand = command;
            return Term.of(new TermId(ResourceId.of("https://w3id.org/arknet/id/stub")),
                    new TermCode("TERM-1"), command.prefLabel(), command.definition(),
                    command.actorFacet());
        }

        @Override
        public List<Term> list(WorkspaceId workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Term> get(WorkspaceId workspaceId, TermCode code) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Term update(WorkspaceId workspaceId, TermCode code, String prefLabel, String definition,
                ActorFacet actorFacet) {
            lastUpdatedTerm = code;
            lastUpdatePrefLabel = prefLabel;
            lastUpdateDefinition = definition;
            lastUpdateActorFacet = actorFacet;
            return Term.of(new TermId(ResourceId.of("https://w3id.org/arknet/id/stub")), code,
                    prefLabel != null ? prefLabel : "p", definition != null ? definition : "d", actorFacet);
        }
    }
}
