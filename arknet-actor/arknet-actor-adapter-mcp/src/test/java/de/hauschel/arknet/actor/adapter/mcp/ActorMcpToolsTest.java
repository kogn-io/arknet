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
import java.util.Set;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.actor.application.port.in.AddActor;
import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.application.port.in.DeleteActor;
import de.hauschel.arknet.actor.application.port.in.DescribeActorDisplayFallback;
import de.hauschel.arknet.actor.application.port.in.GetActor;
import de.hauschel.arknet.actor.application.port.in.ListActors;
import de.hauschel.arknet.actor.application.port.in.UpdateActor;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorDisplayFallback;
import de.hauschel.arknet.actor.domain.ActorId;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.FieldLanguageLookup;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.StaleTranslationHint;

/**
 * Scaffold-level check that the adapter declares exactly the five actor tools and guards its
 * in-port dependencies, plus each tool's delegation to its in-port and rendering of the result -
 * mirrors {@code RoleMcpToolsTest}'s structure, including the language/{@code displayLocale} and
 * stale-translation coverage (kogn-io/arknet#520).
 */
class ActorMcpToolsTest {

    /**
     * The stale-translation signal over an empty store (kogn-io/arknet#474/kogn-io/arknet#520):
     * every test that sets up no language inventory keeps the answer it always had, because a
     * field that carries no other language has nothing to report.
     */
    private static final StaleTranslationHint NO_TRANSLATIONS = hints(Map.of());

    /** A lookup answering the same field-to-tags inventory for every resource. */
    private static StaleTranslationHint hints(Map<String, Set<String>> byField) {
        return new StaleTranslationHint(new FieldLanguageLookup() {
            @Override
            public Map<String, Set<String>> ofResource(ProjectId projectId, String code) {
                return byField;
            }

            @Override
            public Map<String, Set<String>> ofProjectRegistration(String projectLabel) {
                return byField;
            }
        });
    }

    private static final ActorId ID =
            new ActorId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));

    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Stands in for the registry lookup: every anchor this test sends resolves to a project without a default. */
    private static final ProjectResolver PROJECTS = anchor -> new ResolvedProject(PROJECT, null);

    /** A project whose configured default language is German, for the language/displayLocale coverage. */
    private static final ProjectResolver GERMAN_PROJECTS = anchor -> new ResolvedProject(PROJECT, "de");

    private final Stub stub = new Stub();
    private final ActorMcpTools adapter = new ActorMcpTools(stub, stub, stub, stub, stub, stub, PROJECTS,
            NO_TRANSLATIONS);

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
        assertThrows(NullPointerException.class,
                () -> new ActorMcpTools(null, stub, stub, stub, stub, stub, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class,
                () -> new ActorMcpTools(stub, null, stub, stub, stub, stub, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class,
                () -> new ActorMcpTools(stub, stub, null, stub, stub, stub, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class,
                () -> new ActorMcpTools(stub, stub, stub, null, stub, stub, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class,
                () -> new ActorMcpTools(stub, stub, stub, stub, null, stub, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class,
                () -> new ActorMcpTools(stub, stub, stub, stub, stub, null, PROJECTS, NO_TRANSLATIONS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new ActorMcpTools(stub, stub, stub, stub, stub, stub, null, NO_TRANSLATIONS));
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
                "Der Fachbereich, der die Freigabe erteilt.", null, null);

        assertEquals(ActorType.GROUP, stub.lastAddCommand.type());
        assertEquals("Fachbereich Vertrieb", stub.lastAddCommand.name());
        assertEquals("Der Fachbereich, der die Freigabe erteilt.", stub.lastAddCommand.description());
        assertTrue(rendered.contains("ACTOR-1"), rendered);
        assertTrue(rendered.contains("Fachbereich Vertrieb"), rendered);
    }

    /** A blank optional argument reaches the in-port as {@code null} - "absent", not "blank". */
    @Test
    void addPassesABlankDescriptionAsNull() {
        adapter.add(null, "HUMAN", "Sachbearbeiter", "  ", null, null);

        assertNull(stub.lastAddCommand.description());
    }

    /** The type argument is case-insensitive, the same leniency {@code bc_link_context} grants. */
    @Test
    void addAcceptsALowercaseType() {
        adapter.add(null, " human ", "Sachbearbeiter", null, null, null);

        assertEquals(ActorType.HUMAN, stub.lastAddCommand.type());
    }

    /** An unknown type must be rejected with this tool's own didactic message, not swallowed. */
    @Test
    void addRejectsAnUnknownTypeString() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "DOES_NOT_EXIST", "Sachbearbeiter", null, null, null));

        assertTrue(ex.getMessage().contains("HUMAN"), ex.getMessage());
        assertTrue(ex.getMessage().contains("DOES_NOT_EXIST"), ex.getMessage());
    }

    /** {@code actor_add}'s {@code language} argument reaches the in-port, and the project's default too. */
    @Test
    void addPassesTheLanguageAndTheProjectDefaultThrough() {
        ActorMcpTools germanAdapter = new ActorMcpTools(stub, stub, stub, stub, stub, stub, GERMAN_PROJECTS,
                NO_TRANSLATIONS);

        germanAdapter.add(null, "HUMAN", "Sachbearbeiter", null, "en", null);

        assertEquals("en", stub.lastAddCommand.language());
        assertEquals("de", stub.lastAddDefaultLanguage);
    }

    @Test
    void listRendersEveryActor() {
        stub.allActors = List.of(
                actor("ACTOR-1", ActorType.HUMAN, "Sachbearbeiter", null),
                actor("ACTOR-2", ActorType.SYSTEM, "PaymentService", "Zahlt aus."));

        String rendered = adapter.list(null, null, null);

        assertTrue(rendered.contains("ACTOR-1"), rendered);
        assertTrue(rendered.contains("ACTOR-2"), rendered);
    }

    /** The documented empty-project rendering: {@code actor_list} must not return a blank string. */
    @Test
    void listRendersAPlaceholderWhenTheProjectHasNoActors() {
        stub.allActors = List.of();

        assertEquals("(no actors)", adapter.list(null, null, null));
    }

    /** {@code actor_list} appends the {@code [fallback: ...]} tag for an actor whose fallback map entry is set. */
    @Test
    void listAppendsTheFallbackTagWhenDescribed() {
        Actor stored = actor("ACTOR-1", ActorType.HUMAN, "Sachbearbeiter", null);
        stub.allActors = List.of(stored);
        stub.nextFallbacks = Map.of(stored.code(), new ActorDisplayFallback("en", null));

        String rendered = adapter.list(null, null, null);

        assertTrue(rendered.contains("[fallback: name=en]"), rendered);
    }

    @Test
    void getRendersTheActorWhenFound() {
        stub.nextGetResult = Optional.of(actor("ACTOR-1", ActorType.HUMAN, "Sachbearbeiter", null));

        String rendered = adapter.get(null, "ACTOR-1", null, null);

        assertEquals(new ActorCode("ACTOR-1"), stub.lastGetCode);
        assertTrue(rendered.contains("ACTOR-1"), rendered);
        assertFalse(rendered.endsWith(": null"), rendered);
    }

    /** The documented not-found rendering: {@code actor_get} must not throw for an unknown code. */
    @Test
    void getRendersANotFoundFallbackWhenAbsent() {
        stub.nextGetResult = Optional.empty();

        assertEquals("Actor not found: ACTOR-99", adapter.get(null, "ACTOR-99", null, null));
    }

    /** {@code actor_get}'s {@code displayLocale} argument reaches the in-port, overriding the project default. */
    @Test
    void getPassesAnExplicitDisplayLocaleOverridingTheProjectDefault() {
        ActorMcpTools germanAdapter = new ActorMcpTools(stub, stub, stub, stub, stub, stub, GERMAN_PROJECTS,
                NO_TRANSLATIONS);
        stub.nextGetResult = Optional.of(actor("ACTOR-1", ActorType.HUMAN, "Sachbearbeiter", null));

        germanAdapter.get(null, "ACTOR-1", "en", null);

        assertEquals("en", stub.lastGetDisplayLocale);
    }

    /** Omitting {@code displayLocale} falls back to the project's configured default language. */
    @Test
    void getFallsBackToTheProjectDefaultDisplayLocaleWhenOmitted() {
        ActorMcpTools germanAdapter = new ActorMcpTools(stub, stub, stub, stub, stub, stub, GERMAN_PROJECTS,
                NO_TRANSLATIONS);
        stub.nextGetResult = Optional.of(actor("ACTOR-1", ActorType.HUMAN, "Sachbearbeiter", null));

        germanAdapter.get(null, "ACTOR-1", null, null);

        assertEquals("de", stub.lastGetDisplayLocale);
    }

    @Test
    void updatePassesTheCorrectionThroughAndRendersTheResult() {
        stub.nextUpdateResult = actor("ACTOR-1", ActorType.HUMAN, "Antragsbearbeiter", "Neue Beschreibung.");

        String rendered = adapter.update(null, "ACTOR-1", "Antragsbearbeiter", "Neue Beschreibung.", "en", null);

        assertEquals(new ActorCode("ACTOR-1"), stub.lastUpdateCode);
        assertEquals("Antragsbearbeiter", stub.lastUpdateName);
        assertEquals("Neue Beschreibung.", stub.lastUpdateDescription);
        assertEquals("en", stub.lastUpdateLanguage);
        assertTrue(rendered.contains("ACTOR-1"), rendered);
    }

    /** A blank optional argument reaches the in-port as {@code null} - "unchanged", not "blank". */
    @Test
    void updatePassesBlankArgumentsAsNull() {
        stub.nextUpdateResult = actor("ACTOR-1", ActorType.HUMAN, "Sachbearbeiter", null);

        adapter.update(null, "ACTOR-1", "  ", "", "  ", null);

        assertNull(stub.lastUpdateName);
        assertNull(stub.lastUpdateDescription);
        assertNull(stub.lastUpdateLanguage);
    }

    private static Actor actor(String code, ActorType type, String name, String description) {
        return new Actor(ID, new ActorCode(code), type, name, description);
    }

    // --- stale-translation signal (kogn-io/arknet#474/kogn-io/arknet#520) ------------------------

    /** The corrected actor name still carries the other maintained language, from an earlier write. */
    @Test
    void updateReportsTheOtherMaintainedLanguageTheCorrectedFieldsStillCarry() {
        ActorMcpTools bilingual = new ActorMcpTools(stub, stub, stub, stub, stub, stub,
                anchor -> new ResolvedProject(PROJECT, "en", List.of("en", "de")),
                hints(Map.of("name", Set.of("en", "de"), "description", Set.of("en"))));

        stub.nextUpdateResult = actor("ACTOR-1", ActorType.HUMAN, "Senior Case Worker", "New description.");
        String rendered = bilingual.update(null, "ACTOR-1", "Senior Case Worker", "New description.", "en", null);

        assertTrue(rendered.contains("de: name"), rendered);
    }

    /**
     * The second call of a two-language workflow - the field so far carries only the other
     * language, and this call adds the written one - is a translation, not a correction: the
     * variant already there is its source, and nothing is stale. The lookup must therefore see
     * the state before the write.
     */
    @Test
    void updateStaysSilentWhenTheCallAddsATranslation() {
        ActorMcpTools bilingual = new ActorMcpTools(stub, stub, stub, stub, stub, stub,
                anchor -> new ResolvedProject(PROJECT, "en", List.of("en", "de")),
                new StaleTranslationHint(lookupBeforeTheWrite(Map.of("name", Set.of("en")))));

        stub.nextUpdateResult = actor("ACTOR-1", ActorType.HUMAN, "Senior Case Worker", "New description.");
        String rendered = bilingual.update(null, "ACTOR-1", "Leitender Sachbearbeiter", null, "de", null);

        assertFalse(rendered.contains("stale"), rendered);
    }

    /** A project maintaining a single language has no other language to warn about. */
    @Test
    void updateStaysSilentForASingleLanguageProject() {
        ActorMcpTools monolingual = new ActorMcpTools(stub, stub, stub, stub, stub, stub,
                anchor -> new ResolvedProject(PROJECT, "en", List.of("en")),
                hints(Map.of("name", Set.of("en", "de"))));

        stub.nextUpdateResult = actor("ACTOR-1", ActorType.HUMAN, "Senior Case Worker", "New description.");
        String rendered = monolingual.update(null, "ACTOR-1", "Senior Case Worker", null, "en", null);

        assertFalse(rendered.contains("stale"), rendered);
    }

    /**
     * A lookup answering {@code byField} as the state <em>before</em> the write - and failing the
     * test if the write has already happened when it is asked, because only that state tells a
     * correction from a translation (kogn-io/arknet#474).
     */
    private FieldLanguageLookup lookupBeforeTheWrite(Map<String, Set<String>> byField) {
        return new FieldLanguageLookup() {
            @Override
            public Map<String, Set<String>> ofResource(ProjectId projectId, String code) {
                assertNull(stub.lastUpdateCode, "the lookup must run before the write");
                return byField;
            }

            @Override
            public Map<String, Set<String>> ofProjectRegistration(String projectLabel) {
                assertNull(stub.lastUpdateCode, "the lookup must run before the write");
                return byField;
            }
        };
    }

    /** Structural stub implementing the six driving in-ports. */
    private static final class Stub
            implements AddActor, ListActors, DescribeActorDisplayFallback, GetActor, UpdateActor, DeleteActor {

        private NewActor lastAddCommand;
        private String lastAddDefaultLanguage;
        private List<Actor> allActors = List.of();
        private Map<ActorCode, ActorDisplayFallback> nextFallbacks = Map.of();
        private ActorCode lastGetCode;
        private String lastGetDisplayLocale;
        private Optional<Actor> nextGetResult = Optional.empty();
        private ActorCode lastUpdateCode;
        private String lastUpdateName;
        private String lastUpdateDescription;
        private String lastUpdateLanguage;
        private Actor nextUpdateResult;
        private ActorCode lastDeleteCode;

        @Override
        public Actor add(ProjectId projectId, NewActor command, String defaultLanguage) {
            lastAddCommand = command;
            lastAddDefaultLanguage = defaultLanguage;
            return new Actor(ID, new ActorCode("ACTOR-1"), command.type(), command.name(),
                    command.description());
        }

        @Override
        public List<Actor> list(ProjectId projectId, String displayLocale) {
            return allActors;
        }

        @Override
        public Map<ActorCode, ActorDisplayFallback> describe(ProjectId projectId, String displayLocale) {
            return nextFallbacks;
        }

        @Override
        public Optional<Actor> get(ProjectId projectId, ActorCode code, String displayLocale) {
            lastGetCode = code;
            lastGetDisplayLocale = displayLocale;
            return nextGetResult;
        }

        @Override
        public Actor update(ProjectId projectId, ActorCode code, String name, String description,
                String language, String defaultLanguage) {
            lastUpdateCode = code;
            lastUpdateName = name;
            lastUpdateDescription = description;
            lastUpdateLanguage = language;
            return nextUpdateResult;
        }

        @Override
        public void delete(ProjectId projectId, ActorCode code) {
            lastDeleteCode = code;
        }
    }
}
