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

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.in.AddTerm;
import de.hauschel.arknet.ul.application.port.in.GetTerm;
import de.hauschel.arknet.ul.application.port.in.ListTerms;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Scaffold-level check that the adapter declares exactly the three term tools and
 * guards its in-port dependencies. Behaviour of the handlers is not asserted here.
 */
class UbiquitousLanguageMcpToolsTest {

    private final Stub stub = new Stub();
    private final UbiquitousLanguageMcpTools adapter =
            new UbiquitousLanguageMcpTools(stub, stub, stub, WorkspaceId.DEFAULT);

    @Test
    void declaresTheThreeTermTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(3, names.size());
        assertTrue(names.containsAll(List.of("term_add", "term_list", "term_get")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(null, stub, stub, WorkspaceId.DEFAULT));
    }

    @Test
    void rejectsNullWorkspace() {
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, stub, null));
    }

    @Test
    void addPassesThroughActorFacet() {
        adapter.add("Kunde", "Person, die eine Bestellung aufgibt.", "HUMAN", "Besteller");

        assertEquals(new ActorFacet(ActorKind.HUMAN, "Besteller"), stub.lastCommand.actorFacet());
    }

    @Test
    void addWithoutActorKindLeavesFacetNull() {
        adapter.add("Gutschrift", "def a", null, null);

        assertNull(stub.lastCommand.actorFacet());
    }

    @Test
    void addRejectsInvalidActorKind() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add("Gutschrift", "def a", "NOT_A_KIND", null));
    }

    /** Structural stub implementing the three driving in-ports. */
    private static final class Stub implements AddTerm, ListTerms, GetTerm {

        private NewTerm lastCommand;

        @Override
        public Term add(WorkspaceId workspaceId, NewTerm command) {
            lastCommand = command;
            return new Term(new TermId("TERM-1"), command.prefLabel(), command.definition(),
                    command.actorFacet());
        }

        @Override
        public List<Term> list(WorkspaceId workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Term> get(WorkspaceId workspaceId, TermId id) {
            throw new UnsupportedOperationException();
        }
    }
}
