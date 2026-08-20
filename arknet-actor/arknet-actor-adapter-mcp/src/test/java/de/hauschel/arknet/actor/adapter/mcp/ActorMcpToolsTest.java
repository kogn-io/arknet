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
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.actor.application.port.in.AddActor;
import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.application.port.in.DeleteActor;
import de.hauschel.arknet.actor.application.port.in.GetActor;
import de.hauschel.arknet.actor.application.port.in.ListActors;
import de.hauschel.arknet.actor.application.port.in.UpdateActor;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Scaffold-level check that the adapter declares exactly the five actor tools and guards its
 * in-port dependencies, plus each tool's delegation to its in-port and rendering of the result.
 */
class ActorMcpToolsTest {

    private static final ActorId ID =
            new ActorId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));

    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Stands in for the registry lookup: every anchor this test sends resolves to {@link #PROJECT}. */
    private static final ProjectResolver PROJECTS = anchor -> new ResolvedProject(PROJECT, null);

    private final Stub stub = new Stub();
    private final ActorMcpTools adapter = new ActorMcpTools(stub, stub, stub, stub, stub, PROJECTS);

    @Test
    void declaresTheFiveActorTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(5, names.size());
        assertTrue(names.containsAll(
                List.of("actor_add", "actor_list", "actor_get", "actor_update", "actor_delete")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class, () -> new ActorMcpTools(null, stub, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class, () -> new ActorMcpTools(stub, null, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class, () -> new ActorMcpTools(stub, stub, null, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class, () -> new ActorMcpTools(stub, stub, stub, null, stub, PROJECTS));
        assertThrows(NullPointerException.class, () -> new ActorMcpTools(stub, stub, stub, stub, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class, () -> new ActorMcpTools(stub, stub, stub, stub, stub, null));
    }

    /** {@code actor_delete} passes the parsed code straight through to the in-port. */
    @Test
    void deletePassesTheCodeThrough() {
        String rendered = adapter.delete(null, "ACTOR-1", null);

        assertEquals(new ActorCode("ACTOR-1"), stub.lastDeleteCode);
        assertEquals("Deleted: ACTOR-1", rendered);
    }

    @Test
    void addPassesTheCommandThroughAndRendersTheCreatedActor() {
        String rendered = adapter.add(null, "GROUP", "Fachbereich Vertrieb",
                "Der Fachbereich, der die Freigabe erteilt.", null);

        assertEquals(ActorType.GROUP, stub.lastAddCommand.type());
        assertEquals("Fachbereich Vertrieb", stub.lastAddCommand.name());
        assertEquals("Der Fachbereich, der die Freigabe erteilt.", stub.lastAddCommand.description());
        assertTrue(rendered.contains("ACTOR-1"), rendered);
        assertTrue(rendered.contains("Fachbereich Vertrieb"), rendered);
    }

    /** A blank optional argument reaches the in-port as {@code null} - "absent", not "blank". */
    @Test
    void addPassesABlankDescriptionAsNull() {
        adapter.add(null, "HUMAN", "Sachbearbeiter", "  ", null);

        assertNull(stub.lastAddCommand.description());
    }

    /** The type argument is case-insensitive, the same leniency {@code bc_link_context} grants. */
    @Test
    void addAcceptsALowercaseType() {
        adapter.add(null, " human ", "Sachbearbeiter", null, null);

        assertEquals(ActorType.HUMAN, stub.lastAddCommand.type());
    }

    /** An unknown type must be rejected with this tool's own didactic message, not swallowed. */
    @Test
    void addRejectsAnUnknownTypeString() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "DOES_NOT_EXIST", "Sachbearbeiter", null, null));

        assertTrue(ex.getMessage().contains("HUMAN"), ex.getMessage());
        assertTrue(ex.getMessage().contains("DOES_NOT_EXIST"), ex.getMessage());
    }

    @Test
    void listRendersEveryActor() {
        stub.allActors = List.of(
                actor("ACTOR-1", ActorType.HUMAN, "Sachbearbeiter", null),
                actor("ACTOR-2", ActorType.SYSTEM, "PaymentService", "Zahlt aus."));

        String rendered = adapter.list(null, null);

        assertTrue(rendered.contains("ACTOR-1"), rendered);
        assertTrue(rendered.contains("ACTOR-2"), rendered);
    }

    /** The documented empty-project rendering: {@code actor_list} must not return a blank string. */
    @Test
    void listRendersAPlaceholderWhenTheProjectHasNoActors() {
        stub.allActors = List.of();

        assertEquals("(no actors)", adapter.list(null, null));
    }

    @Test
    void getRendersTheActorWhenFound() {
        stub.nextGetResult = Optional.of(actor("ACTOR-1", ActorType.HUMAN, "Sachbearbeiter", null));

        String rendered = adapter.get(null, "ACTOR-1", null);

        assertEquals(new ActorCode("ACTOR-1"), stub.lastGetCode);
        assertTrue(rendered.contains("ACTOR-1"), rendered);
        assertFalse(rendered.endsWith(": null"), rendered);
    }

    /** The documented not-found rendering: {@code actor_get} must not throw for an unknown code. */
    @Test
    void getRendersANotFoundFallbackWhenAbsent() {
        stub.nextGetResult = Optional.empty();

        assertEquals("Actor not found: ACTOR-99", adapter.get(null, "ACTOR-99", null));
    }

    @Test
    void updatePassesTheCorrectionThroughAndRendersTheResult() {
        String rendered = adapter.update(null, "ACTOR-1", "Antragsbearbeiter", "Neue Beschreibung.", null);

        assertEquals(new ActorCode("ACTOR-1"), stub.lastUpdateCode);
        assertEquals("Antragsbearbeiter", stub.lastUpdateName);
        assertEquals("Neue Beschreibung.", stub.lastUpdateDescription);
        assertTrue(rendered.contains("ACTOR-1"), rendered);
    }

    /** A blank optional argument reaches the in-port as {@code null} - "unchanged", not "blank". */
    @Test
    void updatePassesBlankArgumentsAsNull() {
        adapter.update(null, "ACTOR-1", "  ", "", null);

        assertNull(stub.lastUpdateName);
        assertNull(stub.lastUpdateDescription);
    }

    private static Actor actor(String code, ActorType type, String name, String description) {
        return new Actor(ID, new ActorCode(code), type, name, description);
    }

    /** Structural stub implementing the five driving in-ports. */
    private static final class Stub implements AddActor, ListActors, GetActor, UpdateActor, DeleteActor {

        private NewActor lastAddCommand;
        private List<Actor> allActors = List.of();
        private ActorCode lastGetCode;
        private Optional<Actor> nextGetResult = Optional.empty();
        private ActorCode lastUpdateCode;
        private String lastUpdateName;
        private String lastUpdateDescription;
        private ActorCode lastDeleteCode;

        @Override
        public Actor add(ProjectId projectId, NewActor command) {
            lastAddCommand = command;
            return new Actor(ID, new ActorCode("ACTOR-1"), command.type(), command.name(),
                    command.description());
        }

        @Override
        public List<Actor> list(ProjectId projectId) {
            return allActors;
        }

        @Override
        public Optional<Actor> get(ProjectId projectId, ActorCode code) {
            lastGetCode = code;
            return nextGetResult;
        }

        @Override
        public Actor update(ProjectId projectId, ActorCode code, String name, String description) {
            lastUpdateCode = code;
            lastUpdateName = name;
            lastUpdateDescription = description;
            return new Actor(ID, code, ActorType.HUMAN, name == null ? "unchanged" : name, description);
        }

        @Override
        public void delete(ProjectId projectId, ActorCode code) {
            lastDeleteCode = code;
        }
    }
}
