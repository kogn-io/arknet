// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.actor.application.port.in.AddRole;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.DeleteRole;
import de.hauschel.arknet.actor.application.port.in.DescribeRoleDisplayFallback;
import de.hauschel.arknet.actor.application.port.in.GetRole;
import de.hauschel.arknet.actor.application.port.in.ListRoles;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.application.port.in.UpdateRole;
import de.hauschel.arknet.actor.domain.Role;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.actor.domain.RoleDisplayFallback;
import de.hauschel.arknet.actor.domain.RoleId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Scaffold-level check that the adapter declares exactly the five role tools and guards its
 * in-port dependencies, plus each tool's delegation to its in-port and rendering of the result -
 * mirrors {@code ActorMcpToolsTest}'s structure, plus the language/{@code displayLocale} coverage
 * {@code ConstraintMcpToolsTest} carries for its own multilingual fields.
 */
class RoleMcpToolsTest {

    private static final RoleId ID =
            new RoleId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));

    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Stands in for the registry lookup: every anchor this test sends resolves to a project without a default. */
    private static final ProjectResolver PROJECTS = anchor -> new ResolvedProject(PROJECT, null);

    /** A project whose configured default language is German, for the language/displayLocale coverage. */
    private static final ProjectResolver GERMAN_PROJECTS = anchor -> new ResolvedProject(PROJECT, "de");

    private final Stub stub = new Stub();
    private final RoleMcpTools adapter = new RoleMcpTools(stub, stub, stub, stub, stub, stub, PROJECTS);

    @Test
    void declaresTheFiveRoleTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(5, names.size());
        assertTrue(names.containsAll(
                List.of("role_add", "role_list", "role_get", "role_update", "role_delete")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new RoleMcpTools(null, stub, stub, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RoleMcpTools(stub, null, stub, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RoleMcpTools(stub, stub, null, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RoleMcpTools(stub, stub, stub, null, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RoleMcpTools(stub, stub, stub, stub, null, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RoleMcpTools(stub, stub, stub, stub, stub, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new RoleMcpTools(stub, stub, stub, stub, stub, stub, null));
    }

    @Test
    void deletePassesTheCodeThrough() {
        String rendered = adapter.delete(null, "ROLE-1", null);

        assertEquals(new RoleCode("ROLE-1"), stub.lastDeleteCode);
        assertEquals("Deleted: ROLE-1", rendered);
    }

    @Test
    void addPassesTheCommandThroughAndRendersTheCreatedRole() {
        String rendered = adapter.add(null, "Requirements Engineer", "Writes requirements.",
                List.of("ACTOR-1"), null, null);

        assertEquals("Requirements Engineer", stub.lastAddCommand.name());
        assertEquals("Writes requirements.", stub.lastAddCommand.description());
        assertEquals(List.of("ACTOR-1"), stub.lastAddCommand.filledByActorCodes());
        assertTrue(rendered.contains("ROLE-1"), rendered);
        assertTrue(rendered.contains("Requirements Engineer"), rendered);
    }

    /** A blank optional argument reaches the in-port as {@code null} - "absent", not "blank". */
    @Test
    void addPassesABlankDescriptionAsNull() {
        adapter.add(null, "Architect", "  ", null, null, null);

        assertNull(stub.lastAddCommand.description());
    }

    /** {@code role_add}'s {@code language} argument reaches the in-port, and the project's default too. */
    @Test
    void addPassesTheLanguageAndTheProjectDefaultThrough() {
        RoleMcpTools germanAdapter = new RoleMcpTools(stub, stub, stub, stub, stub, stub, GERMAN_PROJECTS);

        germanAdapter.add(null, "Requirements Engineer", null, null, "en", null);

        assertEquals("en", stub.lastAddCommand.language());
        assertEquals("de", stub.lastAddDefaultLanguage);
    }

    @Test
    void listRendersEveryRole() {
        stub.allRoles = List.of(
                roleDetail("ROLE-1", "Requirements Engineer", null),
                roleDetail("ROLE-2", "Architect", "Designs the solution."));

        String rendered = adapter.list(null, null, null);

        assertTrue(rendered.contains("ROLE-1"), rendered);
        assertTrue(rendered.contains("ROLE-2"), rendered);
    }

    /** The documented empty-project rendering: {@code role_list} must not return a blank string. */
    @Test
    void listRendersAPlaceholderWhenTheProjectHasNoRoles() {
        stub.allRoles = List.of();

        assertEquals("(no roles)", adapter.list(null, null, null));
    }

    /** {@code role_list} appends the {@code [fallback: ...]} tag for a role whose fallback map entry is set. */
    @Test
    void listAppendsTheFallbackTagWhenDescribed() {
        RoleDetail detail = roleDetail("ROLE-1", "Requirements Engineer", null);
        stub.allRoles = List.of(detail);
        stub.nextFallbacks = Map.of(detail.role().code(), new RoleDisplayFallback("en", null));

        String rendered = adapter.list(null, null, null);

        assertTrue(rendered.contains("[fallback: name=en]"), rendered);
    }

    @Test
    void getRendersTheRoleWhenFound() {
        stub.nextGetResult = Optional.of(roleDetail("ROLE-1", "Requirements Engineer", null));

        String rendered = adapter.get(null, "ROLE-1", null, null);

        assertEquals(new RoleCode("ROLE-1"), stub.lastGetCode);
        assertTrue(rendered.contains("ROLE-1"), rendered);
    }

    /** The documented not-found rendering: {@code role_get} must not throw for an unknown code. */
    @Test
    void getRendersANotFoundFallbackWhenAbsent() {
        stub.nextGetResult = Optional.empty();

        assertEquals("Role not found: ROLE-99", adapter.get(null, "ROLE-99", null, null));
    }

    /** {@code role_get}'s {@code displayLocale} argument reaches the in-port, overriding the project default. */
    @Test
    void getPassesAnExplicitDisplayLocaleOverridingTheProjectDefault() {
        RoleMcpTools germanAdapter = new RoleMcpTools(stub, stub, stub, stub, stub, stub, GERMAN_PROJECTS);
        stub.nextGetResult = Optional.of(roleDetail("ROLE-1", "Requirements Engineer", null));

        germanAdapter.get(null, "ROLE-1", "en", null);

        assertEquals("en", stub.lastGetDisplayLocale);
    }

    /** Omitting {@code displayLocale} falls back to the project's configured default language. */
    @Test
    void getFallsBackToTheProjectDefaultDisplayLocaleWhenOmitted() {
        RoleMcpTools germanAdapter = new RoleMcpTools(stub, stub, stub, stub, stub, stub, GERMAN_PROJECTS);
        stub.nextGetResult = Optional.of(roleDetail("ROLE-1", "Requirements Engineer", null));

        germanAdapter.get(null, "ROLE-1", null, null);

        assertEquals("de", stub.lastGetDisplayLocale);
    }

    @Test
    void updatePassesTheCorrectionThroughAndRendersTheResult() {
        stub.nextUpdateResult = roleDetail("ROLE-1", "Senior Requirements Engineer", "New description.");

        String rendered = adapter.update(null, "ROLE-1", "Senior Requirements Engineer", "New description.",
                List.of("ACTOR-2"), "en", null);

        assertEquals(new RoleCode("ROLE-1"), stub.lastUpdateCode);
        assertEquals("Senior Requirements Engineer", stub.lastUpdateName);
        assertEquals("New description.", stub.lastUpdateDescription);
        assertEquals(List.of("ACTOR-2"), stub.lastUpdateFilledBy);
        assertEquals("en", stub.lastUpdateLanguage);
        assertTrue(rendered.contains("ROLE-1"), rendered);
    }

    /** A blank optional argument reaches the in-port as {@code null} - "unchanged", not "blank". */
    @Test
    void updatePassesBlankArgumentsAsNull() {
        stub.nextUpdateResult = roleDetail("ROLE-1", "Requirements Engineer", null);

        adapter.update(null, "ROLE-1", "  ", "", null, "  ", null);

        assertNull(stub.lastUpdateName);
        assertNull(stub.lastUpdateDescription);
        assertNull(stub.lastUpdateLanguage);
    }

    /** {@code filledBy}'s tri-state: an omitted (not merely blank) list reaches the in-port as {@code null}. */
    @Test
    void updatePassesAnOmittedFilledByAsNullNotEmpty() {
        stub.nextUpdateResult = roleDetail("ROLE-1", "Requirements Engineer", null);

        adapter.update(null, "ROLE-1", null, null, null, null, null);

        assertNull(stub.lastUpdateFilledBy);
    }

    /** ... while an explicitly empty list reaches the in-port as empty, the "clear occupancy" signal. */
    @Test
    void updatePassesAnExplicitlyEmptyFilledByThrough() {
        stub.nextUpdateResult = roleDetail("ROLE-1", "Requirements Engineer", null);

        adapter.update(null, "ROLE-1", null, null, List.of(), null, null);

        assertEquals(List.of(), stub.lastUpdateFilledBy);
    }

    private static RoleDetail roleDetail(String code, String name, String description) {
        return new RoleDetail(new Role(ID, new RoleCode(code), name, description, List.of()), List.of());
    }

    /** Structural stub implementing the six driving in-ports. */
    private static final class Stub
            implements AddRole, ListRoles, DescribeRoleDisplayFallback, GetRole, UpdateRole, DeleteRole {

        private NewRole lastAddCommand;
        private String lastAddDefaultLanguage;
        private List<RoleDetail> allRoles = List.of();
        private String lastListDisplayLocale;
        private Map<RoleCode, RoleDisplayFallback> nextFallbacks = Map.of();
        private RoleCode lastGetCode;
        private String lastGetDisplayLocale;
        private Optional<RoleDetail> nextGetResult = Optional.empty();
        private RoleCode lastUpdateCode;
        private String lastUpdateName;
        private String lastUpdateDescription;
        private List<String> lastUpdateFilledBy;
        private String lastUpdateLanguage;
        private RoleDetail nextUpdateResult;
        private RoleCode lastDeleteCode;

        @Override
        public RoleDetail add(ProjectId projectId, NewRole command, String defaultLanguage) {
            lastAddCommand = command;
            lastAddDefaultLanguage = defaultLanguage;
            return new RoleDetail(new Role(ID, new RoleCode("ROLE-1"), command.name(), command.description(),
                    List.of()), List.of());
        }

        @Override
        public List<RoleDetail> list(ProjectId projectId, String displayLocale) {
            lastListDisplayLocale = displayLocale;
            return allRoles;
        }

        @Override
        public Map<RoleCode, RoleDisplayFallback> describe(ProjectId projectId, String displayLocale) {
            return nextFallbacks;
        }

        @Override
        public Optional<RoleDetail> get(ProjectId projectId, RoleCode code, String displayLocale) {
            lastGetCode = code;
            lastGetDisplayLocale = displayLocale;
            return nextGetResult;
        }

        @Override
        public RoleDetail update(ProjectId projectId, RoleCode code, String name, String description,
                List<String> filledByActorCodes, String language, String defaultLanguage) {
            lastUpdateCode = code;
            lastUpdateName = name;
            lastUpdateDescription = description;
            lastUpdateFilledBy = filledByActorCodes;
            lastUpdateLanguage = language;
            return nextUpdateResult;
        }

        @Override
        public void delete(ProjectId projectId, RoleCode code) {
            lastDeleteCode = code;
        }
    }
}
