package de.hauschel.arknet.req.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.SetRequirementStatus;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Scaffold-level check that the adapter declares exactly the four requirement
 * tools and guards its in-port dependencies. Behaviour of the handlers is not
 * asserted here; the delegated in-ports are still scaffold stubs.
 */
class RequirementMcpToolsTest {

    private final Stub stub = new Stub();
    private final RequirementMcpTools adapter =
            new RequirementMcpTools(stub, stub, stub, stub, WorkspaceId.DEFAULT);

    @Test
    void declaresTheFourRequirementTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(4, names.size());
        assertTrue(names.containsAll(
                List.of("req_add", "req_list", "req_get", "req_set_status")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(null, stub, stub, stub, WorkspaceId.DEFAULT));
    }

    @Test
    void rejectsNullWorkspace() {
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(stub, stub, stub, stub, null));
    }

    /** Structural stub implementing the four driving in-ports. */
    private static final class Stub
            implements AddRequirement, ListRequirements, GetRequirement, SetRequirementStatus {

        @Override
        public Requirement add(WorkspaceId workspaceId, NewRequirement command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Requirement> list(WorkspaceId workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Requirement> get(WorkspaceId workspaceId, RequirementId id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Requirement setStatus(WorkspaceId workspaceId, RequirementId id, RequirementStatus status) {
            throw new UnsupportedOperationException();
        }
    }
}
