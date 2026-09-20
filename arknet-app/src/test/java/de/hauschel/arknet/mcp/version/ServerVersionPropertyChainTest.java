// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.version;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

/**
 * Pins the claim the whole of issue #194 rests on: the version the MCP handshake reports and the
 * version stamped onto a failed tool call come from <em>one</em> property, not from two
 * independently maintained ones.
 *
 * <p>The three consumers are wired in three different places - {@code application.properties} feeds
 * {@code spring.ai.mcp.server.version}, {@code ArknetMcpConfiguration} reads {@code arknet.version}
 * into {@link ServerVersion}, and the export envelope takes that bean. Nothing but this test says
 * they must agree, and the failure would be exactly the confusion the issue set out to remove: a
 * daemon that names one build at connect time and another when something goes wrong.</p>
 *
 * <p>Loads only the real {@code application.properties} through
 * {@link ConfigDataApplicationContextInitializer} - no beans, no web server, no
 * {@code @SpringBootTest} and therefore no context-cache entry.</p>
 */
class ServerVersionPropertyChainTest {

    private final ApplicationContextRunner contextRunner =
            new ApplicationContextRunner().withInitializer(new ConfigDataApplicationContextInitializer());

    @Test
    void theHandshakeVersionIsTheSamePropertyTheErrorStampReads() {
        contextRunner.run(context -> {
            final Environment environment = context.getEnvironment();

            assertThat(environment.getProperty("spring.ai.mcp.server.version"))
                    .isEqualTo(environment.getProperty("arknet.version"));
        });
    }

    /**
     * With no {@code ARKNET_VERSION} baked in (the bare-jar and reactor case), the version is the
     * Maven {@code ${revision}} the {@code build-info} goal wrote - never blank, and never the
     * {@code unknown} placeholder as long as the module was actually built.
     */
    @Test
    void withoutAnImageVersionTheMavenBuildVersionWins() {
        contextRunner.run(context -> {
            final Environment environment = context.getEnvironment();
            final String buildVersion = environment.getProperty("build.version");

            assertThat(buildVersion)
                    .describedAs("build-info.properties must be on the test classpath - "
                            + "spring-boot-maven-plugin:build-info runs at generate-resources")
                    .isNotBlank();
            assertThat(environment.getProperty("arknet.version")).isEqualTo(buildVersion);
        });
    }

    /** An explicit image version overrides the jar's own, which is what the Docker build relies on. */
    @Test
    void anExplicitImageVersionOverridesTheBuildVersion() {
        contextRunner.withPropertyValues("ARKNET_VERSION=v9.9.9").run(context ->
                assertThat(context.getEnvironment().getProperty("arknet.version")).isEqualTo("v9.9.9"));
    }
}
