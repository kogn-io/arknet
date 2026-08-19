// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrId;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextId;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.uc.domain.ActorRef;
import de.hauschel.arknet.uc.domain.Step;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.uc.domain.UseCaseId;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * {@code store_overview} is the tool a user reaches for when they suspect the store is broken.
 * Assembling the model sections must therefore never be able to fail the whole call.
 */
class ModelViewsTest {

    private static final ProjectId PROJECT = new ProjectId("views-test");

    @Test
    void dropsASectionWhoseInPortThrowsAndKeepsTheRest() {
        final ModelViews views = new ModelViews(
                (projectId, displayLocale) -> List.of(term()),
                new UseCaseCards((projectId, displayLocale) -> {
                    throw new IllegalStateException("store closed");
                }, (projectId, ids) -> List.of()),
                new RequirementCards((projectId, displayLocale) -> List.of()),
                new BoundedContextCards(projectId -> List.of()),
                emptyAdrCards());

        final ModelViews.Views result = views.of(PROJECT, null);

        assertThat(result.sections()).extracting(ModelSection::title).containsExactly("Glossary");
        assertThat(result.failures()).singleElement().asString()
                .contains("Use Cases")
                .contains("IllegalStateException")
                .contains("store closed")
                .contains("Other resources");
    }

    /**
     * The glossary is read once for the whole report - it is the glossary section, every term
     * chip's label and what the other sections' prose is matched against. That makes it the one
     * read whose failure could take the report down, so it has to fail like a section: loudly,
     * and only for itself.
     */
    @Test
    void survivesAnUnreadableGlossaryAndSaysSo() {
        final ModelViews views = new ModelViews(
                (projectId, displayLocale) -> {
                    throw new IllegalStateException("glossary unreadable");
                },
                new UseCaseCards((projectId, displayLocale) -> List.of(useCase()), (projectId, ids) -> List.of()),
                new RequirementCards((projectId, displayLocale) -> List.of(requirement())),
                new BoundedContextCards(projectId -> List.of(boundedContext())),
                emptyAdrCards());

        final ModelViews.Views result = views.of(PROJECT, null);

        assertThat(result.sections()).extracting(ModelSection::title)
                .containsExactly("Bounded Contexts", "Requirements", "Use Cases");
        assertThat(result.failures()).singleElement().asString()
                .contains("Glossary")
                .contains("glossary unreadable")
                .contains("identities rather than labels");
    }

    /** An empty context contributes no heading - a section with zero cards is noise, not information. */
    @Test
    void leavesOutEmptySections() {
        final ModelViews views = new ModelViews(
                (projectId, displayLocale) -> List.of(term()),
                new UseCaseCards((projectId, displayLocale) -> List.of(), (projectId, ids) -> List.of()),
                new RequirementCards((projectId, displayLocale) -> List.of()),
                new BoundedContextCards(projectId -> List.of()),
                emptyAdrCards());

        final ModelViews.Views result = views.of(PROJECT, null);

        assertThat(result.sections()).extracting(ModelSection::title).containsExactly("Glossary");
        assertThat(result.failures()).isEmpty();
    }

    /**
     * Reading order is strategic to detailed: what the model is about (bounded contexts), what it
     * must do (requirements), how that plays out (use cases), what was decided about it (ADRs),
     * and the shared language underneath all of it.
     */
    @Test
    void ordersSectionsFromStrategicToDetailed() {
        final ModelViews views = new ModelViews(
                (projectId, displayLocale) -> List.of(term()),
                new UseCaseCards((projectId, displayLocale) -> List.of(useCase()), (projectId, ids) -> List.of()),
                new RequirementCards((projectId, displayLocale) -> List.of(requirement())),
                new BoundedContextCards(projectId -> List.of(boundedContext())),
                new AdrCards(projectId -> List.of(adrDetail()),
                        (projectId, ids) -> List.of(), (projectId, ids) -> List.of()));

        assertThat(views.of(PROJECT, null).sections()).extracting(ModelSection::title)
                .containsExactly(
                        "Bounded Contexts", "Requirements", "Use Cases", "Architecture Decisions", "Glossary");
    }

    private static UseCase useCase() {
        return new UseCase(
                new UseCaseId(ResourceId.of("https://w3id.org/arknet/id/uc-1")),
                new UseCaseCode("UC1"), "Bestellung aufgeben", "Der Kunde bestellt Artikel.",
                null, null, new ActorRef(ResourceId.of("https://w3id.org/arknet/id/actor-1")),
                List.of(), null, null,
                List.of(new Step(1, "Artikel in den Warenkorb legen", List.of())), List.of(), List.of(), List.of());
    }

    private static Requirement requirement() {
        return new Requirement(
                new RequirementId(ResourceId.of("https://w3id.org/arknet/id/fr-1")),
                new RequirementCode("FR-1"), "Anmeldung", "Das System muss Nutzer authentifizieren.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                List.of(), List.of(new AcceptanceCriterion(1, "Anmeldung gelingt mit gueltigen Zugangsdaten")),
                List.of());
    }

    private static BoundedContext boundedContext() {
        return new BoundedContext(
                new BoundedContextId(ResourceId.of("https://w3id.org/arknet/id/bc-1")),
                new BoundedContextCode("BC-1"), "Ordering", "Bestellungen aufnehmen und verfolgen.",
                Subdomain.CORE_DOMAIN, null, List.of());
    }

    private static Term term() {
        return new Term(
                new TermId(ResourceId.of("https://w3id.org/arknet/id/term-1")),
                new TermCode("TERM-1"), "Anmeldung", "Der Nachweis der eigenen Identitaet.", null);
    }

    private static AdrDetail adrDetail() {
        final Adr adr = new Adr(
                new AdrId(ResourceId.of("https://w3id.org/arknet/id/adr-1")),
                new AdrCode("ADR-1"), "Use kognio-rdf", AdrStatus.ACCEPTED,
                "Forces and constraints.", "What was decided.", null, null, null, List.of(), List.of(), List.of());
        return new AdrDetail(adr, List.of(), List.of());
    }

    private static AdrCards emptyAdrCards() {
        return new AdrCards(projectId -> List.of(), (projectId, ids) -> List.of(), (projectId, ids) -> List.of());
    }
}
