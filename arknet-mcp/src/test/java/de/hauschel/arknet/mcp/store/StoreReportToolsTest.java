package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.DatasetId;
import io.kogn.rdf.dataset.DatasetLifecycle;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.adapter.kogniordf.KognioRdfRequirementRepositoryFactory;
import de.hauschel.arknet.req.application.port.out.RequirementRepository;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.port.out.TermRepository;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;
import de.hauschel.arknet.ul.domain.TermId;

/**
 * End-to-end test of the generic store report against a real kognio-rdf store shared by
 * both bounded contexts (requirements + ubiquitous-language) - proving the tools are truly
 * generic and read from one dataset. Uses the shared-lifecycle factory path introduced for
 * #47, so no RDF4J type is named here.
 */
class StoreReportToolsTest {

    private static final WorkspaceId WORKSPACE = new WorkspaceId("noistill");
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/store-report-test-fr-1";
    private static final String TERM_1_IRI = "https://w3id.org/arknet/id/store-report-test-term-1";

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

        requirements.create(WORKSPACE, new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of("Login succeeds with valid credentials")));
        terms.create(WORKSPACE, new Term(
                new TermId(ResourceId.of(TERM_1_IRI)), new TermCode("TERM-1"), "Anmeldung",
                "The act of proving one's identity.", null));

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

        // Digest is generic and contains resources from both BCs. Both identities are opaque
        // IRIs (requirement since #68, term since #71), unbound to any CURIE prefix, so the
        // digest handle falls back to their dcterms:identifier ("FR-1" / "TERM-1") instead of
        // the raw IRI.
        assertThat(result).contains("# Workspace noistill");
        assertThat(result).doesNotContain(FR_1_IRI);
        assertThat(result).doesNotContain(TERM_1_IRI);
        assertThat(result).contains("FR-1").contains("-> resource_get(\"FR-1\")");
        assertThat(result).contains("TERM-1").contains("-> resource_get(\"TERM-1\")");
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

    /**
     * Since requirement identity became an opaque IRI (#68), the {@code req:} CURIE prefix
     * (bound to the old {@code .../model/requirement/} namespace) no longer aliases a freshly
     * added requirement's subject - only the full IRI and the {@code dcterms:identifier}-based
     * bare-id lookup do.
     */
    @Test
    void resourceGetResolvesFullIriAndBareIdToTheSameResource() {
        String viaIri = tools.resourceGet(FR_1_IRI);
        String viaBareId = tools.resourceGet("FR-1");

        assertThat(viaBareId).isEqualTo(viaIri);
        assertThat(viaIri).contains("dcterms:title").contains("\"Login\"");
        assertThat(viaIri).contains("# Outgoing").contains("# Incoming");
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
