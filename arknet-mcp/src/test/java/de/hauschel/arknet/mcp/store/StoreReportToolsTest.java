// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.RDF;
import io.kogn.rdf.terms.SimpleRdf;
import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.DisplayLocale;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.kernel.UuidResourceIdFactory;
import de.hauschel.arknet.mcp.report.ActorCards;
import de.hauschel.arknet.mcp.report.AdrCards;
import de.hauschel.arknet.mcp.report.BoundedContextCards;
import de.hauschel.arknet.mcp.report.ConstraintCards;
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
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.ul.adapter.kogniordf.KognioRdfTermRepositoryFactory;
import de.hauschel.arknet.ul.application.TermService;
import de.hauschel.arknet.ul.application.port.in.AddTerm.NewTerm;
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
                "The system shall authenticate a user.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of());
        term1 = new Term(
                new TermId(ResourceId.of(TERM_1_IRI)), new TermCode("TERM-1"), "Anmeldung",
                "The act of proving one's identity.", null);
        requirements.create(PROJECT, fr1, null);
        terms.create(PROJECT, term1, null);

        Prefixes prefixes = Prefixes.defaults();
        StoreReader reader = new StoreReader(lifecycle);
        // Shared server: the project is resolved per call by looking up the caller's anchor
        //. The stub below stands in for the registry with exactly two registered
        // anchors, so these tests address two projects the way a real client does.
        ProjectResolver projects = StoreReportToolsTest::resolveTestAnchor;
        tools = new StoreReportTools(
                reader, prefixes, DisplayLocale.DEFAULT, new HtmlReportRenderer(prefixes),
                modelViews(), projects, NO_LABELS, reportDir, null);
    }

    /**
     * Stands in for the project registry: two registered anchors, and a hard failure for anything
     * else. Rejecting the unknown case rather than defaulting is the point - a stub that answered
     * every anchor would let a test pass that a real deployment fails.
     */
    private static ResolvedProject resolveTestAnchor(final String anchor) {
        if (ANCHOR.equals(anchor)) {
            return new ResolvedProject(PROJECT, null);
        }
        if (OTHER_ANCHOR.equals(anchor)) {
            return new ResolvedProject(OTHER_PROJECT, null);
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
                (projectId, displayLocale) -> PROJECT.equals(projectId) ? List.of(term1) : List.of(),
                new UseCaseCards((projectId, displayLocale) -> List.of(), (projectId, ids) -> List.of()),
                new RequirementCards(
                        (projectId, displayLocale) -> PROJECT.equals(projectId) ? List.of(fr1) : List.of()),
                new ConstraintCards((projectId, displayLocale) -> List.of()),
                new BoundedContextCards(projectId -> List.of()),
                new AdrCards((projectId, displayLocale) -> List.of(), (projectId, ids) -> List.of(), (projectId, ids) -> List.of()),
                new ActorCards(projectId -> List.of()));
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
        Path html = reportDir.resolve(StoreReportTools.reportSegment(PROJECT)).resolve("store-report.html");
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
     * The report is written to the server's own report directory
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
        final Path serverHtml = reportDir.resolve(StoreReportTools.reportSegment(PROJECT)).resolve("store-report.html");
        assertThat(serverHtml).exists();
        assertThat(result).contains("# HTML report: " + serverHtml.toAbsolutePath());
        assertThat(anchorThatIsAFile).isRegularFile();
    }

    /**
     * The header path end to end: an anchor arriving in the transport context routes the call,
     * with no explicit parameter involved. Its counterpart - an anchor nobody registered - fails
     * rather than falling back.
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
                reader, prefixes, DisplayLocale.DEFAULT, new HtmlReportRenderer(prefixes),
                modelViews(), projects, NO_LABELS, blockedFallbackDir, null);

        final String result = toolsWithBrokenFallback.storeOverview(null, ANCHOR);

        assertThat(result).contains("# Project sample-project").contains("FR-1");
        assertThat(result)
                .contains("# HTML report: FAILED to write to "
                        + blockedFallbackDir.resolve(StoreReportTools.reportSegment(PROJECT)));
        assertThat(result).contains("FileSystemException");
    }

    /**
     * Regression test for issue #146: {@code ProjectId} is, by its own javadoc, "deliberately
     * unconstrained beyond non-blankness" - a value carrying a filesystem-unsafe character (as a
     * client's {@code project_adopt} datasetId string could produce) must not reach {@link
     * java.nio.file.Path#resolve} unfiltered. The report still lands under a project-scoped
     * subdirectory of {@link #reportDir}, just a sanitized one, and the call must not throw.
     */
    @Test
    void storeOverviewSanitizesAProjectIdWithFilesystemUnsafeCharactersIntoTheReportSubdirectory()
            throws Exception {
        final ProjectId unsafeProject = new ProjectId("team/main");
        final String unsafeAnchor = "/wherever/team-main";
        try {
            final Prefixes prefixes = Prefixes.defaults();
            final StoreReader reader = new StoreReader(lifecycle);
            final ProjectResolver projects = anchor ->
                    unsafeAnchor.equals(anchor) ? new ResolvedProject(unsafeProject, null) : resolveTestAnchor(anchor);
            final StoreReportTools toolsWithUnsafeId = new StoreReportTools(
                    reader, prefixes, DisplayLocale.DEFAULT, new HtmlReportRenderer(prefixes),
                    modelViews(), projects, NO_LABELS, reportDir, null);

            final String result = toolsWithUnsafeId.storeOverview(null, unsafeAnchor);

            assertThat(result).doesNotContain("FAILED");
            final Path html = reportDir.resolve(StoreReportTools.reportSegment(unsafeProject))
                    .resolve("store-report.html");
            assertThat(html).exists();
            assertThat(result).contains("# HTML report: " + html.toAbsolutePath());
        } finally {
            lifecycle.close(new DatasetId(unsafeProject.value()));
        }
    }

    /**
     * Regression test for issue #146, updated for the #147 review follow-up: a project id that
     * would have sanitized to exactly {@code ".."} under {@link FileNameSanitizer#sanitize} alone
     * - the one input that function cannot rule out, since {@code .} and {@code -} are themselves
     * filesystem-safe characters - can no longer do so now that {@link StoreReportTools#reportSegment}
     * appends {@link FileNameSanitizer#uniqueSegment}'s hash suffix: the segment always carries
     * extra characters after the sanitized prefix, so it can never literally equal {@code ".."}
     * again. The call succeeds like any other project id, rather than needing the {@code
     * normalize()}/containment check in {@code fallbackDirFor} to catch an escape attempt - that
     * check remains in place as defense in depth, but this scenario no longer reaches it.
     */
    @Test
    void storeOverviewNeverEscapesTheReportDirectoryForAProjectIdThatWouldSanitizeToDotDot() {
        final ProjectId escapingProject = new ProjectId("..");
        final String escapingAnchor = "/wherever/escaping";
        try {
            final Prefixes prefixes = Prefixes.defaults();
            final StoreReader reader = new StoreReader(lifecycle);
            final ProjectResolver projects = anchor ->
                    escapingAnchor.equals(anchor) ? new ResolvedProject(escapingProject, null) : resolveTestAnchor(anchor);
            final StoreReportTools toolsWithEscapingId = new StoreReportTools(
                    reader, prefixes, DisplayLocale.DEFAULT, new HtmlReportRenderer(prefixes),
                    modelViews(), projects, NO_LABELS, reportDir, null);

            final String result = toolsWithEscapingId.storeOverview(null, escapingAnchor);

            assertThat(result).doesNotContain("FAILED");
            final Path html = reportDir.resolve(StoreReportTools.reportSegment(escapingProject))
                    .resolve("store-report.html");
            assertThat(html).exists();
            assertThat(html.startsWith(reportDir)).isTrue();
        } finally {
            lifecycle.close(new DatasetId(escapingProject.value()));
        }
    }

    /**
     * Regression test for the #147 review follow-up (P1): {@link FileNameSanitizer#sanitize}
     * alone is not injective - {@code "team/main"} and {@code "team_main"} both collapse onto the
     * identical {@code "team_main"} segment. Two projects registered under exactly these ids must
     * still land under two DIFFERENT report subdirectories rather than silently sharing (and
     * overwriting) one - the isolation {@link StoreReportTools#reportSegment}'s hash suffix
     * ({@link FileNameSanitizer#uniqueSegment}) exists to guarantee.
     */
    @Test
    void storeOverviewWritesDistinctReportsForProjectIdsThatCollideAfterPlainSanitizing() throws Exception {
        final ProjectId slashProject = new ProjectId("team/main");
        final ProjectId underscoreProject = new ProjectId("team_main");
        final String slashAnchor = "/wherever/team-slash-main";
        final String underscoreAnchor = "/wherever/team-underscore-main";
        try {
            final RequirementRepository requirements =
                    KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
            requirements.create(underscoreProject, new Requirement(
                    new RequirementId(ResourceId.of("https://w3id.org/arknet/id/store-report-test-fr-collide")),
                    new RequirementCode("FR-1"), "Zweitprojekt",
                    "The system shall authenticate a second user.", null,
                    RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                    List.of(new AcceptanceCriterion(1, "Zweitprojekt succeeds")), List.of()), null);

            final Prefixes prefixes = Prefixes.defaults();
            final StoreReader reader = new StoreReader(lifecycle);
            final ProjectResolver projects = anchor -> {
                if (slashAnchor.equals(anchor)) {
                    return new ResolvedProject(slashProject, null);
                }
                if (underscoreAnchor.equals(anchor)) {
                    return new ResolvedProject(underscoreProject, null);
                }
                return resolveTestAnchor(anchor);
            };
            final StoreReportTools toolsWithCollidingIds = new StoreReportTools(
                    reader, prefixes, DisplayLocale.DEFAULT, new HtmlReportRenderer(prefixes),
                    modelViews(), projects, NO_LABELS, reportDir, null);

            toolsWithCollidingIds.storeOverview(null, slashAnchor);
            toolsWithCollidingIds.storeOverview(null, underscoreAnchor);

            final String slashSegment = StoreReportTools.reportSegment(slashProject);
            final String underscoreSegment = StoreReportTools.reportSegment(underscoreProject);
            assertThat(slashSegment).isNotEqualTo(underscoreSegment);
            final Path slashReport = reportDir.resolve(slashSegment).resolve("store-report.html");
            final Path underscoreReport = reportDir.resolve(underscoreSegment).resolve("store-report.html");
            assertThat(slashReport).exists();
            assertThat(underscoreReport).exists();
            assertThat(Files.readString(slashReport)).doesNotContain("Zweitprojekt");
        } finally {
            lifecycle.close(new DatasetId(underscoreProject.value()));
        }
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
                reader, prefixes, DisplayLocale.DEFAULT, new HtmlReportRenderer(prefixes),
                modelViews(), projects, NO_LABELS, reportDir, hostDir);

        final String result = toolsWithHostDir.storeOverview(null, ANCHOR);

        final String segment = StoreReportTools.reportSegment(PROJECT);
        final Path writtenHtml = reportDir.resolve(segment).resolve("store-report.html");
        assertThat(writtenHtml).exists();
        assertThat(result)
                .contains("# HTML report: " + hostDir.resolve(segment).resolve("store-report.html"))
                .doesNotContain(reportDir.toString());
    }

    /**
     * Regression test for issue #305 part 3: two overlapping {@code store_overview} calls for the
     * SAME project - an ordinary occurrence under the shared daemon, several sessions of one project
     * against it - used to both write straight onto {@code store-report.html} via {@code
     * Files.writeString}, risking a reader observing a truncated or interleaved file mid-write.
     * Each call now writes through its own uniquely named temp file and only the final {@link
     * Files#move} touches the report's real name, so every writer that finishes leaves a whole,
     * valid document behind and no temp file lingers once every writer is done.
     */
    @Test
    void storeOverviewNeverLeavesATruncatedReportUnderConcurrentCalls() throws Exception {
        final int callers = 8;
        final ExecutorService pool = Executors.newFixedThreadPool(callers);
        try {
            final CountDownLatch ready = new CountDownLatch(callers);
            final CountDownLatch go = new CountDownLatch(1);
            final List<Future<String>> futures = new ArrayList<>();
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return tools.storeOverview(null, ANCHOR);
                }));
            }
            ready.await();
            go.countDown();
            for (final Future<String> future : futures) {
                assertThat(future.get()).doesNotContain("FAILED");
            }
        } finally {
            pool.shutdown();
        }

        final Path targetDir = reportDir.resolve(StoreReportTools.reportSegment(PROJECT));
        final Path html = targetDir.resolve("store-report.html");
        assertThat(html).exists();
        final String content = Files.readString(html);
        assertThat(content).startsWith("<!doctype html>").endsWith("</html>\n");
        try (Stream<Path> files = Files.list(targetDir)) {
            assertThat(files.map(Path::getFileName).map(Path::toString))
                    .as("no leftover temp file once every concurrent writer finished")
                    .noneMatch(name -> name.endsWith(".tmp"));
        }
    }

    /**
     * {@code htmlReport} backs the browser-reachable {@code GET /report} endpoint
     * ({@code StoreReportController}, issue #391) and, per its own javadoc, exists specifically
     * to render "without writing it anywhere" - a GET a person triggers by navigating to a URL
     * must not have the side effect of a file write. Pins both halves of that promise: the return
     * value is exactly the HTML document - unlike {@code storeOverview}, which wraps the very same
     * HTML's digest between the text digest and the "# HTML report: ..." write-result line - and
     * no file ever lands under the project's report subdirectory. The rendered HTML legitimately
     * embeds a copy of the digest in its own agent panel either way (see
     * {@code HtmlReportRenderer}), so that text appearing inside {@code html} is expected, not a
     * sign of leakage.
     */
    @Test
    void htmlReportRendersOnlyTheHtmlAndNeverWritesAFile() throws Exception {
        final String html = tools.htmlReport(ANCHOR);

        assertThat(html).startsWith("<!doctype html>").contains("arknet Store Report").endsWith("</html>\n");
        assertThat(html).doesNotContain("# HTML report:");
        final Path segmentDir = reportDir.resolve(StoreReportTools.reportSegment(PROJECT));
        assertThat(segmentDir).doesNotExist();
    }

    /** An anchor no project is registered under is rejected here too, never quietly resolved. */
    @Test
    void htmlReportRejectsAnUnregisteredAnchor() {
        assertThatThrownBy(() -> tools.htmlReport("/never/registered"))
                .isInstanceOf(UnresolvedProjectAnchorException.class)
                .hasMessageContaining("/never/registered");
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
                reader, prefixes, DisplayLocale.DEFAULT, new HtmlReportRenderer(prefixes),
                modelViews(), projects, withLabel, reportDir, null);

        final String result = toolsWithLabel.storeOverview(null, ANCHOR);

        assertThat(result).contains("# Project arknet-demo (id: sample-project) --");

        final Path html = reportDir.resolve(StoreReportTools.reportSegment(PROJECT)).resolve("store-report.html");
        assertThat(Files.readString(html)).contains(
                "<span class=\"ws\">project: arknet-demo (id: sample-project)</span>");
    }

    /**
     * Regression test for issue #276: {@code store_overview}'s glossary section (fed by
     * {@code ListTerms} through {@link ModelViews}) must honour a project's own configured
     * default language the same way {@code orphan_check}/{@code trace_matrix} already do (issue
     * #274), rather than always this daemon's process-wide {@link DisplayLocale} bean (english,
     * unless configured - see {@code ArknetMcpConfiguration#displayLocale}). The seeded term
     * carries both a german and an english {@code skos:prefLabel}; a project configured with
     * {@code defaultLanguage} {@code "de"} must see the german one, even though every collaborator
     * below is otherwise built against {@link DisplayLocale#DEFAULT} (english).
     */
    @Test
    void storeOverviewGlossaryHonorsTheProjectsOwnDefaultLanguageNotTheDaemons() throws Exception {
        final TermRepository termRepository = KognioRdfTermRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        final TermService termService = new TermService(termRepository, new UuidResourceIdFactory());
        final Term deTerm = termService.add(PROJECT,
                new NewTerm("Anmeldung", "Der Nachweis der eigenen Identitaet.", null, null), "de");
        termService.update(PROJECT, deTerm.code(), "Login", "The act of proving one's identity.",
                "en", "de", null, null);

        final Prefixes prefixes = Prefixes.defaults();
        final StoreReader reader = new StoreReader(lifecycle);
        final ProjectResolver germanDefaultProject = anchor ->
                ANCHOR.equals(anchor) ? new ResolvedProject(PROJECT, "de") : resolveTestAnchor(anchor);
        final ModelViews modelViewsWithRealTerms = new ModelViews(
                termService,
                new UseCaseCards((projectId, displayLocale) -> List.of(), (projectId, ids) -> List.of()),
                new RequirementCards((projectId, displayLocale) -> List.of()),
                new ConstraintCards((projectId, displayLocale) -> List.of()),
                new BoundedContextCards(projectId -> List.of()),
                new AdrCards((projectId, displayLocale) -> List.of(), (projectId, ids) -> List.of(), (projectId, ids) -> List.of()),
                new ActorCards(projectId -> List.of()));
        final StoreReportTools toolsWithGermanDefault = new StoreReportTools(
                reader, prefixes, DisplayLocale.DEFAULT, new HtmlReportRenderer(prefixes),
                modelViewsWithRealTerms, germanDefaultProject, NO_LABELS, reportDir, null);

        final String result = toolsWithGermanDefault.storeOverview(null, ANCHOR);

        assertThat(result).doesNotContain("FAILED");
        final Path html = reportDir.resolve(StoreReportTools.reportSegment(PROJECT)).resolve("store-report.html");
        // Both languages ship for the client-side switch (issue #270); the active one - de, the
        // project's own default, not this daemon's english - is the one shown, not hidden.
        assertThat(Files.readString(html))
                .contains("<span class=\"lang-group\" data-default-lang=\"de\">")
                .contains("<span class=\"lang-variant\" data-lang=\"de\">Anmeldung</span>")
                .contains("<span class=\"lang-variant\" data-lang=\"en\" hidden>Login</span>");
    }

    /**
     * Every project served by a shared daemon writes into the very same
     * {@code fallbackReportDir}. Without a project-scoped subdirectory, the second project's
     * report would silently overwrite the first's under the identical file name - that directory
     * is the <em>only</em> target, which makes the subdirectory the sole
     * thing keeping the two apart.
     */
    @Test
    void storeOverviewWritesEachProjectToItsOwnSubdirectoryOfTheFallbackDir() throws Exception {
        try {
            tools.storeOverview(null, ANCHOR);
            tools.storeOverview(null, OTHER_ANCHOR);

            final Path ownReport = reportDir.resolve(StoreReportTools.reportSegment(PROJECT))
                    .resolve("store-report.html");
            final Path otherReport = reportDir.resolve(StoreReportTools.reportSegment(OTHER_PROJECT))
                    .resolve("store-report.html");
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

    /**
     * "1nope" cannot be a syntactically valid RFC 3986 URI scheme (a scheme must start with a
     * letter, not a digit), so this stays rejected as an unknown CURIE prefix. A scheme-shaped
     * unknown prefix such as {@code urn} is a different case since issue #305 - see
     * {@link #resourceGetResolvesAUrnHandleEvenThoughItIsNeverAKnownPrefix()}.
     */
    @Test
    void resourceGetRejectsUnknownPrefixWithDidacticMessage() {
        assertThatThrownBy(() -> tools.resourceGet(null, "1nope:X", ANCHOR))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown prefix")
                .hasMessageContaining("Known prefixes");
    }

    /**
     * Regression test for issue #305: a {@code urn:}/{@code mailto:}-style handle is a complete,
     * self-authoritative IRI - its "prefix" is an RFC 3986 URI scheme, not a CURIE prefix - and
     * must not be rejected as an unknown prefix. Nothing in the store carries this IRI, so
     * {@code resourceGet} does not throw at all - it resolves the handle and then reports the
     * ordinary not-found notice ({@link ResourceRenderer#notFoundMessage}), the same outcome a
     * well-formed but absent full IRI already gets.
     */
    @Test
    void resourceGetResolvesAUrnHandleEvenThoughItIsNeverAKnownPrefix() {
        String urn = "urn:uuid:6ba7b810-9dad-11d1-80b4-00c04fd430c8";
        assertThat(tools.resourceGet(null, urn, ANCHOR))
                .isEqualTo(ResourceRenderer.notFoundMessage(Prefixes.defaults(), urn));
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
     * <p>The parameter carries an anchor rather than a raw project id, which is what
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
                "The system shall authenticate a user in the other project.", null,
                RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null,
                List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials")), List.of()), null);

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

    /**
     * Happy path for issue #251: {@code setUp}'s create is the only write {@code fr1} has gone
     * through so far, so its history is exactly one revision, and it is the current one.
     */
    @Test
    void resourceHistoryShowsExactlyOneCurrentRevisionForAFreshlyCreatedResource() {
        String result = tools.resourceHistory(null, "FR-1", ANCHOR);

        assertThat(result).contains("# History (1)").contains("(current)");
    }

    /**
     * A further write through the funnel must extend the history and move the current marker to
     * the new revision, without losing the first one.
     */
    @Test
    void resourceHistoryReflectsAFurtherWriteThroughTheFunnelAndMovesTheCurrentMarker() {
        RequirementRepository requirements = KognioRdfRequirementRepositoryFactory.over(lifecycle, DisplayLocale.DEFAULT);
        RequirementRepository.CurrentRequirement current =
                requirements.findCurrentByCode(PROJECT, fr1.code(), null).orElseThrow();
        Requirement updated = new Requirement(
                fr1.id(), fr1.code(), "Login v2", fr1.description(), null, fr1.type(), fr1.status(), fr1.priority(), null, null, fr1.acceptanceCriteria(), List.of());
        requirements.compareAndUpdate(
                PROJECT, current.head(), updated, null, null, null, noAcceptanceCriteriaLanguages(updated), null);

        String result = tools.resourceHistory(null, "FR-1", ANCHOR);

        assertThat(result).contains("# History (2)");
        assertThat(result.lines().filter(line -> line.contains("(current)")).count()).isEqualTo(1);
    }

    /**
     * A resource that exists but was written entirely store-first - straight into a
     * model graph, bypassing every repository and therefore the funnel - has no revision to
     * report: an empty, non-error history, not a "not found".
     */
    @Test
    void resourceHistoryReportsNoRevisionsForAnExistingResourceWrittenOnlyStoreFirst() {
        String storeFirstIri = "https://w3id.org/arknet/id/store-report-test-store-first-1";
        seedStoreFirstResource(storeFirstIri);

        String result = tools.resourceHistory(null, storeFirstIri, ANCHOR);

        assertThat(result).contains("# History (0)");
        assertThat(result).doesNotContain("not found");
    }

    /** Writes a single triple straight into a model graph, touching no repository or funnel. */
    private void seedStoreFirstResource(String iri) {
        RDF rdf = new SimpleRdf();
        Graph graph = rdf.createGraph();
        graph.add(rdf.createIRI(iri), rdf.createIRI("http://purl.org/dc/terms/title"),
                rdf.createLiteral("Store-first resource"));
        try (DatasetHandle handle = lifecycle.acquire(new DatasetId(PROJECT.value()))) {
            handle.transactor().inTransaction(tx -> {
                tx.add(rdf.createIRI("https://w3id.org/arknet/id/store-report-test-store-first-graph"), graph);
                return null;
            });
        }
    }

    /** A syntactically valid but unknown IRI is reported identically to {@code resource_get}. */
    @Test
    void resourceHistoryReportsNotFoundForAWellFormedButUnknownIri() {
        String unknownIri = "https://w3id.org/arknet/id/store-report-test-does-not-exist";

        String result = tools.resourceHistory(null, unknownIri, ANCHOR);

        assertThat(result).isEqualTo(ResourceRenderer.notFoundMessage(Prefixes.defaults(), unknownIri));
    }

    /** An anchor no project is registered under is rejected, never quietly resolved. */
    @Test
    void resourceHistoryRejectsAnUnregisteredAnchor() {
        assertThatThrownBy(() -> tools.resourceHistory(null, "FR-1", "/never/registered"))
                .isInstanceOf(UnresolvedProjectAnchorException.class)
                .hasMessageContaining("/never/registered");
    }

    /** An untagged (all-{@code null}) map, covering every position {@code updated} carries. */
    private static Map<Integer, String> noAcceptanceCriteriaLanguages(Requirement updated) {
        Map<Integer, String> languages = new LinkedHashMap<>();
        updated.acceptanceCriteria().forEach(criterion -> languages.put(criterion.position(), null));
        return languages;
    }
}
