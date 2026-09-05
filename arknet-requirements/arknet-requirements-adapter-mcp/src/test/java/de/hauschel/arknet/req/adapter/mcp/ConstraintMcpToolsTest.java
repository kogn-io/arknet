// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import de.hauschel.arknet.req.application.port.in.DeleteConstraint;
import de.hauschel.arknet.req.application.port.in.GetConstraint;
import de.hauschel.arknet.req.application.port.in.ListConstraints;
import de.hauschel.arknet.req.application.port.in.UpdateConstraint;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintId;
import de.hauschel.arknet.req.domain.ConstraintType;

/**
 * Scaffold-level check that the adapter declares exactly the five constraint tools and guards
 * its in-port dependencies, plus each tool's delegation to its in-port and rendering of the
 * result - mirrors {@code RequirementMcpToolsTest} in shape.
 */
class ConstraintMcpToolsTest {

    private static final ConstraintId ID =
            new ConstraintId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));

    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Stands in for the registry lookup: every anchor this test sends resolves to {@link #PROJECT}. */
    private static final ProjectResolver PROJECTS = anchor -> new ResolvedProject(PROJECT, null);

    /** The same registry lookup for a project that does have a configured default language. */
    private static final ProjectResolver PROJECTS_WITH_DEFAULT_DE =
            anchor -> new ResolvedProject(PROJECT, "de");

    private final Stub stub = new Stub();
    private final ConstraintMcpTools adapter = new ConstraintMcpTools(stub, stub, stub, stub, stub, PROJECTS);

    @Test
    void declaresTheFiveConstraintTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(5, names.size());
        assertTrue(names.containsAll(List.of(
                "constraint_add", "constraint_list", "constraint_get", "constraint_update", "constraint_delete")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new ConstraintMcpTools(null, stub, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new ConstraintMcpTools(stub, null, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new ConstraintMcpTools(stub, stub, null, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new ConstraintMcpTools(stub, stub, stub, null, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new ConstraintMcpTools(stub, stub, stub, stub, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class, () -> new ConstraintMcpTools(stub, stub, stub, stub, stub, null));
    }

    /** {@code constraint_add} passes title/statement/type through and renders the created constraint. */
    @Test
    void addPassesTheCommandThroughAndRendersTheCreatedConstraint() {
        String rendered = adapter.add(null, "Must run on the JVM", "The system shall be JVM-based.",
                "TECHNICAL", "en", null);

        assertEquals("Must run on the JVM", stub.lastAddCommand.title());
        assertEquals("The system shall be JVM-based.", stub.lastAddCommand.statement());
        assertEquals(ConstraintType.TECHNICAL, stub.lastAddCommand.type());
        assertEquals("en", stub.lastAddCommand.language());
        assertTrue(rendered.contains("TCON-1"), rendered);
        assertTrue(rendered.contains("Must run on the JVM"), rendered);
    }

    /**
     * An omitted {@code language} reaches the in-port as {@code null}, and the project's own
     * configured default is passed alongside it - the in-port, not this adapter, decides the
     * fallback (issue #313).
     */
    @Test
    void addPassesABlankLanguageAsNullAndForwardsTheProjectDefault() {
        ConstraintMcpTools withDefault = new ConstraintMcpTools(stub, stub, stub, stub, stub, PROJECTS_WITH_DEFAULT_DE);

        withDefault.add(null, "t", "s", "TECHNICAL", "  ", null);

        assertNull(stub.lastAddCommand.language());
        assertEquals("de", stub.lastAddDefaultLanguage);
    }

    /** An unknown {@code type} string must reject with the JDK's own enum failure, not be swallowed. */
    @Test
    void addRejectsAnUnknownTypeString() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "t", "s", "DOES_NOT_EXIST", "en", null));
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

    /**
     * {@code constraint_list} has no {@code displayLocale} argument of its own, so it reads every
     * constraint under the resolved project's configured default language (mirrors {@code
     * req_list}, issue #281).
     */
    @Test
    void listReadsUnderTheProjectsDefaultLanguage() {
        ConstraintMcpTools withDefault = new ConstraintMcpTools(stub, stub, stub, stub, stub, PROJECTS_WITH_DEFAULT_DE);

        withDefault.list(null, null);

        assertEquals("de", stub.lastListDisplayLocale);
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

        String rendered = adapter.get(null, "TCON-1", null, null);

        assertEquals(new ConstraintCode("TCON-1"), stub.lastGetCode);
        assertTrue(rendered.contains("TCON-1"), rendered);
    }

    /** An explicit {@code displayLocale} wins over the project's configured default. */
    @Test
    void getPrefersAnExplicitDisplayLocaleOverTheProjectDefault() {
        ConstraintMcpTools withDefault = new ConstraintMcpTools(stub, stub, stub, stub, stub, PROJECTS_WITH_DEFAULT_DE);
        stub.nextGetResult = Optional.of(constraint("TCON-1", ConstraintType.TECHNICAL, "a", "statement a"));

        withDefault.get(null, "TCON-1", "en", null);

        assertEquals("en", stub.lastGetDisplayLocale);
    }

    /** ... and an omitted one falls back to it. */
    @Test
    void getFallsBackToTheProjectDefaultDisplayLocale() {
        ConstraintMcpTools withDefault = new ConstraintMcpTools(stub, stub, stub, stub, stub, PROJECTS_WITH_DEFAULT_DE);
        stub.nextGetResult = Optional.of(constraint("TCON-1", ConstraintType.TECHNICAL, "a", "statement a"));

        withDefault.get(null, "TCON-1", null, null);

        assertEquals("de", stub.lastGetDisplayLocale);
    }

    /** The documented not-found rendering: {@code constraint_get} must not throw for an unknown code. */
    @Test
    void getRendersANotFoundFallbackWhenAbsent() {
        stub.nextGetResult = Optional.empty();

        String rendered = adapter.get(null, "TCON-99", null, null);

        assertEquals("Constraint not found: TCON-99", rendered);
    }

    /** {@code constraint_update} passes its text/language arguments through and renders the result. */
    @Test
    void updatePassesTheCorrectionThroughAndRendersTheResult() {
        String rendered = adapter.update(null, "TCON-1", "JVM only", "Must run on the JVM.", "en", null);

        assertEquals(new ConstraintCode("TCON-1"), stub.lastUpdateCode);
        assertEquals("JVM only", stub.lastUpdateTitle);
        assertEquals("Must run on the JVM.", stub.lastUpdateStatement);
        assertEquals("en", stub.lastUpdateLanguage);
        assertTrue(rendered.contains("TCON-1"), rendered);
    }

    /** A blank optional argument reaches the in-port as {@code null} - "unchanged", not "blank". */
    @Test
    void updatePassesBlankArgumentsAsNull() {
        adapter.update(null, "TCON-1", "  ", null, "", null);

        assertNull(stub.lastUpdateTitle);
        assertNull(stub.lastUpdateStatement);
        assertNull(stub.lastUpdateLanguage);
    }

    /** {@code constraint_delete} passes the parsed code straight through to the in-port. */
    @Test
    void deletePassesTheCodeThrough() {
        String rendered = adapter.delete(null, "TCON-1", null);

        assertEquals(new ConstraintCode("TCON-1"), stub.lastDeleteCode);
        assertEquals("Deleted: TCON-1", rendered);
    }

    private static Constraint constraint(String code, ConstraintType type, String title, String statement) {
        return new Constraint(ID, new ConstraintCode(code), title, statement, type);
    }

    /** Structural stub implementing the five driving in-ports. */
    private static final class Stub
            implements AddConstraint, ListConstraints, GetConstraint, UpdateConstraint, DeleteConstraint {

        private NewConstraint lastAddCommand;
        private String lastAddDefaultLanguage;
        private List<Constraint> allConstraints = List.of();
        private String lastListDisplayLocale;
        private ConstraintCode lastGetCode;
        private String lastGetDisplayLocale;
        private Optional<Constraint> nextGetResult = Optional.empty();
        private ConstraintCode lastUpdateCode;
        private String lastUpdateTitle;
        private String lastUpdateStatement;
        private String lastUpdateLanguage;
        private ConstraintCode lastDeleteCode;

        @Override
        public Constraint add(ProjectId projectId, NewConstraint command, String defaultLanguage) {
            lastAddCommand = command;
            lastAddDefaultLanguage = defaultLanguage;
            return new Constraint(ID, new ConstraintCode(command.type().idPrefix() + "-1"),
                    command.title(), command.statement(), command.type());
        }

        @Override
        public List<Constraint> list(ProjectId projectId, String displayLocale) {
            lastListDisplayLocale = displayLocale;
            return allConstraints;
        }

        @Override
        public Optional<Constraint> get(ProjectId projectId, ConstraintCode code, String displayLocale) {
            lastGetCode = code;
            lastGetDisplayLocale = displayLocale;
            return nextGetResult;
        }

        @Override
        public Constraint update(ProjectId projectId, ConstraintCode code, String title, String statement,
                String language, String defaultLanguage) {
            lastUpdateCode = code;
            lastUpdateTitle = title;
            lastUpdateStatement = statement;
            lastUpdateLanguage = language;
            return new Constraint(ID, code, title == null ? "unchanged" : title,
                    statement == null ? "unchanged" : statement, ConstraintType.TECHNICAL);
        }

        @Override
        public void delete(ProjectId projectId, ConstraintCode code) {
            lastDeleteCode = code;
        }
    }
}
