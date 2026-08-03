// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.req.application.port.in.AddConstraint;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.GetConstraint;
import de.hauschel.arknet.req.application.port.in.ListConstraints;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintType;

/**
 * Scaffold-level check that the adapter declares exactly the three constraint tools and guards
 * its in-port dependencies, plus each tool's delegation to its in-port and rendering of the
 * result - mirrors {@code RequirementMcpToolsTest} in shape.
 */
class ConstraintMcpToolsTest {

    private static final ConstraintId ID =
            new ConstraintId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));

    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Stands in for the registry lookup: every anchor this test sends resolves to {@link #PROJECT}. */
    private static final ProjectResolver PROJECTS = anchor -> new ResolvedProject(PROJECT, null);

    private final Stub stub = new Stub();
    private final ConstraintMcpTools adapter = new ConstraintMcpTools(stub, stub, stub, PROJECTS);

    @Test
    void declaresTheThreeConstraintTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(3, names.size());
        assertTrue(names.containsAll(List.of("constraint_add", "constraint_list", "constraint_get")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class, () -> new ConstraintMcpTools(null, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class, () -> new ConstraintMcpTools(stub, null, stub, PROJECTS));
        assertThrows(NullPointerException.class, () -> new ConstraintMcpTools(stub, stub, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class, () -> new ConstraintMcpTools(stub, stub, stub, null));
    }

    /** {@code constraint_add} passes title/statement/type through and renders the created constraint. */
    @Test
    void addPassesTheCommandThroughAndRendersTheCreatedConstraint() {
        String rendered = adapter.add(null, "Must run on the JVM", "The system shall be JVM-based.",
                "TECHNICAL", null);

        assertEquals("Must run on the JVM", stub.lastAddCommand.title());
        assertEquals("The system shall be JVM-based.", stub.lastAddCommand.statement());
        assertEquals(ConstraintType.TECHNICAL, stub.lastAddCommand.type());
        assertTrue(rendered.contains("TCON-1"), rendered);
        assertTrue(rendered.contains("Must run on the JVM"), rendered);
    }

    /** An unknown {@code type} string must reject with the JDK's own enum failure, not be swallowed. */
    @Test
    void addRejectsAnUnknownTypeString() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "t", "s", "DOES_NOT_EXIST", null));
    }

    /** {@code constraint_list} renders every constraint when the project has some. */
    @Test
    void listRendersEveryConstraint() {
        stub.allConstraints = List.of(
                constraint("TCON-1", ConstraintType.TECHNICAL, "a", "statement a"),
                constraint("BCON-1", ConstraintType.BUSINESS, "b", "statement b"));

        String rendered = adapter.list(null, null);

        assertTrue(rendered.contains("TCON-1"), rendered);
        assertTrue(rendered.contains("BCON-1"), rendered);
    }

    /** The documented empty-project rendering: {@code constraint_list} must not return a blank string. */
    @Test
    void listRendersAPlaceholderWhenTheProjectHasNoConstraints() {
        stub.allConstraints = List.of();

        String rendered = adapter.list(null, null);

        assertEquals("(no constraints)", rendered);
    }

    /** {@code constraint_get} renders the constraint when found. */
    @Test
    void getRendersTheConstraintWhenFound() {
        stub.nextGetResult = Optional.of(constraint("TCON-1", ConstraintType.TECHNICAL, "a", "statement a"));

        String rendered = adapter.get(null, "TCON-1", null);

        assertEquals(new ConstraintCode("TCON-1"), stub.lastGetCode);
        assertTrue(rendered.contains("TCON-1"), rendered);
    }

    /** The documented not-found rendering: {@code constraint_get} must not throw for an unknown code. */
    @Test
    void getRendersANotFoundFallbackWhenAbsent() {
        stub.nextGetResult = Optional.empty();

        String rendered = adapter.get(null, "TCON-99", null);

        assertEquals("Constraint not found: TCON-99", rendered);
    }

    private static Constraint constraint(String code, ConstraintType type, String title, String statement) {
        return new Constraint(ID, new ConstraintCode(code), title, statement, type);
    }

    /** Structural stub implementing the three driving in-ports. */
    private static final class Stub implements AddConstraint, ListConstraints, GetConstraint {

        private NewConstraint lastAddCommand;
        private List<Constraint> allConstraints = List.of();
        private ConstraintCode lastGetCode;
        private Optional<Constraint> nextGetResult = Optional.empty();

        @Override
        public Constraint add(ProjectId projectId, NewConstraint command) {
            lastAddCommand = command;
            return new Constraint(ID, new ConstraintCode(command.type().idPrefix() + "-1"),
                    command.title(), command.statement(), command.type());
        }

        @Override
        public List<Constraint> list(ProjectId projectId) {
            return allConstraints;
        }

        @Override
        public Optional<Constraint> get(ProjectId projectId, ConstraintCode code) {
            lastGetCode = code;
            return nextGetResult;
        }
    }
}
