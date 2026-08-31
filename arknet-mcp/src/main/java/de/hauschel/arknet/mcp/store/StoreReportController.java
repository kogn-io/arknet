// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.modelcontextprotocol.server.transport.ServerTransportSecurityException;
import io.modelcontextprotocol.server.transport.ServerTransportSecurityValidator;

import de.hauschel.arknet.kernel.UnresolvedProjectAnchorException;
import de.hauschel.arknet.mcp.LoopbackHostSecurity;

/**
 * A second, browser-reachable way to fetch the very same self-contained HTML report
 * {@code store_overview} already renders (issue #391): {@code GET /report} returns it directly
 * as the response body, instead of a filesystem path a human has to open by hand
 * ({@code file://...}). Purely additive - {@link StoreReportTools#storeOverview} keeps writing
 * the file exactly as before, unaffected by this second consumer of the same rendering
 * ({@link StoreReportTools#htmlReport}).
 *
 * <p><strong>Project selection.</strong> A browser navigating to a plain URL cannot set the
 * {@code X-Arknet-Project-Anchor} header every MCP tool call carries
 * ({@code AnchorHttpTransportConfiguration}), so this endpoint takes the project's anchor as a
 * query parameter instead - the same anchor a client registers via {@code project_add}/
 * {@code project_adopt}, resolved through the identical {@link
 * de.hauschel.arknet.kernel.ProjectResolver} every tool call uses. There is no transport-context
 * anchor to fall back to here, unlike the MCP tools' optional {@code projectAnchor} parameter.</p>
 *
 * <p><strong>Same loopback boundary as the MCP endpoint (ADR-009 decision 4).</strong> Spring AI
 * MCP's DNS-rebinding defense lives on the {@code WebMvcStreamableServerTransportProvider} bean
 * alone; a plain {@code @RestController} added beside it would not inherit that protection and
 * would hand a rebound page a readable copy of the whole project model. This endpoint therefore
 * validates the request's {@code Host}/{@code Origin} headers itself, reusing
 * {@link LoopbackHostSecurity}'s allowlist rather than a second, independently maintained copy of
 * it.</p>
 */
@RestController
public final class StoreReportController {

    private final StoreReportTools reportTools;
    private final ServerTransportSecurityValidator hostValidator;

    public StoreReportController(final StoreReportTools reportTools) {
        this.reportTools = Objects.requireNonNull(reportTools, "reportTools");
        this.hostValidator = LoopbackHostSecurity.hostValidator();
    }

    @GetMapping(value = "/report", produces = MediaType.TEXT_HTML_VALUE)
    public ResponseEntity<String> report(
            @RequestParam(name = "projectAnchor", required = false) final String projectAnchor,
            final HttpServletRequest request) {
        try {
            hostValidator.validateHeaders(headersOf(request));
        } catch (final ServerTransportSecurityException rejected) {
            return textResponse(HttpStatus.valueOf(rejected.getStatusCode()), rejected.getMessage());
        }
        try {
            return ResponseEntity.ok(reportTools.htmlReport(projectAnchor));
        } catch (final UnresolvedProjectAnchorException unresolved) {
            return textResponse(HttpStatus.BAD_REQUEST,
                    "GET /report needs a registered project - pass ?projectAnchor=<anchor>. "
                            + unresolved.getMessage());
        }
    }

    private static ResponseEntity<String> textResponse(final HttpStatus status, final String message) {
        return ResponseEntity.status(status).contentType(MediaType.TEXT_PLAIN).body(message);
    }

    /** Lifts just the two headers {@link ServerTransportSecurityValidator} cares about. */
    private static Map<String, List<String>> headersOf(final HttpServletRequest request) {
        final Map<String, List<String>> headers = new LinkedHashMap<>();
        final String host = request.getHeader("Host");
        if (host != null) {
            headers.put("Host", List.of(host));
        }
        final String origin = request.getHeader("Origin");
        if (origin != null) {
            headers.put("Origin", List.of(origin));
        }
        return headers;
    }
}
