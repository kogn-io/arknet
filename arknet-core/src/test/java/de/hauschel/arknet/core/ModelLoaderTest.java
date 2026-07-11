package de.hauschel.arknet.core;

import org.eclipse.rdf4j.repository.Repository;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ModelLoaderTest {

    private static final Path ORDER_DOMAIN_MODEL = Path.of("..", "examples", "order-domain.ttl");

    private static final String ASK_REQUIREMENTS_CLASS_LOADED = """
            PREFIX arkreq: <https://w3id.org/arknet/requirements#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            ASK { arkreq:Requirement a owl:Class }
            """;

    private static final String ASK_ARCHITECTURE_CLASS_LOADED = """
            PREFIX arkarch: <https://w3id.org/arknet/architecture#>
            PREFIX owl: <http://www.w3.org/2002/07/owl#>
            ASK { arkarch:ArchitectureDecisionRecord a owl:Class }
            """;

    private final ModelLoader modelLoader = new ModelLoader();

    @Test
    void loadModelWiresAllOntologyModules() throws IOException {
        // given
        Repository repo = modelLoader.loadModel(ORDER_DOMAIN_MODEL);

        // when
        try (var conn = repo.getConnection()) {
            boolean requirementClassLoaded = conn.prepareBooleanQuery(ASK_REQUIREMENTS_CLASS_LOADED).evaluate();
            boolean architectureClassLoaded = conn.prepareBooleanQuery(ASK_ARCHITECTURE_CLASS_LOADED).evaluate();

            // then
            assertTrue(requirementClassLoaded, "arkreq:Requirement should be a known owl:Class after loadModel");
            assertTrue(architectureClassLoaded,
                    "arkarch:ArchitectureDecisionRecord should be a known owl:Class after loadModel");
        } finally {
            repo.shutDown();
        }
    }

    @Test
    void validateModelReturnsReportForOrderDomainExample() throws IOException {
        // when
        ValidationReport report = modelLoader.validateModel(ORDER_DOMAIN_MODEL);

        // then
        assertNotNull(report);
        assertNotNull(report.results());
    }
}
