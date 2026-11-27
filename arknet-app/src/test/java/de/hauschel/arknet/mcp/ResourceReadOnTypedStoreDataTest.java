// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.BlankNode;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.adr.application.AdrService;
import de.hauschel.arknet.adr.application.port.in.AddAdr.NewAdr;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.domain.ConsequenceType;
import de.hauschel.arknet.adr.domain.NewConsequence;
import de.hauschel.arknet.bc.application.BoundedContextService;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.UnresolvedProjectAnchorException;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.prj.application.ProjectService;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.req.application.RequirementService;
import de.hauschel.arknet.req.application.port.in.AddRequirement.NewRequirement;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.resource.adapter.mcp.ResourceReadMcpTools;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
import de.hauschel.arknet.ul.domain.Term;

/**
 * {@code resource_get} and {@code resource_history} over data the typed cores wrote and over data
 * written past them, through the beans of {@link ArknetMcpConfiguration} without
 * {@code arknet.resource-access.enabled}: what {@code store_overview}'s drill-down, an agent
 * checking context relationships and an earlier store layout rely on.
 *
 * <p>Takes over the {@code resource_get}/{@code resource_history} cases of
 * {@code StoreReportToolsTest} from the time both tools lived in the composition root.</p>
 */
class ResourceReadOnTypedStoreDataTest {

    private static final String LANGUAGE = "en";
    private static final String DCTERMS = "http://purl.org/dc/terms/";
    private static final String ARKREQ = "https://w3id.org/arknet/requirements#";
    private static final String ARKARCH = "https://w3id.org/arknet/architecture#";
    private static final String SKOS = "http://www.w3.org/2004/02/skos/core#";
    private static final String RDF_TYPE = "http://www.w3.org/1999/02/22-rdf-syntax-ns#type";
    /** A requirement of an earlier layout: an IRI under the instance namespace, an untagged title. */
    private static final String LEGACY_FR = "https://w3id.org/arknet/model/requirement/FR-7";

    @TempDir
    Path storageDir;

    @TempDir
    Path workspace;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    @Test
    void aTypedRequirementReadsWithStatusCriteriaInOrderTermLabelAndTimes() {
        onStore(store -> {
            // given
            Term term = store.addTerm("Order", "A customer's request to buy.");
            Requirement fr = store.addRequirement();
            store.bean(RequirementService.class).linkTerm(store.project, fr.code(), term.code().value(), LANGUAGE);

            // when
            String byCode = store.get(fr.code().value());
            String byIri = store.get(fr.id().value().value());

            // then
            assertThat(byCode).startsWith("# FR-1 [").contains("\"Customer can order\"@en")
                    .contains("IRI: <" + fr.id().value().value() + ">")
                    .contains("Status: arkreq:Proposed")
                    .contains("Created: ").contains("Last changed: ")
                    .contains(term.code().value() + " \"Order\"");
            assertThat(byCode.indexOf("\"First.\"@en")).isPositive().isLessThan(byCode.indexOf("\"Second.\"@en"));
            assertThat(byIri).isEqualTo(byCode);
            assertThat(byCode).doesNotContain(" NOT FOUND");
        });
    }

    @Test
    void severalHandlesReadInOneCallAndAnUnknownOneDoesNotFailTheOthers() {
        onStore(store -> {
            // given
            Term term = store.addTerm("Order", "A customer's request to buy.");
            Requirement fr = store.addRequirement();

            // when
            String answer = store.get(fr.code().value() + ", FR-999 " + term.code().value());

            // then
            assertThat(answer.indexOf("# FR-1 [")).isZero();
            assertThat(answer).contains("\n# FR-999 NOT FOUND\n- no resource carries the code FR-999\n");
            assertThat(answer.indexOf("# FR-999 NOT FOUND")).isLessThan(answer.indexOf("# " + term.code().value()));
        });
    }

    /**
     * An agent checks a context relationship by reading the bounded context: the relationship
     * resource points at it, carries no code and no label field, and appears in the incoming
     * section under its IRI and type label; read by that IRI, it shows the relationship type.
     */
    @Test
    void aBoundedContextListsTheRelationshipPointingAtItAndTheRelationshipReadsByItsIri() {
        onStore(store -> {
            // given
            BoundedContext upstream = store.addBoundedContext("Modelling");
            BoundedContext downstream = store.addBoundedContext("Reporting");
            ContextRelationship relationship = store.bean(BoundedContextService.class).linkContext(store.project,
                    upstream.code(), downstream.code(), RelationshipType.CONFORMIST);
            String relationshipIri = relationship.id().value().value();

            // when
            String context = store.get(downstream.code().value());
            String edge = store.get(relationshipIri);

            // then
            String incoming = context.substring(context.indexOf("## Incoming (what points here)"));
            assertThat(incoming).contains(relationshipIri);
            assertThat(edge).startsWith("# " + relationshipIri + " [")
                    .contains(upstream.code().value() + " \"Modelling\"")
                    .contains(downstream.code().value() + " \"Reporting\"")
                    .containsIgnoringCase("conformist");
        });
    }

    /**
     * Data of an earlier layout, never migrated to the target vocabulary: an IRI under the
     * instance namespace, an untagged title, a property outside every shape. It reads by its
     * code and its IRI; the instance prefix of that layout is not expanded and says so.
     */
    @Test
    void aResourceOfAnEarlierLayoutReadsByCodeAndIriAndItsInstancePrefixIsRefused() {
        onStore(store -> {
            // given
            store.seed(graph -> {
                graph.add(store.iri(LEGACY_FR), store.iri(RDF_TYPE), store.iri(ARKREQ + "FunctionalRequirement"));
                graph.add(store.iri(LEGACY_FR), store.iri(DCTERMS + "identifier"), store.rdf.createLiteral("FR-7"));
                graph.add(store.iri(LEGACY_FR), store.iri(DCTERMS + "title"), store.rdf.createLiteral("Old login"));
                graph.add(store.iri(LEGACY_FR), store.iri(ARKREQ + "refinesTerm"),
                        store.rdf.createLiteral("kept as it is"));
            });

            // when
            String byCode = store.get("FR-7");
            String byIri = store.get(LEGACY_FR);
            String byInstancePrefix = store.get("req:FR-7");

            // then
            assertThat(byCode).startsWith("# FR-7 [").contains("\"Old login\"")
                    .contains("## Other statements (outside the shapes)").contains("\"kept as it is\"")
                    .doesNotContain("Created: ");
            assertThat(byIri).isEqualTo(byCode);
            assertThat(byInstancePrefix).startsWith("# req:FR-7 NOT FOUND\n").contains("is not bound");
            assertThat(store.history("FR-7")).contains("## History (0)").contains("never published");
        });
    }

    /**
     * An ADR with the flat consequence literal and the old {@code supersedes} direction of an
     * earlier version: the literal stays readable outside the shapes, the old edge is merged into
     * the field of its inverse on the superseded record.
     */
    @Test
    void anAdrWithEarlierFormsKeepsTheFlatLiteralAndMergesTheOldSupersedesEdge() {
        onStore(store -> {
            // given
            AdrDetail older = store.addAdr("Store resources as RDF");
            AdrDetail newer = store.addAdr("Store resources as RDF-star");
            String newerIri = newer.adr().id().value().value();
            String olderIri = older.adr().id().value().value();
            store.seed(graph -> {
                graph.add(store.iri(newerIri), store.iri(ARKARCH + "adrConsequences"),
                        store.rdf.createLiteral("Flat consequence text."));
                graph.add(store.iri(newerIri), store.iri(ARKARCH + "supersedes"), store.iri(olderIri));
            });

            // when
            String olderReading = store.get(older.adr().code().value());
            String newerReading = store.get(newer.adr().code().value());

            // then
            assertThat(newerReading).contains("\"Flat consequence text.\"");
            String olderFields = olderReading.substring(0, olderReading.indexOf("## Incoming") > 0
                    ? olderReading.indexOf("## Incoming") : olderReading.length());
            assertThat(olderFields).contains(newer.adr().code().value() + " \"Store resources as RDF-star\"");
        });
    }

    /**
     * A blank node has no handle that stays the same between two reads: {@code resource_get}
     * refuses it with the reason and still reads the other handles of the call, and
     * {@code store_overview} prints no drill-down for a blank-node subject.
     */
    @Test
    void aBlankNodeHandleIsRefusedWithItsReasonAndTheDigestPromisesNoDrillDownForIt() {
        onStore(store -> {
            // given
            Requirement fr = store.addRequirement();
            store.seed(graph -> {
                BlankNode loose = store.rdf.createBlankNode();
                graph.add(loose, store.iri(RDF_TYPE), store.iri(SKOS + "Concept"));
                graph.add(loose, store.iri(SKOS + "prefLabel"), store.rdf.createLiteral("Loose concept"));
            });
            String overview = store.bean(de.hauschel.arknet.mcp.store.StoreReportTools.class)
                    .storeOverview(null, store.anchor);
            String blankLine = overview.lines().filter(line -> line.contains("\"Loose concept\"")).findFirst()
                    .orElseThrow();
            String blankHandle = blankLine.substring(0, blankLine.indexOf(' '));

            // when
            String answer = store.get(blankHandle + " " + fr.code().value());

            // then
            assertThat(blankHandle).startsWith("_:");
            assertThat(blankLine).doesNotContain("resource_get(");
            assertThat(answer).startsWith("# " + blankHandle + " NOT FOUND\n").contains("blank node")
                    .contains("\n# FR-1 [");
        });
    }

    @Test
    void aDeletedTermAnswersWithWhatItWasByCodeAndIriAndItsHistoryEndsWithTheTombstone() {
        onStore(store -> {
            // given
            Term term = store.addTerm("Order", "A customer's request to buy.");
            store.bean(TermService.class).delete(store.project, term.code());

            // when
            String byCode = store.get(term.code().value());
            String byIri = store.get(term.id().value().value());
            String history = store.history(term.code().value());

            // then
            for (String answer : List.of(byCode, byIri)) {
                assertThat(answer).contains(" DELETED\n").contains("- deleted at ")
                        .contains(term.code().value() + " stays taken").contains("## Last version")
                        .contains("\"Order\"@en").doesNotContain("NOT FOUND");
            }
            assertThat(history).contains("Publish state: DELETED").contains("## DELETED").contains("## History (2)");
            assertThat(history.lines().filter(line -> line.contains("  tombstone")).count()).isEqualTo(1);
        });
    }

    @Test
    void theHistoryOfATypedRequirementListsEveryPublishWithCreatedAndLastChanged() {
        onStore(store -> {
            // given
            Term term = store.addTerm("Order", "A customer's request to buy.");
            Requirement fr = store.addRequirement();
            store.bean(RequirementService.class).linkTerm(store.project, fr.code(), term.code().value(), LANGUAGE);

            // when
            String history = store.history(fr.code().value());

            // then
            assertThat(history).startsWith("# FR-1\nIRI: <" + fr.id().value().value() + ">\n")
                    .contains("Publish state: PUBLISHED_UNCHANGED").contains("Created: ").contains("Last changed: ")
                    .contains("## History (2)");
            assertThat(history.lines().filter(line -> line.contains("  publish  ")).count()).isEqualTo(2);
        });
    }

    @Test
    void theHistoryOfAHandleNamingNothingIsRejected() {
        onStore(store -> assertThatThrownBy(() -> store.history("FR-999"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("FR-999"));
    }

    @Test
    void eachCallReadsTheProjectOfItsAnchorAndAnUnregisteredAnchorIsRejected() {
        onStore(store -> {
            // given
            store.addRequirement();
            Store other = store.secondProject("other");
            other.bean(RequirementService.class).add(other.project, new NewRequirement("Other login",
                    "The system shall let a user log in elsewhere.", null, RequirementType.FUNCTIONAL, null, null,
                    List.of("Logged in."), null), LANGUAGE);

            // when / then
            assertThat(store.get("FR-1")).contains("\"Customer can order\"").doesNotContain("Other login");
            assertThat(other.get("FR-1")).contains("\"Other login\"").doesNotContain("Customer can order");
            ResourceReadMcpTools tools = store.bean(ResourceReadMcpTools.class);
            assertThatThrownBy(() -> tools.resourceGet(null, "FR-1", null, null, "/never/registered"))
                    .isInstanceOf(UnresolvedProjectAnchorException.class);
            assertThatThrownBy(() -> tools.resourceHistory(null, "FR-1", null, "/never/registered"))
                    .isInstanceOf(UnresolvedProjectAnchorException.class);
        });
    }

    private void onStore(final Consumer<Store> body) {
        contextRunner
                .withPropertyValues("arknet.rdf.storage=" + storageDir,
                        "arknet.report.dir=" + workspace.resolve("reports"))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    body.accept(Store.registered(context, workspace, "resource-read"));
                });
    }

    /** One registered project of a Spring context, its typed services and its read tools. */
    static final class Store {

        private final AssertableApplicationContext context;
        private final Path workspace;
        private final String anchor;
        private final ProjectId project;
        private final RDF rdf = new SimpleRdf();

        private Store(final AssertableApplicationContext context, final Path workspace, final String anchor) {
            this.context = context;
            this.workspace = workspace;
            this.anchor = anchor;
            this.project = context.getBean(ProjectResolver.class).resolve(anchor).id();
        }

        static Store registered(final AssertableApplicationContext context, final Path workspace,
                final String label) {
            try {
                Path checkout = Files.createDirectories(workspace.resolve(label));
                context.getBean(ProjectService.class).register(label, new Anchor(checkout.toString(), AnchorType.PATH),
                        null, null, LANGUAGE, null);
                return new Store(context, workspace, checkout.toString());
            } catch (java.io.IOException failure) {
                throw new java.io.UncheckedIOException(failure);
            }
        }

        Store secondProject(final String label) {
            return registered(context, workspace, label);
        }

        <T> T bean(final Class<T> type) {
            return context.getBean(type);
        }

        String get(final String handles) {
            return bean(ResourceReadMcpTools.class).resourceGet(null, handles, null, null, anchor);
        }

        String history(final String handle) {
            return bean(ResourceReadMcpTools.class).resourceHistory(null, handle, null, anchor);
        }

        io.kogn.rdf.terms.IRI iri(final String value) {
            return rdf.createIRI(value);
        }

        /** Writes triples straight into the content graph, past every repository and the versioning. */
        void seed(final Consumer<Graph> triples) {
            Graph graph = rdf.createGraph();
            triples.accept(graph);
            try (DatasetHandle handle = bean(DatasetLifecycle.class).acquire(new DatasetId(project.value()))) {
                handle.transactor().inTransaction(tx -> {
                    tx.add(rdf.createIRI(ContentGraph.GRAPH_IRI), graph);
                    return null;
                });
            }
        }

        Term addTerm(final String label, final String definition) {
            return bean(TermService.class).add(project, new NewTerm(label, definition, null), LANGUAGE);
        }

        Requirement addRequirement() {
            return bean(RequirementService.class).add(project, new NewRequirement("Customer can order",
                    "The system shall let a customer place an order.", null, RequirementType.FUNCTIONAL, null, null,
                    List.of("First.", "Second."), null), LANGUAGE);
        }

        BoundedContext addBoundedContext(final String name) {
            return bean(BoundedContextService.class).add(project,
                    new NewBoundedContext(name, "Keeps the model consistent.", null, null, null), LANGUAGE);
        }

        AdrDetail addAdr(final String name) {
            return bean(AdrService.class).add(project, new NewAdr(name, "Resources need identities.",
                    "Every resource is an RDF resource.",
                    List.of(new NewConsequence("Links are first-class.", ConsequenceType.POSITIVE)), List.of(),
                    null, List.of(), List.of(), List.of(), List.of()), LANGUAGE);
        }
    }
}
