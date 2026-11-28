// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.AddRole.NewRole;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.mcp.ResourceReadOnTypedStoreDataTest.Store;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewStep;
import de.hauschel.arknet.uc.application.port.in.AddUseCase.NewUseCase;
import de.hauschel.arknet.uc.domain.UseCase;

/**
 * {@code text_search} over data the typed cores wrote and over data written past them, through the
 * beans of {@link ArknetMcpConfiguration}: takes over the end-to-end cases of the search tool from
 * the time it lived in the composition root.
 */
class TextSearchOnTypedStoreDataTest {

    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";

    @TempDir
    Path storageDir;

    @TempDir
    Path workspace;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    @Test
    void hitsOnTitleAndRationaleAreGroupedUnderTheSameResource() {
        onStore(store -> {
            Requirement fr = store.bean(RequirementService.class).add(store.project(), new NewRequirement("Login form",
                    "The system shall authenticate a user.", "Users must be able to log in securely.",
                    RequirementType.FUNCTIONAL, null, null, List.of("Nothing to do with the search term"), null),
                    "en");

            String report = store.search("log", null);

            assertThat(report).contains("dcterms:title").contains("arkreq:rationale");
            assertThat(Arrays.stream(report.split("\n")).filter(line -> line.equals("## " + fr.code().value()))
                    .count()).as("both matches land under one group").isEqualTo(1);
        });
    }

    @Test
    void matchingIsCaseInsensitiveAndReportsTheLanguageTag() {
        onStore(store -> {
            store.bean(RequirementService.class).add(store.project(), new NewRequirement("Anmeldung",
                    "Das System muss einen Benutzer authentifizieren.", null, RequirementType.FUNCTIONAL, null, null,
                    List.of("Anmeldung gelingt mit gueltigen Zugangsdaten"), null), "de");

            String report = store.search("ANMELDUNG", null);

            assertThat(report).contains("## FR-1").contains("[de]");
        });
    }

    @Test
    void aStepMatchIsReportedUnderItsOwningUseCase() {
        onStore(store -> {
            RoleDetail role = store.bean(RoleService.class).add(store.project(),
                    new NewRole("Customer", null, List.of(), "en"), "en");
            UseCase uc = store.bean(UseCaseService.class).add(store.project(), new NewUseCase("Log in",
                    "Customer authenticates", null, null, role.role().code().value(), List.of(), null, null,
                    List.of(new NewStep(1, "Customer enters a zzzcredential token", List.of())), List.of(), null),
                    "en");

            String report = store.search("zzzcredential", null);

            assertThat(report).contains("## " + uc.code().value()).contains("arkreq:stepText");
            assertThat(report).containsPattern("via arkreq:\\w*[Ss]tep");
        });
    }

    @Test
    void anAcceptanceCriterionMatchIsReportedUnderItsRequirementAndATypeFiltersByResourceClass() {
        onStore(store -> {
            Requirement fr = store.bean(RequirementService.class).add(store.project(), new NewRequirement("Login",
                    "The system shall authenticate a user.", null, RequirementType.FUNCTIONAL, null, null,
                    List.of("Login succeeds with a zzzvalidcredential"), null), "en");

            String report = store.search("zzzvalidcredential", null);
            String other = store.search("zzzvalidcredential", "UseCase");

            assertThat(report).contains("## " + fr.code().value()).contains("arkreq:criterionText")
                    .contains("via arkreq:acceptanceCriterion");
            assertThat(other).startsWith("No hits");
        });
    }

    /** A store of an earlier layout: a node with no code and no type, and a blank node nothing points at. */
    @Test
    void anEarlierLayoutAndAnOrphanBlankNodeAreSearchedWithoutFailing() {
        onStore(store -> {
            String legacyFr = "https://w3id.org/arknet/model/requirement/FR-7";
            String legacyStep = "https://w3id.org/arknet/model/requirement/step-1";
            store.seed(graph -> {
                graph.add(store.iri(legacyFr), store.iri("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
                        store.iri(ARKREQ + "FunctionalRequirement"));
                graph.add(store.iri(legacyFr), store.iri(DCTERMS + "identifier"), store.literal("FR-7"));
                graph.add(store.iri(legacyFr), store.iri(ARKREQ + "acceptanceCriterion"), store.iri(legacyStep));
                graph.add(store.iri(legacyStep), store.iri(ARKREQ + "criterionText"), store.literal("zzzold text"));
                graph.add(store.blank(), store.iri(ARKREQ + "criterionText"), store.literal("zzzold orphan"));
            });

            String report = store.search("zzzold", null);

            assertThat(report).contains("## FR-7").contains("arkreq:criterionText (via arkreq:acceptanceCriterion)")
                    .contains("zzzold orphan").contains("## _:");
        });
    }

    @Test
    void noHitsAndABlankQueryAreReportedCleanly() {
        onStore(store -> {
            assertThat(store.search("nothing-matches-this-zzz", null)).contains("No hits");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.search("   ", null))
                    .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must not be blank");
        });
    }

    private void onStore(final Consumer<Store> body) {
        contextRunner
                .withPropertyValues("arknet.rdf.storage=" + storageDir,
                        "arknet.report.dir=" + workspace.resolve("reports"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    body.accept(Store.registered(context, workspace, "text-search"));
                });
    }
}
