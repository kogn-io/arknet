// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.uc.adapter.mcp.UseCaseMcpTools.StepInput;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.application.port.in.UpdateUseCase;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.StepPositionNotFoundException;
import de.hauschel.arknet.uc.domain.StepTextPatch;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Behaviour of the use-case MCP tools against an in-port fake: tool declaration, mapping of
 * the nested {@code uc_add} payload onto the {@link AddUseCase.NewUseCase} command (now raw
 * human-typed strings), the {@code uc_get}/{@code uc_list} response shape - including
 * the actor/requirement display-resolution contract borrowed from {@link ResolveTerms}/
 * {@link ResolveRequirements} - and verbatim propagation of a didactic in-port error (which
 * Spring AI turns into a tool error result).
 *
 * <p>The rendered id a caller sees is the human-readable {@link UseCaseCode} ({@code UC1}), not
 * the opaque {@link UseCaseId} - and {@code uc_get} looks a use case up by that code.</p>
 */
class UseCaseMcpToolsTest {

    private static UseCaseId opaqueId(String slug) {
        return new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/" + slug));
    }

    /** Fake resolver: every call routes to the same fixed project, ignoring the origin. */
    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Stands in for the registry lookup: every anchor this test sends resolves to {@link #PROJECT}. */
    private static final ProjectResolver PROJECTS = anchor -> new ResolvedProject(PROJECT, null);

    private final Stub stub = new Stub();
    private final RecordingResolveTerms resolveTerms = new RecordingResolveTerms();
    private final RecordingResolveRequirements resolveRequirements = new RecordingResolveRequirements();
    private final UseCaseMcpTools adapter =
            new UseCaseMcpTools(stub, stub, stub, stub, resolveTerms, resolveRequirements, PROJECTS);

    @Test
    void declaresTheFourUseCaseTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(4, names.size());
        assertTrue(names.containsAll(List.of("uc_add", "uc_list", "uc_get", "uc_update")));
    }

    @Test
    void ucListAndUcGetAreReadOnly() {
        assertTrue(readOnly("list"));
        assertTrue(readOnly("get"));
        assertFalse(readOnly("add"));
        assertFalse(readOnly("update"));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new UseCaseMcpTools(null, stub, stub, stub, resolveTerms, resolveRequirements, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new UseCaseMcpTools(stub, null, stub, stub, resolveTerms, resolveRequirements, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new UseCaseMcpTools(stub, stub, null, stub, resolveTerms, resolveRequirements, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new UseCaseMcpTools(stub, stub, stub, null, resolveTerms, resolveRequirements, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new UseCaseMcpTools(stub, stub, stub, stub, null, resolveRequirements, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new UseCaseMcpTools(stub, stub, stub, stub, resolveTerms, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new UseCaseMcpTools(stub, stub, stub, stub, resolveTerms, resolveRequirements, null));
    }

    @Test
    void addMapsNestedStepsAndReferencesToCommand() {
        adapter.add(null, "Place order", "Customer places an order", "Webshop", "Customer opens the cart",
                "Customer", List.of("PaymentProvider"), "Customer is logged in", "Order is recorded",
                List.of(new StepInput(1, "Customer selects items", List.of("FR-1")),
                        new StepInput(2, "Customer confirms and pays", List.of())),
                List.of("2a. Payment declined -> use case ends in failure"), null, null);

        AddUseCase.NewUseCase command = stub.lastCommand;
        assertEquals("Place order", command.title());
        assertEquals("Customer places an order", command.goal());
        assertEquals("Webshop", command.scope());
        assertEquals("Customer opens the cart", command.trigger());
        assertEquals("Customer", command.primaryActor());
        assertEquals(List.of("PaymentProvider"), command.supportingActors());
        assertEquals("Customer is logged in", command.precondition());
        assertEquals("Order is recorded", command.postcondition());
        assertEquals(2, command.steps().size());
        assertEquals(new NewStep(1, "Customer selects items", List.of("FR-1")), command.steps().get(0));
        assertEquals(List.of(), command.steps().get(1).realises());
        assertEquals(List.of("2a. Payment declined -> use case ends in failure"), command.extensions());
    }

    @Test
    void addNormalizesOmittedOptionalsToNullAndEmpty() {
        adapter.add(null, "Reset password", "User resets password", null, null, "Customer", null, null, null,
                List.of(new StepInput(1, "User requests a reset link", null)), null, null, null);

        AddUseCase.NewUseCase command = stub.lastCommand;
        assertNull(command.scope());
        assertNull(command.trigger());
        assertNull(command.precondition());
        assertNull(command.postcondition());
        assertTrue(command.supportingActors().isEmpty());
        assertTrue(command.extensions().isEmpty());
        assertTrue(command.steps().get(0).realises().isEmpty());
    }

    /** {@code uc_add}'s {@code language} argument reaches {@link AddUseCase.NewUseCase} unchanged. */
    @Test
    void addPassesTheLanguageThrough() {
        adapter.add(null, "Place order", "Customer places an order", null, null, "Customer", null, null, null,
                List.of(new StepInput(1, "Customer selects items", List.of())), null, "de", null);

        assertEquals("de", stub.lastCommand.language());
    }

    /** A blank {@code language} is treated as omitted (untagged), mirroring every other optional field. */
    @Test
    void addTreatsABlankLanguageAsOmitted() {
        adapter.add(null, "Place order", "Customer places an order", null, null, "Customer", null, null, null,
                List.of(new StepInput(1, "Customer selects items", List.of())), null, "  ", null);

        assertEquals(null, stub.lastCommand.language());
    }

    /** An explicit {@code uc_get} {@code displayLocale} wins over the project's own default. */
    @Test
    void getPassesAnExplicitDisplayLocaleThrough() {
        stub.getResult = Optional.of(new UseCase(opaqueId("uc-1"), new UseCaseCode("UC1"), "Place order", "goal",
                null, null, new ActorRef(ResourceId.of("https://w3id.org/arknet/id/actor-customer")), List.of(),
                null, null, List.of(new Step(1, "select items", List.of())), List.of()));
        UseCaseMcpTools adapterWithDefault = new UseCaseMcpTools(stub, stub, stub, stub, resolveTerms,
                resolveRequirements, anchor -> new ResolvedProject(PROJECT, "de"));

        adapterWithDefault.get(null, "UC1", "en", null);

        assertEquals("en", stub.lastGetDisplayLocale);
    }

    /** An omitted {@code uc_get} {@code displayLocale} falls back to the project's own default. */
    @Test
    void getFallsBackToTheProjectsDefaultLanguageWhenDisplayLocaleIsOmitted() {
        stub.getResult = Optional.of(new UseCase(opaqueId("uc-1"), new UseCaseCode("UC1"), "Place order", "goal",
                null, null, new ActorRef(ResourceId.of("https://w3id.org/arknet/id/actor-customer")), List.of(),
                null, null, List.of(new Step(1, "select items", List.of())), List.of()));
        UseCaseMcpTools adapterWithDefault = new UseCaseMcpTools(stub, stub, stub, stub, resolveTerms,
                resolveRequirements, anchor -> new ResolvedProject(PROJECT, "de"));

        adapterWithDefault.get(null, "UC1", null, null);

        assertEquals("de", stub.lastGetDisplayLocale);
    }

    /** {@code uc_update}'s {@code language} argument reaches {@link UpdateUseCase} unchanged. */
    @Test
    void updatePassesTheLanguageThrough() {
        adapter.update(null, "UC1", "Neuer Titel", null, null, null, null, null, null, null, null, "de", null);

        assertEquals("de", stub.lastUpdateLanguage);
    }

    @Test
    void ucGetRendersAllFieldsStepsAndExtensions() {
        ActorRef primaryActor = new ActorRef(ResourceId.of("https://w3id.org/arknet/id/actor-customer"));
        ActorRef supportingActor = new ActorRef(ResourceId.of("https://w3id.org/arknet/id/actor-payment-provider"));
        RequirementRef fr1 = new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/req-fr1"));
        resolveTerms.register(primaryActor.value(), new TermCode("Customer"));
        resolveTerms.register(supportingActor.value(), new TermCode("PaymentProvider"));
        resolveRequirements.register(fr1.value(), new RequirementCode("FR-1"));

        stub.getResult = Optional.of(new UseCase(opaqueId("uc-1"), new UseCaseCode("UC1"), "Place order",
                "Customer places an order", "Webshop", "Customer opens the cart", primaryActor,
                List.of(supportingActor), "Customer is logged in", "Order is recorded",
                List.of(new Step(1, "Customer selects items", List.of(fr1)),
                        new Step(2, "Customer confirms and pays", List.of())),
                List.of("2a. Payment declined -> use case ends in failure")));

        String rendered = adapter.get(null, "UC1", null, null);

        assertTrue(rendered.contains("UC1 Place order"));
        assertTrue(rendered.contains("primaryActor: Customer"));
        assertTrue(rendered.contains("supportingActors: PaymentProvider"));
        assertTrue(rendered.contains("1. Customer selects items -> realises FR-1"));
        assertTrue(rendered.contains("2. Customer confirms and pays"));
        assertTrue(rendered.contains("2a. Payment declined -> use case ends in failure"));
    }

    /**
     * Hard invariant (mirrors {@code RequirementMcpToolsTest}): an id neither
     * {@link ResolveTerms} nor {@link ResolveRequirements} can resolve must never make
     * rendering throw - it falls back to the bare IRI.
     */
    @Test
    void ucGetFallsBackToTheBareIriWhenResolutionCannotResolveIt() {
        ActorRef unresolvableActor = new ActorRef(ResourceId.of("https://w3id.org/arknet/id/unknown-actor"));
        RequirementRef unresolvableRequirement =
                new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/unknown-req"));
        // Deliberately not registered with either resolver - simulates a missing/deleted actor/requirement.

        stub.getResult = Optional.of(new UseCase(opaqueId("uc-1"), new UseCaseCode("UC1"), "Place order",
                "Customer places an order", null, null, unresolvableActor, List.of(), null, null,
                List.of(new Step(1, "select items", List.of(unresolvableRequirement))), List.of()));

        String rendered = adapter.get(null, "UC1", null, null);

        assertTrue(rendered.contains("primaryActor: https://w3id.org/arknet/id/unknown-actor"), rendered);
        assertTrue(rendered.contains("realises https://w3id.org/arknet/id/unknown-req"), rendered);
    }

    @Test
    void ucGetReturnsNotFoundMessageForUnknownCode() {
        stub.getResult = Optional.empty();
        assertEquals("Use case not found: UC99", adapter.get(null, "UC99", null, null));
    }

    @Test
    void ucListRendersCompactLines() {
        ActorRef customer = new ActorRef(ResourceId.of("https://w3id.org/arknet/id/actor-customer"));
        stub.listResult = List.of(
                new UseCase(opaqueId("uc-1"), new UseCaseCode("UC1"), "Place order",
                        "Customer places an order", null, null,
                        customer, List.of(), null, null,
                        List.of(new Step(1, "select items", List.of())), List.of()),
                new UseCase(opaqueId("uc-2"), new UseCaseCode("UC2"), "Reset password",
                        "User resets password", null, null,
                        customer, List.of(), null, null,
                        List.of(new Step(1, "request link", List.of())), List.of()));

        String rendered = adapter.list(null, null);

        assertEquals("UC1 | Place order | Customer places an order\n"
                + "UC2 | Reset password | User resets password", rendered);
    }

    @Test
    void ucListReturnsPlaceholderWhenEmpty() {
        stub.listResult = List.of();
        assertEquals("(no use cases)", adapter.list(null, null));
    }

    @Test
    void addPropagatesDidacticReferenceErrorVerbatim() {
        stub.addFailure = new IllegalStateException(
                "Requirement 'FR-1' does not exist in project 'default'. "
                        + "Create it first with req_add before a use-case step realises it.");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> adapter.add(null, "Place order", "goal", null, null, "Customer", null, null, null,
                        List.of(new StepInput(1, "select items", List.of("FR-1"))), null, null, null));

        assertTrue(thrown.getMessage().contains("FR-1"));
        assertTrue(thrown.getMessage().contains("req_add"));
    }

    /** {@code uc_update} passes every given field through to the in-port. */
    @Test
    void updatePassesAllGivenFieldsThroughToTheInPort() {
        String rendered = adapter.update(null, "UC1", "New title", "New goal", "New scope", "New trigger",
                "New precondition", "New postcondition", List.of("2a. abort"),
                List.of(new UseCaseMcpTools.StepPatchInput(1, "corrected text")), null, null, null);

        assertEquals(new UseCaseCode("UC1"), stub.lastUpdatedUseCase);
        assertEquals("New title", stub.lastUpdateTitle);
        assertEquals("New goal", stub.lastUpdateGoal);
        assertEquals("New scope", stub.lastUpdateScope);
        assertEquals("New trigger", stub.lastUpdateTrigger);
        assertEquals("New precondition", stub.lastUpdatePrecondition);
        assertEquals("New postcondition", stub.lastUpdatePostcondition);
        assertEquals(List.of("2a. abort"), stub.lastUpdateExtensions);
        assertEquals(List.of(new StepTextPatch(1, "corrected text")), stub.lastUpdateStepTextPatches);
        assertTrue(rendered.contains("New title"), rendered);
    }

    /** {@code uc_update} maps {@code stepRealisesPatches} to {@link UpdateUseCase.StepRealisesPatch}. */
    @Test
    void updatePassesStepRealisesPatchesThroughMappedToThePort() {
        adapter.update(null, "UC1", null, null, null, null, null, null, null, null,
                List.of(new UseCaseMcpTools.StepRealisesPatchInput(1, List.of("FR-1", "FR-2")),
                        new UseCaseMcpTools.StepRealisesPatchInput(2, List.of())),
                null, null);

        assertEquals(List.of(new UpdateUseCase.StepRealisesPatch(1, List.of("FR-1", "FR-2")),
                new UpdateUseCase.StepRealisesPatch(2, List.of())), stub.lastUpdateStepRealisesPatches);
    }

    /**
     * A listed {@code stepRealisesPatches} position with {@code realises} omitted/{@code null}
     * must be rejected rather than silently treated as "clear all references" for that step - the
     * one place a caller who simply forgot the field would otherwise delete requirement links by
     * accident (issue #255). To leave a step's realises untouched, its position must not be listed
     * at all; to clear it on purpose, {@code realises} must be an explicit empty list.
     */
    @Test
    void updateRejectsAStepRealisesPatchWithOmittedRealises() {
        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> adapter.update(null, "UC1", null, null, null, null, null, null, null, null,
                        List.of(new UseCaseMcpTools.StepRealisesPatchInput(3, null)), null, null));

        assertTrue(thrown.getMessage().contains("3"), thrown.getMessage());
        assertTrue(thrown.getMessage().contains("realises"), thrown.getMessage());
    }

    /**
     * An omitted field must reach {@link UpdateUseCase} as {@code null} - so the port (not this
     * adapter) decides that "unchanged" means "leave the existing value".
     */
    @Test
    void updateWithOmittedFieldsPassesNullThroughForEachOfThem() {
        adapter.update(null, "UC1", null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(new UseCaseCode("UC1"), stub.lastUpdatedUseCase);
        assertEquals(null, stub.lastUpdateTitle);
        assertEquals(null, stub.lastUpdateGoal);
        assertEquals(null, stub.lastUpdateScope);
        assertEquals(null, stub.lastUpdateTrigger);
        assertEquals(null, stub.lastUpdatePrecondition);
        assertEquals(null, stub.lastUpdatePostcondition);
        assertEquals(null, stub.lastUpdateExtensions);
        assertEquals(null, stub.lastUpdateStepTextPatches);
        assertEquals(null, stub.lastUpdateStepRealisesPatches);
    }

    /** A blank string is treated as omitted, the same tolerance {@code uc_add} already applies. */
    @Test
    void updateTreatsABlankFieldAsOmitted() {
        adapter.update(null, "UC1", "  ", null, null, null, null, null, null, null, null, null, null);

        assertEquals(null, stub.lastUpdateTitle);
    }

    /**
     * An unknown step position surfaces as a tool error, not a silent no-op - the didactic
     * {@link StepPositionNotFoundException} message reaches the caller verbatim, the same way
     * {@code uc_add}'s reference-resolution errors do.
     */
    @Test
    void updatePropagatesAnUnknownStepPositionVerbatim() {
        stub.updateFailure = new StepPositionNotFoundException(PROJECT, new UseCaseCode("UC1"), 99);

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> adapter.update(null, "UC1", null, null, null, null, null, null, null,
                        List.of(new UseCaseMcpTools.StepPatchInput(99, "does not exist")), null, null, null));

        assertTrue(thrown.getMessage().contains("99"), thrown.getMessage());
    }

    private boolean readOnly(String methodName) {
        return Arrays.stream(adapter.getClass().getDeclaredMethods())
                .filter(m -> m.getName().equals(methodName))
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .findFirst()
                .orElseThrow()
                .annotations().readOnlyHint();
    }

    /** Structural stub implementing the four driving in-ports. */
    private static final class Stub implements AddUseCase, ListUseCases, GetUseCase, UpdateUseCase {

        private AddUseCase.NewUseCase lastCommand;
        private RuntimeException addFailure;
        private List<UseCase> listResult = List.of();
        private Optional<UseCase> getResult = Optional.empty();
        private UseCaseCode lastUpdatedUseCase;
        private String lastUpdateTitle;
        private String lastUpdateGoal;
        private String lastUpdateScope;
        private String lastUpdateTrigger;
        private String lastUpdatePrecondition;
        private String lastUpdatePostcondition;
        private List<String> lastUpdateExtensions;
        private List<StepTextPatch> lastUpdateStepTextPatches;
        private List<UpdateUseCase.StepRealisesPatch> lastUpdateStepRealisesPatches;
        private RuntimeException updateFailure;

        @Override
        public UseCase add(ProjectId projectId, AddUseCase.NewUseCase command) {
            this.lastCommand = command;
            if (addFailure != null) {
                throw addFailure;
            }
            ActorRef primaryActor = new ActorRef(ResourceId.of("https://w3id.org/arknet/id/actor-"
                    + command.primaryActor()));
            List<ActorRef> supportingActors = command.supportingActors().stream()
                    .map(name -> new ActorRef(ResourceId.of("https://w3id.org/arknet/id/actor-" + name)))
                    .toList();
            List<Step> steps = command.steps().stream()
                    .map(step -> new Step(step.position(), step.text(),
                            step.realises().stream()
                                    .map(code -> new RequirementRef(ResourceId.of("https://w3id.org/arknet/id/req-"
                                            + code)))
                                    .toList()))
                    .toList();
            return new UseCase(opaqueId("uc-1"), new UseCaseCode("UC1"), command.title(), command.goal(),
                    command.scope(), command.trigger(), primaryActor, supportingActors,
                    command.precondition(), command.postcondition(), steps, command.extensions());
        }

        @Override
        public List<UseCase> list(ProjectId projectId) {
            return listResult;
        }

        private String lastGetDisplayLocale;

        @Override
        public Optional<UseCase> get(ProjectId projectId, UseCaseCode code, String displayLocale) {
            lastGetDisplayLocale = displayLocale;
            return getResult;
        }

        private String lastUpdateLanguage;

        @Override
        public UseCase update(ProjectId projectId, UseCaseCode code, String title, String goal, String scope,
                String trigger, String precondition, String postcondition, List<String> extensions,
                List<StepTextPatch> stepTextPatches, List<UpdateUseCase.StepRealisesPatch> stepRealisesPatches,
                String language) {
            if (updateFailure != null) {
                throw updateFailure;
            }
            lastUpdatedUseCase = code;
            lastUpdateTitle = title;
            lastUpdateGoal = goal;
            lastUpdateScope = scope;
            lastUpdateTrigger = trigger;
            lastUpdatePrecondition = precondition;
            lastUpdatePostcondition = postcondition;
            lastUpdateExtensions = extensions;
            lastUpdateStepTextPatches = stepTextPatches;
            lastUpdateStepRealisesPatches = stepRealisesPatches;
            lastUpdateLanguage = language;
            ActorRef primaryActor = new ActorRef(ResourceId.of("https://w3id.org/arknet/id/actor-customer"));
            return new UseCase(opaqueId("uc-1"), code, title != null ? title : "t",
                    goal != null ? goal : "goal", scope, trigger, primaryActor, List.of(), precondition,
                    postcondition, List.of(new Step(1, "do something", List.of())),
                    extensions != null ? extensions : List.of());
        }
    }

    /**
     * Fake {@link ResolveTerms}: resolves only what was {@link #register} registered - like the
     * real port, never throws for an id it cannot resolve.
     */
    private static final class RecordingResolveTerms implements ResolveTerms {

        private final List<ResolvedTerm> known = new ArrayList<>();

        void register(ResourceId id, TermCode code) {
            known.add(new ResolvedTerm(id, code));
        }

        @Override
        public List<ResolvedTerm> resolve(ProjectId projectId, ResourceId... ids) {
            List<ResourceId> wanted = Arrays.asList(ids);
            return known.stream().filter(t -> wanted.contains(t.id())).toList();
        }
    }

    /**
     * Fake {@link ResolveRequirements}: resolves only what was {@link #register} registered -
     * like the real port, never throws for an id it cannot resolve.
     */
    private static final class RecordingResolveRequirements implements ResolveRequirements {

        private final List<ResolvedRequirement> known = new ArrayList<>();

        void register(ResourceId id, RequirementCode code) {
            known.add(new ResolvedRequirement(id, code));
        }

        @Override
        public List<ResolvedRequirement> resolveExisting(ProjectId projectId, ResourceId... ids) {
            List<ResourceId> wanted = Arrays.asList(ids);
            return known.stream().filter(r -> wanted.contains(r.id())).toList();
        }
    }
}
