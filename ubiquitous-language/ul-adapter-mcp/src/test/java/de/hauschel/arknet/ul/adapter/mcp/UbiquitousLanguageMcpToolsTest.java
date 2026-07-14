package de.hauschel.arknet.ul.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    /** Structural stub implementing the three driving in-ports. */
    private static final class Stub implements AddTerm, ListTerms, GetTerm {

        @Override
        public Term add(WorkspaceId workspaceId, NewTerm command) {
            throw new UnsupportedOperationException();
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
