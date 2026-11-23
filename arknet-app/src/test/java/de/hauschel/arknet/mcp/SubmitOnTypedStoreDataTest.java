// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.kogn.rdf.dataset.hosting.DatasetLifecycle;

import de.hauschel.arknet.actor.application.ActorService;
import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.AddActor.NewActor;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.adr.application.AdrService;
import de.hauschel.arknet.adr.application.port.in.AddAdr.NewAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.NewConsequence;
import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.application.port.in.RelatedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.pr.shared.ConstraintCode;
import de.hauschel.arknet.pr.shared.RequirementCode;
import de.hauschel.arknet.pr.shared.TermRef;
import de.hauschel.arknet.req.application.ConstraintService;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddConstraint.NewConstraint;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Constraint;
import de.hauschel.arknet.req.domain.ConstraintRef;
import de.hauschel.arknet.req.domain.ConstraintType;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.resource.adapter.kogniordf.KognioRdfResourceRepositoryFactory;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.SubmitDocument;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.domain.RequirementRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.Term;

/**
 * {@code submit} on data the typed cores wrote: every resource type the engine writes is first
 * created through its typed application service, then changed, referenced or deleted through a
 * document, and read back through the typed read path.
 *
 * <p>The engine checks every resource type with one gate over all shapes and puts the slices of
 * the neighbours it reaches into the asserted context. These tests pin that this gate carries on
 * resources it did not write itself - typed resources with the triples the typed adapters add -
 * and that the typed read path still reads what the engine left behind. The engine is built over
 * the {@link DatasetLifecycle} bean of {@link ArknetMcpConfiguration}, the one the typed adapters
 * share.</p>
 */
class SubmitOnTypedStoreDataTest {

    private static final ProjectId PROJECT = new ProjectId("submit-on-typed-data");

    private static final String LANGUAGE = "en";

    private static final String PREFIXES = """
            @prefix arkres:  <https://w3id.org/arknet/resource#> .
            @prefix arknet:  <https://w3id.org/arknet/core#> .
            @prefix arkreq:  <https://w3id.org/arknet/requirements#> .
            @prefix arkarch: <https://w3id.org/arknet/architecture#> .
            @prefix arkddd:  <https://w3id.org/arknet/ddd#> .
            @prefix arkproc: <https://w3id.org/arknet/process#> .
            @prefix oslc_rm: <http://open-services.net/ns/rm#> .
            @prefix skos:    <http://www.w3.org/2004/02/skos/core#> .
            @prefix dcterms: <http://purl.org/dc/terms/> .
            """;

    @TempDir
    Path storageDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    @Test
    void updatesTheDefinitionOfATypedTermAndRelatesItToAnotherTypedTerm() {
        onStore(store -> {
            // given
            Term order = store.addTerm("Order", "A customer's request to buy.");
            Term customer = store.addTerm("Customer", "Someone who buys.");

            // when
            store.submit("[] a arkres:Update ; arkres:object <" + iri(order) + "> ;"
                    + " arkres:set [ skos:definition \"A request to buy goods.\"@en ] .\n"
                    + "[] a arkres:AddTo ; arkres:object <" + iri(order) + "> ; arkres:field skos:related ;"
                    + " arkres:value <" + iri(customer) + "> .\n");

            // then
            Term reloaded = store.bean(TermService.class).get(PROJECT, order.code(), null).orElseThrow();
            assertThat(reloaded.definition()).isEqualTo("A request to buy goods.");
            assertThat(reloaded.prefLabel()).isEqualTo("Order");
            assertThat(reloaded.related()).containsExactly(customer.code());
        });
    }

    @Test
    void updatesTheStatementOfATypedConstraint() {
        onStore(store -> {
            // given
            Constraint constraint = store.addConstraint();

            // when
            store.submit("[] a arkres:Update ; arkres:object <" + constraint.id().value().value() + "> ;"
                    + " arkres:set [ arkreq:constraintStatement \"Runs on Java 25 or later.\"@en ] .\n");

            // then
            Constraint reloaded = store.bean(ConstraintService.class)
                    .get(PROJECT, constraint.code(), null).orElseThrow();
            assertThat(reloaded.statement()).isEqualTo("Runs on Java 25 or later.");
            assertThat(reloaded.title()).isEqualTo(constraint.title());
            assertThat(reloaded.type()).isEqualTo(ConstraintType.TECHNICAL);
        });
    }

    @Test
    void changesTheCriteriaOfATypedRequirementAndLinksATypedTermAndConstraint() {
        onStore(store -> {
            // given
            Requirement fr = store.addFunctionalRequirement();
            Term term = store.addTerm("Order", "A customer's request to buy.");
            Constraint constraint = store.addConstraint();
            String frIri = fr.id().value().value();

            // when
            store.submit("[] a arkres:AddTo ; arkres:object <" + frIri + "> ; arkres:field arkreq:acceptanceCriterion ;"
                    + " arkres:value [ arkreq:position 1 ; arkreq:criterionText \"New first.\"@en ] .\n"
                    + "[] a arkres:RemoveFrom ; arkres:object <" + frIri + "> ;"
                    + " arkres:field arkreq:acceptanceCriterion ; arkres:position 1 .\n"
                    + "[] a arkres:Update ; arkres:object <" + frIri + "> ;"
                    + " arkres:set [ dcterms:title \"Customer places an order\"@en ] .\n"
                    + "[] a arkres:AddTo ; arkres:object <" + frIri + "> ; arkres:field arkreq:usesTerm ;"
                    + " arkres:value <" + iri(term) + "> .\n"
                    + "[] a arkres:AddTo ; arkres:object <" + frIri + "> ; arkres:field oslc_rm:constrainedBy ;"
                    + " arkres:value <" + constraint.id().value().value() + "> .\n");

            // then
            Requirement reloaded = store.bean(RequirementService.class).get(PROJECT, fr.code(), null).orElseThrow();
            assertThat(reloaded.title()).isEqualTo("Customer places an order");
            assertThat(reloaded.description()).isEqualTo(fr.description());
            assertThat(reloaded.status()).isEqualTo(fr.status());
            assertThat(reloaded.acceptanceCriteria())
                    .containsExactly(new AcceptanceCriterion(1, "New first."), new AcceptanceCriterion(2, "Second."));
            assertThat(reloaded.usesTerms()).containsExactly(new TermRef(term.id().value()));
            assertThat(reloaded.constrainedBy()).containsExactly(new ConstraintRef(constraint.id().value()));
        });
    }

    @Test
    void updatesATypedNonFunctionalRequirementAndKeepsItsQualityCategory() {
        onStore(store -> {
            // given
            Requirement nfr = store.bean(RequirementService.class).add(PROJECT, new NewRequirement("Fast",
                    "Answers within a second.", null, RequirementType.NON_FUNCTIONAL, null, "performance",
                    List.of("Below one second."), null), LANGUAGE);

            // when
            store.submit("[] a arkres:Update ; arkres:object <" + nfr.id().value().value() + "> ;"
                    + " arkres:set [ dcterms:description \"Answers within half a second.\"@en ] .\n");

            // then
            Requirement reloaded = store.bean(RequirementService.class).get(PROJECT, nfr.code(), null).orElseThrow();
            assertThat(reloaded.description()).isEqualTo("Answers within half a second.");
            assertThat(reloaded.type()).isEqualTo(RequirementType.NON_FUNCTIONAL);
            assertThat(reloaded.qualityCategory()).isEqualTo("performance");
            assertThat(reloaded.acceptanceCriteria()).containsExactly(new AcceptanceCriterion(1, "Below one second."));
        });
    }

    @Test
    void renamesATypedActorAndKeepsItsKind() {
        onStore(store -> {
            // given
            Actor actor = store.addActor();

            // when
            store.submit("[] a arkres:Update ; arkres:object <" + actor.id().value().value() + "> ;"
                    + " arkres:set [ arknet:name \"Lead architect\"@en ] .\n");

            // then
            Actor reloaded = store.bean(ActorService.class).get(PROJECT, actor.code(), null).orElseThrow();
            assertThat(reloaded.name()).isEqualTo("Lead architect");
            assertThat(reloaded.type()).isEqualTo(ActorType.HUMAN);
        });
    }

    @Test
    void letsATypedActorFillATypedRole() {
        onStore(store -> {
            // given
            Actor actor = store.addActor();
            RoleDetail role = store.addRole();

            // when
            store.submit("[] a arkres:AddTo ; arkres:object <" + role.role().id().value().value() + "> ;"
                    + " arkres:field arkproc:filledBy ; arkres:value <" + actor.id().value().value() + "> .\n");

            // then
            RoleDetail reloaded = store.bean(RoleService.class).get(PROJECT, role.role().code(), null).orElseThrow();
            assertThat(reloaded.role().filledBy()).containsExactly(actor.id());
            assertThat(reloaded.role().name()).isEqualTo("Modeller");
        });
    }

    @Test
    void addsAStepRealisingATypedRequirementToATypedUseCase() {
        onStore(store -> {
            // given
            Requirement fr = store.addFunctionalRequirement();
            Requirement second = store.addFunctionalRequirement();
            UseCase useCase = store.addUseCase(store.addRole(), fr);
            String ucIri = useCase.id().value().value();

            // when
            store.submit("[] a arkres:AddTo ; arkres:object <" + ucIri + "> ; arkres:field arkreq:mainStep ;"
                    + " arkres:value [ arkreq:position 2 ; arkreq:stepText \"Confirms the decision.\"@en ;"
                    + " arkreq:stepRealises <" + second.id().value().value() + "> ] .\n"
                    + "[] a arkres:Update ; arkres:object <" + ucIri + "> ;"
                    + " arkres:set [ arkreq:useCaseGoal \"A decision is recorded and confirmed.\"@en ] .\n");

            // then
            UseCase reloaded = store.bean(UseCaseService.class).get(PROJECT, useCase.code(), null).orElseThrow();
            assertThat(reloaded.goal()).isEqualTo("A decision is recorded and confirmed.");
            assertThat(reloaded.primaryRole()).isEqualTo(useCase.primaryRole());
            assertThat(reloaded.steps()).containsExactly(
                    new Step(1, "Writes the decision.", List.of(new RequirementRef(fr.id().value()))),
                    new Step(2, "Confirms the decision.", List.of(new RequirementRef(second.id().value()))));
        });
    }

    @Test
    void createsAResourceOfEveryTypeOverTypedResourcesAndTheTypedReadPathReadsIt() {
        onStore(store -> {
            // given
            Term term = store.addTerm("Order", "A customer's request to buy.");
            Actor actor = store.addActor();
            Requirement fr = store.addFunctionalRequirement();
            BoundedContext upstream = store.addBoundedContext("Modelling");

            // when
            // TODO #742: arkreq:status is set by hand - the typed read path skips a requirement without
            // one, and the shapes do not require it; the initial status as data belongs to #742.
            SubmitResult result = store.submit("[] a arkres:Create ; arkres:object _:term .\n"
                    + "_:term a skos:Concept ; skos:prefLabel \"Customer\"@en ; skos:definition \"Buys.\"@en ;"
                    + " skos:related <" + iri(term) + "> .\n"
                    + "[] a arkres:Create ; arkres:object _:con .\n"
                    + "_:con a arkreq:TechnicalConstraint ; dcterms:title \"Java 25\"@en ;"
                    + " arkreq:constraintStatement \"Runs on Java 25.\"@en .\n"
                    + "[] a arkres:Create ; arkres:object _:fr .\n"
                    + "_:fr a arkreq:FunctionalRequirement ; dcterms:title \"Confirm an order\"@en ;"
                    + " dcterms:description \"The user confirms an order.\"@en ;"
                    + " arkreq:status arkreq:Proposed ;"
                    + " arkreq:usesTerm <" + iri(term) + "> ; oslc_rm:constrainedBy _:con ;"
                    + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Confirmed.\"@en ] .\n"
                    + "[] a arkres:Create ; arkres:object _:nfr .\n"
                    + "_:nfr a arkreq:NonFunctionalRequirement ; dcterms:title \"Fast\"@en ;"
                    + " dcterms:description \"Within a second.\"@en ; arkreq:qualityCategory \"performance\" ;"
                    + " arkreq:status arkreq:Proposed ;"
                    + " arkreq:acceptanceCriterion [ arkreq:position 1 ; arkreq:criterionText \"Fast.\"@en ] .\n"
                    + "[] a arkres:Create ; arkres:object _:actor .\n"
                    + "_:actor a arkproc:SystemActor ; arknet:name \"Store\"@en .\n"
                    + "[] a arkres:Create ; arkres:object _:role .\n"
                    + "_:role a arkproc:Role ; arknet:name \"Modeller\"@en ; arkproc:filledBy <"
                    + actor.id().value().value() + "> .\n"
                    + "[] a arkres:Create ; arkres:object _:uc .\n"
                    + "_:uc a arkreq:UseCase ; dcterms:title \"Model a decision\"@en ;"
                    + " arkreq:useCaseGoal \"A decision is recorded.\"@en ; arkreq:primaryRole _:role ;"
                    + " arkreq:mainStep [ arkreq:position 1 ; arkreq:stepText \"Writes the decision.\"@en ;"
                    + " arkreq:stepRealises <" + fr.id().value().value() + "> ] .\n"
                    + "[] a arkres:Create ; arkres:object _:bc .\n"
                    + "_:bc a arkddd:BoundedContext ; arknet:name \"Export\"@en ;"
                    + " arkddd:domainVision \"Turns the model into documents.\"@en .\n"
                    + "[] a arkres:Create ; arkres:object _:rel .\n"
                    + "_:rel a arkddd:ContextRelationship ; arkddd:upstream <" + upstream.id().value().value() + "> ;"
                    + " arkddd:downstream _:bc ; arkddd:relationshipType arkddd:CustomerSupplier .\n"
                    + "[] a arkres:Create ; arkres:object _:adr .\n"
                    + "_:adr a arkarch:ArchitectureDecisionRecord ; arknet:name \"Store resources as RDF\"@en ;"
                    + " arkarch:adrStatus arkarch:Proposed ; arkarch:adrContext \"Resources need identities.\"@en ;"
                    + " arkarch:adrDecision \"Every resource is an RDF resource.\"@en ;"
                    + " arkarch:addressesRequirement <" + fr.id().value().value() + "> ;"
                    + " arkarch:affectsContext <" + upstream.id().value().value() + "> .\n");

            // then
            assertThat(result.changes()).extracting(SubmittedChange::code).containsExactlyInAnyOrder("TERM-2",
                    "TCON-1", "FR-2", "NFR-1", "ACTOR-2", "ROLE-1", "UC1", "BC-2", null, "ADR-1");
            SoftAssertions softly = new SoftAssertions();
            softly.assertThat(store.bean(TermService.class).get(PROJECT, new TermCode("TERM-2"), null))
                    .hasValueSatisfying(created -> assertThat(created.related()).containsExactly(term.code()));
            softly.assertThat(store.bean(ConstraintService.class).get(PROJECT, new ConstraintCode("TCON-1"), null))
                    .hasValueSatisfying(created -> assertThat(created.type()).isEqualTo(ConstraintType.TECHNICAL));
            softly.assertThat(store.bean(RequirementService.class).get(PROJECT, new RequirementCode("FR-2"), null))
                    .hasValueSatisfying(created -> assertThat(created.usesTerms())
                            .containsExactly(new TermRef(term.id().value())));
            softly.assertThat(store.bean(RequirementService.class).get(PROJECT, new RequirementCode("NFR-1"), null))
                    .hasValueSatisfying(created -> assertThat(created.qualityCategory()).isEqualTo("performance"));
            softly.assertThat(store.bean(RequirementService.class).list(PROJECT, null)).hasSize(3);
            softly.assertThat(store.bean(ActorService.class).get(PROJECT, new ActorCode("ACTOR-2"), null))
                    .hasValueSatisfying(created -> assertThat(created.type()).isEqualTo(ActorType.SYSTEM));
            softly.assertThat(store.bean(RoleService.class).get(PROJECT, new RoleCode("ROLE-1"), null))
                    .hasValueSatisfying(created -> assertThat(created.role().filledBy()).containsExactly(actor.id()));
            softly.assertThat(store.bean(UseCaseService.class).get(PROJECT, new UseCaseCode("UC1"), null))
                    .hasValueSatisfying(created -> assertThat(created.steps()).singleElement()
                            .satisfies(step -> assertThat(step.realises())
                                    .containsExactly(new RequirementRef(fr.id().value()))));
            softly.assertThat(store.bean(BoundedContextService.class).get(PROJECT, upstream.code(), null))
                    .hasValueSatisfying(detail -> assertThat(detail.relationships())
                            .extracting(RelatedContext::peerCode).containsExactly(new BoundedContextCode("BC-2")));
            softly.assertThat(store.bean(AdrService.class).get(PROJECT, new AdrCode("ADR-1"), null))
                    .hasValueSatisfying(created -> assertThat(created.adr().addressesRequirements())
                            .extracting(ref -> ref.value()).containsExactly(fr.id().value()));
            softly.assertAll();
        });
    }

    @Test
    void updatesTheVisionOfATypedBoundedContext() {
        onStore(store -> {
            // given
            BoundedContext context = store.addBoundedContext("Modelling");

            // when
            store.submit("[] a arkres:Update ; arkres:object <" + context.id().value().value() + "> ;"
                    + " arkres:set [ arkddd:domainVision \"Keeps every model consistent.\"@en ] .\n");

            // then
            BoundedContext reloaded = store.bean(BoundedContextService.class)
                    .get(PROJECT, context.code(), null).orElseThrow().context();
            assertThat(reloaded.domainVision()).isEqualTo("Keeps every model consistent.");
            assertThat(reloaded.name()).isEqualTo("Modelling");
        });
    }

    @Test
    void retypesAndDeletesATypedContextRelationship() {
        onStore(store -> {
            // given
            BoundedContextService contexts = store.bean(BoundedContextService.class);
            BoundedContext upstream = store.addBoundedContext("Modelling");
            BoundedContext downstream = store.addBoundedContext("Export");
            ContextRelationship relationship = contexts.linkContext(PROJECT, upstream.code(), downstream.code(),
                    RelationshipType.CUSTOMER_SUPPLIER);
            String relationshipIri = relationship.id().value().value();

            // when
            store.submit("[] a arkres:Update ; arkres:object <" + relationshipIri + "> ;"
                    + " arkres:set [ arkddd:relationshipType arkddd:Conformist ] .\n");

            // then
            assertThat(contexts.get(PROJECT, upstream.code(), null).orElseThrow().relationships())
                    .extracting(RelatedContext::relationshipType).containsExactly(RelationshipType.CONFORMIST);

            // when
            store.submit("[] a arkres:Delete ; arkres:object <" + relationshipIri + "> .\n");

            // then
            assertThat(contexts.get(PROJECT, upstream.code(), null).orElseThrow().relationships()).isEmpty();
            assertThat(contexts.get(PROJECT, downstream.code(), null).orElseThrow().relationships()).isEmpty();
        });
    }

    @Test
    void addsAConsequenceToATypedAdrAndLetsItAddressATypedRequirement() {
        onStore(store -> {
            // given
            Requirement fr = store.addFunctionalRequirement();
            AdrDetail adr = store.addAdr();
            String adrIri = adr.adr().id().value().value();

            // when
            store.submit("[] a arkres:AddTo ; arkres:object <" + adrIri + "> ; arkres:field arkarch:consequence ;"
                    + " arkres:value [ arknet:position 2 ; arkarch:consequenceStatement \"Queries get longer.\"@en ;"
                    + " arkarch:consequenceType arkarch:Negative ] .\n"
                    + "[] a arkres:Update ; arkres:object <" + adrIri + "> ;"
                    + " arkres:set [ arkarch:adrDecision \"Every resource is an RDF resource with an IRI.\"@en ] .\n"
                    + "[] a arkres:AddTo ; arkres:object <" + adrIri + "> ; arkres:field arkarch:addressesRequirement ;"
                    + " arkres:value <" + fr.id().value().value() + "> .\n");

            // then
            AdrDetail reloaded = store.bean(AdrService.class).get(PROJECT, adr.adr().code(), null).orElseThrow();
            assertThat(reloaded.adr().decision()).isEqualTo("Every resource is an RDF resource with an IRI.");
            assertThat(reloaded.adr().status()).isEqualTo(adr.adr().status());
            assertThat(reloaded.adr().consequences()).containsExactly(
                    new Consequence(1, "Links are first-class.", ConsequenceType.POSITIVE),
                    new Consequence(2, "Queries get longer.", ConsequenceType.NEGATIVE));
            assertThat(reloaded.adr().addressesRequirements()).extracting(ref -> ref.value())
                    .containsExactly(fr.id().value());
        });
    }

    @ParameterizedTest
    @MethodSource("typedResources")
    void deletesATypedResource(final Function<Store, Seed> typedResource) {
        onStore(store -> {
            // given
            Seed seed = typedResource.apply(store);

            // when
            SubmitResult result = store.submit("[] a arkres:Delete ; arkres:object <" + seed.iri() + "> .\n");

            // then
            assertThat(result.changes()).extracting(SubmittedChange::kind)
                    .containsExactly(SubmittedChange.Kind.DELETED);
            assertThat(seed.present().getAsBoolean()).isFalse();
        });
    }

    static Stream<Named<Function<Store, Seed>>> typedResources() {
        return Stream.of(
                Named.of("term", store -> {
                    Term term = store.addTerm("Order", "A customer's request to buy.");
                    return new Seed(iri(term),
                            () -> store.bean(TermService.class).get(PROJECT, term.code(), null).isPresent());
                }),
                Named.of("constraint", store -> {
                    Constraint constraint = store.addConstraint();
                    return new Seed(constraint.id().value().value(), () -> store.bean(ConstraintService.class)
                            .get(PROJECT, constraint.code(), null).isPresent());
                }),
                Named.of("requirement with criteria", store -> {
                    Requirement fr = store.addFunctionalRequirement();
                    return new Seed(fr.id().value().value(),
                            () -> store.bean(RequirementService.class).get(PROJECT, fr.code(), null).isPresent());
                }),
                Named.of("actor", store -> {
                    Actor actor = store.addActor();
                    return new Seed(actor.id().value().value(),
                            () -> store.bean(ActorService.class).get(PROJECT, actor.code(), null).isPresent());
                }),
                Named.of("role filled by an actor", store -> {
                    Actor actor = store.addActor();
                    RoleDetail role = store.bean(RoleService.class).add(PROJECT,
                            new NewRole("Modeller", null, List.of(actor.code().value()), null), LANGUAGE);
                    return new Seed(role.role().id().value().value(), () -> store.bean(RoleService.class)
                            .get(PROJECT, role.role().code(), null).isPresent());
                }),
                Named.of("use case with a step", store -> {
                    UseCase useCase = store.addUseCase(store.addRole(), store.addFunctionalRequirement());
                    return new Seed(useCase.id().value().value(), () -> store.bean(UseCaseService.class)
                            .get(PROJECT, useCase.code(), null).isPresent());
                }),
                Named.of("bounded context", store -> {
                    BoundedContext context = store.addBoundedContext("Modelling");
                    return new Seed(context.id().value().value(), () -> store.bean(BoundedContextService.class)
                            .get(PROJECT, context.code(), null).isPresent());
                }),
                Named.of("adr with a consequence", store -> {
                    AdrDetail adr = store.addAdr();
                    return new Seed(adr.adr().id().value().value(), () -> store.bean(AdrService.class)
                            .get(PROJECT, adr.adr().code(), null).isPresent());
                }));
    }

    /**
     * The gate sees the deleted term from the requirement the same document changes: its reference no
     * longer finds a type. A delete alone is not rejected - the engine validates what the document
     * writes and the neighbours reachable from it, not the resources still pointing at a deleted one;
     * protection against deleting a referenced resource is #742.
     */
    @Test
    void rejectsDeletingATypedTermInTheDocumentThatChangesTheTypedRequirementUsingIt() {
        onStore(store -> {
            // given
            Term term = store.addTerm("Order", "A customer's request to buy.");
            Requirement fr = store.addFunctionalRequirement();
            store.bean(RequirementService.class).linkTerm(PROJECT, fr.code(), term.code().value(), LANGUAGE);

            // when / then
            assertThatThrownBy(() -> store.submit("[] a arkres:Delete ; arkres:object <" + iri(term) + "> .\n"
                    + "[] a arkres:Update ; arkres:object <" + fr.id().value().value() + "> ;"
                    + " arkres:set [ dcterms:title \"Customer places an order\"@en ] .\n"))
                    .isInstanceOf(SubmitRejectedException.class);
            assertThat(store.bean(TermService.class).get(PROJECT, term.code(), null)).isPresent();
            assertThat(store.bean(RequirementService.class).get(PROJECT, fr.code(), null).orElseThrow().usesTerms())
                    .containsExactly(new TermRef(term.id().value()));
        });
    }

    @Test
    void rejectsClearingTheTitleOfATypedRequirement() {
        onStore(store -> {
            // given
            Requirement fr = store.addFunctionalRequirement();

            // when / then
            assertThatThrownBy(() -> store.submit("[] a arkres:Update ; arkres:object <" + fr.id().value().value()
                    + "> ; arkres:clear dcterms:title .\n"))
                    .isInstanceOf(SubmitRejectedException.class);
            assertThat(store.bean(RequirementService.class).get(PROJECT, fr.code(), null).orElseThrow().title())
                    .isEqualTo(fr.title());
        });
    }

    private void onStore(final Consumer<Store> body) {
        contextRunner
                .withPropertyValues("arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    body.accept(new Store(context));
                });
    }

    private static String iri(final Term term) {
        return term.id().value().value();
    }

    /** A typed resource to delete through a document, and how the typed read path sees whether it is still there. */
    record Seed(String iri, BooleanSupplier present) {
    }

    /**
     * The typed application services of one Spring context and the engine over the same dataset lifecycle bean.
     */
    static final class Store {

        private final AssertableApplicationContext context;
        private final SubmitDocument engine;

        Store(final AssertableApplicationContext context) {
            this.context = context;
            this.engine = new SubmitService(KognioRdfResourceRepositoryFactory.schema(),
                    KognioRdfResourceRepositoryFactory.over(context.getBean(DatasetLifecycle.class),
                            context.getBean(DisplayLocale.class)),
                    KognioRdfResourceRepositoryFactory.parser(), context.getBean(ResourceIdFactory.class));
        }

        <T> T bean(final Class<T> type) {
            return context.getBean(type);
        }

        SubmitResult submit(final String activities) {
            return engine.submit(PROJECT, PREFIXES + activities);
        }

        Term addTerm(final String label, final String definition) {
            return bean(TermService.class).add(PROJECT, new NewTerm(label, definition, null), LANGUAGE);
        }

        Constraint addConstraint() {
            return bean(ConstraintService.class).add(PROJECT,
                    new NewConstraint("Java 25", "Runs on Java 25.", ConstraintType.TECHNICAL, null), LANGUAGE);
        }

        Requirement addFunctionalRequirement() {
            return bean(RequirementService.class).add(PROJECT, new NewRequirement("Customer can order",
                    "The system shall let a customer place an order.", null, RequirementType.FUNCTIONAL, null, null,
                    List.of("First.", "Second."), null), LANGUAGE);
        }

        Actor addActor() {
            return bean(ActorService.class).add(PROJECT, new NewActor(ActorType.HUMAN, "Architect", null, null),
                    LANGUAGE);
        }

        RoleDetail addRole() {
            return bean(RoleService.class).add(PROJECT, new NewRole("Modeller", null, List.of(), null), LANGUAGE);
        }

        UseCase addUseCase(final RoleDetail role, final Requirement realised) {
            return bean(UseCaseService.class).add(PROJECT, new NewUseCase("Model a decision",
                    "A decision is recorded.", null, null, role.role().code().value(), List.of(), null, null,
                    List.of(new NewStep(1, "Writes the decision.", List.of(realised.code().value()))),
                    List.of(), null), LANGUAGE);
        }

        BoundedContext addBoundedContext(final String name) {
            return bean(BoundedContextService.class).add(PROJECT,
                    new NewBoundedContext(name, "Keeps the model consistent.", null, null, null), LANGUAGE);
        }

        AdrDetail addAdr() {
            return bean(AdrService.class).add(PROJECT, new NewAdr("Store resources as RDF",
                    "Resources need identities.", "Every resource is an RDF resource.",
                    List.of(new NewConsequence("Links are first-class.", ConsequenceType.POSITIVE)), List.of(),
                    null, List.of(), List.of(), List.of(), List.of()), LANGUAGE);
        }
    }
}
