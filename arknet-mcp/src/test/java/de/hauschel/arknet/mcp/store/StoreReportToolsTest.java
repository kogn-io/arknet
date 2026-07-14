package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * End-to-end test of the generic store report against a real kognio-rdf store shared by
 * both bounded contexts (requirements + ubiquitous-language) - proving the tools are truly
 * generic and read from one dataset. Uses the shared-lifecycle factory path introduced for
 * #47, so no RDF4J type is named here.
 */
class StoreReportToolsTest {

    private static final WorkspaceId WORKSPACE = new WorkspaceId("noistill");
    private static final String FR_1_IRI = "https://w3id.org/arknet/model/requirement/FR-1";

    @TempDir
    Path storageDir;

    @TempDir
    Path reportDir;

    private DatasetLifecycle lifecycle;
    private StoreReportTools tools;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        RequirementRepository requirements = KognioRdfRequirementRepositoryFactory.over(lifecycle);
        TermRepository terms = KognioRdfTermRepositoryFactory.over(lifecycle);

        requirements.save(WORKSPACE, new Requirement(
                new RequirementId("FR-1"), "Login", "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null));
        terms.save(WORKSPACE, new Term(
                new TermId("TERM-1"), "Anmeldung", "The act of proving one's identity.", null));

        Prefixes prefixes = Prefixes.defaults();
        StoreReader reader = new StoreReader(lifecycle);
        tools = new StoreReportTools(reader, prefixes, new HtmlReportRenderer(prefixes), WORKSPACE, reportDir);
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(WORKSPACE.value()));
    }

    @Test
    void storeOverviewDigestSpansBothBoundedContextsAndWritesHtml() throws Exception {
        String result = tools.storeOverview(null);

        // Digest is generic and contains resources from both BCs.
        assertThat(result).contains("# Workspace noistill");
        assertThat(result).contains("req:FR-1").contains("-> resource_get(\"req:FR-1\")");
        assertThat(result).contains("term:TERM-1").contains("-> resource_get(\"term:TERM-1\")");
        assertThat(result).contains("# HTML report: ");
        assertThat(result).contains("no dangling references");

        // HTML side effect: a self-contained file with no external dependencies.
        Path html = reportDir.resolve("store-report.html");
        assertThat(html).exists();
        String content = Files.readString(html);
        assertThat(content).contains("<!doctype html>").contains("arknet Store Report");
        assertThat(content).doesNotContain("http://cdn").doesNotContain("<script src");
        // Status renders as a pill (stored as vocabulary IRI arkreq:Proposed -> local name).
        assertThat(content).contains("class=\"pill status-proposed\">Proposed<");
    }

    @Test
    void resourceGetResolvesCurieFullIriAndBareIdToTheSameResource() {
        String viaCurie = tools.resourceGet("req:FR-1");
        String viaIri = tools.resourceGet(FR_1_IRI);
        String viaBareId = tools.resourceGet("FR-1");

        assertThat(viaCurie).isEqualTo(viaIri);
        assertThat(viaBareId).isEqualTo(viaCurie);
        assertThat(viaCurie).contains("req:FR-1");
        assertThat(viaCurie).contains("dcterms:title").contains("\"Login\"");
        assertThat(viaCurie).contains("# Outgoing").contains("# Incoming");
    }

    @Test
    void resourceGetRejectsUnknownPrefixWithDidacticMessage() {
        assertThatThrownBy(() -> tools.resourceGet("nope:X"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown prefix")
                .hasMessageContaining("Known prefixes");
    }

    @Test
    void resourceGetRejectsUnknownBareIdWithGuidance() {
        assertThatThrownBy(() -> tools.resourceGet("FR-999"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No resource found");
    }
}
