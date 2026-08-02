// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.method.tool.ReturnMode;
import org.springframework.ai.mcp.annotation.method.tool.SyncMcpToolMethodCallback;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import de.hauschel.arknet.adr.application.port.in.AcceptAdr;
import de.hauschel.arknet.adr.application.port.in.AddAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.DeprecateAdr;
import de.hauschel.arknet.adr.application.port.in.GetAdr;
import de.hauschel.arknet.adr.application.port.in.ListAdrs;
import de.hauschel.arknet.adr.application.port.in.RejectAdr;
import de.hauschel.arknet.adr.application.port.in.SupersedeAdr;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.domain.RequirementCode;

/**
 * Scaffold-level check that the adapter declares exactly the five ADR tools and guards its in-port
 * dependencies, plus the reference-display-resolution contract ({@link ResolveRequirements},
 * {@link ResolveBoundedContexts}, ADR-008): renders the resolved business codes, falls back to the
 * bare IRI for an id it cannot resolve, and never issues more than one batch call per port per
 * rendering.
 */
class AdrMcpToolsTest {

    private static final AdrId ID =
            new AdrId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));
    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final String ANCHOR = "/home/dev/projects/test-project";

    /**
     * Stands in for the project registry (ADR-016): exactly one registered anchor, and a hard failure
     * for anything else. Rejecting the unknown case rather than resolving it is what makes
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
    private final RecordingResolveRequirements requirements = new RecordingResolveRequirements();
    private final RecordingResolveBoundedContexts contexts = new RecordingResolveBoundedContexts();
    private final AdrMcpTools adapter =
            new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, requirements, contexts, PROJECTS);

    /**
     * ADR-016 decision 2: the explicit tool parameter is a full second delivery path, open to a client
     * that cannot set the transport header - not a fallback for when the header is missing. Passing it
     * here with a {@code null} context is exactly that client's situation.
     */
    @Test
    void routesByTheExplicitAnchorParameterWhenTheTransportCarriesNone() {
        String created = adapter.add(null, "A title", "Why this was needed", "What was decided",
                null, null, null, null, null, ANCHOR);

        assertTrue(created.contains("ADR-1"), created);
        assertEquals(PROJECT, stub.lastProjectId);
    }

    /**
     * The counterpart: no anchor at all is a caller error, never a route to a default project
     * (ADR-016 decision 3).
     */
    @Test
    void rejectsACallThatCarriesNoAnchorAtAll() {
        assertThrows(UnresolvedProjectAnchorException.class,
                () -> adapter.add(null, "A title", "Why this was needed", "What was decided",
                        null, null, null, null, null, null));
    }

    @Test
    void declaresTheFiveAdrTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(5, names.size());
        assertTrue(names.containsAll(
                List.of("adr_add", "adr_list", "adr_get", "adr_set_status", "adr_supersede")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(null, stub, stub, stub, stub, stub, stub, requirements, contexts,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, null, stub, stub, stub, requirements, contexts,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, null, stub, stub, requirements, contexts,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, null, stub, requirements, contexts,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, null, requirements, contexts,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, null, contexts, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, requirements, null,
                        PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, requirements, contexts, null));
    }

    @Test
    void addPassesTheFieldsThroughAndRendersThem() {
        String rendered = adapter.add(null, "Use an embedded triple store", "Why this was needed",
                "What was decided", "What follows", "What else was considered", "2026-07-31",
                List.of("FR-1"), List.of("BC-1"), ANCHOR);

        assertEquals("Use an embedded triple store", stub.lastAddCommand.name());
        assertEquals("Why this was needed", stub.lastAddCommand.context());
        assertEquals("What was decided", stub.lastAddCommand.decision());
        assertEquals("What follows", stub.lastAddCommand.consequences());
        assertEquals("What else was considered", stub.lastAddCommand.alternatives());
        assertEquals(LocalDate.of(2026, 7, 31), stub.lastAddCommand.decisionDate());
        assertEquals(List.of("FR-1"), stub.lastAddCommand.addressesRequirementCodes());
        assertEquals(List.of("BC-1"), stub.lastAddCommand.affectsContextCodes());
        assertTrue(rendered.contains("ADR-1"), rendered);
        assertTrue(rendered.contains("[PROPOSED]"), rendered);
        assertTrue(rendered.contains("decided: 2026-07-31"), rendered);
    }

    @Test
    void addNormalisesBlankOptionalFieldsToNull() {
        adapter.add(null, "A title", "Why this was needed", "What was decided", "  ", "", "  ",
                null, null, ANCHOR);

        assertEquals(null, stub.lastAddCommand.consequences());
        assertEquals(null, stub.lastAddCommand.alternatives());
        assertEquals(null, stub.lastAddCommand.decisionDate());
    }

    /**
     * A malformed date is rejected loudly: silently recording a decision without the date its caller
     * believed it had given would be a quiet data loss.
     */
    @Test
    void addRejectsAMalformedDecisionDate() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "A title", "Why this was needed", "What was decided",
                        null, null, "31.07.2026", null, null, ANCHOR));
    }

    /**
     * Reproduces #186 at the layer the bug actually lived in: Spring AI's
     * {@link SyncMcpToolMethodCallback} renders the deepest exception in a thrown exception's
     * {@code getCause()} chain, not the exception actually thrown. {@link #addRejectsAMalformedDecisionDate}
     * cannot catch this - it asserts on {@code getMessage()} of the exception actually thrown, not on
     * what a caller behind the real callback receives. This test drives that real callback instead,
     * over the exact production {@code parseDate} translation in {@link AdrMcpTools#add}.
     */
    @Test
    void malformedDecisionDateRemedyReachesTheMcpCaller() throws NoSuchMethodException {
        final Method method = AdrMcpTools.class.getMethod("add", McpSyncRequestContext.class, String.class,
                String.class, String.class, String.class, String.class, String.class, List.class,
                List.class, String.class);
        final SyncMcpToolMethodCallback callback = new SyncMcpToolMethodCallback(ReturnMode.TEXT, method, adapter);
        // Never actually invoked: the explicit projectAnchor parameter below short-circuits
        // resolveProject before it would read anything off the context/exchange. Only its
        // non-nullness matters - DefaultMcpSyncRequestContext asserts that eagerly on construction.
        final McpSyncServerExchange exchange = new McpSyncServerExchange(null);

        final CallToolResult result = callback.apply(exchange, new CallToolRequest("adr_add", Map.of(
                "name", "A title",
                "adrContext", "Why this was needed",
                "decision", "What was decided",
                "decisionDate", "31.07.2026",
                "projectAnchor", ANCHOR)));

        final String text = result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce((a, b) -> a + b)
                .orElseThrow();
        assertTrue(text.contains("decisionDate must be an ISO-8601 date"), text);
        // Distinguishes the composed remedy from the raw JDK DateTimeParseException message, which
        // names the index it choked on instead of the expected format - the exact text that used to
        // win when the parse failure was chained as this exception's cause.
        assertFalse(text.contains("could not be parsed"), text);
    }

    @Test
    void formatRendersTheResolvedReferenceCodesInsteadOfTheBareIris() {
        ResourceId requirementId = ResourceId.of("https://w3id.org/arknet/id/some-requirement");
        ResourceId contextId = ResourceId.of("https://w3id.org/arknet/id/some-context");
        requirements.register(requirementId, new RequirementCode("FR-7"));
        contexts.register(contextId, new BoundedContextCode("BC-3"));
        stub.nextDetail = detail(adrWith(List.of(requirementId), List.of(contextId), List.of()),
                List.of(), List.of());

        String rendered = adapter.get(null, "ADR-1", ANCHOR);

        assertTrue(rendered.contains("addresses: FR-7"), rendered);
        assertTrue(rendered.contains("affects: BC-3"), rendered);
    }

    @Test
    void formatFallsBackToTheBareIriWhenAReferenceCannotBeResolved() {
        ResourceId unresolvable = ResourceId.of("https://w3id.org/arknet/id/unknown-requirement");
        stub.nextDetail = detail(adrWith(List.of(unresolvable), List.of(), List.of()), List.of(), List.of());

        String rendered = adapter.get(null, "ADR-1", ANCHOR);

        assertTrue(rendered.contains("addresses: https://w3id.org/arknet/id/unknown-requirement"), rendered);
    }

    @Test
    void formatNeverThrowsWhenAResolverReturnsDuplicateEntriesForTheSameIdentity() {
        ResourceId duplicated = ResourceId.of("https://w3id.org/arknet/id/duplicated-requirement");
        requirements.register(duplicated, new RequirementCode("FR-7"));
        requirements.register(duplicated, new RequirementCode("FR-7"));
        stub.nextDetail = detail(adrWith(List.of(duplicated), List.of(), List.of()), List.of(), List.of());

        String rendered = adapter.get(null, "ADR-1", ANCHOR);

        assertTrue(rendered.contains("addresses: FR-7"), rendered);
    }

    @Test
    void listResolvesReferencesOfAllAdrsInExactlyOneBatchCallPerPort() {
        ResourceId requirementA = ResourceId.of("https://w3id.org/arknet/id/req-a");
        ResourceId requirementB = ResourceId.of("https://w3id.org/arknet/id/req-b");
        ResourceId contextA = ResourceId.of("https://w3id.org/arknet/id/ctx-a");
        requirements.register(requirementA, new RequirementCode("FR-1"));
        requirements.register(requirementB, new RequirementCode("FR-2"));
        contexts.register(contextA, new BoundedContextCode("BC-1"));
        stub.allAdrs = List.of(
                detail(adrWith(List.of(requirementA), List.of(contextA), List.of()), List.of(), List.of()),
                detail(adrWith(List.of(requirementB), List.of(), List.of()), List.of(), List.of()));

        String rendered = adapter.list(null, ANCHOR);

        assertEquals(1, requirements.callCount());
        assertEquals(1, contexts.callCount());
        assertTrue(rendered.contains("[addresses: FR-1]"), rendered);
        assertTrue(rendered.contains("[addresses: FR-2]"), rendered);
        assertTrue(rendered.contains("[affects: BC-1]"), rendered);
    }

    @Test
    void listOfAdrsWithoutAnyReferencesDoesNotCallEitherResolver() {
        stub.allAdrs = List.of(detail(adrWith(List.of(), List.of(), List.of()), List.of(), List.of()));

        adapter.list(null, ANCHOR);

        assertEquals(0, requirements.callCount());
        assertEquals(0, contexts.callCount());
    }

    @Test
    void listRendersEmptyProjectAsAnExplicitMarker() {
        assertEquals("(no ADRs)", adapter.list(null, ANCHOR));
    }

    @Test
    void getRendersUnknownAdrMessage() {
        assertTrue(adapter.get(null, "ADR-99", ANCHOR).contains("ADR not found: ADR-99"));
    }

    @Test
    void getRendersBothSupersedesDirections() {
        stub.nextDetail = detail(adrWith(List.of(), List.of(), List.of()),
                List.of(new AdrCode("ADR-0")), List.of(new AdrCode("ADR-9")));

        String rendered = adapter.get(null, "ADR-1", ANCHOR);

        assertTrue(rendered.contains("supersedes: ADR-0"), rendered);
        assertTrue(rendered.contains("superseded by: ADR-9"), rendered);
    }

    /** Absent optional fields are omitted entirely rather than printed empty. */
    @Test
    void formatOmitsFieldsTheDecisionDoesNotCarry() {
        stub.nextDetail = detail(adrWith(List.of(), List.of(), List.of()), List.of(), List.of());

        String rendered = adapter.get(null, "ADR-1", ANCHOR);

        assertFalse(rendered.contains("consequences:"), rendered);
        assertFalse(rendered.contains("decided:"), rendered);
        assertFalse(rendered.contains("addresses:"), rendered);
        assertFalse(rendered.contains("supersedes:"), rendered);
    }

    @Test
    void setStatusAcceptsTheAcceptedTransition() {
        String rendered = adapter.setStatus(null, "ADR-1", "ACCEPTED", ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastAcceptedCode);
        assertTrue(rendered.contains("ADR-1"), rendered);
    }

    @Test
    void setStatusAcceptsTheRejectedTransition() {
        String rendered = adapter.setStatus(null, "ADR-1", "REJECTED", ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastRejectedCode);
        assertTrue(rendered.contains("ADR-1"), rendered);
    }

    @Test
    void setStatusAcceptsTheDeprecatedTransition() {
        String rendered = adapter.setStatus(null, "ADR-1", "DEPRECATED", ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastDeprecatedCode);
        assertTrue(rendered.contains("ADR-1"), rendered);
    }

    /**
     * A target is case-insensitive and trimmed, same as before REJECTED/DEPRECATED existed - proven
     * here for REJECTED so the parsing path is not assumed to still work untested.
     */
    @Test
    void setStatusIsCaseInsensitiveAndTrimmed() {
        adapter.setStatus(null, "ADR-1", "  rejected  ", ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastRejectedCode);
    }

    @Test
    void setStatusRejectsAnyOtherTargetStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "PROPOSED", ANCHOR));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "SUPERSEDED", ANCHOR));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "NOT_A_STATUS", ANCHOR));
    }

    @Test
    void setStatusRejectionMessageNamesTheTargetInsteadOfLeakingTheRawEnumFailure() {
        // PROPOSED is a real AdrStatus value that is simply not a legal target of this tool (you
        // never transition into it via adr_set_status). SUPERSEDED is a real ashapes:ADR-status
        // value AdrStatus deliberately never implements at all. A completely unknown string must be
        // rejected the same way as both. None of the three may surface AdrStatus.valueOf's raw
        // "No enum constant ..." message.
        IllegalArgumentException proposed = assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "PROPOSED", ANCHOR));
        assertTrue(proposed.getMessage().contains("ACCEPTED"), proposed.getMessage());
        assertFalse(proposed.getMessage().contains("No enum constant"), proposed.getMessage());

        IllegalArgumentException superseded = assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "SUPERSEDED", ANCHOR));
        assertTrue(superseded.getMessage().contains("ACCEPTED"), superseded.getMessage());
        assertFalse(superseded.getMessage().contains("No enum constant"), superseded.getMessage());

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "NOT_A_STATUS", ANCHOR));
        assertTrue(unknown.getMessage().contains("ACCEPTED"), unknown.getMessage());
        assertFalse(unknown.getMessage().contains("No enum constant"), unknown.getMessage());
    }

    @Test
    void supersedePassesBothCodesThroughToTheInPort() {
        adapter.supersede(null, "ADR-2", "ADR-1", ANCHOR);

        assertEquals(new AdrCode("ADR-2"), stub.lastSupersedingCode);
        assertEquals(new AdrCode("ADR-1"), stub.lastSupersededCode);
    }

    private static Adr adrWith(List<ResourceId> requirementIds, List<ResourceId> contextIds,
            List<AdrId> supersedes) {
        return new Adr(ID, new AdrCode("ADR-1"), "Use an embedded triple store", AdrStatus.PROPOSED,
                "Why this was needed", "What was decided", null, null, null,
                requirementIds.stream().map(RequirementRef::new).toList(),
                contextIds.stream().map(BoundedContextRef::new).toList(),
                supersedes);
    }

    private static AdrDetail detail(Adr adr, List<AdrCode> supersedes, List<AdrCode> supersededBy) {
        return new AdrDetail(adr, supersedes, supersededBy);
    }

    /** Structural stub implementing the seven driving in-ports. */
    private static final class Stub
            implements AddAdr, ListAdrs, GetAdr, AcceptAdr, RejectAdr, DeprecateAdr, SupersedeAdr {

        private NewAdr lastAddCommand;
        private AdrCode lastAcceptedCode;
        private AdrCode lastRejectedCode;
        private AdrCode lastDeprecatedCode;
        private AdrCode lastSupersedingCode;
        private AdrCode lastSupersededCode;
        private AdrDetail nextDetail;
        private List<AdrDetail> allAdrs = List.of();
        /** Records which project the adapter routed to, so a test can assert the routing itself. */
        private ProjectId lastProjectId;

        @Override
        public AdrDetail add(ProjectId projectId, NewAdr command) {
            lastAddCommand = command;
            lastProjectId = projectId;
            Adr adr = new Adr(ID, new AdrCode("ADR-1"), command.name(), AdrStatus.PROPOSED,
                    command.context(), command.decision(), command.consequences(), command.alternatives(),
                    command.decisionDate(), List.of(), List.of(), List.of());
            return new AdrDetail(adr, List.of(), List.of());
        }

        @Override
        public List<AdrDetail> list(ProjectId projectId) {
            return allAdrs;
        }

        @Override
        public Optional<AdrDetail> get(ProjectId projectId, AdrCode code) {
            return Optional.ofNullable(nextDetail);
        }

        @Override
        public AdrDetail accept(ProjectId projectId, AdrCode code) {
            lastAcceptedCode = code;
            return detail(adrWith(List.of(), List.of(), List.of()), List.of(), List.of());
        }

        @Override
        public AdrDetail reject(ProjectId projectId, AdrCode code) {
            lastRejectedCode = code;
            return detail(adrWith(List.of(), List.of(), List.of()), List.of(), List.of());
        }

        @Override
        public AdrDetail deprecate(ProjectId projectId, AdrCode code) {
            lastDeprecatedCode = code;
            return detail(adrWith(List.of(), List.of(), List.of()), List.of(), List.of());
        }

        @Override
        public AdrDetail supersede(ProjectId projectId, AdrCode code, AdrCode supersededCode) {
            lastSupersedingCode = code;
            lastSupersededCode = supersededCode;
            return detail(adrWith(List.of(), List.of(), List.of()), List.of(supersededCode), List.of());
        }
    }

    /**
     * Fake {@link ResolveRequirements}: resolves only what was registered, counts its own invocations
     * so tests can pin the "at most one batch call" invariant, and - like the real port - never throws
     * for an id it cannot resolve.
     */
    private static final class RecordingResolveRequirements implements ResolveRequirements {

        private final List<ResolvedRequirement> known = new ArrayList<>();
        private int calls;

        void register(ResourceId id, RequirementCode code) {
            known.add(new ResolvedRequirement(id, code));
        }

        int callCount() {
            return calls;
        }

        @Override
        public List<ResolvedRequirement> resolveExisting(ProjectId projectId, ResourceId... ids) {
            calls++;
            List<ResourceId> wanted = Arrays.asList(ids);
            return known.stream().filter(r -> wanted.contains(r.id())).toList();
        }
    }

    /** The bounded-context counterpart of {@link RecordingResolveRequirements}. */
    private static final class RecordingResolveBoundedContexts implements ResolveBoundedContexts {

        private final List<ResolvedBoundedContext> known = new ArrayList<>();
        private int calls;

        void register(ResourceId id, BoundedContextCode code) {
            known.add(new ResolvedBoundedContext(id, code));
        }

        int callCount() {
            return calls;
        }

        @Override
        public List<ResolvedBoundedContext> resolveExisting(ProjectId projectId, ResourceId... ids) {
            calls++;
            List<ResourceId> wanted = Arrays.asList(ids);
            return known.stream().filter(c -> wanted.contains(c.id())).toList();
        }
    }
}
