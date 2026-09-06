// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.ul.application.port.in.AddTerm;
import de.hauschel.arknet.ul.application.port.in.DeleteTerm;
import de.hauschel.arknet.ul.application.port.in.DescribeTermDisplayFallback;
import de.hauschel.arknet.ul.application.port.in.GetTerm;
import de.hauschel.arknet.ul.application.port.in.ListTerms;
import de.hauschel.arknet.ul.application.port.in.UpdateTerm;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermDisplayFallback;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * Scaffold-level check that the adapter declares exactly the five term tools and
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
            new UbiquitousLanguageMcpTools(stub, stub, stub, stub, stub, stub, PROJECTS);

    @Test
    void declaresTheFiveTermTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(5, names.size());
        assertTrue(names.containsAll(
                List.of("term_add", "term_list", "term_get", "term_update", "term_delete")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(null, stub, stub, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, null, stub, stub, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, stub, stub, null, stub, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, stub, stub, stub, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new UbiquitousLanguageMcpTools(stub, stub, stub, stub, stub, stub, null));
    }

    /** {@code term_delete} passes the parsed code straight through to the in-port. */
    @Test
    void deletePassesTheCodeThrough() {
        String rendered = adapter.delete(null, "TERM-1", null);

        assertEquals(new TermCode("TERM-1"), stub.lastDeletedTerm);
        assertEquals("Deleted: TERM-1", rendered);
    }

    /**
     * {@code term_add}'s explicit {@code language} argument passes straight through unchanged -
     * this adapter never merges it with the project's configured default language itself; the
     * explicit-wins-otherwise-fall-back-to-default resolution (issue #258) happens one layer down,
     * in {@code TermService#add}, via {@code LanguageTag#resolveWriteLanguage}.
     */
    @Test
    void addPassesTheLanguageArgumentThroughUnchanged() {
        adapter.add(null, "Kunde", "def a", null, null, "de", null);

        assertEquals("de", stub.lastCommand.language());
    }

    /** {@code term_update} passes every given field through to the in-port. */
    @Test
    void updatePassesAllGivenFieldsThroughToTheInPort() {
        String rendered = adapter.update(null, "TERM-1", "Erstattung", "Neue Definition", null, null, "de", null);

        assertEquals(new TermCode("TERM-1"), stub.lastUpdatedTerm);
        assertEquals("Erstattung", stub.lastUpdatePrefLabel);
        assertEquals("Neue Definition", stub.lastUpdateDefinition);
        assertEquals("de", stub.lastUpdateLanguage);
        assertTrue(rendered.contains("Erstattung"), rendered);
    }

    /**
     * An omitted field must reach {@link UpdateTerm} as {@code null} - so the port (not this
     * adapter) decides that "unchanged" means "leave the existing value" rather than the adapter
     * silently substituting a blank or empty value.
     */
    @Test
    void updateWithOmittedFieldsPassesNullThroughForEachOfThem() {
        adapter.update(null, "TERM-1", null, null, null, null, null, null);

        assertEquals(new TermCode("TERM-1"), stub.lastUpdatedTerm);
        assertNull(stub.lastUpdatePrefLabel);
        assertNull(stub.lastUpdateDefinition);
        assertNull(stub.lastUpdateLanguage);
    }

    /** {@code term_add}'s optional {@code broader} argument resolves to a {@link TermCode}. */
    @Test
    void addPassesThroughTheBroaderCode() {
        adapter.add(null, "Human Actor", "A human acting.", "TERM-1", null, null, null);

        assertEquals(new TermCode("TERM-1"), stub.lastCommand.broader());
    }

    /** Omitting {@code broader} on {@code term_add} leaves it unset. */
    @Test
    void addWithoutBroaderLeavesItNull() {
        adapter.add(null, "Actor", "Someone or something acting.", null, null, null, null);

        assertNull(stub.lastCommand.broader());
    }

    /**
     * {@code term_update}'s {@code broader} argument is a three-way signal (issue #252), unlike
     * every other argument: omitting it must reach {@link UpdateTerm} as {@code null} (leave an
     * already-set broader term unchanged).
     */
    @Test
    void updateOmittingBroaderPassesNullThrough() {
        adapter.update(null, "TERM-1", null, null, null, null, null, null);

        assertNull(stub.lastUpdateBroader);
    }

    /** An explicit, non-blank {@code broader} resolves to {@code Optional.of(...)} - set/replace. */
    @Test
    void updatePassesANonBlankBroaderAsOptionalOfTheCode() {
        adapter.update(null, "TERM-1", null, null, "TERM-2", null, null, null);

        assertEquals(Optional.of(new TermCode("TERM-2")), stub.lastUpdateBroader);
    }

    /** An explicit blank {@code broader} resolves to {@code Optional.empty()} - explicit clear. */
    @Test
    void updatePassesABlankBroaderAsOptionalEmpty() {
        adapter.update(null, "TERM-1", null, null, "", null, null, null);

        assertEquals(Optional.empty(), stub.lastUpdateBroader);
    }

    /** {@code term_add}'s optional {@code related} argument resolves to {@link TermCode}s. */
    @Test
    void addPassesThroughTheRelatedCodes() {
        adapter.add(null, "Anker", "Ein opakes Merkmal.", null, List.of("TERM-1", " TERM-2 "), null, null);

        assertEquals(List.of(new TermCode("TERM-1"), new TermCode("TERM-2")), stub.lastCommand.related());
    }

    /** Omitting {@code related} on {@code term_add} leaves the term without any related peer. */
    @Test
    void addWithoutRelatedLeavesItEmpty() {
        adapter.add(null, "Anker", "Ein opakes Merkmal.", null, null, null, null);

        assertEquals(List.of(), stub.lastCommand.related());
    }

    /**
     * {@code term_update}'s {@code related} argument is the same three-way signal {@code broader}
     * is, spelt with the list itself: omitting it must reach {@link UpdateTerm} as {@code null}
     * (leave the existing peers alone), never as an empty list, which is the explicit "remove them
     * all" instruction.
     */
    @Test
    void updateOmittingRelatedPassesNullThrough() {
        adapter.update(null, "TERM-1", null, null, null, null, null, null);

        assertNull(stub.lastUpdateRelated);
    }

    /** An explicitly empty {@code related} list reaches the port as an empty list - clear them all. */
    @Test
    void updatePassesAnEmptyRelatedListThroughAsAClear() {
        adapter.update(null, "TERM-1", null, null, null, List.of(), null, null);

        assertEquals(List.of(), stub.lastUpdateRelated);
    }

    /** A non-empty {@code related} list reaches the port as codes - replace them wholesale. */
    @Test
    void updatePassesTheRelatedCodesThrough() {
        adapter.update(null, "TERM-1", null, null, null, List.of("TERM-2", "TERM-3"), null, null);

        assertEquals(List.of(new TermCode("TERM-2"), new TermCode("TERM-3")), stub.lastUpdateRelated);
    }

    /** A term's related peers are rendered next to its broader term, not swallowed. */
    @Test
    void getRendersTheRelatedPeers() {
        stub.termForGet = Optional.of(new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/1")),
                new TermCode("TERM-17"), "Projekt", "def a", null,
                List.of(new TermCode("TERM-18"), new TermCode("TERM-20"))));

        String rendered = adapter.get(null, "TERM-17", null, null);

        assertEquals("TERM-17 Projekt - def a [related:TERM-18,TERM-20]", rendered);
    }

    @Test
    void listRendersNoTermsFallbackWhenEmpty() {
        stub.termsForList = List.of();

        String rendered = adapter.list(null, null, null);

        assertEquals("(no terms)", rendered);
    }

    @Test
    void listJoinsMultipleTermsWithNewlines() {
        Term first = new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/1")),
                new TermCode("TERM-1"), "Gutschrift", "def a", null);
        Term second = new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/2")),
                new TermCode("TERM-2"), "Bestellung", "def b", null);
        stub.termsForList = List.of(first, second);

        String rendered = adapter.list(null, null, null);

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
     * {@code term_list} falls back to the resolved project's own configured default language
     * automatically when its own {@code displayLocale} argument is omitted (issue #274) - the
     * same value {@code term_add}/{@code term_update} already pass to their in-ports. Before
     * issue #274's fix, {@code UbiquitousLanguageMcpTools#list} called {@code
     * listTerms.list(project.id())} without any locale at all, so every listed term's label was
     * read under whichever language the process-wide, per-daemon default happened to be - never
     * the calling project's own, even for a project (like this test's) whose configured default
     * differs from it.
     */
    @Test
    void listPassesTheProjectsDefaultLanguageThrough() {
        UbiquitousLanguageMcpTools adapterWithGermanDefault =
                new UbiquitousLanguageMcpTools(stub, stub, stub, stub, stub, stub, PROJECTS_WITH_GERMAN_DEFAULT);

        adapterWithGermanDefault.list(null, null, null);

        assertEquals("de", stub.lastListDisplayLocale);
    }

    /**
     * {@code term_list}'s own explicit {@code displayLocale} argument wins over the project's
     * configured default (kogn-io/arknet#475) - the same explicit-wins-otherwise-fall-back
     * merge {@link #getPassesTheDisplayLocaleArgumentThrough} already proves for {@code
     * term_get}.
     */
    @Test
    void listPassesAnExplicitDisplayLocaleArgumentThrough() {
        UbiquitousLanguageMcpTools adapterWithGermanDefault =
                new UbiquitousLanguageMcpTools(stub, stub, stub, stub, stub, stub, PROJECTS_WITH_GERMAN_DEFAULT);

        adapterWithGermanDefault.list(null, "fr", null);

        assertEquals("fr", stub.lastListDisplayLocale);
    }

    /**
     * The core of issue #475: a term shown under a fallen-back language (its gegensprache is
     * missing) carries a visible {@code [fallback: ...]} tag naming the language actually shown.
     */
    @Test
    void listMarksATermWhoseDisplayedLanguageFellBack() {
        Term term = new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/1")),
                new TermCode("TERM-1"), "Customer", "def en", null);
        stub.termsForList = List.of(term);
        stub.fallbacksForList = Map.of(term.code(), new TermDisplayFallback("en", null));

        String rendered = adapter.list(null, null, null);

        assertEquals("TERM-1 Customer - def en [fallback: prefLabel=en]", rendered);
    }

    /**
     * The counterpart to {@link #listMarksATermWhoseDisplayedLanguageFellBack}: a term whose
     * gegensprache is present carries no fallback tag at all - the normal case stays free of
     * noise.
     */
    @Test
    void listLeavesATermWithNoFallbackUnmarked() {
        Term term = new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/1")),
                new TermCode("TERM-1"), "Kunde", "def de", null);
        stub.termsForList = List.of(term);
        stub.fallbacksForList = Map.of();

        String rendered = adapter.list(null, "de", null);

        assertEquals("TERM-1 Kunde - def de", rendered);
    }

    /** Structural stub implementing the six driving in-ports. */
    private static final class Stub implements AddTerm, ListTerms, DescribeTermDisplayFallback, GetTerm,
            UpdateTerm, DeleteTerm {

        private NewTerm lastCommand;
        private TermCode lastUpdatedTerm;
        private TermCode lastDeletedTerm;
        private String lastUpdatePrefLabel;
        private String lastUpdateDefinition;
        private String lastUpdateLanguage;
        private Optional<TermCode> lastUpdateBroader;
        private List<TermCode> lastUpdateRelated;
        private String lastGetDisplayLocale;
        private String lastListDisplayLocale;
        private List<Term> termsForList = List.of();
        private Map<TermCode, TermDisplayFallback> fallbacksForList = Map.of();
        private Optional<Term> termForGet = Optional.empty();

        @Override
        public Term add(ProjectId projectId, NewTerm command, String defaultLanguage) {
            lastCommand = command;
            // The relations are deliberately not echoed back into the returned Term: this stub
            // always answers under the fixed code TERM-1, which a test naming TERM-1 as the broader
            // or related peer would then hand straight into Term's own self-reference guard. What
            // each test asserts is what reached the port (lastCommand), not what came back.
            return new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/stub")),
                    new TermCode("TERM-1"), command.prefLabel(), command.definition());
        }

        @Override
        public List<Term> list(ProjectId projectId, String displayLocale) {
            lastListDisplayLocale = displayLocale;
            return termsForList;
        }

        @Override
        public Map<TermCode, TermDisplayFallback> describe(ProjectId projectId, String displayLocale) {
            return fallbacksForList;
        }

        @Override
        public Optional<Term> get(ProjectId projectId, TermCode code, String displayLocale) {
            lastGetDisplayLocale = displayLocale;
            return termForGet;
        }

        @Override
        public Term update(ProjectId projectId, TermCode code, String prefLabel, String definition,
                String language, String defaultLanguage, Optional<TermCode> broader, List<TermCode> related) {
            lastUpdatedTerm = code;
            lastUpdatePrefLabel = prefLabel;
            lastUpdateDefinition = definition;
            lastUpdateLanguage = language;
            lastUpdateBroader = broader;
            lastUpdateRelated = related;
            return new Term(new TermId(ResourceId.of("https://w3id.org/arknet/id/stub")), code,
                    prefLabel != null ? prefLabel : "p", definition != null ? definition : "d",
                    broader != null ? broader.orElse(null) : null,
                    related != null ? related : List.of());
        }

        @Override
        public void delete(ProjectId projectId, TermCode code) {
            lastDeletedTerm = code;
        }
    }
}
