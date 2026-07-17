package de.hauschel.arknet.req.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.LinkTerm;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.SetRequirementStatus;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Scaffold-level check that the adapter declares exactly the five requirement
 * tools and guards its in-port dependencies. Behaviour of the handlers is not
 * asserted here beyond the pass-through of {@code req_link_term}'s arguments;
 * the delegated in-ports are still scaffold stubs.
 */
class RequirementMcpToolsTest {

    private static final RequirementId ID =
            new RequirementId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));

    private final Stub stub = new Stub();
    private final RequirementMcpTools adapter =
            new RequirementMcpTools(stub, stub, stub, stub, stub, WorkspaceId.DEFAULT);

    @Test
    void declaresTheFiveRequirementTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(5, names.size());
        assertTrue(names.containsAll(
                List.of("req_add", "req_list", "req_get", "req_set_status", "req_link_term")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(null, stub, stub, stub, stub, WorkspaceId.DEFAULT));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(stub, stub, stub, stub, null, WorkspaceId.DEFAULT));
    }

    @Test
    void rejectsNullWorkspace() {
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(stub, stub, stub, stub, stub, null));
    }

    @Test
    void linkTermPassesTheRawTermCodeThroughToTheInPort() {
        String rendered = adapter.linkTerm("FR-1", "TERM-1");

        assertEquals(new RequirementCode("FR-1"), stub.lastLinkedRequirement);
        assertEquals("TERM-1", stub.lastLinkedTermCode);
        assertTrue(rendered.contains("[terms: https://w3id.org/arknet/id/TERM-1]"), rendered);
    }

    /** Structural stub implementing the five driving in-ports. */
    private static final class Stub
            implements AddRequirement, ListRequirements, GetRequirement, SetRequirementStatus, LinkTerm {

        private RequirementCode lastLinkedRequirement;
        private String lastLinkedTermCode;

        @Override
        public Requirement add(WorkspaceId workspaceId, NewRequirement command) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<Requirement> list(WorkspaceId workspaceId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<Requirement> get(WorkspaceId workspaceId, RequirementCode code) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Requirement setStatus(WorkspaceId workspaceId, RequirementCode code, RequirementStatus status) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Requirement linkTerm(WorkspaceId workspaceId, RequirementCode code, String termCode) {
            lastLinkedRequirement = code;
            lastLinkedTermCode = termCode;
            TermRef term = new TermRef(ResourceId.of("https://w3id.org/arknet/id/" + termCode));
            return new Requirement(ID, code, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                    Priority.MUST_HAVE, null, null, List.of(term));
        }
    }
}
