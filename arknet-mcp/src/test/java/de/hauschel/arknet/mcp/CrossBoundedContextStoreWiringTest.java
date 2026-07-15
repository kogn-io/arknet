package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.kogn.rdf.dataset.DatasetLifecycle;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.uc.adapter.kogniordf.UnresolvedReferenceException;
import de.hauschel.arknet.uc.adapter.mcp.UseCaseMcpTools;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;

/**
 * The regression proof for issue #41: three bounded contexts (requirements,
 * ubiquitous-language, use-cases) wired by {@link ArknetMcpConfiguration} must share the
 * <em>single</em> {@link DatasetLifecycle} bean, so a use case can strictly resolve its
 * requirement/actor label references against the very resources the other two contexts wrote.
 *
 * <p>Every earlier adapter test ran each context on its own in-memory lifecycle, which is
 * exactly what hid the store-lock/isolation bug: cross-context lookup was never exercised over
 * a shared store. Here all three services are obtained from one Spring context - hence one
 * shared lifecycle bean - and the real out-adapters (not fakes) are used end to end.</p>
 */
class CrossBoundedContextStoreWiringTest {

    private static final WorkspaceId WS = new WorkspaceId("cross-bc");

    @TempDir
    Path storageDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    @Test
    void useCaseResolvesRequirementAndActorWrittenByTheOtherContextsOverTheSharedStore() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir,
                        "arknet.workspace.id=test-workspace")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // One shared lifecycle, and the use-cases hexagon is wired as a tool bean.
                    assertThat(context).hasSingleBean(DatasetLifecycle.class);
                    assertThat(context).hasSingleBean(UseCaseMcpTools.class);

                    RequirementService requirements = context.getBean(RequirementService.class);
                    TermService terms = context.getBean(TermService.class);
                    UseCaseService useCases = context.getBean(UseCaseService.class);

                    // req_add (FR) and term_add (actor) into the same shared workspace store.
                    Requirement fr = requirements.add(WS, new NewRequirement("Customer can order",
                            "The system shall let a customer place an order.",
                            RequirementType.FUNCTIONAL, null, null, null));
                    terms.add(WS, new NewTerm("Customer", "A person placing an order.",
                            new ActorFacet(ActorKind.HUMAN, "orderer")));

                    // uc_add referencing that FR (by its assigned id) and that actor (by label).
                    UseCase created = useCases.add(WS, new NewUseCase("Place order",
                            "Customer places an order", null, null, new ActorRef("Customer"),
                            List.of(), null, null,
                            List.of(new Step(1, "Customer selects items and confirms",
                                    List.of(new RequirementRef(fr.id().value())))),
                            List.of()));

                    // uc_get reads the resolved cross-context edges back.
                    UseCase reloaded = useCases.get(WS, created.id()).orElseThrow();
                    assertThat(reloaded.primaryActor()).isEqualTo(new ActorRef("Customer"));
                    assertThat(reloaded.steps()).singleElement()
                            .satisfies(step -> assertThat(step.realises())
                                    .containsExactly(new RequirementRef(fr.id().value())));
                });
    }

    @Test
    void useCaseWithUnknownReferencesIsRejectedWithDidacticMessageAndNothingPersisted() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir,
                        "arknet.workspace.id=test-workspace")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TermService terms = context.getBean(TermService.class);
                    UseCaseService useCases = context.getBean(UseCaseService.class);

                    // The actor exists, but the referenced requirement FR-1 was never created:
                    // strict step-realises resolution must abort on the unknown FR (order in the
                    // out-adapter resolves the primary actor first, hence seed it).
                    terms.add(WS, new NewTerm("Customer", "A person placing an order.",
                            new ActorFacet(ActorKind.HUMAN, "orderer")));

                    NewUseCase danglingFr = new NewUseCase("Broken", "Unresolvable requirement",
                            null, null, new ActorRef("Customer"), List.of(), null, null,
                            List.of(new Step(1, "does something", List.of(new RequirementRef("FR-1")))),
                            List.of());

                    assertThatThrownBy(() -> useCases.add(WS, danglingFr))
                            .isInstanceOf(UnresolvedReferenceException.class)
                            .hasMessageContaining("FR-1")
                            .hasMessageContaining("req_add");

                    assertThat(useCases.list(WS)).isEmpty();
                });
    }

    /**
     * The same proof for the requirement -&gt; glossary-term edge of issue #36: {@code term_add}
     * writes a concept, {@code req_link_term} resolves it over the shared store and
     * {@code req_get} reads the edge back.
     */
    @Test
    void requirementLinksATermWrittenByTheOtherContextOverTheSharedStore() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir,
                        "arknet.workspace.id=test-workspace")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RequirementService requirements = context.getBean(RequirementService.class);
                    TermService terms = context.getBean(TermService.class);

                    Requirement fr = requirements.add(WS, new NewRequirement("Customer can order",
                            "The system shall let a customer place an order.",
                            RequirementType.FUNCTIONAL, null, null, null));
                    Term order = terms.add(WS, new NewTerm("Order", "A customer's request to buy.", null));

                    requirements.linkTerm(WS, fr.id(), new TermRef(order.id().value()));

                    assertThat(requirements.get(WS, fr.id()).orElseThrow().usesTerms())
                            .containsExactly(new TermRef(order.id().value()));
                });
    }

    /**
     * A term identity that no concept carries must abort the link with a didactic message and
     * persist nothing - the requirements BC never creates a dangling {@code arkreq:usesTerm}.
     */
    @Test
    void linkingAnUnknownTermIsRejectedWithDidacticMessageAndNothingPersisted() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir,
                        "arknet.workspace.id=test-workspace")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RequirementService requirements = context.getBean(RequirementService.class);

                    Requirement fr = requirements.add(WS, new NewRequirement("Customer can order",
                            "The system shall let a customer place an order.",
                            RequirementType.FUNCTIONAL, null, null, null));

                    assertThatThrownBy(() -> requirements.linkTerm(WS, fr.id(), new TermRef("TERM-99")))
                            .isInstanceOf(
                                    de.hauschel.arknet.req.adapter.kogniordf.UnresolvedReferenceException.class)
                            .hasMessageContaining("TERM-99")
                            .hasMessageContaining("term_add");

                    assertThat(requirements.get(WS, fr.id()).orElseThrow().usesTerms()).isEmpty();
                });
    }
}
