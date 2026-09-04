// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.function.ServerRequest;

import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpTransportContextExtractor;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

import org.springframework.ai.mcp.server.common.autoconfigure.properties.McpServerStreamableHttpProperties;
import org.springframework.ai.mcp.server.webmvc.transport.WebMvcStreamableServerTransportProvider;

import tools.jackson.databind.json.JsonMapper;

import de.hauschel.arknet.kernel.ProjectResolver;

/**
 * Pins the project-anchor transport wiring: the context extractor lifts the client's
 * {@value AnchorHttpTransportConfiguration#ANCHOR_HEADER} header into the per-call transport
 * context (where the in-adapters read it), and the provider bean assembles.
 *
 * <p>A missing header yielding an <em>empty</em> context is the load-bearing case here. It used to
 * mean "fall back to the server's own directory"; it now means "this call names no project", which
 * the in-adapters turn into a caller error. The two tests below therefore pin behaviour that has
 * changed meaning without changing shape.</p>
 *
 * <p>Pins the loopback security validator the same way (issue #303): a reflection-based build
 * check that it is not the builder's {@code NOOP} default, and a behaviour check that it actually
 * rejects a foreign Host header and accepts the daemon's own loopback names - including on a port
 * overridden away from the {@code application.properties} default (issue #295).</p>
 */
class AnchorHttpTransportConfigurationTest {

    @Test
    void extractsTheAnchorHeaderIntoTheTransportContext() {
        ServerRequest request = requestWithHeader("/home/dev/projects/sample-project");

        McpTransportContext context = AnchorHttpTransportConfiguration.extractAnchor(request);

        assertThat(context.get(ProjectResolver.ANCHOR_KEY)).isEqualTo("/home/dev/projects/sample-project");
    }

    @Test
    void aMissingHeaderYieldsAnEmptyContext() {
        ServerRequest request = requestWithHeader(null);

        McpTransportContext context = AnchorHttpTransportConfiguration.extractAnchor(request);

        assertThat(context).isSameAs(McpTransportContext.EMPTY);
    }

    @Test
    void aBlankHeaderYieldsAnEmptyContext() {
        ServerRequest request = requestWithHeader("   ");

        McpTransportContext context = AnchorHttpTransportConfiguration.extractAnchor(request);

        assertThat(context).isSameAs(McpTransportContext.EMPTY);
    }

    /** The overriding provider bean assembles from the same inputs the auto-configuration uses. */
    @Test
    void buildsTheStreamableHttpTransportProvider() {
        WebMvcStreamableServerTransportProvider provider =
                new AnchorHttpTransportConfiguration().webMvcStreamableServerTransportProvider(
                        JsonMapper.builder().build(), new McpServerStreamableHttpProperties());

        assertThat(provider).isNotNull();
    }

    /**
     * The regression this class exists to prevent: a provider built without the anchor
     * extractor wired in would fall back to {@link McpTransportContextExtractor}'s default
     * ({@code serverRequest -> McpTransportContext.EMPTY}), so every real request would silently
     * carry no project - exactly the production failure ADR-016 routing depends on this bean to
     * avoid. The extractor is package-private state with no accessor, so this pins the built
     * object's actual field rather than trusting the builder call alone.
     */
    @Test
    void wiresTheAnchorExtractorIntoTheBuiltTransportProvider() throws ReflectiveOperationException {
        WebMvcStreamableServerTransportProvider provider =
                new AnchorHttpTransportConfiguration().webMvcStreamableServerTransportProvider(
                        JsonMapper.builder().build(), new McpServerStreamableHttpProperties());

        Field field = WebMvcStreamableServerTransportProvider.class.getDeclaredField("contextExtractor");
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        McpTransportContextExtractor<ServerRequest> wiredExtractor =
                (McpTransportContextExtractor<ServerRequest>) field.get(provider);

        McpTransportContext extracted =
                wiredExtractor.extract(requestWithHeader("/home/dev/projects/sample-project"));

        assertThat(extracted.get(ProjectResolver.ANCHOR_KEY)).isEqualTo("/home/dev/projects/sample-project");
    }

    /**
     * Pins the header literal itself (issue #303 point 3), not just the constant that carries it:
     * a change to this value would break every deployed {@code .mcp.json} and the arknet-plugin
     * client, silently, while every test that only asserts against the constant stayed green.
     */
    @Test
    void theAnchorHeaderIsTheDocumentedLiteral() {
        assertThat(AnchorHttpTransportConfiguration.ANCHOR_HEADER).isEqualTo("X-Arknet-Project-Anchor");
    }

    /**
     * The regression this pins (issue #303, the review's most weighty test gap): a provider built
     * without an explicit {@code .securityValidator(...)} call falls back to the builder's own
     * default, {@link ServerTransportSecurityValidator#NOOP} - exactly the sibling case the
     * {@code wiresTheAnchorExtractorIntoTheBuiltTransportProvider} test above already guards for the
     * context extractor, which the validator never got until now. Removing the
     * {@code .securityValidator(...)} call in {@link AnchorHttpTransportConfiguration} would leave
     * every other test in this suite green while quietly reopening the DNS-rebinding gap ADR-009
     * decision 4 closes - this is the test that would go red.
     */
    @Test
    void wiresANonNoopSecurityValidatorIntoTheBuiltTransportProvider() throws ReflectiveOperationException {
        ServerTransportSecurityValidator wiredValidator = wiredSecurityValidator(builtProvider());

        assertThat(wiredValidator).isNotNull().isNotSameAs(ServerTransportSecurityValidator.NOOP);
    }

    /**
     * Behaviour test for the wired validator: a Host header naming a machine other than this
     * daemon's loopback names is rejected with 421, before the request ever reaches the anchor
     * extractor.
     */
    @Test
    void securityValidatorRejectsAForeignHostHeader() throws ReflectiveOperationException {
        ServerTransportSecurityValidator validator = wiredSecurityValidator(builtProvider());

        assertThatThrownBy(() -> validator.validateHeaders(Map.of("Host", List.of("evil.example.com:47331"))))
                .isInstanceOf(ServerTransportSecurityException.class)
                .extracting(exception -> ((ServerTransportSecurityException) exception).getStatusCode())
                .isEqualTo(421);
    }

    /**
     * A request naming the daemon's own loopback host on its {@code application.properties} default
     * port passes - the case the review already expected a test for.
     */
    @Test
    void securityValidatorAcceptsTheDefaultLoopbackHostAndPort() throws ReflectiveOperationException {
        ServerTransportSecurityValidator validator = wiredSecurityValidator(builtProvider());

        assertThatCode(() -> validator.validateHeaders(Map.of("Host", List.of("127.0.0.1:47331"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateHeaders(Map.of("Host", List.of("localhost:47331"))))
                .doesNotThrowAnyException();
    }

    /**
     * The case issue #295 is about: an admin running the daemon with {@code -Darknet.mcp.port}
     * overridden away from the {@code application.properties} default must not be locked out by the
     * very allowlist meant to protect them. A validator pinned to the literal default port would
     * 421 every request here; the wildcard-port allowlist does not.
     */
    @Test
    void securityValidatorAcceptsTheLoopbackHostOnAnOverriddenPort() throws ReflectiveOperationException {
        ServerTransportSecurityValidator validator = wiredSecurityValidator(builtProvider());

        assertThatCode(() -> validator.validateHeaders(Map.of("Host", List.of("127.0.0.1:48000"))))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateHeaders(Map.of("Host", List.of("localhost:48000"))))
                .doesNotThrowAnyException();
    }

    private static WebMvcStreamableServerTransportProvider builtProvider() {
        return new AnchorHttpTransportConfiguration().webMvcStreamableServerTransportProvider(
                JsonMapper.builder().build(), new McpServerStreamableHttpProperties());
    }

    private static ServerTransportSecurityValidator wiredSecurityValidator(
            final WebMvcStreamableServerTransportProvider provider) throws ReflectiveOperationException {
        Field field = WebMvcStreamableServerTransportProvider.class.getDeclaredField("securityValidator");
        field.setAccessible(true);
        return (ServerTransportSecurityValidator) field.get(provider);
    }

    private static ServerRequest requestWithHeader(final String value) {
        ServerRequest request = mock(ServerRequest.class);
        ServerRequest.Headers headers = mock(ServerRequest.Headers.class);
        when(request.headers()).thenReturn(headers);
        when(headers.firstHeader(AnchorHttpTransportConfiguration.ANCHOR_HEADER)).thenReturn(value);
        return request;
    }
}
