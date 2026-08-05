// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

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

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.ul.application.port.in.AddTerm;
import de.hauschel.arknet.ul.application.port.in.GetTerm;
import de.hauschel.arknet.ul.application.port.in.ListTerms;
import de.hauschel.arknet.ul.application.port.in.UpdateTerm;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Scaffold-level check that the adapter declares exactly the four term tools and
 * guards its in-port dependencies. Behaviour of the handlers is not asserted here.
 */
class UbiquitousLanguageMcpToolsTest {

    /** Fake resolver: every call routes to the same fixed project, ignoring the origin. */
    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Stands in for the registry lookup: every anchor this test sends resolves to {@link #PROJECT}. */
    private static final ProjectResolver PROJECTS = anchor -> new ResolvedProject(PROJECT, null);

    /**
     * Same fixed resolution as {@link #PROJECTS}, but {@link #PROJECT} carries a configured
     * default language - needed to prove {@code term_list} passes it through (issue #274), the
     * same way {@link #getPassesTheDisplayLocaleArgumentThrough} already proves for {@code
     * term_get}'s explicit argument.
     */
    private static final ProjectResolver PROJECTS_WITH_GERMAN_DEFAULT = anchor -> new ResolvedProject(PROJECT, "de");

    private final Stub stub = new Stub();
    private final UbiquitousLanguageMcpTools adapter =
            new UbiquitousLanguageMcpTools(stub, stub, stub, stub, PROJECTS);

    @Test
    void declaresTheFourTermTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(4, names.size());
        assertTrue(names.containsAll(List.of("term_add", "term_list", "term_get", "term_update")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(null, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, stub, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, stub, stub, null));
    }

    @Test
    void addPassesThroughActorFacet() {
        String rendered = adapter.add(null, "Kunde", "Person, die eine Bestellung aufgibt.", "HUMAN", "Besteller",
                null, null, null);

        assertEquals(new ActorFacet(ActorKind.HUMAN, "Besteller"), stub.lastCommand.actorFacet());
        assertTrue(rendered.contains("[actor:HUMAN role=Besteller]"), rendered);
    }

    @Test
    void addPassesThroughLegalActorKind() {
        String rendered = adapter.add(null, "Kunde GmbH", "Ein Unternehmen, das Bestellungen aufgibt.", "LEGAL",
                "Besteller", null, null, null);

        assertEquals(new ActorFacet(ActorKind.LEGAL, "Besteller"), stub.lastCommand.actorFacet());
        assertTrue(rendered.contains("[actor:LEGAL role=Besteller]"), rendered);
    }

    @Test
    void addWithoutActorKindLeavesFacetNull() {
        adapter.add(null, "Gutschrift", "def a", null, null, null, null, null);

        assertNull(stub.lastCommand.actorFacet());
    }

    @Test
    void addRejectsInvalidActorKind() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "Gutschrift", "def a", "NOT_A_KIND", null, null, null, null));
    }

    /**
     * {@code term_add}'s explicit {@code language} argument passes straight through unchanged -
     * this adapter never merges it with the project's configured default language itself; the
     * explicit-wins-otherwise-fall-back-to-default resolution (issue #258) happens one layer down,
     * in {@code TermService#add}, via {@code LanguageTag#resolveWriteLanguage}.
     */
    @Test
    void addPassesTheLanguageArgumentThroughUnchanged() {
        adapter.add(null, "Kunde", "def a", null, null, null, "de", null);

        assertEquals("de", stub.lastCommand.language());
    }

    /** {@code term_update} passes every given field through to the in-port. */
    @Test
    void updatePassesAllGivenFieldsThroughToTheInPort() {
        String rendered = adapter.update(
                null, "TERM-1", "Erstattung", "Neue Definition", "HUMAN", "Kunde", null, "de", null);

        assertEquals(new TermCode("TERM-1"), stub.lastUpdatedTerm);
        assertEquals("Erstattung", stub.lastUpdatePrefLabel);
        assertEquals("Neue Definition", stub.lastUpdateDefinition);
        assertEquals(new ActorFacet(ActorKind.HUMAN, "Kunde"), stub.lastUpdateActorFacet);
        assertEquals("de", stub.lastUpdateLanguage);
        assertTrue(rendered.contains("Erstattung"), rendered);
        assertTrue(rendered.contains("[actor:HUMAN role=Kunde]"), rendered);
    }

    /**
     * An omitted field must reach {@link UpdateTerm} as {@code null} - so the port (not this
     * adapter) decides that "unchanged" means "leave the existing value" rather than the adapter
     * silently substituting a blank or empty value.
     */
    @Test
    void updateWithOmittedFieldsPassesNullThroughForEachOfThem() {
        adapter.update(null, "TERM-1", null, null, null, null, null, null, null);

        assertEquals(new TermCode("TERM-1"), stub.lastUpdatedTerm);
        assertNull(stub.lastUpdatePrefLabel);
        assertNull(stub.lastUpdateDefinition);
        assertNull(stub.lastUpdateActorFacet);
        assertNull(stub.lastUpdateLanguage);
    }

    @Test
    void updateRejectsInvalidActorKind() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.update(null, "TERM-1", null, null, "NOT_A_KIND", null, null, null, null));
    }

    /** {@code term_add}'s optional {@code broader} argument resolves to a {@link TermCode}. */
    @Test
    void addPassesThroughTheBroaderCode() {
        adapter.add(null, "Human Actor", "A human acting.", null, null, "TERM-1", null, null);

        assertEquals(new TermCode("TERM-1"), stub.lastCommand.broader());
    }

    /** Omitting {@code broader} on {@code term_add} leaves it unset. */
    @Test
    void addWithoutBroaderLeavesItNull() {
        adapter.add(null, "Actor", "Someone or something acting.", null, null, null, null, null);

        assertNull(stub.lastCommand.broader());
    }

    /**
     * {@code term_update}'s {@code broader} argument is a three-way signal (issue #252), unlike
     * every other argument: omitting it must reach {@link UpdateTerm} as {@code null} (leave an
     * already-set broader term unchanged).
     */
    @Test
    void updateOmittingBroaderPassesNullThrough() {
        adapter.update(null, "TERM-1", null, null, null, null, null, null, null);

        assertNull(stub.lastUpdateBroader);
    }

    /** An explicit, non-blank {@code broader} resolves to {@code Optional.of(...)} - set/replace. */
    @Test
    void updatePassesANonBlankBroaderAsOptionalOfTheCode() {
        adapter.update(null, "TERM-1", null, null, null, null, "TERM-2", null, null);

        assertEquals(Optional.of(new TermCode("TERM-2")), stub.lastUpdateBroader);
    }

    /** An explicit blank {@code broader} resolves to {@code Optional.empty()} - explicit clear. */
    @Test
    void updatePassesABlankBroaderAsOptionalEmpty() {
        adapter.update(null, "TERM-1", null, null, null, null, "", null, null);

        assertEquals(Optional.empty(), stub.lastUpdateBroader);
    }

    @Test
    void listRendersNoTermsFallbackWhenEmpty() {
        stub.termsForList = List.of();

        String rendered = adapter.list(null, null);

        assertEquals("(no terms)", rendered);
    }

    @Test
    void listJoinsMultipleTermsWithNewlines() {
        Term first = new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/1")),
                new TermCode("TERM-1"), "Gutschrift", "def a", null);
        Term second = new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/2")),
                new TermCode("TERM-2"), "Bestellung", "def b", null);
        stub.termsForList = List.of(first, second);

        String rendered = adapter.list(null, null);

        assertEquals("TERM-1 Gutschrift - def a\nTERM-2 Bestellung - def b", rendered);
    }

    @Test
    void getRendersTheTermWhenFound() {
        stub.termForGet = Optional.of(new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/1")),
                new TermCode("TERM-1"), "Gutschrift", "def a", null));

        String rendered = adapter.get(null, "TERM-1", null, null);

        assertEquals("TERM-1 Gutschrift - def a", rendered);
    }

    @Test
    void getRendersNotFoundMessageWhenAbsent() {
        stub.termForGet = Optional.empty();

        String rendered = adapter.get(null, "TERM-99", null, null);

        assertEquals("Term not found: TERM-99", rendered);
    }

    /** {@code term_get}'s {@code displayLocale} argument reaches the in-port unchanged. */
    @Test
    void getPassesTheDisplayLocaleArgumentThrough() {
        stub.termForGet = Optional.of(new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/1")),
                new TermCode("TERM-1"), "Kunde", "def a", null));

        adapter.get(null, "TERM-1", "de", null);

        assertEquals("de", stub.lastGetDisplayLocale);
    }

    /**
     * {@code term_list} exposes no explicit {@code displayLocale} tool argument of its own
     * (unlike {@code term_get}) - issue #274 asks only that it fall back to the resolved
     * project's own configured default language automatically, the same value {@code
     * term_add}/{@code term_update} already pass to their in-ports. Before this fix, {@code
     * UbiquitousLanguageMcpTools#list} called {@code listTerms.list(project.id())} without any
     * locale at all, so every listed term's label was read under whichever language the
     * process-wide, per-daemon default happened to be - never the calling project's own, even
     * for a project (like this test's) whose configured default differs from it.
     */
    @Test
    void listPassesTheProjectsDefaultLanguageThrough() {
        UbiquitousLanguageMcpTools adapterWithGermanDefault =
                new UbiquitousLanguageMcpTools(stub, stub, stub, stub, PROJECTS_WITH_GERMAN_DEFAULT);

        adapterWithGermanDefault.list(null, null);

        assertEquals("de", stub.lastListDisplayLocale);
    }

    /** Structural stub implementing the four driving in-ports. */
    private static final class Stub implements AddTerm, ListTerms, GetTerm, UpdateTerm {

        private NewTerm lastCommand;
        private TermCode lastUpdatedTerm;
        private String lastUpdatePrefLabel;
        private String lastUpdateDefinition;
        private ActorFacet lastUpdateActorFacet;
        private String lastUpdateLanguage;
        private Optional<TermCode> lastUpdateBroader;
        private String lastGetDisplayLocale;
        private String lastListDisplayLocale;
        private List<Term> termsForList = List.of();
        private Optional<Term> termForGet = Optional.empty();

        @Override
        public Term add(ProjectId projectId, NewTerm command, String defaultLanguage) {
            lastCommand = command;
            return new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/stub")),
                    new TermCode("TERM-1"), command.prefLabel(), command.definition(),
                    command.actorFacet());
        }

        @Override
        public List<Term> list(ProjectId projectId, String displayLocale) {
            lastListDisplayLocale = displayLocale;
            return termsForList;
        }

        @Override
        public Optional<Term> get(ProjectId projectId, TermCode code, String displayLocale) {
            lastGetDisplayLocale = displayLocale;
            return termForGet;
        }

        @Override
        public Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
                ActorFacet actorFacet, String language, String defaultLanguage, Optional<TermCode> broader) {
            lastUpdatedTerm = code;
            lastUpdatePrefLabel = prefLabel;
            lastUpdateDefinition = definition;
            lastUpdateActorFacet = actorFacet;
            lastUpdateLanguage = language;
            lastUpdateBroader = broader;
            return new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/stub")), code,
                    prefLabel != null ? prefLabel : "p", definition != null ? definition : "d", actorFacet,
                    broader != null ? broader.orElse(null) : null);
        }
    }
}
