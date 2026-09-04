// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.prj.application.port.in.ResolveProject;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.UnknownAnchorException;

/**
 * The switch-over itself, unit-tested: routing is a registry lookup on the whole anchor,
 * and everything it cannot answer is an error rather than a default.
 *
 * <p>The first test is the regression stated at its smallest. Two projects live in
 * identically named directories at different places. The old resolver reduced each anchor to
 * {@code slug(basename(git-common-dir))} - both became {@code arknet}, both addressed one dataset,
 * and the two projects' requirements ended up interleaved with nothing at either call site to
 * notice. Here the two anchors are simply two different keys.</p>
 */
class RegisteredAnchorProjectResolverTest {

    private static final ProjectId ARKNET = new ProjectId(UUID.randomUUID().toString());
    private static final ProjectId OTHER_ARKNET = new ProjectId(UUID.randomUUID().toString());

    /** Stands in for the registry: the two anchors below are registered, nothing else is. */
    private final ResolveProject registry = new FakeRegistry(Map.of(
            "/home/a/DEV/arknet", ARKNET,
            "/home/b/work/arknet", OTHER_ARKNET));

    private final RegisteredAnchorProjectResolver resolver = new RegisteredAnchorProjectResolver(registry);

    @Test
    void identicallyNamedDirectoriesInDifferentPlacesResolveToDifferentProjects() {
        assertThat(resolver.resolve("/home/a/DEV/arknet").id()).isEqualTo(ARKNET);
        assertThat(resolver.resolve("/home/b/work/arknet").id()).isEqualTo(OTHER_ARKNET);
        assertThat(ARKNET).isNotEqualTo(OTHER_ARKNET);
    }

    @Test
    void anUnregisteredAnchorFailsAndTheMessageNamesItAndTheWayOut() {
        assertThatThrownBy(() -> resolver.resolve("/home/c/arknet"))
                .isInstanceOf(UnresolvedProjectAnchorException.class)
                .hasMessageContaining("/home/c/arknet")
                .hasMessageContaining("project_add")
                .hasMessageContaining("project_adopt")
                .hasNoCause()
                .satisfies(e -> assertThat(e.getSuppressed()).hasAtLeastOneElementOfType(UnknownAnchorException.class))
                .satisfies(e -> assertThat(((UnresolvedProjectAnchorException) e).anchor()).isEqualTo("/home/c/arknet"));
    }

    @Test
    void aMissingAnchorFailsRatherThanRoutingAnywhere() {
        assertThatThrownBy(() -> resolver.resolve(null))
                .isInstanceOf(UnresolvedProjectAnchorException.class)
                .hasMessageContaining(AnchorHttpTransportConfiguration.ANCHOR_HEADER)
                .hasMessageContaining("projectAnchor")
                .satisfies(e -> assertThat(((UnresolvedProjectAnchorException) e).anchor()).isNull());
        assertThatThrownBy(() -> resolver.resolve("   "))
                .isInstanceOf(UnresolvedProjectAnchorException.class);
    }

    /**
     * A missing anchor and an unknown one get different remedies on purpose: telling a caller that
     * already sent one to send one would send it hunting for a transport fault instead of
     * registering the project it is actually missing.
     */
    @Test
    void theTwoFailuresGiveDifferentAdvice() {
        final String missing = failureMessage(() -> resolver.resolve(null));
        final String unknown = failureMessage(() -> resolver.resolve("/home/c/arknet"));

        assertThat(missing).isNotEqualTo(unknown);
        assertThat(unknown).doesNotContain(AnchorHttpTransportConfiguration.ANCHOR_HEADER);
    }

    /**
     * An anchor's identity is its value alone - the type is descriptive metadata, never a second
     * identity axis (see {@code Anchor#equals}). A resolver that has only ever seen a raw string
     * therefore cannot get the lookup wrong by picking the wrong type for it.
     */
    @Test
    void resolvesAUrlShapedAnchorRegisteredUnderADifferentType() {
        final ResolveProject urlRegistry = new FakeRegistry(
                Map.of("https://example.invalid/team/arknet", ARKNET), AnchorType.URL);

        assertThat(new RegisteredAnchorProjectResolver(urlRegistry)
                .resolve("https://example.invalid/team/arknet").id())
                .isEqualTo(ARKNET);
    }

    /**
     * The resolved project's configured default language (issue #228) travels through {@link
     * RegisteredAnchorProjectResolver} unchanged - the bounded contexts that need it (e.g.
     * ubiquitous-language) get it "for free" from the very same anchor resolution every tool call
     * already performs, without a second lookup.
     */
    @Test
    void resolvedProjectCarriesTheDefaultLanguageOfTheUnderlyingProject() {
        final ResolveProject registryWithDefaultLanguage = anchor -> new Project(ARKNET, "arknet",
                List.of(new Anchor(anchor.value(), AnchorType.PATH)), null, "de");

        assertThat(new RegisteredAnchorProjectResolver(registryWithDefaultLanguage)
                .resolve("/home/a/DEV/arknet").defaultLanguage())
                .isEqualTo("de");
    }

    private static String failureMessage(final Runnable call) {
        try {
            call.run();
            throw new AssertionError("expected the call to fail");
        } catch (final UnresolvedProjectAnchorException e) {
            return e.getMessage();
        }
    }

    /** Minimal stand-in for the project component: a fixed anchor-to-project map. */
    private record FakeRegistry(Map<String, ProjectId> registered, AnchorType storedType)
            implements ResolveProject {

        FakeRegistry(Map<String, ProjectId> registered) {
            this(registered, AnchorType.PATH);
        }

        @Override
        public Project resolve(final Anchor anchor) {
            return Optional.ofNullable(registered.get(anchor.value()))
                    .map(id -> new Project(id, id.value(), List.of(new Anchor(anchor.value(), storedType))))
                    .orElseThrow(() -> new UnknownAnchorException(anchor));
        }
    }
}
