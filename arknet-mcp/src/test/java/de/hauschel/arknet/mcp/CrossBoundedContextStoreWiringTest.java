// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.UnresolvedReferenceException;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.uc.adapter.mcp.UseCaseMcpTools;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.ActorKind;
import de.hauschel.arknet.ul.domain.Term;

/**
 * The regression proof: three bounded contexts (requirements,
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

    /**
     * The project all four hexagons write into here.
     *
     * <p>These tests used to additionally pin {@code arknet.workspace.id}, a property removed with
     * the derived resolution path (ADR-016 decision 9). Dropping it costs this test nothing: it
     * drives the application services directly, and each of them takes the project as a parameter -
     * the property only ever configured the resolver, which is not on this path.</p>
     */
    private static final ProjectId PROJECT = new ProjectId("cross-bc");

    @TempDir
    Path storageDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    @Test
    void wiresASingleSharedDatasetLifecycleAndUseCaseMcpTools() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    // One shared lifecycle, and the use-cases hexagon is wired as a tool bean.
                    assertThat(context).hasSingleBean(DatasetLifecycle.class);
                    assertThat(context).hasSingleBean(UseCaseMcpTools.class);
                });
    }

    /**
     * Shared setup for the domain-resolution assertions below: {@code req_add} (FR) and {@code
     * term_add} (actor) into the same shared project store, then {@code uc_add} referencing that
     * FR (by its code) and that actor (by name) - the service resolves both raw strings to opaque
     * identities via ActorLookup/RequirementLookup before the real UseCase is constructed. {@code
     * uc_get} reads the resolved cross-context edges back (looked up by code), so the resolved
     * identity, not a label, is what the reference carries.
     *
     * <p>Split from a formerly bundled test (issue #118) that mixed this domain-resolution proof
     * with the wiring assertions now in {@link #wiresASingleSharedDatasetLifecycleAndUseCaseMcpTools()}.</p>
     */
    private void assertOnResolvedUseCaseRoundTrip(final Consumer<ResolvedUseCaseRoundTrip> assertion) {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();

                    RequirementService requirements = context.getBean(RequirementService.class);
                    TermService terms = context.getBean(TermService.class);
                    UseCaseService useCases = context.getBean(UseCaseService.class);

                    Requirement fr = requirements.add(PROJECT, new NewRequirement("Customer can order",
                            "The system shall let a customer place an order.",
                            RequirementType.FUNCTIONAL, null, null, null,
                            List.of("An order is placed and confirmed"), null), "en");
                    terms.add(PROJECT, new NewTerm("Customer", "A person placing an order.",
                            new ActorFacet(ActorKind.HUMAN, "orderer"), null), "en");

                    UseCase created = useCases.add(PROJECT, new NewUseCase("Place order",
                            "Customer places an order", null, null, "Customer",
                            List.of(), null, null,
                            List.of(new NewStep(1, "Customer selects items and confirms",
                                    List.of(fr.code().value()))),
                            List.of(), null), "en");

                    UseCase reloaded = useCases.get(PROJECT, created.code(), null).orElseThrow();
                    assertion.accept(new ResolvedUseCaseRoundTrip(created, reloaded, fr.id().value()));
                });
    }

    /** The created/reloaded pair a resolved-round-trip assertion inspects, plus the FR's opaque identity. */
    private record ResolvedUseCaseRoundTrip(UseCase created, UseCase reloaded, ResourceId requirementId) { }

    @Test
    void useCaseResolvesTheActorWrittenByTheOtherContextOverTheSharedStore() {
        assertOnResolvedUseCaseRoundTrip(roundTrip ->
                assertThat(roundTrip.reloaded().primaryActor()).isEqualTo(roundTrip.created().primaryActor()));
    }

    @Test
    void useCaseResolvesTheRequirementWrittenByTheOtherContextOverTheSharedStore() {
        assertOnResolvedUseCaseRoundTrip(roundTrip ->
                assertThat(roundTrip.reloaded().steps()).singleElement()
                        .satisfies(step -> assertThat(step.realises())
                                .extracting(RequirementRef::value)
                                .containsExactly(roundTrip.requirementId())));
    }

    @Test
    void useCaseWithUnknownReferencesIsRejectedWithDidacticMessageAndNothingPersisted() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    TermService terms = context.getBean(TermService.class);
                    UseCaseService useCases = context.getBean(UseCaseService.class);

                    // The actor exists, but the referenced requirement FR-1 was never created:
                    // strict step-realises resolution must abort on the unknown FR (order in the
                    // out-adapter resolves the primary actor first, hence seed it).
                    terms.add(PROJECT, new NewTerm("Customer", "A person placing an order.",
                            new ActorFacet(ActorKind.HUMAN, "orderer"), null), "en");

                    NewUseCase danglingFr = new NewUseCase("Broken", "Unresolvable requirement",
                            null, null, "Customer", List.of(), null, null,
                            List.of(new NewStep(1, "does something", List.of("FR-1"))),
                            List.of(), null);

                    assertThatThrownBy(() -> useCases.add(PROJECT, danglingFr, "en"))
                            .isInstanceOf(UnresolvedReferenceException.class)
                            .hasMessageContaining("FR-1")
                            .hasMessageContaining("req_add");

                    assertThat(useCases.list(PROJECT, null)).isEmpty();
                });
    }

    /**
     * The same proof for the requirement -&gt; glossary-term edge: {@code term_add}
     * writes a concept, {@code req_link_term} resolves it over the shared store and
     * {@code req_get} reads the edge back.
     */
    @Test
    void requirementLinksATermWrittenByTheOtherContextOverTheSharedStore() {
        contextRunner
                .withPropertyValues(
                        "arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RequirementService requirements = context.getBean(RequirementService.class);
                    TermService terms = context.getBean(TermService.class);

                    Requirement fr = requirements.add(PROJECT, new NewRequirement("Customer can order",
                            "The system shall let a customer place an order.",
                            RequirementType.FUNCTIONAL, null, null, null,
                            List.of("An order is placed and confirmed"), null), "en");
                    Term order = terms.add(PROJECT, new NewTerm("Order", "A customer's request to buy.", null, null), "en");

                    requirements.linkTerm(PROJECT, fr.code(), order.code().value());

                    assertThat(requirements.get(PROJECT, fr.code(), null).orElseThrow().usesTerms())
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
                        "arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    RequirementService requirements = context.getBean(RequirementService.class);

                    Requirement fr = requirements.add(PROJECT, new NewRequirement("Customer can order",
                            "The system shall let a customer place an order.",
                            RequirementType.FUNCTIONAL, null, null, null,
                            List.of("An order is placed and confirmed"), null), "en");

                    assertThatThrownBy(() -> requirements.linkTerm(PROJECT, fr.code(), "TERM-99"))
                            .isInstanceOf(UnresolvedReferenceException.class)
                            .hasMessageContaining("TERM-99")
                            .hasMessageContaining("term_add");

                    assertThat(requirements.get(PROJECT, fr.code(), null).orElseThrow().usesTerms()).isEmpty();
                });
    }
}
