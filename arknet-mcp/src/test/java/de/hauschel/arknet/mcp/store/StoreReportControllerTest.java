// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
     * No anchor at all - the expected first contact for a browser opening {@code /report} cold -
     * gets a remedy a browser user can actually act on (add the query parameter), not {@code
     * unresolved.getMessage()} verbatim: that message is composed for MCP tool callers and names
     * {@code project_list}/{@code project_add}, tools unreachable from a browser tab (issue #391
     * review follow-up).
     */
    @Test
    void reportsAMissingAnchorAsABadRequestTellingTheBrowserToAddTheQueryParameter() {
        final StoreReportTools reportTools = mock(StoreReportTools.class);
        when(reportTools.htmlReport(null))
                .thenThrow(new UnresolvedProjectAnchorException(null, "no anchor was sent"));
        final StoreReportController controller = new StoreReportController(reportTools);

        final ResponseEntity<String> response = controller.report(null, requestWithHost("127.0.0.1:47331"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("?projectAnchor=");
        assertThat(response.getBody()).doesNotContain("no anchor was sent");
    }

    /**
     * An anchor nobody registered - the caller did send something, unlike the case above - names
     * that anchor back in the response rather than reading as if nothing had arrived, the same
     * distinction {@code RegisteredAnchorProjectResolver} draws between its two remedy messages.
     */
    @Test
    void reportsAnUnregisteredAnchorAsABadRequestNamingTheAnchor() {
        final StoreReportTools reportTools = mock(StoreReportTools.class);
        when(reportTools.htmlReport(ANCHOR)).thenThrow(
                new UnresolvedProjectAnchorException(ANCHOR, "no project registered for '" + ANCHOR + "'"));
        final StoreReportController controller = new StoreReportController(reportTools);

        final ResponseEntity<String> response = controller.report(ANCHOR, requestWithHost("127.0.0.1:47331"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains(ANCHOR).contains("?projectAnchor=");
    }

    /**
     * The happy path proven through actual Spring MVC dispatch rather than a direct method call -
     * exercising the {@code @GetMapping}/{@code produces} routing and the query-parameter binding
     * the unit tests above never touch (issue #391 review follow-up).
     */
    @Test
    void dispatchesGetReportThroughRealSpringMvcRoutingAndReturnsTheHtmlBody() throws Exception {
        final StoreReportTools reportTools = mock(StoreReportTools.class);
        when(reportTools.htmlReport(ANCHOR)).thenReturn("<!doctype html><html></html>");
        final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StoreReportController(reportTools)).build();

        mockMvc.perform(get("/report").param("projectAnchor", ANCHOR).header("Host", "127.0.0.1:47331"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML))
                .andExpect(content().string("<!doctype html><html></html>"));
    }

    /**
     * The same rejection {@link #rejectsAForeignHostHeaderBeforeRenderingAnything()} pins via a
     * direct method call, now proven through real Spring MVC dispatch - the actual route the DNS
     * rebinding defense has to survive in production, not a hand-stubbed {@link
     * HttpServletRequest}.
     */
    @Test
    void dispatchesRejectsAForeignHostHeaderThroughRealSpringMvcRouting() throws Exception {
        final StoreReportTools reportTools = mock(StoreReportTools.class);
        final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StoreReportController(reportTools)).build();

        mockMvc.perform(get("/report").param("projectAnchor", ANCHOR).header("Host", "evil.example.com:47331"))
                .andExpect(status().is(421));

        verifyNoInteractions(reportTools);
    }

    /**
     * Documents a quirk real dispatch surfaced that the direct-call tests above could not: an
     * {@code Accept: application/json} request is rejected with 406 by Spring's content
     * negotiation - matching this endpoint's {@code produces = TEXT_HTML_VALUE} - BEFORE the
     * controller method, and therefore the Host check inside it, ever runs. A malicious {@code
     * Host} header combined with this {@code Accept} header therefore gets 406 instead of the 421
     * the Host check would otherwise return. Not a data leak - 406 carries no report content, and
     * {@code reportTools} is never invoked either way - but a silent bypass of the documented 421
     * path for this one request shape, pinned here so a future change is not surprised by it
     * (issue #391 review follow-up).
     */
    @Test
    void dispatchesAnAcceptJsonRequestGets406BeforeTheHostCheckEvenWithAForeignHost() throws Exception {
        final StoreReportTools reportTools = mock(StoreReportTools.class);
        final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new StoreReportController(reportTools)).build();

        mockMvc.perform(get("/report").param("projectAnchor", ANCHOR)
                        .header("Host", "evil.example.com:47331")
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotAcceptable());

        verifyNoInteractions(reportTools);
    }

    private static HttpServletRequest requestWithHost(final String host) {
        final HttpServletRequest request = mock(HttpServletRequest.class);
        when(request.getHeader("Host")).thenReturn(host);
        return request;
    }
}
