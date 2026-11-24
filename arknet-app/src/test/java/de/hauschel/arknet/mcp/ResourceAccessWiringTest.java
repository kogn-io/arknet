// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import de.hauschel.arknet.prj.application.ProjectService;
import de.hauschel.arknet.prj.domain.Anchor;
import de.hauschel.arknet.prj.domain.AnchorType;
import de.hauschel.arknet.req.adapter.mcp.RequirementMcpTools;
import de.hauschel.arknet.resource.adapter.mcp.ResourceAccessMcpTools;
import de.hauschel.arknet.resource.application.SubmitService;
import de.hauschel.arknet.resource.application.port.in.ChangeExample;
import de.hauschel.arknet.resource.application.port.in.DescribeResourceType;
import de.hauschel.arknet.resource.application.port.out.ResourceRepository;
import de.hauschel.arknet.ul.adapter.mcp.UbiquitousLanguageMcpTools;

/**
 * The composition root's wiring of generic resource access: {@code describe} and {@code submit}
 * are offered only with {@value ResourceAccessMcpTools#ENABLED_PROPERTY}{@code =true}, and once
 * offered, a document taken from {@code describe} runs through {@code submit} and is read back
 * through the type-specific read tools.
 *
 * <p>Which tools a context offers is asked the way the Spring AI annotation scanner asks it: every
 * bean with an {@link McpTool} method goes through {@link SyncMcpToolProvider}. The full MCP
 * server auto-configuration and its transport stay out of scope, as in
 * {@link ArknetMcpConfigurationTest}.</p>
 */
class ResourceAccessWiringTest {

    private static final String LANGUAGE = "en";

    private static final String EXAMPLE_HEADING = "Example document (creates one resource; runs as it stands):\n";

    private static final String THEN = "\nThen ";

    private static final String PREFIXES_HEADING = "\nPrefixes behind every name above:";

    private static final String EXAMPLE_TEXT = "Example text";

    private static final String CHANGED_TEXT = "Changed example text";

    @TempDir
    Path storageDir;

    /** Where the registered project's anchor lives - apart from {@link #storageDir}, which the store lists. */
    @TempDir
    Path workspace;

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ArknetMcpConfiguration.class);

    @Test
    void offersNeitherToolWithoutTheProperty() {
        contextRunner
                .withPropertyValues("arknet.rdf.storage=" + storageDir)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ResourceAccessMcpTools.class);
                    assertThat(context).doesNotHaveBean(SubmitService.class);
                    assertThat(context).doesNotHaveBean(DescribeResourceType.class);
                    assertThat(context).doesNotHaveBean(ResourceRepository.class);
                    assertThat(toolNames(context))
                            .as("the type-specific tools are offered, describe and submit are not")
                            .contains("req_add", "term_get")
                            .doesNotContain("describe", "submit");
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "yes", ""})
    void offersNeitherToolWhenThePropertyIsAnythingButTrue(final String value) {
        contextRunner
                .withPropertyValues("arknet.rdf.storage=" + storageDir,
                        ResourceAccessMcpTools.ENABLED_PROPERTY + "=" + value)
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ResourceAccessMcpTools.class);
                    assertThat(toolNames(context)).doesNotContain("describe", "submit");
                });
    }

    @Test
    void offersBothToolsWithThePropertySetToTrue() {
        onEnabledStore((context, tools, anchor) -> {
            assertThat(context).hasSingleBean(ResourceAccessMcpTools.class);
            assertThat(toolNames(context)).contains("describe", "submit", "req_add", "term_get");
        });
    }

    @Test
    void aTermFromTheDescribedExampleIsReadBackAndUpdatedThroughTheTypedTermTool() {
        onEnabledStore((context, tools, anchor) -> {
            // given
            String type = "skos:Concept";
            String description = tools.describe(null, type, null, anchor);
            // the shapes require skos:definition, so the example carries the one the typed read path needs
            String document = example(description);

            // when
            String created = tools.submit(null, document, anchor);

            // then
            String code = code(created, "TERM");
            UbiquitousLanguageMcpTools terms = context.getBean(UbiquitousLanguageMcpTools.class);
            assertThat(terms.get(null, code, null, anchor)).contains(code, EXAMPLE_TEXT);

            // when
            tools.submit(null, changeExample(description, "Update").replace(ChangeExample.PLACEHOLDER,
                    "<" + code + ">"), anchor);

            // then
            assertThat(terms.get(null, code, null, anchor)).contains(code, CHANGED_TEXT);
        });
    }

    @Test
    void aRequirementFromTheDescribedExampleIsReadBackThroughTheTypedRequirementTool() {
        onEnabledStore((context, tools, anchor) -> {
            // given
            String type = "arkreq:FunctionalRequirement";
            // TODO #742: arkreq:status is set by hand - the typed read path skips a requirement without
            // one, and the example writes only what the shapes require.
            String document = example(tools.describe(null, type, null, anchor))
                    .replace("a " + type, "a " + type + " ;\n    arkreq:status arkreq:Proposed");

            // when
            String created = tools.submit(null, document, anchor);

            // then
            String code = code(created, "FR");
            assertThat(context.getBean(RequirementMcpTools.class).get(null, code, null, anchor))
                    .contains(code, EXAMPLE_TEXT);
        });
    }

    private void onEnabledStore(final EnabledStoreBody body) {
        contextRunner
                .withPropertyValues("arknet.rdf.storage=" + storageDir,
                        ResourceAccessMcpTools.ENABLED_PROPERTY + "=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    Path checkout = Files.createDirectories(workspace.resolve("resource-access"));
                    context.getBean(ProjectService.class).register("resource-access",
                            new Anchor(checkout.toString(), AnchorType.PATH), null, null, LANGUAGE, null);
                    body.accept(context, context.getBean(ResourceAccessMcpTools.class), checkout.toString());
                });
    }

    /** The tool names the Spring AI annotation scanner would publish for the beans of this context. */
    private static List<String> toolNames(final AssertableApplicationContext context) {
        List<Object> toolBeans = new ArrayList<>();
        context.getBeansOfType(Object.class).values().stream()
                .filter(bean -> Arrays.stream(bean.getClass().getMethods())
                        .anyMatch(method -> method.isAnnotationPresent(McpTool.class)))
                .forEach(toolBeans::add);
        return new SyncMcpToolProvider(toolBeans).getToolSpecifications().stream()
                .map(specification -> specification.tool().name())
                .toList();
    }

    private static String example(final String description) {
        int start = description.indexOf(EXAMPLE_HEADING);
        assertThat(start).as("describe shows an example document:\n%s", description).isNotNegative();
        String rest = description.substring(start + EXAMPLE_HEADING.length());
        int then = rest.indexOf(THEN);
        return rest.substring(0, then >= 0 ? then : rest.indexOf(PREFIXES_HEADING));
    }

    private static String changeExample(final String description, final String activity) {
        String rest = description.substring(description.indexOf(EXAMPLE_HEADING));
        for (String block : rest.split(Pattern.quote(THEN))) {
            if (block.contains("a arkres:" + activity)) {
                String document = block.substring(block.indexOf(":\n") + 2);
                int prefixes = document.indexOf(PREFIXES_HEADING);
                return prefixes >= 0 ? document.substring(0, prefixes) : document;
            }
        }
        throw new AssertionError("describe shows no " + activity + " example:\n" + description);
    }

    private static String code(final String submitted, final String prefix) {
        Matcher matcher = Pattern.compile("\\b" + prefix + "-\\d+\\b").matcher(submitted);
        assertThat(matcher.find()).as("submit names a %s code:\n%s", prefix, submitted).isTrue();
        return matcher.group();
    }

    @FunctionalInterface
    private interface EnabledStoreBody {
        void accept(AssertableApplicationContext context, ResourceAccessMcpTools tools, String anchor)
                throws Exception;
    }
}
