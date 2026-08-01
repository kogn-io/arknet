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
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.mcp.report.AdrCards;
import de.hauschel.arknet.mcp.report.BoundedContextCards;
import de.hauschel.arknet.mcp.report.HtmlReportRenderer;
import de.hauschel.arknet.mcp.report.ModelViews;
import de.hauschel.arknet.mcp.report.RequirementCards;
import de.hauschel.arknet.mcp.report.UseCaseCards;
import de.hauschel.arknet.prj.application.port.in.FindProject;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;
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

    private static final ProjectId PROJECT = new ProjectId("sample-project");
    private static final String ANCHOR = "/home/dev/projects/sample-project";
    private static final ProjectId OTHER_PROJECT = new ProjectId("other-project");
    private static final String OTHER_ANCHOR = "/elsewhere/other-project";
    private static final String FR_1_IRI = "https://w3id.org/arknet/id/store-report-test-fr-1";
    private static final String TERM_1_IRI = "https://w3id.org/arknet/id/store-report-test-term-1";

    @TempDir
    Path storageDir;

    @TempDir
    Path reportDir;

    private DatasetLifecycle lifecycle;
    private Requirement fr1;
    private Term term1;
    private StoreReportTools tools;

    @BeforeEach
    void setUp() {
        lifecycle = KognioRdfRequirementRepositoryFactory.persistentLifecycle(storageDir);
        RequirementRepository requirements =
                KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        TermRepository terms = KognioRdfTermRepositoryFactory.over(lifecycle);

        fr1 = new Requirement(
                new RequirementId(ResourceId.of(FR_1_IRI)), new RequirementCode("FR-1"), "Login",
                "The system shall authenticate a user.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of("Login succeeds with valid credentials"));
        term1 = new Term(
                new TermId(ResourceId.of(TERM_1_IRI)), new TermCode("TERM-1"), "Anmeldung",
                "The act of proving one's identity.", null);
        requirements.create(PROJECT, fr1);
        terms.create(PROJECT, term1);

        Prefixes prefixes = Prefixes.defaults();
        StoreReader reader = new StoreReader(lifecycle);
        // Shared server: the project is resolved per call by looking up the caller's anchor
        // (ADR-016). The stub below stands in for the registry with exactly two registered
        // anchors, so these tests address two projects the way a real client does.
        ProjectResolver projects = StoreReportToolsTest::resolveTestAnchor;
        tools = new StoreReportTools(
                reader, prefixes, new HtmlReportRenderer(prefixes), modelViews(), projects, NO_LABELS,
                reportDir, null);
    }

    /**
     * Stands in for the project registry: two registered anchors, and a hard failure for anything
     * else. Rejecting the unknown case rather than defaulting is the point - a stub that answered
     * every anchor would let a test pass that a real deployment fails (ADR-016 decision 3).
     */
    private static ProjectId resolveTestAnchor(final String anchor) {
        if (ANCHOR.equals(anchor)) {
            return PROJECT;
        }
        if (OTHER_ANCHOR.equals(anchor)) {
            return OTHER_PROJECT;
        }
        throw new UnresolvedProjectAnchorException(anchor, "no project registered for '" + anchor + "'");
    }

    /**
     * Stands in for a project id that is not (or not yet) in the label registry - the header then
     * falls back to the raw id, which is what most of these tests assert on
     * ({@code "# Project sample-project"}). The label-aware header itself is covered by
     * {@link #storeOverviewNamesTheRegisteredLabelInTheDigestAndHtmlHeader()}.
     */
    private static final FindProject NO_LABELS = id -> Optional.empty();

    /**
     * The HTML report's model sections come from the bounded contexts' read in-ports, not from
     * the snapshot. This test is about the generic read path and the write resilience around it,
     * so the in-ports are stubbed to the very objects the repositories above were seeded with -
     * and, like the real services, they answer per project, so a report for a different
     * project stays empty.
     */
    private ModelViews modelViews() {
        return new ModelViews(
                projectId -> PROJECT.equals(projectId) ? List.of(term1) : List.of(),
                new UseCaseCards(projectId -> List.of(), (projectId, ids) -> List.of()),
                new RequirementCards(
                        projectId -> PROJECT.equals(projectId) ? List.of(fr1) : List.of()),
                new BoundedContextCards(projectId -> List.of()),
                new AdrCards(projectId -> List.of(), (projectId, ids) -> List.of(), (projectId, ids) -> List.of()));
    }

    @AfterEach
    void tearDown() {
        lifecycle.close(new DatasetId(PROJECT.value()));
    }

    @Test
    void storeOverviewDigestSpansBothBoundedContextsAndWritesHtml() throws Exception {
        String result = tools.storeOverview(null, ANCHOR);

        // Digest is generic and contains resources from both BCs. Both identities are opaque
        // IRIs, unbound to any CURIE prefix, so the
        // digest handle falls back to their dcterms:identifier ("FR-1" / "TERM-1") instead of
        // the raw IRI.
        assertThat(result).contains("# Project sample-project");
        assertThat(result).doesNotContain(FR_1_IRI);
        assertThat(result).doesNotContain(TERM_1_IRI);
        assertThat(result).contains("FR-1").contains("-> resource_get(\"FR-1\")");
        assertThat(result).contains("TERM-1").contains("-> resource_get(\"TERM-1\")");
        assertThat(result).contains("# HTML report: ");
        assertThat(result).contains("no dangling references");

        // HTML side effect: a self-contained file with no external dependencies, under a
        // project-scoped subdirectory of the fallback dir.
        Path html = reportDir.resolve(PROJECT.value()).resolve("store-report.html");
        assertThat(html).exists();
        String content = Files.readString(html);
        assertThat(content).contains("<!doctype html>").contains("arknet Store Report");
        assertThat(content).doesNotContain("http://cdn").doesNotContain("<script src");
        // The HTML is grouped by bounded context, not by rdf:type, and the requirement's status
        // renders as a pill.
        assertThat(content).contains("id=\"sec-requirements\"").contains("id=\"sec-glossary\"");
        assertThat(content).contains("class=\"pill status v-proposed\">Proposed<");
    }

    /**
     * Under ADR-016 the report is written to the server's own report directory
     * and never to anything the client sent, so a daemon that shares no filesystem with its caller
     * (containerized, only {@code /data/rdf} mounted) cannot fail the tool call over it.
     *
     * <p>The anchor here is deliberately a path that exists but cannot hold a directory. Under the
     * previous behaviour the header named the write target, so this was the case that had to fall
     * back; now it must never be treated as a path at all - it is a lookup key that happens to look
     * like one. Passing it through the transport context (rather than as a parameter) is what makes
     * this the header path specifically.</p>
     */
    @Test
    void storeOverviewWritesToTheServerReportDirEvenWhenTheAnchorLooksLikeAnUnwritablePath(
            @TempDir final Path root) throws Exception {
        final Path anchorThatIsAFile = root.resolve("not-a-directory");
        Files.writeString(anchorThatIsAFile, "a plain file blocking directory creation");

        final McpTransportContext transportContext = McpTransportContext.create(
                Map.of(ProjectResolver.ANCHOR_KEY, ANCHOR));
        final McpSyncRequestContext context = mock(McpSyncRequestContext.class);
        when(context.transportContext()).thenReturn(transportContext);

        final String result = tools.storeOverview(context, null);

        assertThat(result).contains("# Project sample-project").doesNotContain("FAILED");
        final Path serverHtml = reportDir.resolve(PROJECT.value()).resolve("store-report.html");
        assertThat(serverHtml).exists();
        assertThat(result).contains("# HTML report: " + serverHtml.toAbsolutePath());
        assertThat(anchorThatIsAFile).isRegularFile();
    }

    /**
     * The header path end to end: an anchor arriving in the transport context routes the call,
     * with no explicit parameter involved. Its counterpart - an anchor nobody registered - fails
     * rather than falling back (ADR-016 decision 3).
     */
    @Test
    void storeOverviewRoutesByTheTransportAnchorAndRejectsAnUnregisteredOne() {
        final McpSyncRequestContext context = mock(McpSyncRequestContext.class);
        when(context.transportContext())
                .thenReturn(McpTransportContext.create(Map.of(ProjectResolver.ANCHOR_KEY, ANCHOR)));

        assertThat(tools.storeOverview(context, null)).contains("# Project sample-project").contains("FR-1");

        final McpSyncRequestContext strangerContext = mock(McpSyncRequestContext.class);
        when(strangerContext.transportContext()).thenReturn(
                McpTransportContext.create(Map.of(ProjectResolver.ANCHOR_KEY, "/somewhere/else/sample-project")));

        assertThatThrownBy(() -> tools.storeOverview(strangerContext, null))
                .as("an identically named directory elsewhere is a different, unregistered anchor")
                .isInstanceOf(UnresolvedProjectAnchorException.class);
    }

    /**
     * The other half: when even the fallback report dir is unwritable, {@code
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
        final ProjectResolver projects = StoreReportToolsTest::resolveTestAnchor;
        final StoreReportTools toolsWithBrokenFallback = new StoreReportTools(
                reader, prefixes, new HtmlReportRenderer(prefixes), modelViews(), projects, NO_LABELS,
                blockedFallbackDir, null);

        final String result = toolsWithBrokenFallback.storeOverview(null, ANCHOR);

        assertThat(result).contains("# Project sample-project").contains("FR-1");
        assertThat(result)
                .contains("# HTML report: FAILED to write to " + blockedFallbackDir.resolve(PROJECT.value()));
        assertThat(result).contains("FileSystemException");
    }

    /**
     * A containerized daemon's {@code fallbackReportDir} is a mount point
     * (e.g. {@code /data/report}) the calling agent, running outside the container, cannot
     * reach. When {@code reportHostDir} names that mount's host-side path, the digest must
     * report the host path instead of the path the file was actually written to.
     */
    @Test
    void storeOverviewReportsTheHostMountPathInsteadOfTheContainerInternalWriteTarget(@TempDir final Path hostDir)
            throws Exception {
        final Prefixes prefixes = Prefixes.defaults();
        final StoreReader reader = new StoreReader(lifecycle);
        final ProjectResolver projects = StoreReportToolsTest::resolveTestAnchor;
        final StoreReportTools toolsWithHostDir = new StoreReportTools(
                reader, prefixes, new HtmlReportRenderer(prefixes), modelViews(), projects, NO_LABELS,
                reportDir, hostDir);

        final String result = toolsWithHostDir.storeOverview(null, ANCHOR);

        final Path writtenHtml = reportDir.resolve(PROJECT.value()).resolve("store-report.html");
        assertThat(writtenHtml).exists();
        assertThat(result)
                .contains("# HTML report: " + hostDir.resolve(PROJECT.value()).resolve("store-report.html"))
                .doesNotContain(reportDir.toString());
    }

    /**
     * A project registered with a human-readable label must show it in both the
     * digest and the HTML report header, with the raw id kept alongside rather than replaced.
     */
    @Test
    void storeOverviewNamesTheRegisteredLabelInTheDigestAndHtmlHeader() throws Exception {
        final Prefixes prefixes = Prefixes.defaults();
        final StoreReader reader = new StoreReader(lifecycle);
        final ProjectResolver projects = StoreReportToolsTest::resolveTestAnchor;
        final FindProject withLabel = id -> PROJECT.equals(id)
                ? Optional.of(new Project(PROJECT, "arknet-demo", List.of(new Anchor(ANCHOR, AnchorType.PATH))))
                : Optional.empty();
        final StoreReportTools toolsWithLabel = new StoreReportTools(
                reader, prefixes, new HtmlReportRenderer(prefixes), modelViews(), projects, withLabel,
                reportDir, null);

        final String result = toolsWithLabel.storeOverview(null, ANCHOR);

        assertThat(result).contains("# Project arknet-demo (id: sample-project) --");

        final Path html = reportDir.resolve(PROJECT.value()).resolve("store-report.html");
        assertThat(Files.readString(html)).contains(
                "<span class=\"ws\">project: arknet-demo (id: sample-project)</span>");
    }

    /**
     * Every project served by a shared daemon writes into the very same
     * {@code fallbackReportDir}. Without a project-scoped subdirectory, the second project's
     * report would silently overwrite the first's under the identical file name - and since
     * ADR-016 that directory is the <em>only</em> target, which makes the subdirectory the sole
     * thing keeping the two apart.
     */
    @Test
    void storeOverviewWritesEachProjectToItsOwnSubdirectoryOfTheFallbackDir() throws Exception {
        try {
            tools.storeOverview(null, ANCHOR);
            tools.storeOverview(null, OTHER_ANCHOR);

            final Path ownReport = reportDir.resolve(PROJECT.value()).resolve("store-report.html");
            final Path otherReport = reportDir.resolve(OTHER_PROJECT.value()).resolve("store-report.html");
            assertThat(ownReport).exists();
            assertThat(otherReport).exists();
            assertThat(Files.readString(ownReport)).contains("FR-1");
            assertThat(Files.readString(otherReport)).doesNotContain("FR-1");
        } finally {
            lifecycle.close(new DatasetId(OTHER_PROJECT.value()));
        }
    }

    /**
     * Since requirement identity became an opaque IRI, the {@code req:} CURIE prefix
     * (bound to the old {@code .../model/requirement/} namespace) no longer aliases a freshly
     * added requirement's subject - only the full IRI and the {@code dcterms:identifier}-based
     * bare-id lookup do.
     */
    @Test
    void resourceGetResolvesFullIriAndBareIdToTheSameResource() {
        String viaIri = tools.resourceGet(null, FR_1_IRI, ANCHOR);
        String viaBareId = tools.resourceGet(null, "FR-1", ANCHOR);

        assertThat(viaBareId).isEqualTo(viaIri);
        assertThat(viaIri).contains("dcterms:title").contains("\"Login\"");
        assertThat(viaIri).contains("# Outgoing").contains("# Incoming");
    }

    @Test
    void resourceGetRejectsUnknownPrefixWithDidacticMessage() {
        assertThatThrownBy(() -> tools.resourceGet(null, "nope:X", ANCHOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown prefix")
                .hasMessageContaining("Known prefixes");
    }

    @Test
    void resourceGetRejectsUnknownBareIdWithGuidance() {
        assertThatThrownBy(() -> tools.resourceGet(null, "FR-999", ANCHOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No resource found");
    }

    /**
     * {@code resource_get} used to ignore its project parameter entirely and
     * always read the default project, unlike {@code store_overview} which already honored one.
     * Two projects each carry a requirement with the SAME business code ("FR-1") but different
     * identities/titles - a caller passing the other project's anchor must get that project's
     * resource back, not a silent hit in the one its transport would have selected.
     *
     * <p>Since ADR-016 the parameter carries an anchor rather than a raw project id, which is what
     * keeps this convenience from being a second, unchecked way into any dataset on the machine:
     * {@link #resolveTestAnchor} rejects anything unregistered, as the real registry does.</p>
     */
    @Test
    void resourceGetHonorsExplicitProjectAnchorParameter() {
        String otherFr1Iri = "https://w3id.org/arknet/id/store-report-test-fr-1-other";

        RequirementRepository requirements =
                KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        requirements.create(OTHER_PROJECT, new Requirement(
                new RequirementId(ResourceId.of(otherFr1Iri)), new RequirementCode("FR-1"), "Andere Anmeldung",
                "The system shall authenticate a user in the other project.",
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, null,
                List.of("Login succeeds with valid credentials")));

        try {
            String fromOtherProject = tools.resourceGet(null, "FR-1", OTHER_ANCHOR);
            String fromDefaultProject = tools.resourceGet(null, "FR-1", ANCHOR);

            assertThat(fromOtherProject).contains("dcterms:title").contains("\"Andere Anmeldung\"");
            assertThat(fromOtherProject).doesNotContain("\"Login\"");
            assertThat(fromDefaultProject).contains("dcterms:title").contains("\"Login\"");
            assertThat(fromDefaultProject).doesNotContain("\"Andere Anmeldung\"");
        } finally {
            lifecycle.close(new DatasetId(OTHER_PROJECT.value()));
        }
    }

    /** An anchor no project is registered under is rejected, never quietly resolved. */
    @Test
    void resourceGetRejectsAnUnregisteredAnchor() {
        assertThatThrownBy(() -> tools.resourceGet(null, "FR-1", "/never/registered"))
                .isInstanceOf(UnresolvedProjectAnchorException.class)
                .hasMessageContaining("/never/registered");
    }
}
