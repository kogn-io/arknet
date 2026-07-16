package de.hauschel.arknet.uc.adapter.mcp;

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

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.uc.adapter.mcp.UseCaseMcpTools.StepInput;
import de.hauschel.arknet.uc.application.port.in.AddUseCase;
import de.hauschel.arknet.uc.application.port.in.GetUseCase;
import de.hauschel.arknet.uc.application.port.in.ListUseCases;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;

/**
 * Behaviour of the use-case MCP tools against an in-port fake: tool declaration, mapping of
 * the nested {@code uc_add} payload onto the {@link AddUseCase.NewUseCase} command, the
 * {@code uc_get}/{@code uc_list} response shape, and verbatim propagation of a didactic
 * in-port error (which Spring AI turns into a tool error result).
 *
 * <p>The rendered id a caller sees is the human-readable {@link UseCaseCode} ({@code UC1}), not
 * the opaque {@link UseCaseId} - and {@code uc_get} looks a use case up by that code.</p>
 */
class UseCaseMcpToolsTest {

    private static UseCaseId opaqueId(String slug) {
        return new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/" + slug));
    }

    private final Stub stub = new Stub();
    private final UseCaseMcpTools adapter =
            new UseCaseMcpTools(stub, stub, stub, WorkspaceId.DEFAULT);

    @Test
    void declaresTheThreeUseCaseTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(3, names.size());
        assertTrue(names.containsAll(List.of("uc_add", "uc_list", "uc_get")));
    }

    @Test
    void ucListAndUcGetAreReadOnly() {
        assertTrue(readOnly("list"));
        assertTrue(readOnly("get"));
        assertFalse(readOnly("add"));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new UseCaseMcpTools(null, stub, stub, WorkspaceId.DEFAULT));
    }

    @Test
    void rejectsNullWorkspace() {
        assertThrows(NullPointerException.class,
                () -> new UseCaseMcpTools(stub, stub, stub, null));
    }

    @Test
    void addMapsNestedStepsAndReferencesToCommand() {
        adapter.add("Place order", "Customer places an order", "Webshop", "Customer opens the cart",
                "Customer", List.of("PaymentProvider"), "Customer is logged in", "Order is recorded",
                List.of(new StepInput(1, "Customer selects items", List.of("FR-1")),
                        new StepInput(2, "Customer confirms and pays", List.of())),
                List.of("2a. Payment declined -> use case ends in failure"));

        AddUseCase.NewUseCase command = stub.lastCommand;
        assertEquals("Place order", command.title());
        assertEquals("Customer places an order", command.goal());
        assertEquals("Webshop", command.scope());
        assertEquals("Customer opens the cart", command.trigger());
        assertEquals(new ActorRef("Customer"), command.primaryActor());
        assertEquals(List.of(new ActorRef("PaymentProvider")), command.supportingActors());
        assertEquals("Customer is logged in", command.precondition());
        assertEquals("Order is recorded", command.postcondition());
        assertEquals(2, command.steps().size());
        assertEquals(new Step(1, "Customer selects items", List.of(new RequirementRef("FR-1"))),
                command.steps().get(0));
        assertEquals(List.of(), command.steps().get(1).realises());
        assertEquals(List.of("2a. Payment declined -> use case ends in failure"), command.extensions());
    }

    @Test
    void addNormalizesOmittedOptionalsToNullAndEmpty() {
        adapter.add("Reset password", "User resets password", null, null, "Customer", null, null, null,
                List.of(new StepInput(1, "User requests a reset link", null)), null);

        AddUseCase.NewUseCase command = stub.lastCommand;
        assertNull(command.scope());
        assertNull(command.trigger());
        assertNull(command.precondition());
        assertNull(command.postcondition());
        assertTrue(command.supportingActors().isEmpty());
        assertTrue(command.extensions().isEmpty());
        assertTrue(command.steps().get(0).realises().isEmpty());
    }

    @Test
    void ucGetRendersAllFieldsStepsAndExtensions() {
        stub.getResult = Optional.of(new UseCase(opaqueId("uc-1"), new UseCaseCode("UC1"), "Place order",
                "Customer places an order", "Webshop", "Customer opens the cart", new ActorRef("Customer"),
                List.of(new ActorRef("PaymentProvider")), "Customer is logged in", "Order is recorded",
                List.of(new Step(1, "Customer selects items", List.of(new RequirementRef("FR-1"))),
                        new Step(2, "Customer confirms and pays", List.of())),
                List.of("2a. Payment declined -> use case ends in failure")));

        String rendered = adapter.get("UC1");

        assertTrue(rendered.contains("UC1 Place order"));
        assertTrue(rendered.contains("primaryActor: Customer"));
        assertTrue(rendered.contains("supportingActors: PaymentProvider"));
        assertTrue(rendered.contains("1. Customer selects items -> realises FR-1"));
        assertTrue(rendered.contains("2. Customer confirms and pays"));
        assertTrue(rendered.contains("2a. Payment declined -> use case ends in failure"));
    }

    @Test
    void ucGetReturnsNotFoundMessageForUnknownCode() {
        stub.getResult = Optional.empty();
        assertEquals("Use case not found: UC99", adapter.get("UC99"));
    }

    @Test
    void ucListRendersCompactLines() {
        stub.listResult = List.of(
                new UseCase(opaqueId("uc-1"), new UseCaseCode("UC1"), "Place order",
                        "Customer places an order", null, null,
                        new ActorRef("Customer"), List.of(), null, null,
                        List.of(new Step(1, "select items", List.of())), List.of()),
                new UseCase(opaqueId("uc-2"), new UseCaseCode("UC2"), "Reset password",
                        "User resets password", null, null,
                        new ActorRef("Customer"), List.of(), null, null,
                        List.of(new Step(1, "request link", List.of())), List.of()));

        String rendered = adapter.list();

        assertEquals("UC1 | Place order | Customer places an order\n"
                + "UC2 | Reset password | User resets password", rendered);
    }

    @Test
    void ucListReturnsPlaceholderWhenEmpty() {
        stub.listResult = List.of();
        assertEquals("(no use cases)", adapter.list());
    }

    @Test
    void addPropagatesDidacticReferenceErrorVerbatim() {
        stub.addFailure = new IllegalStateException(
                "Requirement 'FR-1' does not exist in workspace 'default'. "
                        + "Create it first with req_add before a use-case step realises it.");

        RuntimeException thrown = assertThrows(RuntimeException.class,
                () -> adapter.add("Place order", "goal", null, null, "Customer", null, null, null,
                        List.of(new StepInput(1, "select items", List.of("FR-1"))), null));

        assertTrue(thrown.getMessage().contains("FR-1"));
        assertTrue(thrown.getMessage().contains("req_add"));
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

    /** Structural stub implementing the three driving in-ports. */
    private static final class Stub implements AddUseCase, ListUseCases, GetUseCase {

        private NewUseCase lastCommand;
        private RuntimeException addFailure;
        private List<UseCase> listResult = List.of();
        private Optional<UseCase> getResult = Optional.empty();

        @Override
        public UseCase add(WorkspaceId workspaceId, NewUseCase command) {
            this.lastCommand = command;
            if (addFailure != null) {
                throw addFailure;
            }
            return new UseCase(opaqueId("uc-1"), new UseCaseCode("UC1"), command.title(), command.goal(),
                    command.scope(), command.trigger(), command.primaryActor(), command.supportingActors(),
                    command.precondition(), command.postcondition(), command.steps(), command.extensions());
        }

        @Override
        public List<UseCase> list(WorkspaceId workspaceId) {
            return listResult;
        }

        @Override
        public Optional<UseCase> get(WorkspaceId workspaceId, UseCaseCode code) {
            return getResult;
        }
    }
}
