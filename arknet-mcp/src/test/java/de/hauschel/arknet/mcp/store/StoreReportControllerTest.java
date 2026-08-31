// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;

/**
 * Pins {@code GET /report} (issue #391): the loopback Host check the endpoint must not skip
 * (ADR-009 decision 4, the same DNS-rebinding defense {@code AnchorHttpTransportConfiguration}
 * enforces for the MCP transport), and the fall-through to {@link StoreReportTools#htmlReport}
 * for a request that passes it.
 */
class StoreReportControllerTest {

    private static final String ANCHOR = "/home/dev/projects/sample-project";

    @Test
    void returnsTheRenderedHtmlForAnAllowedLoopbackHost() {
        final StoreReportTools reportTools = mock(StoreReportTools.class);
        when(reportTools.htmlReport(ANCHOR)).thenReturn("<!doctype html><html></html>");
        final StoreReportController controller = new StoreReportController(reportTools);

        final ResponseEntity<String> response = controller.report(ANCHOR, requestWithHost("127.0.0.1:47331"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isEqualTo("<!doctype html><html></html>");
    }

    @Test
    void acceptsTheLocalhostSpellingToo() {
        final StoreReportTools reportTools = mock(StoreReportTools.class);
        when(reportTools.htmlReport(ANCHOR)).thenReturn("<!doctype html><html></html>");
        final StoreReportController controller = new StoreReportController(reportTools);

        final ResponseEntity<String> response = controller.report(ANCHOR, requestWithHost("localhost:47331"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    /**
     * The regression this test exists to prevent: a plain {@code @RestController} added beside
     * the MCP transport does not automatically inherit that transport's own
     * {@code DefaultServerTransportSecurityValidator} - without this check, a page a browser
     * rebinds to {@code evil.example.com} becomes same-origin with the daemon and could read the
     * whole project model. Rejected with 421, the same status the MCP transport's own validator
     * uses, and {@link StoreReportTools} is never even asked to render.
     */
    @Test
    void rejectsAForeignHostHeaderBeforeRenderingAnything() {
        final StoreReportTools reportTools = mock(StoreReportTools.class);
        final StoreReportController controller = new StoreReportController(reportTools);

        final ResponseEntity<String> response =
                controller.report(ANCHOR, requestWithHost("evil.example.com:47331"));

        assertThat(response.getStatusCode().value()).isEqualTo(421);
        verifyNoInteractions(reportTools);
    }

    /**
     * A request carrying no {@code Host} header at all - not the ordinary case for a real HTTP/1.1
     * client, but still a rejection rather than a silent pass-through, matching
     * {@code DefaultServerTransportSecurityValidator}'s own "missing Host is invalid" rule.
     */
    @Test
    void rejectsAMissingHostHeader() {
        final StoreReportTools reportTools = mock(StoreReportTools.class);
        final StoreReportController controller = new StoreReportController(reportTools);

        final ResponseEntity<String> response = controller.report(ANCHOR, requestWithHost(null));

        assertThat(response.getStatusCode().value()).isEqualTo(421);
        verifyNoInteractions(reportTools);
    }

    /**
     * An anchor nobody registered - or none at all, the expected case for a browser that has not
     * been given one - surfaces as a 400 naming the query parameter, not a raw stack trace.
     */
    @Test
    void reportsAnUnresolvedAnchorAsABadRequestNamingTheQueryParameter() {
        final StoreReportTools reportTools = mock(StoreReportTools.class);
        when(reportTools.htmlReport(null))
                .thenThrow(new UnresolvedProjectAnchorException(null, "no anchor was sent"));
        final StoreReportController controller = new StoreReportController(reportTools);

        final ResponseEntity<String> response = controller.report(null, requestWithHost("127.0.0.1:47331"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("projectAnchor").contains("no anchor was sent");
    }

    private static HttpServletRequest requestWithHost(final String host) {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Host")).thenReturn(host);
        return request;
    }
}
