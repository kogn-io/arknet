// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.kernel.WorkspaceResolver;
import de.hauschel.arknet.prj.application.port.in.AttachAnchor;
import de.hauschel.arknet.prj.application.port.in.ListProjects;
import de.hauschel.arknet.prj.application.port.in.RegisterProject;
import de.hauschel.arknet.prj.application.port.in.RenameProject;
import de.hauschel.arknet.prj.application.port.in.ResolveProject;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.ProjectId;
import de.hauschel.arknet.prj.domain.UnknownAnchorException;

/**
 * Scaffold-level check that the adapter declares exactly the four project tools, resolves the
 * caller's own project from its transport-context anchor rather than from any derived {@link
 * WorkspaceResolver} workspace, and never turns an unknown-anchor situation into a silent default
 * (ADR-016 decision 3).
 */
class ProjectMcpToolsTest {

    private final FakeRegisterProject registerProject = new FakeRegisterProject();
    private final FakeAttachAnchor attachAnchor = new FakeAttachAnchor();
    private final FakeRenameProject renameProject = new FakeRenameProject();
    private final FakeListProjects listProjects = new FakeListProjects();
    private final FakeResolveProject resolveProject = new FakeResolveProject();
    private final ProjectMcpTools adapter =
            new ProjectMcpTools(registerProject, attachAnchor, renameProject, listProjects, resolveProject);

    @Test
    void declaresTheFourProjectTools() {
        final List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(4, names.size());
        assertTrue(names.containsAll(
                List.of("project_add", "project_attach_anchor", "project_rename", "project_list")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new ProjectMcpTools(null, attachAnchor, renameProject, listProjects, resolveProject));
        assertThrows(NullPointerException.class,
                () -> new ProjectMcpTools(registerProject, null, renameProject, listProjects, resolveProject));
        assertThrows(NullPointerException.class,
                () -> new ProjectMcpTools(registerProject, attachAnchor, null, listProjects, resolveProject));
        assertThrows(NullPointerException.class,
                () -> new ProjectMcpTools(registerProject, attachAnchor, renameProject, null, resolveProject));
        assertThrows(NullPointerException.class,
                () -> new ProjectMcpTools(registerProject, attachAnchor, renameProject, listProjects, null));
    }

    @Test
    void addPassesLabelAndTheContextAnchorAsPathThrough() {
        final String rendered = adapter.add(contextWithOrigin("/home/f/DEV/arknet"), "arknet", null, null);

        assertEquals("arknet", registerProject.lastLabel);
        assertEquals(new Anchor("/home/f/DEV/arknet", AnchorType.PATH), registerProject.lastAnchor);
        assertTrue(rendered.contains("arknet"), rendered);
    }

    @Test
    void addWithAnExplicitAnchorParameterUsesItInsteadOfTheContextAnchor() {
        adapter.add(contextWithOrigin("/home/f/DEV/arknet"), "arknet", "https://example.org/arknet", "url");

        assertEquals(new Anchor("https://example.org/arknet", AnchorType.URL), registerProject.lastAnchor);
    }

    @Test
    void addWithoutAContextAnchorAndWithoutAnExplicitAnchorParameterFailsInsteadOfDefaulting() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "arknet", null, null));

        assertTrue(ex.getMessage().contains("project_add"), ex.getMessage());
    }

    @Test
    void attachAnchorResolvesTheTargetProjectFromTheContextAnchorAndAttachesTheNewOne() {
        final Anchor callerAnchor = new Anchor("/home/f/DEV/arknet", AnchorType.PATH);
        final Project target = new Project(new ProjectId("p-1"), "arknet", List.of(callerAnchor));
        resolveProject.register(callerAnchor, target);
        attachAnchor.result = new Project(target.id(), target.label(),
                List.of(callerAnchor, new Anchor("/home/f/DEV/arknet-wt", AnchorType.PATH)));

        final String rendered =
                adapter.attachAnchor(contextWithOrigin("/home/f/DEV/arknet"), "/home/f/DEV/arknet-wt", "path");

        assertEquals(target.id(), attachAnchor.lastProjectId);
        assertEquals(new Anchor("/home/f/DEV/arknet-wt", AnchorType.PATH), attachAnchor.lastAnchor);
        assertTrue(rendered.contains("arknet-wt"), rendered);
    }

    @Test
    void renameOnlyChangesTheLabelOfTheProjectResolvedFromTheContextAnchor() {
        final Anchor callerAnchor = new Anchor("/home/f/DEV/arknet", AnchorType.PATH);
        final Project target = new Project(new ProjectId("p-1"), "arknet", List.of(callerAnchor));
        resolveProject.register(callerAnchor, target);
        renameProject.result = new Project(target.id(), "arknet-renamed", target.anchors());

        final String rendered = adapter.rename(contextWithOrigin("/home/f/DEV/arknet"), "arknet-renamed");

        assertEquals(target.id(), renameProject.lastProjectId);
        assertEquals("arknet-renamed", renameProject.lastLabel);
        assertTrue(rendered.contains("arknet-renamed"), rendered);
    }

    @Test
    void listRendersEveryProjectWithAllItsAnchorsAndItsOpaqueId() {
        listProjects.all = List.of(
                new Project(new ProjectId("id-1"), "arknet",
                        List.of(new Anchor("/home/f/DEV/arknet", AnchorType.PATH),
                                new Anchor("/home/f/DEV/arknet-wt", AnchorType.PATH))),
                new Project(new ProjectId("id-2"), "noistill",
                        List.of(new Anchor("/home/f/DEV/noistill", AnchorType.PATH))));

        final String rendered = adapter.list();

        assertTrue(rendered.contains("arknet [path:/home/f/DEV/arknet, path:/home/f/DEV/arknet-wt] (id: id-1)"),
                rendered);
        assertTrue(rendered.contains("noistill [path:/home/f/DEV/noistill] (id: id-2)"), rendered);
    }

    @Test
    void listOfNoProjectsRendersAPlaceholder() {
        assertEquals("(no projects)", adapter.list());
    }

    @Test
    void unknownAnchorTypeFailsWithAMessageNamingTheThreeAllowedValues() {
        final IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "arknet", "some-anchor", "nonsense"));

        assertTrue(ex.getMessage().contains("path"), ex.getMessage());
        assertTrue(ex.getMessage().contains("url"), ex.getMessage());
        assertTrue(ex.getMessage().contains("uuid"), ex.getMessage());
    }

    @Test
    void unknownAnchorExceptionFromResolveProjectIsPropagatedNotSwallowedIntoAReturnString() {
        assertThrows(UnknownAnchorException.class,
                () -> adapter.rename(contextWithOrigin("/home/f/DEV/unregistered"), "new-label"));
    }

    private static McpSyncRequestContext contextWithOrigin(final String originDir) {
        return contextWithOriginDir(originDir);
    }

    /** Structural fake implementing {@link RegisterProject}. */
    private static final class FakeRegisterProject implements RegisterProject {
        private String lastLabel;
        private Anchor lastAnchor;

        @Override
        public Project register(final String label, final Anchor anchor) {
            lastLabel = label;
            lastAnchor = anchor;
            return new Project(new ProjectId("test-project"), label, List.of(anchor));
        }
    }

    /** Structural fake implementing {@link AttachAnchor}. */
    private static final class FakeAttachAnchor implements AttachAnchor {
        private ProjectId lastProjectId;
        private Anchor lastAnchor;
        private Project result;

        @Override
        public Project attach(final ProjectId projectId, final Anchor anchor) {
            lastProjectId = projectId;
            lastAnchor = anchor;
            return result;
        }
    }

    /** Structural fake implementing {@link RenameProject}. */
    private static final class FakeRenameProject implements RenameProject {
        private ProjectId lastProjectId;
        private String lastLabel;
        private Project result;

        @Override
        public Project rename(final ProjectId projectId, final String newLabel) {
            lastProjectId = projectId;
            lastLabel = newLabel;
            return result;
        }
    }

    /** Structural fake implementing {@link ListProjects}. */
    private static final class FakeListProjects implements ListProjects {
        private List<Project> all = List.of();

        @Override
        public List<Project> list() {
            return all;
        }
    }

    /**
     * Fake {@link ResolveProject}: resolves only what {@link #register} registered and, like the
     * real port, throws {@link UnknownAnchorException} rather than returning an empty result for
     * an anchor it does not know.
     */
    private static final class FakeResolveProject implements ResolveProject {
        private final Map<Anchor, Project> known = new HashMap<>();

        void register(final Anchor anchor, final Project project) {
            known.put(anchor, project);
        }

        @Override
        public Project resolve(final Anchor anchor) {
            final Project found = known.get(anchor);
            if (found == null) {
                throw new UnknownAnchorException(anchor);
            }
            return found;
        }
    }

    /**
     * Builds an {@link McpSyncRequestContext} carrying a fixed origin directory under
     * {@link WorkspaceResolver#WORKSPACE_DIR_KEY} - the single thing this adapter reads out of
     * the framework context.
     *
     * <p>A dynamic proxy rather than a hand-written implementation of the interface. That
     * interface declares some thirty methods (sampling, elicitation, roots, progress, logging),
     * none of which this adapter calls; spelling them all out would produce a test file that
     * breaks whenever Spring AI adds a capability - a compile error carrying no information about
     * this adapter. The proxy stays silent on interface growth and still fails loudly, naming the
     * method, should a test ever exercise a capability beyond the transport context. The
     * framework's own builder is not an alternative here: it takes a live
     * {@code McpSyncServerExchange}, which is harder to stand up than the interface itself.</p>
     */
    private static McpSyncRequestContext contextWithOriginDir(final String originDir) {
        final McpTransportContext transport = originDir == null
                ? McpTransportContext.create(Map.of())
                : McpTransportContext.create(Map.of(WorkspaceResolver.WORKSPACE_DIR_KEY, originDir));
        return (McpSyncRequestContext) Proxy.newProxyInstance(
                McpSyncRequestContext.class.getClassLoader(),
                new Class<?>[] {McpSyncRequestContext.class},
                (proxy, method, args) -> {
                    if ("transportContext".equals(method.getName())) {
                        return transport;
                    }
                    throw new UnsupportedOperationException(
                            "the project tools must not call " + method.getName());
                });
    }
}
