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
import de.hauschel.arknet.bc.application.port.in.DescribeBoundedContextDisplayFallback;
import de.hauschel.arknet.bc.application.port.in.GetBoundedContext;
import de.hauschel.arknet.bc.application.port.in.LinkContext;
import de.hauschel.arknet.bc.application.port.in.LinkTerm;
import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.UpdateBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextDisplayFallback;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.ContextRelationshipId;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.bc.domain.Subdomain;
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
            stub, stub, stub, stub, stub, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS);

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
    void declaresTheSixBoundedContextTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(6, names.size());
        assertTrue(names.containsAll(
                List.of("bc_add", "bc_list", "bc_get", "bc_update", "bc_link_term", "bc_link_context")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                null, stub, stub, stub, stub, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, null, stub, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, null, stub, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, null, stub, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, null, resolveTerms, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, stub, null, PROJECTS, NO_TRANSLATIONS));
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, stub, resolveTerms, PROJECTS, null));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class, () -> new BoundedContextMcpTools(
                stub, stub, stub, stub, stub, stub, stub, resolveTerms, null, NO_TRANSLATIONS));
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
        String rendered = adapter.update(null, "BC-1", "Renamed", null, "en", ANCHOR);

        assertEquals(new BoundedContextCode("BC-1"), stub.lastUpdatedCode);
        assertEquals("Renamed", stub.lastUpdatedName);
        assertTrue(rendered.contains("BC-1"), rendered);
    }

    @Test
    void updateNormalisesBlankFieldsToNull() {
        adapter.update(null, "BC-1", "  ", " ", "  ", ANCHOR);

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
                stubWithMaintainedLanguages, resolveTerms,
                anchor -> new ResolvedProject(PROJECT, "en", List.of("en", "de")),
                hints(Map.of("name", Set.of("en", "de"))));

        String rendered = adapterWithHints.update(null, "BC-1", "Renamed", null, "en", ANCHOR);

        assertTrue(rendered.contains("Possibly stale translations"), rendered);
        assertTrue(rendered.contains("de"), rendered);
    }

    @Test
    void linkTermPassesTheRawTermCodeThroughToTheInPort() {
        resolveTerms.register(ResourceId.of("https://w3id.org/arknet/id/TERM-1"), new TermCode("TERM-1"));

        String rendered = adapter.linkTerm(null, "BC-1", "TERM-1", ANCHOR);

        assertEquals(new BoundedContextCode("BC-1"), stub.lastLinkedBoundedContext);
        assertEquals("TERM-1", stub.lastLinkedTermCode);
        assertTrue(rendered.contains("[terms: TERM-1]"), rendered);
    }

    @Test
    void formatRendersTheResolvedTermCodeInsteadOfTheBareIri() {
        ResourceId termResourceId = ResourceId.of("https://w3id.org/arknet/id/some-term");
        resolveTerms.register(termResourceId, new TermCode("TERM-7"));
        stub.nextLinkedTermResourceId = termResourceId;

        String rendered = adapter.linkTerm(null, "BC-1", "TERM-1", ANCHOR);

        assertTrue(rendered.contains("[terms: TERM-7]"), rendered);
    }

    @Test
    void formatFallsBackToTheBareIriWhenResolveTermsCannotResolveIt() {
        ResourceId unresolvable = ResourceId.of("https://w3id.org/arknet/id/unknown-term");
        stub.nextLinkedTermResourceId = unresolvable;

        String rendered = adapter.linkTerm(null, "BC-1", "TERM-1", ANCHOR);

        assertTrue(rendered.contains("[terms: https://w3id.org/arknet/id/unknown-term]"), rendered);
    }

    @Test
    void formatNeverThrowsWhenResolveTermsReturnsDuplicateEntriesForTheSameIdentity() {
        ResourceId duplicated = ResourceId.of("https://w3id.org/arknet/id/duplicated-term");
        resolveTerms.register(duplicated, new TermCode("TERM-7"));
        resolveTerms.register(duplicated, new TermCode("TERM-7"));
        stub.nextLinkedTermResourceId = duplicated;

        String rendered = adapter.linkTerm(null, "BC-1", "TERM-1", ANCHOR);

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

    private static BoundedContext boundedContextWithTerms(String code, ResourceId... termIds) {
        List<TermRef> terms = Arrays.stream(termIds).map(TermRef::new).toList();
        return new BoundedContext(ID, new BoundedContextCode(code), "OrderManagement",
                "Owns the customer order lifecycle end to end.", Subdomain.CORE_DOMAIN, "orders-team", terms);
    }

    /** Structural stub implementing the six driving in-ports. */
    private static final class Stub
            implements AddBoundedContext, ListBoundedContexts, DescribeBoundedContextDisplayFallback,
            GetBoundedContext, UpdateBoundedContext, LinkTerm, LinkContext {

        private BoundedContextCode lastLinkedBoundedContext;
        private String lastLinkedTermCode;
        private ResourceId nextLinkedTermResourceId;
        private List<ResourceId> nextLinkedTerms = List.of();
        private List<BoundedContext> allBoundedContexts = List.of();
        private NewBoundedContext lastAddCommand;
        /** Records which project the adapter routed to, so a test can assert the routing itself. */
        private ProjectId lastProjectId;
        private BoundedContextCode lastUpstreamCode;
        private BoundedContextCode lastDownstreamCode;
        private RelationshipType lastRelationshipType;
        private String lastListDisplayLocale;
        private String lastGetDisplayLocale;
        private Map<BoundedContextCode, BoundedContextDisplayFallback> fallbacks = Map.of();
        private BoundedContextCode lastUpdatedCode;
        private String lastUpdatedName;
        private String lastUpdatedDomainVision;
        private String lastUpdatedLanguage;

        @Override
        public BoundedContext add(ProjectId projectId, NewBoundedContext command, String defaultLanguage) {
            lastAddCommand = command;
            lastProjectId = projectId;
            return new BoundedContext(ID, new BoundedContextCode("BC-1"), command.name(),
                    command.domainVision(), command.subdomain(), command.ownedBy(), List.of());
        }

        @Override
        public List<BoundedContext> list(ProjectId projectId, String displayLocale) {
            lastListDisplayLocale = displayLocale;
            return allBoundedContexts;
        }

        @Override
        public Map<BoundedContextCode, BoundedContextDisplayFallback> describe(
                ProjectId projectId, String displayLocale) {
            return fallbacks;
        }

        @Override
        public Optional<BoundedContext> get(ProjectId projectId, BoundedContextCode code, String displayLocale) {
            lastGetDisplayLocale = displayLocale;
            return Optional.empty();
        }

        @Override
        public BoundedContext update(ProjectId projectId, BoundedContextCode code, String name,
                String domainVision, String language, String defaultLanguage) {
            lastUpdatedCode = code;
            lastUpdatedName = name;
            lastUpdatedDomainVision = domainVision;
            lastUpdatedLanguage = language;
            return new BoundedContext(ID, code, name != null ? name : "OrderManagement",
                    domainVision != null ? domainVision : "Owns the customer order lifecycle end to end.",
                    Subdomain.CORE_DOMAIN, "orders-team", List.of());
        }

        @Override
        public BoundedContext linkTerm(ProjectId projectId, BoundedContextCode code, String termCode) {
            lastLinkedBoundedContext = code;
            lastLinkedTermCode = termCode;
            List<ResourceId> ids = new ArrayList<>(nextLinkedTerms);
            if (nextLinkedTermResourceId != null) {
                ids.add(nextLinkedTermResourceId);
            }
            if (ids.isEmpty()) {
                ids.add(ResourceId.of("https://w3id.org/arknet/id/" + termCode));
            }
            List<TermRef> terms = ids.stream().map(TermRef::new).toList();
            return new BoundedContext(ID, code, "OrderManagement",
                    "Owns the customer order lifecycle end to end.", Subdomain.CORE_DOMAIN, "orders-team", terms);
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
