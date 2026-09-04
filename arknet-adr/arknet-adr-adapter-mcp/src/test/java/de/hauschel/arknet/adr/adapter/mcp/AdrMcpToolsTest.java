// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;
import org.springframework.ai.mcp.annotation.method.tool.ReturnMode;
import org.springframework.ai.mcp.annotation.method.tool.SyncMcpToolMethodCallback;

import io.modelcontextprotocol.server.McpSyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema.CallToolRequest;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.TextContent;

import de.hauschel.arknet.adr.adapter.mcp.AdrMcpTools.ConsequenceCorrectionInput;
import de.hauschel.arknet.adr.adapter.mcp.AdrMcpTools.ConsideredOptionCorrectionInput;
import de.hauschel.arknet.adr.adapter.mcp.AdrMcpTools.NewConsequenceInput;
import de.hauschel.arknet.adr.adapter.mcp.AdrMcpTools.NewConsideredOptionInput;
import de.hauschel.arknet.adr.application.port.in.AcceptAdr;
import de.hauschel.arknet.adr.application.port.in.AddAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.CountSkippedAdrs;
import de.hauschel.arknet.adr.application.port.in.DeleteAdr;
import de.hauschel.arknet.adr.application.port.in.DeprecateAdr;
import de.hauschel.arknet.adr.application.port.in.GetAdr;
import de.hauschel.arknet.adr.application.port.in.ListAdrs;
import de.hauschel.arknet.adr.application.port.in.RejectAdr;
import de.hauschel.arknet.adr.application.port.in.SupersedeAdr;
import de.hauschel.arknet.adr.application.port.in.UnsupersedeAdr;
import de.hauschel.arknet.adr.application.port.in.UpdateAdr;
import de.hauschel.arknet.adr.application.port.in.UpdateAdr.AdrCorrection;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrNotDeletableException;
import de.hauschel.arknet.adr.domain.AdrReferencedException;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.ConsideredOption;
import de.hauschel.arknet.adr.domain.NewConsequence;
import de.hauschel.arknet.adr.domain.NewConsideredOption;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.adr.domain.TermRef;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Scaffold-level check that the adapter declares exactly the eight ADR tools and guards its in-port
 * dependencies, plus the reference-display-resolution contract ({@link ResolveRequirements},
 * {@link ResolveBoundedContexts}) and the structured consequence/considered-option input
 * translation (kogn-io/arknet#357).
 */
class AdrMcpToolsTest {

    private static final AdrId ID =
            new AdrId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));
    private static final ProjectId PROJECT = new ProjectId("test-project");
    private static final String ANCHOR = "/home/dev/projects/test-project";

    private static final ProjectResolver PROJECTS = anchor -> {
        if (ANCHOR.equals(anchor)) {
            return new ResolvedProject(PROJECT, "en");
        }
        throw new UnresolvedProjectAnchorException(anchor, "no project registered for '" + anchor + "'");
    };

    private final Stub stub = new Stub();
    private final RecordingResolveRequirements requirements = new RecordingResolveRequirements();
    private final RecordingResolveBoundedContexts contexts = new RecordingResolveBoundedContexts();
    private final RecordingResolveTerms terms = new RecordingResolveTerms();
    private final AdrMcpTools adapter =
            new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, requirements,
                    contexts, terms, PROJECTS);

    @Test
    void routesByTheExplicitAnchorParameterWhenTheTransportCarriesNone() {
        String created = adapter.add(null, "A title", "Why this was needed", "What was decided",
                null, null, null, null, null, null, null, ANCHOR);

        assertTrue(created.contains("ADR-1"), created);
        assertEquals(PROJECT, stub.lastProjectId);
    }

    @Test
    void rejectsACallThatCarriesNoAnchorAtAll() {
        assertThrows(UnresolvedProjectAnchorException.class,
                () -> adapter.add(null, "A title", "Why this was needed", "What was decided",
                        null, null, null, null, null, null, null, null));
    }

    @Test
    void declaresTheEightAdrTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(8, names.size());
        assertTrue(names.containsAll(List.of("adr_add", "adr_list", "adr_get", "adr_update",
                "adr_set_status", "adr_supersede", "adr_unsupersede", "adr_delete")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(null, stub, stub, stub, stub, stub, stub, stub, stub, stub, stub,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, null, stub, stub, stub, stub, stub, stub, stub, stub, stub,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, null, stub, stub, stub, stub, stub, stub, stub, stub,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, null, stub, stub, stub, stub, stub, stub, stub,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, null, stub, stub, stub, stub, stub, stub,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, null, stub, stub, stub, stub, stub,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, null, stub, stub, stub, stub,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, null, stub, stub, stub,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, stub, null, stub, stub,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, stub, stub, null, stub,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, null,
                        requirements, contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, null,
                        contexts, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, stub,
                        requirements, null, terms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, stub,
                        requirements, contexts, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new AdrMcpTools(stub, stub, stub, stub, stub, stub, stub, stub, stub, stub, stub,
                        requirements, contexts, terms, null));
    }

    @Test
    void addPassesTheFieldsThroughAndRendersThem() {
        String rendered = adapter.add(null, "Use an embedded triple store", "Why this was needed",
                "What was decided",
                List.of(new NewConsequenceInput("Faster reads", "POSITIVE")),
                List.of(new NewConsideredOptionInput("Adopt library X", "Well understood", "CHOSEN")), "en",
                List.of("FR-1"), List.of("BC-1"), List.of("TERM-1"), List.of("ADR-3"), ANCHOR);

        assertEquals("Use an embedded triple store", stub.lastAddCommand.name());
        assertEquals("Why this was needed", stub.lastAddCommand.context());
        assertEquals("What was decided", stub.lastAddCommand.decision());
        assertEquals(1, stub.lastAddCommand.consequences().size());
        assertEquals("Faster reads", stub.lastAddCommand.consequences().get(0).statement());
        assertEquals(ConsequenceType.POSITIVE, stub.lastAddCommand.consequences().get(0).type());
        assertEquals(1, stub.lastAddCommand.consideredOptions().size());
        assertEquals(OptionOutcome.CHOSEN, stub.lastAddCommand.consideredOptions().get(0).outcome());
        assertEquals("en", stub.lastAddCommand.language());
        assertEquals(List.of("FR-1"), stub.lastAddCommand.addressesRequirementCodes());
        assertEquals(List.of("BC-1"), stub.lastAddCommand.affectsContextCodes());
        assertEquals(List.of("TERM-1"), stub.lastAddCommand.usesTermCodes());
        assertEquals(List.of("ADR-3"), stub.lastAddCommand.relatedToCodes());
        assertTrue(rendered.contains("ADR-1"), rendered);
        assertTrue(rendered.contains("[PROPOSED]"), rendered);
        // Nothing decided yet, so nothing to date (kogn-io/arknet#374).
        assertFalse(rendered.contains("decided:"), rendered);
    }

    /**
     * kogn-io/arknet#425: {@code architecture-shapes.ttl} carries {@code sh:Warning} best-practice
     * shapes for a missing consequence/considered option, but the SHACL write gate only ever surfaced
     * {@code sh:Violation}s - the caller never learned the record was accepted without either. The
     * tool output now says so itself, non-blocking.
     */
    @Test
    void addWarnsAboutMissingConsequencesAndConsideredOptions() {
        String rendered = adapter.add(null, "A title", "Why this was needed", "What was decided",
                null, null, null, null, null, null, null, ANCHOR);

        assertTrue(rendered.contains("no consequence recorded"), rendered);
        assertTrue(rendered.contains("no considered option recorded"), rendered);
    }

    @Test
    void addOmitsTheWarningWhenBothListsAreRecorded() {
        String rendered = adapter.add(null, "Use an embedded triple store", "Why this was needed",
                "What was decided",
                List.of(new NewConsequenceInput("Faster reads", "POSITIVE")),
                List.of(new NewConsideredOptionInput("Adopt library X", "Well understood", "CHOSEN")),
                null, null, null, null, null, ANCHOR);

        assertFalse(rendered.contains("no consequence recorded"), rendered);
        assertFalse(rendered.contains("no considered option recorded"), rendered);
    }

    /** {@code adr_update}'s default in-port result (the stub's stand-in) still carries neither list. */
    @Test
    void updateRepeatsTheWarningWhileTheResultStillHasNeitherList() {
        String rendered = adapter.update(null, "ADR-1", null, null, null, null, null, null, null, null,
                null, null, null, null, ANCHOR);

        assertTrue(rendered.contains("no consequence recorded"), rendered);
        assertTrue(rendered.contains("no considered option recorded"), rendered);
    }

    @Test
    void addNormalisesBlankOptionalFieldsToNull() {
        adapter.add(null, "A title", "Why this was needed", "What was decided", null, null,
                "  ", null, null, null, null, ANCHOR);

        assertEquals(List.of(), stub.lastAddCommand.consequences());
        assertEquals(List.of(), stub.lastAddCommand.consideredOptions());
        assertEquals(null, stub.lastAddCommand.language());
    }

    @Test
    void setStatusRejectsAMalformedDecidedOn() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "ACCEPTED", "31.07.2026", ANCHOR));
    }

    /**
     * Reproduces #186 at the layer the bug actually lived in: Spring AI's
     * {@link SyncMcpToolMethodCallback} renders the deepest exception in a thrown exception's
     * {@code getCause()} chain, not the exception actually thrown - driven here over the real
     * production {@code parseDate} translation, which since kogn-io/arknet#374 sits in
     * {@link AdrMcpTools#setStatus} rather than {@code add}: that is now the one tool taking a date
     * at all, so it is the one place the #186 rendering can still bite.
     */
    @Test
    void malformedDecisionDateRemedyReachesTheMcpCaller() throws NoSuchMethodException {
        final Method method = AdrMcpTools.class.getMethod("setStatus", McpSyncRequestContext.class,
                String.class, String.class, String.class, String.class);
        final SyncMcpToolMethodCallback callback = new SyncMcpToolMethodCallback(ReturnMode.TEXT, method, adapter);
        final McpSyncServerExchange exchange = new McpSyncServerExchange(null);

        final CallToolResult result = callback.apply(exchange, new CallToolRequest("adr_set_status", Map.of(
                "id", "ADR-1",
                "status", "ACCEPTED",
                "decidedOn", "31.07.2026",
                "projectAnchor", ANCHOR)));

        final String text = result.content().stream()
                .filter(TextContent.class::isInstance)
                .map(TextContent.class::cast)
                .map(TextContent::text)
                .reduce((a, b) -> a + b)
                .orElseThrow();
        assertTrue(text.contains("decidedOn must be an ISO-8601 date"), text);
        assertFalse(text.contains("could not be parsed"), text);
    }

    @Test
    void addRejectsAnUnknownConsequenceType() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "A title", "Why this was needed", "What was decided",
                        List.of(new NewConsequenceInput("text", "NOT_A_TYPE")), null, "en",
                        null, null, null, null, ANCHOR));
    }

    @Test
    void addRejectsAnUnknownOptionOutcome() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "A title", "Why this was needed", "What was decided",
                        null, List.of(new NewConsideredOptionInput("A", "r", "MAYBE")), "en",
                        null, null, null, null, ANCHOR));
    }

    @Test
    void formatRendersTheResolvedReferenceCodesInsteadOfTheBareIris() {
        ResourceId requirementId = ResourceId.of("https://w3id.org/arknet/id/some-requirement");
        ResourceId contextId = ResourceId.of("https://w3id.org/arknet/id/some-context");
        ResourceId termId = ResourceId.of("https://w3id.org/arknet/id/some-term");
        requirements.register(requirementId, new RequirementCode("FR-7"));
        contexts.register(contextId, new BoundedContextCode("BC-3"));
        terms.register(termId, new TermCode("TERM-2"));
        stub.nextDetail = detail(adrWith(List.of(requirementId), List.of(contextId), List.of(termId), null),
                List.of(), List.of());

        String rendered = adapter.get(null, "ADR-1", null, ANCHOR);

        assertTrue(rendered.contains("addresses: FR-7"), rendered);
        assertTrue(rendered.contains("affects: BC-3"), rendered);
        assertTrue(rendered.contains("uses terms: TERM-2"), rendered);
    }

    @Test
    void formatFallsBackToTheBareIriWhenAReferenceCannotBeResolved() {
        ResourceId unresolvable = ResourceId.of("https://w3id.org/arknet/id/unknown-requirement");
        stub.nextDetail = detail(adrWith(List.of(unresolvable), List.of(), null), List.of(), List.of());

        String rendered = adapter.get(null, "ADR-1", null, ANCHOR);

        assertTrue(rendered.contains("addresses: https://w3id.org/arknet/id/unknown-requirement"), rendered);
    }

    @Test
    void formatNeverThrowsWhenAResolverReturnsDuplicateEntriesForTheSameIdentity() {
        ResourceId duplicated = ResourceId.of("https://w3id.org/arknet/id/duplicated-requirement");
        requirements.register(duplicated, new RequirementCode("FR-7"));
        requirements.register(duplicated, new RequirementCode("FR-7"));
        stub.nextDetail = detail(adrWith(List.of(duplicated), List.of(), null), List.of(), List.of());

        String rendered = adapter.get(null, "ADR-1", null, ANCHOR);

        assertTrue(rendered.contains("addresses: FR-7"), rendered);
    }

    /** Consequences/considered options are rendered as their own field, one line per entry. */
    @Test
    void formatRendersConsequencesAndConsideredOptions() {
        Adr base = adrWith(List.of(), List.of(), null);
        Adr withChildren = new Adr(base.id(), base.code(), base.name(), base.status(), base.context(),
                base.decision(), List.of(new Consequence(1, "Faster reads", ConsequenceType.POSITIVE)),
                List.of(new ConsideredOption(1, "Adopt library X", "Well understood", OptionOutcome.CHOSEN)),
                base.decisionDate(), base.addressesRequirements(), base.affectsContexts(), base.usesTerms(),
                base.supersededBy(), base.relatedTo());
        stub.nextDetail = detail(withChildren, List.of(), List.of());

        String rendered = adapter.get(null, "ADR-1", null, ANCHOR);

        assertTrue(rendered.contains("consequences:"), rendered);
        assertTrue(rendered.contains("Faster reads"), rendered);
        assertTrue(rendered.contains("considered options:"), rendered);
        assertTrue(rendered.contains("Adopt library X"), rendered);
    }

    @Test
    void listResolvesReferencesOfAllAdrsInExactlyOneBatchCallPerPort() {
        ResourceId requirementA = ResourceId.of("https://w3id.org/arknet/id/req-a");
        ResourceId requirementB = ResourceId.of("https://w3id.org/arknet/id/req-b");
        ResourceId contextA = ResourceId.of("https://w3id.org/arknet/id/ctx-a");
        ResourceId termA = ResourceId.of("https://w3id.org/arknet/id/term-a");
        requirements.register(requirementA, new RequirementCode("FR-1"));
        requirements.register(requirementB, new RequirementCode("FR-2"));
        contexts.register(contextA, new BoundedContextCode("BC-1"));
        terms.register(termA, new TermCode("TERM-1"));
        stub.allAdrs = List.of(
                detail(adrWith(List.of(requirementA), List.of(contextA), List.of(termA), null),
                        List.of(), List.of()),
                detail(adrWith(List.of(requirementB), List.of(), null), List.of(), List.of()));

        String rendered = adapter.list(null, null, ANCHOR);

        assertEquals(1, requirements.callCount());
        assertEquals(1, contexts.callCount());
        assertEquals(1, terms.callCount());
        assertTrue(rendered.contains("[addresses: FR-1]"), rendered);
        assertTrue(rendered.contains("[addresses: FR-2]"), rendered);
        assertTrue(rendered.contains("[affects: BC-1]"), rendered);
        assertTrue(rendered.contains("[uses terms: TERM-1]"), rendered);
    }

    @Test
    void listOfAdrsWithoutAnyReferencesDoesNotCallEitherResolver() {
        stub.allAdrs = List.of(detail(adrWith(List.of(), List.of(), null), List.of(), List.of()));

        adapter.list(null, null, ANCHOR);

        assertEquals(0, requirements.callCount());
        assertEquals(0, contexts.callCount());
        assertEquals(0, terms.callCount());
    }

    @Test
    void listRendersEmptyProjectAsAnExplicitMarker() {
        assertEquals("(no ADRs)", adapter.list(null, null, ANCHOR));
    }

    /**
     * kogn-io/arknet#359: a store-first status/{@code supersededBy} anomaly used to be
     * visible only as a {@code WARN} log line, so a caller of {@code adr_list} could not tell a
     * genuinely empty project from one silently missing decisions. The note makes the count visible
     * in the tool's own output.
     */
    @Test
    void listAppendsANoteWhenDecisionsWereSkipped() {
        stub.allAdrs = List.of(detail(adrWith(List.of(), List.of(), null), List.of(), List.of()));
        stub.nextSkippedCount = 2;

        String rendered = adapter.list(null, null, ANCHOR);

        assertTrue(rendered.contains("2 decisions skipped"), rendered);
    }

    @Test
    void listNotesSkippedDecisionsEvenWhenNothingElseIsListable() {
        stub.nextSkippedCount = 1;

        String rendered = adapter.list(null, null, ANCHOR);

        assertTrue(rendered.contains("1 decision skipped"), rendered);
    }

    /**
     * The note must not cost a second full read of the decision graph: {@code adr_list} already holds
     * the materialised decisions when it asks, so it hands their number over rather than letting the
     * in-port rediscover it (kogn-io/arknet#359).
     */
    @Test
    void listHandsTheAlreadyMaterialisedCountToTheSkippedCountPort() {
        stub.allAdrs = List.of(detail(adrWith(List.of(), List.of(), null), List.of(), List.of()),
                detail(adrWith(List.of(), List.of(), null), List.of(), List.of()));

        adapter.list(null, null, ANCHOR);

        assertEquals(2, stub.lastMaterialisedCount);
    }

    @Test
    void getRendersUnknownAdrMessage() {
        assertTrue(adapter.get(null, "ADR-99", null, ANCHOR).contains("ADR not found: ADR-99"));
    }

    @Test
    void getRendersBothSupersedesDirections() {
        stub.nextDetail = detail(adrWith(List.of(), List.of(), null),
                List.of(new AdrCode("ADR-0")), List.of(new AdrCode("ADR-9")));

        String rendered = adapter.get(null, "ADR-1", null, ANCHOR);

        assertTrue(rendered.contains("supersedes: ADR-0"), rendered);
        assertTrue(rendered.contains("superseded by: ADR-9"), rendered);
    }

    @Test
    void getRendersTheMergedRelatedToList() {
        stub.nextDetail = detail(adrWith(List.of(), List.of(), null), List.of(), List.of(),
                List.of(new AdrCode("ADR-3"), new AdrCode("ADR-4")));

        String rendered = adapter.get(null, "ADR-1", null, ANCHOR);

        assertTrue(rendered.contains("related to: ADR-3, ADR-4"), rendered);
    }

    @Test
    void listRendersRelatedToInline() {
        stub.allAdrs = List.of(detail(adrWith(List.of(), List.of(), null), List.of(), List.of(),
                List.of(new AdrCode("ADR-3"))));

        String rendered = adapter.list(null, null, ANCHOR);

        assertTrue(rendered.contains("[related to: ADR-3]"), rendered);
    }

    @Test
    void formatOmitsFieldsTheDecisionDoesNotCarry() {
        stub.nextDetail = detail(adrWith(List.of(), List.of(), null), List.of(), List.of());

        String rendered = adapter.get(null, "ADR-1", null, ANCHOR);

        assertFalse(rendered.contains("consequences:"), rendered);
        assertFalse(rendered.contains("considered options:"), rendered);
        assertFalse(rendered.contains("decided:"), rendered);
        assertFalse(rendered.contains("addresses:"), rendered);
        assertFalse(rendered.contains("supersedes:"), rendered);
        assertFalse(rendered.contains("related to:"), rendered);
    }

    @Test
    void setStatusAcceptsTheAcceptedTransition() {
        String rendered = adapter.setStatus(null, "ADR-1", "ACCEPTED", null, ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastAcceptedCode);
        assertTrue(rendered.contains("ADR-1"), rendered);
    }

    @Test
    void setStatusAcceptsTheRejectedTransition() {
        String rendered = adapter.setStatus(null, "ADR-1", "REJECTED", null, ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastRejectedCode);
        assertTrue(rendered.contains("ADR-1"), rendered);
    }

    @Test
    void setStatusAcceptsTheDeprecatedTransition() {
        String rendered = adapter.setStatus(null, "ADR-1", "DEPRECATED", null, ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastDeprecatedCode);
        assertTrue(rendered.contains("ADR-1"), rendered);
    }

    /**
     * The transition is the only place a decision date is ever written (kogn-io/arknet#374), so the
     * one thing this adapter owes the in-port is that an explicitly named day travels - and that
     * omitting it hands over {@code null}, which is the service's signal to stamp today rather than
     * a value this adapter invents.
     */
    @Test
    void setStatusPassesAnExplicitlyNamedDecisionDayThrough() {
        adapter.setStatus(null, "ADR-1", "ACCEPTED", "2024-03-11", ANCHOR);

        assertEquals(LocalDate.of(2024, 3, 11), stub.lastDecidedOn);

        adapter.setStatus(null, "ADR-1", "REJECTED", "2024-03-12", ANCHOR);

        assertEquals(LocalDate.of(2024, 3, 12), stub.lastDecidedOn);
    }

    @Test
    void setStatusHandsOverNullWhenNoDayIsNamed() {
        adapter.setStatus(null, "ADR-1", "ACCEPTED", null, ANCHOR);

        assertNull(stub.lastDecidedOn);

        adapter.setStatus(null, "ADR-1", "ACCEPTED", "   ", ANCHOR);

        assertNull(stub.lastDecidedOn);
    }

    /**
     * Refused rather than quietly ignored: deprecating retires a decision that was already made, so
     * a date passed here has nowhere to land - and silently dropping it would leave the caller
     * believing a day was recorded.
     */
    @Test
    void setStatusRefusesADecisionDayOnTheDeprecatedTransition() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "DEPRECATED", "2024-03-11", ANCHOR));

        assertTrue(thrown.getMessage().contains("ACCEPTED"), thrown.getMessage());
        assertNull(stub.lastDeprecatedCode);
    }

    @Test
    void setStatusIsCaseInsensitiveAndTrimmed() {
        adapter.setStatus(null, "ADR-1", "  rejected  ", null, ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastRejectedCode);
    }

    @Test
    void setStatusRejectsAnyOtherTargetStatus() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "PROPOSED", null, ANCHOR));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "SUPERSEDED", null, ANCHOR));
        assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "NOT_A_STATUS", null, ANCHOR));
    }

    @Test
    void setStatusRejectionMessageNamesTheTargetInsteadOfLeakingTheRawEnumFailure() {
        IllegalArgumentException proposed = assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "PROPOSED", null, ANCHOR));
        assertTrue(proposed.getMessage().contains("ACCEPTED"), proposed.getMessage());
        assertFalse(proposed.getMessage().contains("No enum constant"), proposed.getMessage());

        IllegalArgumentException superseded = assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "SUPERSEDED", null, ANCHOR));
        assertTrue(superseded.getMessage().contains("adr_supersede"), superseded.getMessage());
        assertFalse(superseded.getMessage().contains("No enum constant"), superseded.getMessage());

        IllegalArgumentException unknown = assertThrows(IllegalArgumentException.class,
                () -> adapter.setStatus(null, "ADR-1", "NOT_A_STATUS", null, ANCHOR));
        assertTrue(unknown.getMessage().contains("ACCEPTED"), unknown.getMessage());
        assertFalse(unknown.getMessage().contains("No enum constant"), unknown.getMessage());
    }

    @Test
    void supersedePassesBothCodesThroughToTheInPort() {
        adapter.supersede(null, "ADR-2", "ADR-1", ANCHOR);

        assertEquals(new AdrCode("ADR-2"), stub.lastSupersedingCode);
        assertEquals(new AdrCode("ADR-1"), stub.lastSupersededCode);
    }

    @Test
    void unsupersedePassesTheCodeThroughToTheInPort() {
        String rendered = adapter.unsupersede(null, "ADR-1", ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastUnsupersededCode);
        assertEquals(PROJECT, stub.lastProjectId);
        assertTrue(rendered.contains("ADR-1"), rendered);
    }

    @Test
    void deletePassesTheParsedCodeThroughAndConfirmsIt() {
        String rendered = adapter.delete(null, "ADR-1", ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastDeletedCode);
        assertEquals(PROJECT, stub.lastProjectId);
        assertEquals("Deleted: ADR-1", rendered);
    }

    @Test
    void deletePropagatesTheDidacticRefusalsUnchanged() {
        stub.deleteFailure = new AdrNotDeletableException(new AdrCode("ADR-1"), AdrStatus.REJECTED);
        AdrNotDeletableException rejected = assertThrows(AdrNotDeletableException.class,
                () -> adapter.delete(null, "ADR-1", ANCHOR));
        assertTrue(rejected.getMessage().contains("considered and turned down"), rejected.getMessage());
        assertTrue(rejected.getMessage().contains("created by mistake"), rejected.getMessage());

        stub.deleteFailure = new AdrReferencedException(PROJECT, new AdrCode("ADR-1"),
                List.of(new AdrReferencedException.Reference(new AdrCode("ADR-2"),
                        AdrReferencedException.RELATED_TO)));
        AdrReferencedException referenced = assertThrows(AdrReferencedException.class,
                () -> adapter.delete(null, "ADR-1", ANCHOR));
        assertTrue(referenced.getMessage().contains("ADR-2 (relatedTo)"), referenced.getMessage());
        assertTrue(referenced.getMessage().contains("adr_update"), referenced.getMessage());
    }

    @Test
    void theToolDescriptionsSayWhatRejectedMeansAndWhatDeleteIsFor() {
        String delete = descriptionOf("delete");
        assertTrue(delete.contains("Only a PROPOSED decision can be deleted"), delete);
        assertTrue(delete.contains("considered and turned down"), delete);
        assertTrue(delete.contains("adr_supersede"), delete);
        assertTrue(delete.contains("adr_set_status DEPRECATED"), delete);

        String setStatus = descriptionOf("setStatus");
        assertTrue(setStatus.contains("considered and turned down"), setStatus);
        assertTrue(setStatus.contains("adr_delete"), setStatus);
        assertTrue(setStatus.contains("exactly one considered option marked CHOSEN"), setStatus);
        assertTrue(setStatus.contains("consideredOptionCorrections"), setStatus);
    }

    private String descriptionOf(String methodName) {
        return Arrays.stream(adapter.getClass().getDeclaredMethods())
                .filter(method -> method.getName().equals(methodName))
                .map(method -> method.getAnnotation(McpTool.class))
                .filter(annotation -> annotation != null)
                .map(McpTool::description)
                .findFirst()
                .orElseThrow();
    }

    @Test
    void updatePassesEveryFieldThroughToTheInPort() {
        adapter.update(null, "ADR-1", "A better title", "Sharper context", "Sharper decision",
                List.of(new NewConsequenceInput("New one", "NEGATIVE")),
                List.of(new ConsequenceCorrectionInput(1, "Corrected", "POSITIVE")),
                List.of(new NewConsideredOptionInput("New option", "reason", "REJECTED")),
                List.of(new ConsideredOptionCorrectionInput(1, "Corrected option", "reason", "CHOSEN")), "en",
                List.of("FR-1"), List.of("BC-1"), List.of("TERM-1"), List.of("ADR-3"), ANCHOR);

        assertEquals(new AdrCode("ADR-1"), stub.lastUpdatedCode);
        assertEquals("A better title", stub.lastCorrection.name());
        assertEquals("Sharper context", stub.lastCorrection.context());
        assertEquals("Sharper decision", stub.lastCorrection.decision());
        assertEquals(1, stub.lastCorrection.newConsequences().size());
        assertEquals(1, stub.lastCorrection.consequenceCorrections().size());
        assertEquals(1, stub.lastCorrection.newConsideredOptions().size());
        assertEquals(1, stub.lastCorrection.consideredOptionCorrections().size());
        assertEquals("en", stub.lastCorrection.language());
        assertEquals(List.of("FR-1"), stub.lastCorrection.addressesRequirementCodes());
        assertEquals(List.of("BC-1"), stub.lastCorrection.affectsContextCodes());
        assertEquals(List.of("TERM-1"), stub.lastCorrection.usesTermCodes());
        assertEquals(List.of("ADR-3"), stub.lastCorrection.relatedToCodes());
    }

    @Test
    void updateNormalisesBlankFieldsToTheLeaveItUnchangedSentinel() {
        adapter.update(null, "ADR-1", "  ", "", "   ", null, null, null, null, "  ",
                null, null, null, null, ANCHOR);

        assertNull(stub.lastCorrection.name());
        assertNull(stub.lastCorrection.context());
        assertNull(stub.lastCorrection.decision());
        assertNull(stub.lastCorrection.language());
    }

    @Test
    void updateKeepsTheReferenceListsTriStateApart() {
        adapter.update(null, "ADR-1", null, null, null, null, null, null, null, null,
                null, null, null, null, ANCHOR);

        assertNull(stub.lastCorrection.addressesRequirementCodes());
        assertNull(stub.lastCorrection.affectsContextCodes());
        assertNull(stub.lastCorrection.usesTermCodes());
        assertNull(stub.lastCorrection.relatedToCodes());

        adapter.update(null, "ADR-1", null, null, null, null, null, null, null, null,
                List.of(), List.of(), List.of(), List.of(), ANCHOR);

        assertEquals(List.of(), stub.lastCorrection.addressesRequirementCodes());
        assertEquals(List.of(), stub.lastCorrection.affectsContextCodes());
        assertEquals(List.of(), stub.lastCorrection.usesTermCodes());
        assertEquals(List.of(), stub.lastCorrection.relatedToCodes());
    }

    /**
     * Replaces the former {@code updateRejectsAMalformedDecisionDate}, whose subject is gone:
     * {@code adr_update} no longer takes a date to malform. What is worth pinning instead is the
     * promise kogn-io/arknet#374 actually makes to a calling agent - the date is offered by exactly
     * one tool, the one that makes the decision. An agent choosing a tool reads this schema, so a
     * date reappearing on {@code adr_add}/{@code adr_update} would invite the very call the issue
     * set out to make impossible, whatever the domain then does with it.
     */
    @Test
    void onlySetStatusOffersADateAcrossTheWholeToolSurface() {
        Map<String, List<String>> dateParametersByTool = Arrays.stream(AdrMcpTools.class.getDeclaredMethods())
                .filter(m -> m.getAnnotation(McpTool.class) != null)
                .collect(Collectors.toMap(
                        m -> m.getAnnotation(McpTool.class).name(),
                        m -> Arrays.stream(m.getParameters())
                                .filter(parameter -> parameter.getName().toLowerCase(Locale.ROOT).contains("date")
                                        || parameter.getName().toLowerCase(Locale.ROOT).contains("decidedon"))
                                .map(Parameter::getName)
                                .toList()));

        assertEquals(List.of("decidedOn"), dateParametersByTool.get("adr_set_status"));
        dateParametersByTool.forEach((tool, dateParameters) -> {
            if (!"adr_set_status".equals(tool)) {
                assertEquals(List.of(), dateParameters, tool + " must not take a date");
            }
        });
    }

    private static Adr adrWith(List<ResourceId> requirementIds, List<ResourceId> contextIds,
            AdrId supersededBy) {
        return adrWith(requirementIds, contextIds, List.of(), supersededBy);
    }

    private static Adr adrWith(List<ResourceId> requirementIds, List<ResourceId> contextIds,
            List<ResourceId> termIds, AdrId supersededBy) {
        return new Adr(ID, new AdrCode("ADR-1"), "Use an embedded triple store", AdrStatus.PROPOSED,
                "Why this was needed", "What was decided", null, null, null,
                requirementIds.stream().map(RequirementRef::new).toList(),
                contextIds.stream().map(BoundedContextRef::new).toList(),
                termIds.stream().map(TermRef::new).toList(),
                supersededBy, List.of());
    }

    private static AdrDetail detail(Adr adr, List<AdrCode> supersedes, List<AdrCode> supersededBy) {
        return detail(adr, supersedes, supersededBy, List.of());
    }

    private static AdrDetail detail(Adr adr, List<AdrCode> supersedes, List<AdrCode> supersededBy,
            List<AdrCode> relatedTo) {
        return new AdrDetail(adr, supersedes, supersededBy, relatedTo);
    }

    /** Structural stub implementing the nine driving in-ports. */
    private static final class Stub
            implements AddAdr, ListAdrs, CountSkippedAdrs, GetAdr, UpdateAdr, AcceptAdr, RejectAdr, DeprecateAdr,
            SupersedeAdr, UnsupersedeAdr, DeleteAdr {

        private NewAdr lastAddCommand;
        private AdrCorrection lastCorrection;
        private AdrCode lastUpdatedCode;
        private AdrCode lastAcceptedCode;
        /** The day {@code adr_set_status} handed over, so a test can assert it travelled at all. */
        private LocalDate lastDecidedOn;
        private AdrCode lastRejectedCode;
        private AdrCode lastDeprecatedCode;
        private AdrCode lastSupersedingCode;
        private AdrCode lastSupersededCode;
        private AdrCode lastUnsupersededCode;
        private AdrCode lastDeletedCode;
        private RuntimeException deleteFailure;
        private AdrDetail nextDetail;
        private List<AdrDetail> allAdrs = List.of();
        /** What {@link #skippedCount} answers next - {@code 0} unless a test sets otherwise. */
        private int nextSkippedCount;
        /** The materialised count {@code adr_list} handed over, so a test can assert it was reused. */
        private int lastMaterialisedCount = -1;
        /** Records which project the adapter routed to, so a test can assert the routing itself. */
        private ProjectId lastProjectId;

        @Override
        public AdrDetail add(ProjectId projectId, NewAdr command, String defaultLanguage) {
            lastAddCommand = command;
            lastProjectId = projectId;
            Adr adr = new Adr(ID, new AdrCode("ADR-1"), command.name(), AdrStatus.PROPOSED,
                    command.context(), command.decision(), consequencesOf(command), consideredOptionsOf(command),
                    null, List.of(), List.of(), List.of(), null, List.of());
            return new AdrDetail(adr, List.of(), List.of(), List.of());
        }

        /** Positions the command's flat {@link NewConsequence}s the way {@code Adr#add} really would. */
        private static List<Consequence> consequencesOf(NewAdr command) {
            List<Consequence> consequences = new ArrayList<>();
            int position = 1;
            for (NewConsequence c : command.consequences()) {
                consequences.add(new Consequence(position++, c.statement(), c.type()));
            }
            return consequences;
        }

        /** {@link #consequencesOf} for {@link NewConsideredOption}. */
        private static List<ConsideredOption> consideredOptionsOf(NewAdr command) {
            List<ConsideredOption> options = new ArrayList<>();
            int position = 1;
            for (NewConsideredOption o : command.consideredOptions()) {
                options.add(new ConsideredOption(position++, o.name(), o.rationale(), o.outcome()));
            }
            return options;
        }

        @Override
        public AdrDetail update(ProjectId projectId, AdrCode code, AdrCorrection correction, String defaultLanguage) {
            lastUpdatedCode = code;
            lastCorrection = correction;
            lastProjectId = projectId;
            return detail(adrWith(List.of(), List.of(), null), List.of(), List.of());
        }

        @Override
        public List<AdrDetail> list(ProjectId projectId, String displayLocale) {
            return allAdrs;
        }

        @Override
        public int skippedCount(ProjectId projectId, int materialisedCount) {
            lastMaterialisedCount = materialisedCount;
            return nextSkippedCount;
        }

        @Override
        public Optional<AdrDetail> get(ProjectId projectId, AdrCode code, String displayLocale) {
            return Optional.ofNullable(nextDetail);
        }

        @Override
        public AdrDetail accept(ProjectId projectId, AdrCode code, LocalDate decidedOn) {
            lastAcceptedCode = code;
            lastDecidedOn = decidedOn;
            return detail(adrWith(List.of(), List.of(), null), List.of(), List.of());
        }

        @Override
        public AdrDetail reject(ProjectId projectId, AdrCode code, LocalDate decidedOn) {
            lastRejectedCode = code;
            lastDecidedOn = decidedOn;
            return detail(adrWith(List.of(), List.of(), null), List.of(), List.of());
        }

        @Override
        public AdrDetail deprecate(ProjectId projectId, AdrCode code) {
            lastDeprecatedCode = code;
            return detail(adrWith(List.of(), List.of(), null), List.of(), List.of());
        }

        @Override
        public AdrDetail supersede(ProjectId projectId, AdrCode code, AdrCode supersededCode) {
            lastSupersedingCode = code;
            lastSupersededCode = supersededCode;
            return detail(adrWith(List.of(), List.of(), null), List.of(supersededCode), List.of());
        }

        @Override
        public AdrDetail unsupersede(ProjectId projectId, AdrCode code) {
            lastUnsupersededCode = code;
            lastProjectId = projectId;
            return detail(adrWith(List.of(), List.of(), null), List.of(), List.of());
        }

        @Override
        public void delete(ProjectId projectId, AdrCode code) {
            lastDeletedCode = code;
            lastProjectId = projectId;
            if (deleteFailure != null) {
                throw deleteFailure;
            }
        }
    }

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

    /** {@link RecordingResolveBoundedContexts} for {@link ResolveTerms} (kogn-io/arknet#393). */
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
