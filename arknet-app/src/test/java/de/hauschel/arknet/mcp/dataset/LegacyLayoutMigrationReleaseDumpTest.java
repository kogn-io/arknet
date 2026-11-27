// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

import static org.assertj.core.api.Assertions.assertThat;

import de.hauschel.arknet.actor.application.RoleService;
import de.hauschel.arknet.actor.application.port.in.RoleDetail;
import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.adr.application.AdrService;
import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.AdrStatus;
import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcp.ArknetMcpConfiguration;
import de.hauschel.arknet.persistence.ContentGraph;
import de.hauschel.arknet.persistence.VersionedWriteFunnel;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.uc.application.UseCaseService;
import de.hauschel.arknet.uc.domain.UseCase;
import de.hauschel.arknet.uc.domain.UseCaseCode;
import de.hauschel.arknet.ul.application.TermService;
import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.IRI;
import io.kogn.rdf.versioning.PublishState;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.eclipse.rdf4j.model.Model;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.impl.LinkedHashModel;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;

/**
 * The startup migration against a store a released arknet actually wrote, not one rebuilt through
 * this version's services: {@code legacy-layout-v0.12.0.trig} is a frozen {@code project_export} of
 * a synthetic project from the v0.12.0 image. Its graphs are loaded as they are, migrated, and the
 * content graph must then hold every triple of them - only the child IRIs renamed - and every
 * service must read the resources back.
 *
 * <p>The class and property IRIs below are spelled out as v0.12.0 stored them, for the reason the
 * migration spells out its own: a test of a migration step reads the vocabulary of the layout it
 * migrates, not the live constants.</p>
 */
class LegacyLayoutMigrationReleaseDumpTest {

    private static final String DUMP = "legacy-layout-v0.12.0.trig";
    private static final ProjectId PROJECT = new ProjectId("legacy-v0-12-0");
    private static final DatasetId DATASET = new DatasetId(PROJECT.value());
    private static final String EN = "en";
    private static final String GLOSSARY_SCHEME = "https://w3id.org/arknet/model/glossary";

    /** The edges from a resource to a child the migration renames, as v0.12.0 stored them. */
    private static final Set<String> CHILD_EDGES = Set.of(
            "https://w3id.org/arknet/requirements#acceptanceCriterion",
            "https://w3id.org/arknet/requirements#mainStep",
            "https://w3id.org/arknet/requirements#extensionStep",
            "https://w3id.org/arknet/architecture#consequence",
            "https://w3id.org/arknet/architecture#consideredOption",
            "https://w3id.org/arknet/ddd#partOf");

    private static final ValueFactory VALUES = SimpleValueFactory.getInstance();

    @TempDir
    Path storageDir;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    @Test
    void everyResourceOfTheReleasedLayoutIsMigratedAndNothingIsDroppedButTheSchemeDeclaration() {
        loadDump();

        LegacyLayoutMigrationReport report = migrateDirectly();

        assertThat(report.outcome()).isEqualTo(LegacyLayoutMigrationReport.Outcome.MIGRATED);
        assertThat(report.resourcesByType()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "requirement", 2, "constraint", 2, "use case", 1, "glossary term", 3, "bounded context", 2,
                "context relationship", 1, "architecture decision", 3, "actor", 2, "role", 2));
        assertThat(report.droppedEdges()).isEmpty();
        assertThat(report.droppedSubjects()).singleElement().asString().contains(GLOSSARY_SCHEME);
        // ADR-2 is related to ADR-1, which ADR-3 supersedes: the asserted context of ADR-2 holds the
        // slice of ADR-1 but only the type of ADR-3, so the ADR shape rejects the bare ADR-3 and
        // ADR-2 is carried over past the gate although its own stored form is complete
        assertThat(report.carriedPastGate()).singleElement().asString()
                .startsWith("architecture decision ADR-2 ")
                .contains("focusNode=https://w3id.org/arknet/id/bd7e8039-0a9b-4403-83c6-90bb2fe3a186");
        assertThat(report.convertedCriteria()).isEmpty();
        assertThat(report.retainedCodes()).containsExactly("TERM-4");
    }

    @Test
    void theContentGraphHoldsEveryTripleOfTheReleasedLayoutWithOnlyTheChildIrisRenamed() {
        Model legacy = loadDump();

        migrateDirectly();

        Model expected = new LinkedHashModel();
        legacy.stream()
                .filter(statement -> !LegacyLayoutFixture.PROVENANCE.equals(String.valueOf(statement.getContext())))
                .filter(statement -> !GLOSSARY_SCHEME.equals(statement.getSubject().stringValue()))
                .forEach(statement -> expected.add(statement.getSubject(), statement.getPredicate(),
                        statement.getObject()));
        Set<Value> legacyChildren = expected.stream()
                .filter(statement -> CHILD_EDGES.contains(statement.getPredicate().stringValue()))
                .map(Statement::getObject)
                .collect(Collectors.toSet());
        Model migrated = contentGraph();
        Set<Value> migratedChildren = migrated.stream()
                .map(Statement::getSubject)
                .filter(subject -> subject.stringValue().contains("#"))
                .collect(Collectors.toSet());

        assertThat(migratedChildren).hasSameSizeAs(legacyChildren);
        assertThat(Models.isomorphic(blankNodes(expected, legacyChildren), blankNodes(migrated, migratedChildren)))
                .as("content graph equals the released layout up to the child IRIs")
                .isTrue();
    }

    @Test
    void theServicesReadTheMigratedResourcesWithTheirChildrenAndEdges() {
        loadDump();

        inDaemon(context -> {
            VersionedWriteFunnel funnel = context.getBean("requirementsWriteFunnel", VersionedWriteFunnel.class);
            List<String> resources = resourceIris(context.getBean(DatasetLifecycle.class));
            assertThat(resources).hasSize(18);
            for (String resource : resources) {
                assertThat(funnel.history(DATASET, resource)).as(resource).hasSize(1);
                assertThat(funnel.publishState(DATASET, resource)).as(resource)
                        .isEqualTo(PublishState.PUBLISHED_UNCHANGED);
            }

            AdrService adrs = context.getBean(AdrService.class);
            AdrDetail superseded = adrs.get(PROJECT, new AdrCode("ADR-1"), EN).orElseThrow();
            assertThat(superseded.adr().status()).isEqualTo(AdrStatus.SUPERSEDED);
            assertThat(superseded.supersededBy()).containsExactly(new AdrCode("ADR-3"));
            assertThat(superseded.adr().consequences()).hasSize(2);
            assertThat(superseded.adr().consideredOptions()).hasSize(2);
            assertThat(superseded.adr().addressesRequirements()).hasSize(1);
            assertThat(superseded.adr().affectsContexts()).hasSize(1);
            assertThat(superseded.adr().usesTerms()).hasSize(1);
            assertThat(adrs.get(PROJECT, new AdrCode("ADR-2"), EN).orElseThrow().relatedTo())
                    .containsExactly(new AdrCode("ADR-1"));

            RoleDetail role = context.getBean(RoleService.class).get(PROJECT, new RoleCode("ROLE-1"), EN)
                    .orElseThrow();
            assertThat(role.filledByActors()).extracting(actor -> actor.code().value()).containsExactly("ACTOR-1");

            UseCase useCase = context.getBean(UseCaseService.class).get(PROJECT, new UseCaseCode("UC1"), "de")
                    .orElseThrow();
            assertThat(useCase.goal()).isEqualTo("Der Empfaenger erfaehrt, wo das Paket ist.");
            assertThat(useCase.steps()).hasSize(2);
            assertThat(useCase.extensions()).hasSize(1);
            assertThat(useCase.supportingRoles()).hasSize(1);
            assertThat(useCase.constrainedBy()).hasSize(1);

            assertThat(context.getBean(TermService.class).get(PROJECT, new TermCode("TERM-1"), "de").orElseThrow()
                    .definition()).isEqualTo("Ein versiegeltes Paket, das zur Zustellung uebergeben wird.");
        });
    }

    // --- fixture ------------------------------------------------------------------------------

    /** Loads the graphs of the dump the dataset held into {@link #DATASET}, and returns them. */
    private Model loadDump() {
        Model legacy = new LinkedHashModel();
        parseDump().stream()
                .filter(statement -> statement.getContext() != null
                        && LegacyLayoutRefusingDatasetLifecycle.LEGACY_GRAPHS
                                .contains(statement.getContext().stringValue()))
                .forEach(legacy::add);
        StringBuilder insert = new StringBuilder("INSERT DATA {\n");
        legacy.contexts().forEach(graph -> {
            Model triples = new LinkedHashModel();
            legacy.filter(null, null, null, graph)
                    .forEach(statement -> triples.add(statement.getSubject(), statement.getPredicate(),
                            statement.getObject()));
            StringWriter ntriples = new StringWriter();
            Rio.write(triples, ntriples, RDFFormat.NTRIPLES);
            insert.append("GRAPH <").append(graph.stringValue()).append("> {\n").append(ntriples).append("}\n");
        });
        insert.append('}');
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try (DatasetHandle handle = raw.acquire(DATASET)) {
            handle.transactor().inTransaction(tx -> {
                tx.update(insert.toString());
                return null;
            });
            assertThat(LegacyLayoutRefusingDatasetLifecycle.holdsLegacyLayout(handle.sparqlQuery())).isTrue();
        } finally {
            raw.shutDownAll();
        }
        return legacy;
    }

    private static Model parseDump() {
        try (InputStream in = LegacyLayoutMigrationReleaseDumpTest.class.getResourceAsStream(DUMP)) {
            assertThat(in).as(DUMP).isNotNull();
            return Rio.parse(in, RDFFormat.TRIG);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private LegacyLayoutMigrationReport migrateDirectly() {
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try {
            return LegacyLayoutMigration.over(raw, new DisplayLocale(Locale.ENGLISH, Locale.ENGLISH))
                    .migrate(raw, DATASET);
        } finally {
            raw.shutDownAll();
        }
    }

    private Model contentGraph() {
        DatasetLifecycle raw = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        try (DatasetHandle handle = raw.acquire(DATASET)) {
            String triples = handle.sparqlQuery().select("SELECT ?s ?p ?o WHERE { GRAPH <" + ContentGraph.GRAPH_IRI
                    + "> { ?s ?p ?o } }")
                    .map(row -> row.getValue("s").orElseThrow().ntriplesString() + " "
                            + row.getValue("p").orElseThrow().ntriplesString() + " "
                            + row.getValue("o").orElseThrow().ntriplesString() + " .")
                    .collect(Collectors.joining("\n"));
            return Rio.parse(new StringReader(triples), RDFFormat.NTRIPLES);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } finally {
            raw.shutDownAll();
        }
    }

    /** {@code model} with each IRI of {@code children} replaced by one blank node of its own. */
    private static Model blankNodes(Model model, Set<Value> children) {
        Map<Value, Value> blank = new HashMap<>();
        children.forEach(child -> blank.put(child, VALUES.createBNode()));
        Model replaced = new LinkedHashModel();
        model.forEach(statement -> {
            Value subject = blank.getOrDefault(statement.getSubject(), statement.getSubject());
            replaced.add((Resource) subject, statement.getPredicate(),
                    blank.getOrDefault(statement.getObject(), statement.getObject()));
        });
        return replaced;
    }

    private static List<String> resourceIris(DatasetLifecycle lifecycle) {
        try (DatasetHandle handle = lifecycle.acquire(DATASET)) {
            return handle.sparqlQuery().select("SELECT DISTINCT ?s WHERE { GRAPH <" + ContentGraph.GRAPH_IRI
                    + "> { ?s a ?type } FILTER(!CONTAINS(STR(?s), \"#\")) }")
                    .map(row -> ((IRI) row.getValue("s").orElseThrow()).getIRIString())
                    .sorted()
                    .collect(Collectors.toList());
        }
    }

    private void inDaemon(Consumer<ApplicationContext> work) {
        contextRunner.withPropertyValues("arknet.rdf.storage=" + storageDir).run(context -> {
            assertThat(context).hasNotFailed();
            work.accept(context);
        });
    }
}
