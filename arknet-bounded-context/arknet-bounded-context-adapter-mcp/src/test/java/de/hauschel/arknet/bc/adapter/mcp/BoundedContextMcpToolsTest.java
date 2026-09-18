// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.application.port.in.BoundedContextDetail;
import de.hauschel.arknet.bc.application.port.in.DeleteBoundedContext;
import de.hauschel.arknet.bc.application.port.in.DescribeBoundedContextDisplayFallback;
import de.hauschel.arknet.bc.application.port.in.GetBoundedContext;
import de.hauschel.arknet.bc.application.port.in.LinkContext;
import de.hauschel.arknet.bc.application.port.in.LinkTerm;
import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.RelatedContext;
import de.hauschel.arknet.bc.application.port.in.UnlinkContext;
import de.hauschel.arknet.bc.application.port.in.UnlinkTerm;
import de.hauschel.arknet.bc.application.port.in.UpdateBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextDisplayFallback;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.ContextRelationshipId;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermNotLinkedException;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.FieldLanguageLookup;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.StaleTranslationHint;
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Scaffold-level check that the adapter declares exactly the six bounded-context tools and guards
 * its in-port dependencies, plus the term-display-resolution contract ({@link ResolveTerms}):
 * renders the resolved business code, falls back to the bare IRI for an id it cannot
 * resolve, and never issues more than one batch call per rendering. Also covers the multilingual
 * {@code language}/{@code displayLocale} passthrough and {@code bc_update} (kogn-io/arknet#520).
 */
class BoundedContextMcpToolsTest {

    private static final BoundedContextId ID =
            new BoundedContextId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));
    private static final BoundedContextId DOWNSTREAM_ID =
            new BoundedContextId(ResourceId.of("https://w3id.org/arknet/id/22222222-2222-2222-2222-222222222222"));

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final String ANCHOR = "/home/dev/projects/test-project";

    /**
     * The stale-translation signal over an empty store (kogn-io/arknet#474): every test that sets
     * up no language inventory keeps the answer it always had, because a field that carries no
     * other language has nothing to report.
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

    /**
     * Stands in for the project registry: exactly one registered anchor, and a hard
     * failure for anything else. Rejecting the unknown case rather than resolving it is what makes
     * {@link #routesByTheExplicitAnchorParameterWhenTheTransportCarriesNone} mean anything - a stub
     * that answered every anchor would pass whether the parameter was honoured or ignored.
     */
    private static final ProjectResolver PROJECTS = anchor -> {
        if (ANCHOR.equals(anchor)) {
            return new ResolvedProject(PROJECT, null);
        }
        throw new UnresolvedProjectAnchorException(anchor, "no project registered for '" + anchor + "'");
    };

    private final Stub stub = new Stub();
    private final RecordingResolveTerms resolveTerms = new RecordingResolveTerms();
    private final BoundedContextMcpTools adapter = new BoundedContextMcpTools(
            stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS);

    /**
     * The explicit tool parameter is a full second delivery path, open to a
     * client that cannot set the transport header - not a fallback for when the header is missing.
     * Passing it here with a {@code null} context is exactly that client's situation.
     */
    @Test
    void routesByTheExplicitAnchorParameterWhenTheTransportCarriesNone() {
        String created =
                adapter.add(null, "OrderManagement", "Handles orders end to end.", null, null, "en", ANCHOR);

        assertTrue(created.contains("BC-1"), created);
        assertEquals(PROJECT, stub.lastProjectId);
    }

    /**
     * The counterpart: no anchor at all is a caller error, never a route to a default project
     * Without this the adapter could silently pass {@code null} on and let
     * some later layer invent an answer.
     */
    @Test
    void rejectsACallThatCarriesNoAnchorAtAll() {
        assertThrows(UnresolvedProjectAnchorException.class,
                () -> adapter.add(null, "OrderManagement", "Handles orders end to end.", null, null, "en", null));
    }

    @Test
    void declaresTheNineBoundedContextTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(9, names.size());
        assertTrue(names.containsAll(
                List.of("bc_add", "bc_list", "bc_get", "bc_update", "bc_link_term", "bc_unlink_term",
                        "bc_link_context", "bc_unlink_context", "bc_delete")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                null, stub, stub, stub, stub, stub, stub, stub, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, null, stub, stub, stub, stub, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, null, stub, stub, stub, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, null, stub, stub, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, null, stub, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, stub, null, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, stub, stub, null, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, stub, stub, stub, null, resolveTerms, PROJECTS,
                NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, null, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, resolveTerms, PROJECTS, null));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, resolveTerms, null, NO_TRANSLATIONS));
    }

    @Test
    void addPassesTheFieldsThroughAndRendersThem() {
        String rendered = adapter.add(null, "OrderManagement", "Owns the customer order lifecycle end to end.",
                "CORE_DOMAIN", "orders-team", "en", ANCHOR);

        assertEquals("OrderManagement", stub.lastAddCommand.name());
        assertEquals(Subdomain.CORE_DOMAIN, stub.lastAddCommand.subdomain());
        assertEquals("orders-team", stub.lastAddCommand.ownedBy());
        assertEquals("en", stub.lastAddCommand.language());
        assertTrue(rendered.contains("BC-1"), rendered);
        assertTrue(rendered.contains("{CORE_DOMAIN}"), rendered);
        assertTrue(rendered.contains("<orders-team>"), rendered);
    }

    @Test
    void addNormalisesBlankOptionalFieldsToNull() {
        adapter.add(null, "OrderManagement", "Owns the customer order lifecycle end to end.", "  ", "", "en",
                ANCHOR);

        assertEquals(null, stub.lastAddCommand.subdomain());
        assertEquals(null, stub.lastAddCommand.ownedBy());
    }

    @Test
    void updateCorrectsNameAndRendersTheResult() {
        String rendered = adapter.update(null, "BC-1", "Renamed", null, null, "en", ANCHOR);

        assertEquals(new BoundedContextCode("BC-1"), stub.lastUpdatedCode);
        assertEquals("Renamed", stub.lastUpdatedName);
        assertTrue(rendered.contains("BC-1"), rendered);
    }

    @Test
    void updateNormalisesBlankFieldsToNull() {
        adapter.update(null, "BC-1", "  ", " ", null, "  ", ANCHOR);

        assertEquals(null, stub.lastUpdatedName);
        assertEquals(null, stub.lastUpdatedDomainVision);
        assertEquals(null, stub.lastUpdatedLanguage);
    }

    @Test
    void updateAppendsTheStaleTranslationHintWhenAFieldIsWritten() {
        Stub stubWithMaintainedLanguages = stub;
        BoundedContextMcpTools adapterWithHints = new BoundedContextMcpTools(
                stubWithMaintainedLanguages, stubWithMaintainedLanguages, stubWithMaintainedLanguages,
                stubWithMaintainedLanguages, stubWithMaintainedLanguages, stubWithMaintainedLanguages,
                stubWithMaintainedLanguages, stubWithMaintainedLanguages, stubWithMaintainedLanguages,
                stubWithMaintainedLanguages, resolveTerms,
                anchor -> new ResolvedProject(PROJECT, "en", List.of("en", "de")),
                hints(Map.of("name", Set.of("en", "de"))));

        String rendered = adapterWithHints.update(null, "BC-1", "Renamed", null, null, "en", ANCHOR);

        assertTrue(rendered.contains("Possibly stale translations"), rendered);
        assertTrue(rendered.contains("de"), rendered);
    }

    /**
     * {@code bc_update}'s {@code terms} reaches {@link UpdateBoundedContext} unchanged
     * (kogn-io/arknet#567) - an empty list is the explicit signal to remove every link, distinct
     * from the omitted ({@code null}) case.
     */
    @Test
    void updatePassesTermsThroughToTheInPort() {
        adapter.update(null, "BC-1", null, null, List.of("TERM-1", "TERM-2"), null, ANCHOR);

        assertEquals(List.of("TERM-1", "TERM-2"), stub.lastUpdatedTermCodes);
    }

    /** An empty {@code terms} list reaches the in-port as an empty list, not {@code null}. */
    @Test
    void updatePassesAnEmptyTermsListThroughDistinctFromOmitted() {
        adapter.update(null, "BC-1", null, null, List.of(), null, ANCHOR);

        assertEquals(List.of(), stub.lastUpdatedTermCodes);
    }

    /** Omitting {@code terms} reaches the in-port as {@code null}, leaving existing links untouched. */
    @Test
    void updateOmittingTermsPassesNullThroughToTheInPort() {
        adapter.update(null, "BC-1", "Renamed", null, null, null, ANCHOR);

        assertEquals(null, stub.lastUpdatedTermCodes);
    }

    @Test
    void linkTermPassesTheRawTermCodeThroughToTheInPort() {
        String rendered = adapter.linkTerm(null, "BC-1", "TERM-1", ANCHOR);

        assertEquals(new BoundedContextCode("BC-1"), stub.lastLinkedBoundedContext);
        assertEquals("TERM-1", stub.lastLinkedTermCode);
        assertEquals("linked BC-1 -> TERM-1 (ubiquitousLanguageTerm)\n\nproject: " + PROJECT.value(), rendered);
    }

    @Test
    void formatRendersTheResolvedTermCodeInsteadOfTheBareIri() {
        ResourceId termResourceId = ResourceId.of("https://w3id.org/arknet/id/some-term");
        resolveTerms.register(termResourceId, new TermCode("TERM-7"));
        stub.nextGetDetail = new BoundedContextDetail(boundedContextWithTerms("BC-1", termResourceId), List.of());

        String rendered = adapter.get(null, "BC-1", null, ANCHOR);

        assertTrue(rendered.contains("[terms: TERM-7]"), rendered);
    }

    @Test
    void formatFallsBackToTheBareIriWhenResolveTermsCannotResolveIt() {
        ResourceId unresolvable = ResourceId.of("https://w3id.org/arknet/id/unknown-term");
        stub.nextGetDetail = new BoundedContextDetail(boundedContextWithTerms("BC-1", unresolvable), List.of());

        String rendered = adapter.get(null, "BC-1", null, ANCHOR);

        assertTrue(rendered.contains("[terms: https://w3id.org/arknet/id/unknown-term]"), rendered);
    }

    @Test
    void formatNeverThrowsWhenResolveTermsReturnsDuplicateEntriesForTheSameIdentity() {
        ResourceId duplicated = ResourceId.of("https://w3id.org/arknet/id/duplicated-term");
        resolveTerms.register(duplicated, new TermCode("TERM-7"));
        resolveTerms.register(duplicated, new TermCode("TERM-7"));
        stub.nextGetDetail = new BoundedContextDetail(boundedContextWithTerms("BC-1", duplicated), List.of());

        String rendered = adapter.get(null, "BC-1", null, ANCHOR);

        assertTrue(rendered.contains("[terms: TERM-7]"), rendered);
    }

    @Test
    void listResolvesTermsOfAllBoundedContextsInExactlyOneBatchCall() {
        ResourceId termA = ResourceId.of("https://w3id.org/arknet/id/term-a");
        ResourceId termB = ResourceId.of("https://w3id.org/arknet/id/term-b");
        resolveTerms.register(termA, new TermCode("TERM-1"));
        resolveTerms.register(termB, new TermCode("TERM-2"));
        stub.allBoundedContexts = List.of(
                boundedContextWithTerms("BC-1", termA),
                boundedContextWithTerms("BC-2", termB));

        String rendered = adapter.list(null, null, ANCHOR);

        assertEquals(1, resolveTerms.callCount());
        assertTrue(rendered.contains("[terms: TERM-1]"), rendered);
        assertTrue(rendered.contains("[terms: TERM-2]"), rendered);
    }

    @Test
    void listOfBoundedContextsWithoutAnyLinkedTermsDoesNotCallResolveTerms() {
        stub.allBoundedContexts = List.of(boundedContextWithTerms("BC-1"));

        adapter.list(null, null, ANCHOR);

        assertEquals(0, resolveTerms.callCount());
    }

    @Test
    void listPassesAnExplicitDisplayLocaleArgumentThrough() {
        stub.allBoundedContexts = List.of(boundedContextWithTerms("BC-1"));

        adapter.list(null, "de", ANCHOR);

        assertEquals("de", stub.lastListDisplayLocale);
    }

    @Test
    void listMarksABoundedContextWhoseDisplayedLanguageFellBack() {
        stub.allBoundedContexts = List.of(boundedContextWithTerms("BC-1"));
        stub.fallbacks = Map.of(new BoundedContextCode("BC-1"), new BoundedContextDisplayFallback("de", null));

        String rendered = adapter.list(null, null, ANCHOR);

        assertTrue(rendered.contains("[fallback: name=de]"), rendered);
    }

    @Test
    void listLeavesABoundedContextWithNoFallbackUnmarked() {
        stub.allBoundedContexts = List.of(boundedContextWithTerms("BC-1"));

        String rendered = adapter.list(null, null, ANCHOR);

        assertTrue(!rendered.contains("[fallback:"), rendered);
    }

    @Test
    void getPassesAnExplicitDisplayLocaleArgumentThrough() {
        adapter.get(null, "BC-1", "de", ANCHOR);

        assertEquals("de", stub.lastGetDisplayLocale);
    }

    @Test
    void getRendersUnknownBoundedContextMessage() {
        String rendered = adapter.get(null, "BC-99", null, ANCHOR);

        assertTrue(rendered.contains("Bounded context not found: BC-99"), rendered);
    }

    @Test
    void linkContextPassesTheParsedRelationshipTypeThroughToTheInPort() {
        String rendered = adapter.linkContext(null, "BC-1", "BC-2", "CUSTOMER_SUPPLIER", ANCHOR);

        assertEquals(new BoundedContextCode("BC-1"), stub.lastUpstreamCode);
        assertEquals(new BoundedContextCode("BC-2"), stub.lastDownstreamCode);
        assertEquals(RelationshipType.CUSTOMER_SUPPLIER, stub.lastRelationshipType);
        assertEquals(PROJECT, stub.lastProjectId);
        assertTrue(rendered.contains("BC-1"), rendered);
        assertTrue(rendered.contains("BC-2"), rendered);
        assertTrue(rendered.contains("CUSTOMER_SUPPLIER"), rendered);
    }

    @Test
    void linkContextIsCaseInsensitiveOnTheRelationshipType() {
        adapter.linkContext(null, "BC-1", "BC-2", "customer_supplier", ANCHOR);

        assertEquals(RelationshipType.CUSTOMER_SUPPLIER, stub.lastRelationshipType);
    }

    @Test
    void linkContextRejectsAnUnknownRelationshipTypeWithADidacticMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adapter.linkContext(null, "BC-1", "BC-2", "FRENEMIES", ANCHOR));

        assertTrue(ex.getMessage().contains("PARTNERSHIP"), ex.getMessage());
        assertTrue(ex.getMessage().contains("SEPARATE_WAYS"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FRENEMIES"), ex.getMessage());
    }

    @Test
    void unlinkContextPassesTheParsedRelationshipTypeThroughToTheInPort() {
        String rendered = adapter.unlinkContext(null, "BC-1", "BC-2", "CUSTOMER_SUPPLIER", ANCHOR);

        assertEquals(new BoundedContextCode("BC-1"), stub.lastUnlinkedUpstreamCode);
        assertEquals(new BoundedContextCode("BC-2"), stub.lastUnlinkedDownstreamCode);
        assertEquals(RelationshipType.CUSTOMER_SUPPLIER, stub.lastUnlinkedRelationshipType);
        assertEquals(PROJECT, stub.lastProjectId);
        assertTrue(rendered.contains("BC-1"), rendered);
        assertTrue(rendered.contains("BC-2"), rendered);
        assertTrue(rendered.contains("CUSTOMER_SUPPLIER"), rendered);
    }

    @Test
    void unlinkContextRejectsAnUnknownRelationshipTypeWithADidacticMessage() {
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adapter.unlinkContext(null, "BC-1", "BC-2", "FRENEMIES", ANCHOR));

        assertTrue(ex.getMessage().contains("PARTNERSHIP"), ex.getMessage());
        assertTrue(ex.getMessage().contains("FRENEMIES"), ex.getMessage());
    }

    @Test
    void getRendersRelationshipsInBothDirections() {
        BoundedContextCode peerCode = new BoundedContextCode("BC-5");
        stub.nextGetDetail = new BoundedContextDetail(boundedContextWithTerms("BC-1"), List.of(
                new RelatedContext(new ContextRelationshipId(ResourceId.of("https://w3id.org/arknet/id/rel-1")),
                        RelatedContext.Direction.UPSTREAM_OF, DOWNSTREAM_ID, peerCode,
                        RelationshipType.PUBLISHED_LANGUAGE),
                new RelatedContext(new ContextRelationshipId(ResourceId.of("https://w3id.org/arknet/id/rel-2")),
                        RelatedContext.Direction.DOWNSTREAM_OF, ID, new BoundedContextCode("BC-9"),
                        RelationshipType.CONFORMIST)));

        String rendered = adapter.get(null, "BC-1", null, ANCHOR);

        assertTrue(rendered.contains("[upstream of: BC-5 (PUBLISHED_LANGUAGE)]"), rendered);
        assertTrue(rendered.contains("[downstream of: BC-9 (CONFORMIST)]"), rendered);
    }

    @Test
    void getRendersNoRelationshipsSuffixWhenThereAreNone() {
        stub.nextGetDetail = new BoundedContextDetail(boundedContextWithTerms("BC-1"), List.of());

        String rendered = adapter.get(null, "BC-1", null, ANCHOR);

        assertTrue(!rendered.contains("upstream of"), rendered);
        assertTrue(!rendered.contains("downstream of"), rendered);
    }

    @Test
    void listRendersRelationshipsPerContext() {
        stub.allBoundedContexts = List.of(boundedContextWithTerms("BC-1"), boundedContextWithTerms("BC-2"));
        stub.relationshipsByCode = Map.of(new BoundedContextCode("BC-1"), List.of(
                new RelatedContext(new ContextRelationshipId(ResourceId.of("https://w3id.org/arknet/id/rel-1")),
                        RelatedContext.Direction.UPSTREAM_OF, DOWNSTREAM_ID, new BoundedContextCode("BC-2"),
                        RelationshipType.PUBLISHED_LANGUAGE)));

        String rendered = adapter.list(null, null, ANCHOR);

        assertTrue(rendered.contains("BC-1 OrderManagement (Owns the customer order lifecycle end to end.) "
                + "{CORE_DOMAIN} <orders-team> [upstream of: BC-2 (PUBLISHED_LANGUAGE)]"), rendered);
    }

    // --- Answer shapes: project line, short link confirmation, list-field diff ---------------

    /**
     * kogn-io/arknet#597: a call whose {@code projectAnchor} was forgotten writes into the
     * session's project, silently. Every writing answer therefore ends by naming the project it
     * hit - here for each of the seven writing tools, so none of them can lose the line on its own.
     */
    @Test
    void everyWritingToolClosesItsAnswerWithTheProject() {
        BoundedContextMcpTools named = new BoundedContextMcpTools(stub, stub, stub, stub, stub, stub, stub,
                stub, stub, stub, resolveTerms,
                anchor -> new ResolvedProject(PROJECT, "en", List.of(), "arknet"), NO_TRANSLATIONS);
        String trailer = "\n\nproject: arknet";

        assertTrue(named.add(null, "OrderManagement", "Owns orders end to end.", null, null, "en", ANCHOR)
                .endsWith(trailer));
        assertTrue(named.update(null, "BC-1", "Renamed", null, null, "en", ANCHOR).endsWith(trailer));
        assertTrue(named.linkTerm(null, "BC-1", "TERM-1", ANCHOR).endsWith(trailer));
        assertTrue(named.unlinkTerm(null, "BC-1", "TERM-1", ANCHOR).endsWith(trailer));
        assertTrue(named.linkContext(null, "BC-1", "BC-2", "CONFORMIST", ANCHOR).endsWith(trailer));
        assertTrue(named.unlinkContext(null, "BC-1", "BC-2", "CONFORMIST", ANCHOR).endsWith(trailer));
        assertTrue(named.delete(null, "BC-1", ANCHOR).endsWith(trailer));
    }

    /** {@code bc_delete} passes the parsed code straight through to the in-port. */
    @Test
    void deletePassesTheCodeThrough() {
        String rendered = adapter.delete(null, "BC-1", ANCHOR);

        assertEquals(new BoundedContextCode("BC-1"), stub.lastDeleteCode);
        assertEquals("Deleted: BC-1\n\nproject: test-project", rendered);
    }

    /** A read tool carries no project line - the signal is about writes (kogn-io/arknet#597). */
    @Test
    void readingToolsCarryNoProjectLine() {
        stub.allBoundedContexts = List.of(boundedContextWithTerms("BC-1"));

        assertTrue(!adapter.list(null, null, ANCHOR).contains("project:"), "bc_list");
        assertTrue(!adapter.get(null, "BC-99", null, ANCHOR).contains("project:"), "bc_get");
    }

    /**
     * kogn-io/arknet#600: an edge tool answers with the edge, not with the whole resource - its
     * caller already holds both ends.
     */
    @Test
    void linkToolsAnswerWithTheEdgeRatherThanTheWholeResource() {
        String linkedTerm = adapter.linkTerm(null, "BC-1", "TERM-1", ANCHOR);
        String linkedContext = adapter.linkContext(null, "BC-1", "BC-2", "CUSTOMER_SUPPLIER", ANCHOR);
        String unlinkedContext = adapter.unlinkContext(null, "BC-1", "BC-2", "CUSTOMER_SUPPLIER", ANCHOR);

        assertTrue(linkedTerm.startsWith("linked BC-1 -> TERM-1 (ubiquitousLanguageTerm)"), linkedTerm);
        assertTrue(linkedContext.startsWith("linked BC-1 -> BC-2 (CUSTOMER_SUPPLIER)"), linkedContext);
        assertTrue(unlinkedContext.startsWith("unlinked BC-1 -> BC-2 (CUSTOMER_SUPPLIER)"), unlinkedContext);
        assertTrue(!linkedTerm.contains("[terms:"), linkedTerm);
        assertTrue(!linkedTerm.contains("OrderManagement"), linkedTerm);
    }

    /**
     * kogn-io/arknet#598: {@code terms} replaces the set wholesale, so a caller restating it from
     * memory unlinks whatever it forgot. The diff is computed from the field before and after the
     * write - the request cannot show what fell out of it.
     */
    @Test
    void updateNamesWhatLeftAndWhatJoinedTheTermLinks() {
        ResourceId before = ResourceId.of("https://w3id.org/arknet/id/term-before");
        ResourceId after = ResourceId.of("https://w3id.org/arknet/id/term-after");
        resolveTerms.register(before, new TermCode("TERM-22"));
        resolveTerms.register(after, new TermCode("TERM-9"));
        stub.nextGetDetail = new BoundedContextDetail(boundedContextWithTerms("BC-1", before), List.of());
        stub.nextUpdatedTerms = List.of(after);

        String rendered = adapter.update(null, "BC-1", null, null, List.of("TERM-9"), null, ANCHOR);

        assertTrue(rendered.startsWith("ubiquitousLanguageTerm: removed TERM-22, added TERM-9\n"), rendered);
    }

    /** A {@code bc_update} that leaves the links alone costs no diff line. */
    @Test
    void updateStaysSilentWhenTheTermLinksDidNotChange() {
        ResourceId unchanged = ResourceId.of("https://w3id.org/arknet/id/term-unchanged");
        resolveTerms.register(unchanged, new TermCode("TERM-3"));
        stub.nextGetDetail = new BoundedContextDetail(boundedContextWithTerms("BC-1", unchanged), List.of());
        stub.nextUpdatedTerms = List.of(unchanged);

        String rendered = adapter.update(null, "BC-1", "Renamed", null, null, "en", ANCHOR);

        assertTrue(!rendered.contains("ubiquitousLanguageTerm:"), rendered);
        assertTrue(rendered.startsWith("BC-1 Renamed"), rendered);
    }

    // --- bc_unlink_term (kogn-io/arknet#598) --------------------------------------------------

    @Test
    void unlinkTermPassesTheRawTermCodeThroughToTheInPort() {
        String rendered = adapter.unlinkTerm(null, "BC-1", "TERM-1", ANCHOR);

        assertEquals(new BoundedContextCode("BC-1"), stub.lastUnlinkedTermBoundedContext);
        assertEquals("TERM-1", stub.lastUnlinkedTermCode);
        assertEquals(PROJECT, stub.lastProjectId);
        assertTrue(rendered.startsWith("unlinked BC-1 -> TERM-1 (ubiquitousLanguageTerm)"), rendered);
    }

    /** Never a silent no-op: the in-port's rejection reaches the caller unchanged. */
    @Test
    void unlinkTermPropagatesTheRejectionOfATermThatIsNotLinked() {
        stub.unlinkTermRejects = true;

        TermNotLinkedException ex = assertThrows(TermNotLinkedException.class,
                () -> adapter.unlinkTerm(null, "BC-1", "TERM-1", ANCHOR));

        assertEquals("TERM-1", ex.termCode());
    }

    private static BoundedContext boundedContextWithTerms(String code, ResourceId... termIds) {
        List<TermRef> terms = Arrays.stream(termIds).map(TermRef::new).toList();
        return new BoundedContext(ID, new BoundedContextCode(code), "OrderManagement",
                "Owns the customer order lifecycle end to end.", Subdomain.CORE_DOMAIN, "orders-team", terms);
    }

    /** Structural stub implementing the nine driving in-ports. */
    private static final class Stub
            implements AddBoundedContext, ListBoundedContexts, DescribeBoundedContextDisplayFallback,
            GetBoundedContext, UpdateBoundedContext, LinkTerm, UnlinkTerm, LinkContext, UnlinkContext,
            DeleteBoundedContext {

        private BoundedContextCode lastLinkedBoundedContext;
        private String lastLinkedTermCode;
        private BoundedContextCode lastUnlinkedTermBoundedContext;
        private String lastUnlinkedTermCode;
        private boolean unlinkTermRejects;
        private List<BoundedContext> allBoundedContexts = List.of();
        private Map<BoundedContextCode, List<RelatedContext>> relationshipsByCode = Map.of();
        private BoundedContextDetail nextGetDetail;
        private NewBoundedContext lastAddCommand;
        /** Records which project the adapter routed to, so a test can assert the routing itself. */
        private ProjectId lastProjectId;
        private BoundedContextCode lastUpstreamCode;
        private BoundedContextCode lastDownstreamCode;
        private RelationshipType lastRelationshipType;
        private BoundedContextCode lastUnlinkedUpstreamCode;
        private BoundedContextCode lastUnlinkedDownstreamCode;
        private RelationshipType lastUnlinkedRelationshipType;
        private String lastListDisplayLocale;
        private String lastGetDisplayLocale;
        private Map<BoundedContextCode, BoundedContextDisplayFallback> fallbacks = Map.of();
        private BoundedContextCode lastUpdatedCode;
        private String lastUpdatedName;
        private String lastUpdatedDomainVision;
        private List<String> lastUpdatedTermCodes;
        private List<ResourceId> nextUpdatedTerms = List.of();
        private String lastUpdatedLanguage;
        private BoundedContextCode lastDeleteCode;

        @Override
        public BoundedContext add(ProjectId projectId, NewBoundedContext command, String defaultLanguage) {
            lastAddCommand = command;
            lastProjectId = projectId;
            return new BoundedContext(ID, new BoundedContextCode("BC-1"), command.name(),
                    command.domainVision(), command.subdomain(), command.ownedBy(), List.of());
        }

        @Override
        public List<BoundedContextDetail> list(ProjectId projectId, String displayLocale) {
            lastListDisplayLocale = displayLocale;
            return allBoundedContexts.stream()
                    .map(bc -> new BoundedContextDetail(bc,
                            relationshipsByCode.getOrDefault(bc.code(), List.of())))
                    .toList();
        }

        @Override
        public Map<BoundedContextCode, BoundedContextDisplayFallback> describe(
                ProjectId projectId, String displayLocale) {
            return fallbacks;
        }

        @Override
        public Optional<BoundedContextDetail> get(ProjectId projectId, BoundedContextCode code,
                String displayLocale) {
            lastGetDisplayLocale = displayLocale;
            return Optional.ofNullable(nextGetDetail);
        }

        @Override
        public BoundedContext update(ProjectId projectId, BoundedContextCode code, String name,
                String domainVision, List<String> termCodes, String language, String defaultLanguage) {
            lastUpdatedCode = code;
            lastUpdatedName = name;
            lastUpdatedDomainVision = domainVision;
            lastUpdatedTermCodes = termCodes;
            lastUpdatedLanguage = language;
            return new BoundedContext(ID, code, name != null ? name : "OrderManagement",
                    domainVision != null ? domainVision : "Owns the customer order lifecycle end to end.",
                    Subdomain.CORE_DOMAIN, "orders-team",
                    nextUpdatedTerms.stream().map(TermRef::new).toList());
        }

        @Override
        public BoundedContext linkTerm(ProjectId projectId, BoundedContextCode code, String termCode) {
            lastProjectId = projectId;
            lastLinkedBoundedContext = code;
            lastLinkedTermCode = termCode;
            List<TermRef> terms =
                    List.of(new TermRef(ResourceId.of("https://w3id.org/arknet/id/" + termCode)));
            return new BoundedContext(ID, code, "OrderManagement",
                    "Owns the customer order lifecycle end to end.", Subdomain.CORE_DOMAIN, "orders-team", terms);
        }

        @Override
        public BoundedContext unlinkTerm(ProjectId projectId, BoundedContextCode code, String termCode) {
            lastProjectId = projectId;
            lastUnlinkedTermBoundedContext = code;
            lastUnlinkedTermCode = termCode;
            if (unlinkTermRejects) {
                throw new TermNotLinkedException(projectId, code, termCode);
            }
            return new BoundedContext(ID, code, "OrderManagement",
                    "Owns the customer order lifecycle end to end.", Subdomain.CORE_DOMAIN, "orders-team",
                    List.of());
        }

        @Override
        public ContextRelationship linkContext(ProjectId projectId, BoundedContextCode upstreamCode,
                BoundedContextCode downstreamCode, RelationshipType relationshipType) {
            lastProjectId = projectId;
            lastUpstreamCode = upstreamCode;
            lastDownstreamCode = downstreamCode;
            lastRelationshipType = relationshipType;
            return new ContextRelationship(
                    new ContextRelationshipId(ResourceId.of("https://w3id.org/arknet/id/relationship-1")),
                    ID, DOWNSTREAM_ID, relationshipType);
        }

        @Override
        public void unlinkContext(ProjectId projectId, BoundedContextCode upstreamCode,
                BoundedContextCode downstreamCode, RelationshipType relationshipType) {
            lastProjectId = projectId;
            lastUnlinkedUpstreamCode = upstreamCode;
            lastUnlinkedDownstreamCode = downstreamCode;
            lastUnlinkedRelationshipType = relationshipType;
        }

        @Override
        public void delete(ProjectId projectId, BoundedContextCode code) {
            lastProjectId = projectId;
            lastDeleteCode = code;
        }
    }

    /**
     * Fake {@link ResolveTerms}: resolves only what was {@link #register} registered, counts its
     * own invocations so tests can pin the "at most one batch call" invariant, and - like the real
     * port - never throws for an id it cannot resolve.
     */
    private static final class RecordingResolveTerms implements ResolveTerms {

        private final List<ResolvedTerm> known = new ArrayList<>();
        private int calls;

        void register(ResourceId id, TermCode code) {
            known.add(new ResolvedTerm(id, code));
        }

        int callCount() {
            return calls;
        }

        @Override
        public List<ResolvedTerm> resolve(ProjectId projectId, ResourceId... ids) {
            calls++;
            List<ResourceId> wanted = Arrays.asList(ids);
            return known.stream().filter(t -> wanted.contains(t.id())).toList();
        }
    }
}
