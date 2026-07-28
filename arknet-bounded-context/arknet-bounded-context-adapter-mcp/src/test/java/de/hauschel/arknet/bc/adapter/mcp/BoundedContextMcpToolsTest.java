// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext;
import de.hauschel.arknet.bc.application.port.in.GetBoundedContext;
import de.hauschel.arknet.bc.application.port.in.LinkTerm;
import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Scaffold-level check that the adapter declares exactly the four bounded-context tools and guards
 * its in-port dependencies, plus the term-display-resolution contract ({@link ResolveTerms},
 * ADR-008): renders the resolved business code, falls back to the bare IRI for an id it cannot
 * resolve, and never issues more than one batch call per rendering.
 */
class BoundedContextMcpToolsTest {

    private static final BoundedContextId ID =
            new BoundedContextId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));

    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final String ANCHOR = "/home/dev/projects/test-project";

    /**
     * Stands in for the project registry (ADR-016): exactly one registered anchor, and a hard
     * failure for anything else. Rejecting the unknown case rather than resolving it is what makes
     * {@link #routesByTheExplicitAnchorParameterWhenTheTransportCarriesNone} mean anything - a stub
     * that answered every anchor would pass whether the parameter was honoured or ignored.
     */
    private static final ProjectResolver PROJECTS = anchor -> {
        if (ANCHOR.equals(anchor)) {
            return PROJECT;
        }
        throw new UnresolvedProjectAnchorException(anchor, "no project registered for '" + anchor + "'");
    };

    private final Stub stub = new Stub();
    private final RecordingResolveTerms resolveTerms = new RecordingResolveTerms();
    private final BoundedContextMcpTools adapter =
            new BoundedContextMcpTools(stub, stub, stub, stub, resolveTerms, PROJECTS);

    /**
     * ADR-016 decision 2: the explicit tool parameter is a full second delivery path, open to a
     * client that cannot set the transport header - not a fallback for when the header is missing.
     * Passing it here with a {@code null} context is exactly that client's situation.
     */
    @Test
    void routesByTheExplicitAnchorParameterWhenTheTransportCarriesNone() {
        String created = adapter.add(null, "OrderManagement", "Handles orders end to end.", null, null, ANCHOR);

        assertTrue(created.contains("BC-1"), created);
        assertEquals(PROJECT, stub.lastProjectId);
    }

    /**
     * The counterpart: no anchor at all is a caller error, never a route to a default project
     * (ADR-016 decision 3). Without this the adapter could silently pass {@code null} on and let
     * some later layer invent an answer.
     */
    @Test
    void rejectsACallThatCarriesNoAnchorAtAll() {
        assertThrows(UnresolvedProjectAnchorException.class,
                () -> adapter.add(null, "OrderManagement", "Handles orders end to end.", null, null, null));
    }

    @Test
    void declaresTheFourBoundedContextTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(4, names.size());
        assertTrue(names.containsAll(List.of("bc_add", "bc_list", "bc_get", "bc_link_term")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new BoundedContextMcpTools(null, stub, stub, stub, resolveTerms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new BoundedContextMcpTools(stub, stub, stub, null, resolveTerms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new BoundedContextMcpTools(stub, stub, stub, stub, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new BoundedContextMcpTools(stub, stub, stub, stub, resolveTerms, null));
    }

    @Test
    void addPassesTheFieldsThroughAndRendersThem() {
        String rendered = adapter.add(null, "OrderManagement", "Owns the customer order lifecycle end to end.",
                "CORE_DOMAIN", "orders-team", ANCHOR);

        assertEquals("OrderManagement", stub.lastAddCommand.name());
        assertEquals(Subdomain.CORE_DOMAIN, stub.lastAddCommand.subdomain());
        assertEquals("orders-team", stub.lastAddCommand.ownedBy());
        assertTrue(rendered.contains("BC-1"), rendered);
        assertTrue(rendered.contains("{CORE_DOMAIN}"), rendered);
        assertTrue(rendered.contains("<orders-team>"), rendered);
    }

    @Test
    void addNormalisesBlankOptionalFieldsToNull() {
        adapter.add(null, "OrderManagement", "Owns the customer order lifecycle end to end.", "  ", "", ANCHOR);

        assertEquals(null, stub.lastAddCommand.subdomain());
        assertEquals(null, stub.lastAddCommand.ownedBy());
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

        String rendered = adapter.list(null, ANCHOR);

        assertEquals(1, resolveTerms.callCount());
        assertTrue(rendered.contains("[terms: TERM-1]"), rendered);
        assertTrue(rendered.contains("[terms: TERM-2]"), rendered);
    }

    @Test
    void listOfBoundedContextsWithoutAnyLinkedTermsDoesNotCallResolveTerms() {
        stub.allBoundedContexts = List.of(boundedContextWithTerms("BC-1"));

        adapter.list(null, ANCHOR);

        assertEquals(0, resolveTerms.callCount());
    }

    @Test
    void getRendersUnknownBoundedContextMessage() {
        String rendered = adapter.get(null, "BC-99", ANCHOR);

        assertTrue(rendered.contains("Bounded context not found: BC-99"), rendered);
    }

    private static BoundedContext boundedContextWithTerms(String code, ResourceId... termIds) {
        List<TermRef> terms = Arrays.stream(termIds).map(TermRef::new).toList();
        return new BoundedContext(ID, new BoundedContextCode(code), "OrderManagement",
                "Owns the customer order lifecycle end to end.", Subdomain.CORE_DOMAIN, "orders-team", terms);
    }

    /** Structural stub implementing the four driving in-ports. */
    private static final class Stub
            implements AddBoundedContext, ListBoundedContexts, GetBoundedContext, LinkTerm {

        private BoundedContextCode lastLinkedBoundedContext;
        private String lastLinkedTermCode;
        private ResourceId nextLinkedTermResourceId;
        private List<ResourceId> nextLinkedTerms = List.of();
        private List<BoundedContext> allBoundedContexts = List.of();
        private NewBoundedContext lastAddCommand;
        /** Records which project the adapter routed to, so a test can assert the routing itself. */
        private ProjectId lastProjectId;

        @Override
        public BoundedContext add(ProjectId projectId, NewBoundedContext command) {
            lastAddCommand = command;
            lastProjectId = projectId;
            return new BoundedContext(ID, new BoundedContextCode("BC-1"), command.name(),
                    command.domainVision(), command.subdomain(), command.ownedBy(), List.of());
        }

        @Override
        public List<BoundedContext> list(ProjectId projectId) {
            return allBoundedContexts;
        }

        @Override
        public Optional<BoundedContext> get(ProjectId projectId, BoundedContextCode code) {
            return Optional.empty();
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
        public List<ResolvedTerm> getById(ProjectId projectId, ResourceId... ids) {
            calls++;
            List<ResourceId> wanted = Arrays.asList(ids);
            return known.stream().filter(t -> wanted.contains(t.id())).toList();
        }
    }
}
