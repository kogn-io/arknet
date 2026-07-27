// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.kernel.WorkspaceResolver;
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
        // Shared server: the default workspace is resolved per call. This test drives calls with a
        // null context (no origin), so the resolver returns the fixed test workspace and the report
        // lands in the fallback reportDir - exactly the pre-#137 single-workspace behaviour.
        WorkspaceResolver workspaces = originDir -> WORKSPACE;
        tools = new StoreReportTools(reader, prefixes, new HtmlReportRenderer(prefixes), workspaces, reportDir, null);
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(WORKSPACE.value()));
    }

    @Test
    void storeOverviewDigestSpansBothBoundedContextsAndWritesHtml() throws Exception {
        String result = tools.storeOverview(null, null);

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

        // HTML side effect: a self-contained file with no external dependencies, under a
        // workspace-scoped subdirectory of the fallback dir (issue #172).
        Path html = reportDir.resolve(WORKSPACE.value()).resolve("store-report.html");
        assertThat(html).exists();
        String content = Files.readString(html);
        assertThat(content).contains("<!doctype html>").contains("arknet Store Report");
        assertThat(content).doesNotContain("http://cdn").doesNotContain("<script src");
        // Status renders as a pill (stored as vocabulary IRI arkreq:Proposed -> local name).
        assertThat(content).contains("class=\"pill status-proposed\">Proposed<");
    }

    /**
     * Reproduces #158: a shared daemon cannot assume it shares a filesystem with every calling
     * client (e.g. containerized, only {@code /data/rdf} mounted) - the origin dir the caller's
     * {@code X-Arknet-Workspace-Dir} header names may not be writable (or even exist) from the
     * daemon's own filesystem. The write must fall back to the server's report dir instead of
     * failing the whole tool call.
     */
    @Test
    void storeOverviewFallsBackToServerReportDirWhenTheOriginDirCannotBeWritten(@TempDir final Path root)
            throws Exception {
        final Path blockedOriginDir = root.resolve("not-a-directory");
        Files.writeString(blockedOriginDir, "a plain file blocking directory creation");

        final McpTransportContext transportContext = McpTransportContext.create(
                Map.of(WorkspaceResolver.WORKSPACE_DIR_KEY, blockedOriginDir.toString()));
        final McpSyncRequestContext context = mock(McpSyncRequestContext.class);
        when(context.transportContext()).thenReturn(transportContext);

        final String result = tools.storeOverview(context, null);

        assertThat(result).contains("# Workspace noistill").doesNotContain("FAILED");
        final Path fallbackHtml = reportDir.resolve(WORKSPACE.value()).resolve("store-report.html");
        assertThat(fallbackHtml).exists();
        assertThat(result).contains("# HTML report: " + fallbackHtml.toAbsolutePath());
    }

    /**
     * The other half of #158: when even the fallback report dir is unwritable, {@code
     * store_overview} must still return the digest - the whole point of the fallback is
     * resilience, so a client that gets neither writable dir must not lose the digest too.
     */
    @Test
    void storeOverviewReturnsDigestWithFailureLineWhenNoReportDirIsWritable(@TempDir final Path root)
            throws Exception {
        final Path blockedFallbackDir = root.resolve("not-a-directory");
        Files.writeString(blockedFallbackDir, "a plain file blocking directory creation");

        final Prefixes prefixes = Prefixes.defaults();
        final StoreReader reader = new StoreReader(lifecycle);
        final WorkspaceResolver workspaces = originDir -> WORKSPACE;
        final StoreReportTools toolsWithBrokenFallback = new StoreReportTools(
                reader, prefixes, new HtmlReportRenderer(prefixes), workspaces, blockedFallbackDir, null);

        final String result = toolsWithBrokenFallback.storeOverview(null, null);

        assertThat(result).contains("# Workspace noistill").contains("FR-1");
        assertThat(result)
                .contains("# HTML report: FAILED to write to " + blockedFallbackDir.resolve(WORKSPACE.value()));
        assertThat(result).contains("FileSystemException");
    }

    /**
     * #160: a containerized daemon's {@code fallbackReportDir} is a mount point
     * (e.g. {@code /data/report}) the calling agent, running outside the container, cannot
     * reach. When {@code reportHostDir} names that mount's host-side path, the digest must
     * report the host path instead of the path the file was actually written to.
     */
    @Test
    void storeOverviewReportsTheHostMountPathInsteadOfTheContainerInternalWriteTarget(@TempDir final Path hostDir)
            throws Exception {
        final Prefixes prefixes = Prefixes.defaults();
        final StoreReader reader = new StoreReader(lifecycle);
        final WorkspaceResolver workspaces = originDir -> WORKSPACE;
        final StoreReportTools toolsWithHostDir = new StoreReportTools(
                reader, prefixes, new HtmlReportRenderer(prefixes), workspaces, reportDir, hostDir);

        final String result = toolsWithHostDir.storeOverview(null, null);

        final Path writtenHtml = reportDir.resolve(WORKSPACE.value()).resolve("store-report.html");
        assertThat(writtenHtml).exists();
        assertThat(result)
                .contains("# HTML report: " + hostDir.resolve(WORKSPACE.value()).resolve("store-report.html"))
                .doesNotContain(reportDir.toString());
    }

    /**
     * #172: every workspace served by a shared daemon falls back to the very same
     * {@code fallbackReportDir} (e.g. a containerized daemon has no writable origin dir for
     * ANY project). Without a workspace-scoped subdirectory, the second workspace's report
     * would silently overwrite the first's under the identical file name.
     */
    @Test
    void storeOverviewWritesEachWorkspaceToItsOwnSubdirectoryOfTheFallbackDir() throws Exception {
        final WorkspaceId otherWorkspace = new WorkspaceId("other-workspace-172");
        try {
            tools.storeOverview(null, null);
            tools.storeOverview(null, otherWorkspace.value());

            final Path ownReport = reportDir.resolve(WORKSPACE.value()).resolve("store-report.html");
            final Path otherReport = reportDir.resolve(otherWorkspace.value()).resolve("store-report.html");
            assertThat(ownReport).exists();
            assertThat(otherReport).exists();
            assertThat(Files.readString(ownReport)).contains("FR-1");
            assertThat(Files.readString(otherReport)).doesNotContain("FR-1");
        } finally {
            lifecycle.close(new DatasetId(otherWorkspace.value()));
        }
    }

    /**
     * Since requirement identity became an opaque IRI (#68), the {@code req:} CURIE prefix
     * (bound to the old {@code .../model/requirement/} namespace) no longer aliases a freshly
     * added requirement's subject - only the full IRI and the {@code dcterms:identifier}-based
     * bare-id lookup do.
     */
    @Test
    void resourceGetResolvesFullIriAndBareIdToTheSameResource() {
        String viaIri = tools.resourceGet(null, FR_1_IRI, null);
        String viaBareId = tools.resourceGet(null, "FR-1", null);

        assertThat(viaBareId).isEqualTo(viaIri);
        assertThat(viaIri).contains("dcterms:title").contains("\"Login\"");
        assertThat(viaIri).contains("# Outgoing").contains("# Incoming");
    }

    @Test
    void resourceGetRejectsUnknownPrefixWithDidacticMessage() {
        assertThatThrownBy(() -> tools.resourceGet(null, "nope:X", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown prefix")
                .hasMessageContaining("Known prefixes");
    }

    @Test
    void resourceGetRejectsUnknownBareIdWithGuidance() {
        assertThatThrownBy(() -> tools.resourceGet(null, "FR-999", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No resource found");
    }

    /**
     * Reproduces #106: {@code resource_get} used to ignore its workspace parameter entirely
     * and always read {@code defaultWorkspaceId}, unlike {@code store_overview} which already
     * honored an optional {@code workspace} argument. Two workspaces each carry a requirement
     * with the SAME business code ("FR-1") but different identities/titles - a caller passing
     * the other workspace's id must get that workspace's resource back, not a silent hit in
     * the default workspace.
     */
    @Test
    void resourceGetHonorsExplicitWorkspaceParameter() {
        WorkspaceId otherWorkspace = new WorkspaceId("other-workspace");
        String otherFr1Iri = "https://w3id.org/arknet/id/store-report-test-fr-1-other";

        RequirementRepository requirements = KognioRdfRequirementRepositoryFactory.over(lifecycle);
        requirements.create(otherWorkspace, new Requirement(
                new RequirementId(ResourceId.of(otherFr1Iri)), new RequirementCode("FR-1"), "Andere Anmeldung",
                "The system shall authenticate a user in the other workspace.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of("Login succeeds with valid credentials")));

        try {
            String fromOtherWorkspace = tools.resourceGet(null, "FR-1", otherWorkspace.value());
            String fromDefaultWorkspace = tools.resourceGet(null, "FR-1", null);

            assertThat(fromOtherWorkspace).contains("dcterms:title").contains("\"Andere Anmeldung\"");
            assertThat(fromOtherWorkspace).doesNotContain("\"Login\"");
            assertThat(fromDefaultWorkspace).contains("dcterms:title").contains("\"Login\"");
            assertThat(fromDefaultWorkspace).doesNotContain("\"Andere Anmeldung\"");
        } finally {
            lifecycle.close(new DatasetId(otherWorkspace.value()));
        }
    }
}
