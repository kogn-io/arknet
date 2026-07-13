package de.hauschel.arknet.req.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.SetRequirementStatus;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.WorkspaceId;

/**
 * Scaffold-level check that the adapter publishes exactly the four requirement
 * tools and guards its in-port dependencies. Behaviour of the handlers is not
 * asserted here; the delegated in-ports are still scaffold stubs.
 */
class RequirementMcpToolsTest {

    private final Stub stub = new Stub();
    private final RequirementMcpTools adapter =
            new RequirementMcpTools(stub, stub, stub, stub);

    @Test
    void publishesTheFourRequirementTools() {
        List<String> names = adapter.tools().stream()
                .map(spec -> spec.tool().name())
                .toList();

        assertEquals(4, names.size());
        assertTrue(names.containsAll(
                List.of("req_add", "req_list", "req_get", "req_set_status")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(null, stub, stub, stub));
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
