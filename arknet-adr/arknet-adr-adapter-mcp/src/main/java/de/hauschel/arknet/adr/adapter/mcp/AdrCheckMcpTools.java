// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.adapter.mcp;

import static de.hauschel.arknet.adr.adapter.mcp.ToolArguments.blankToNull;
import static de.hauschel.arknet.adr.adapter.mcp.ToolArguments.effectiveDisplayLocale;

import java.util.List;
import java.util.Objects;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.adr.application.port.in.CheckAdrs;
import de.hauschel.arknet.adr.application.port.in.CheckAdrs.CheckReport;
import de.hauschel.arknet.adr.application.port.in.CheckAdrs.Finding;
import de.hauschel.arknet.adr.application.port.in.CountSkippedAdrs;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;

/**
 * Driving (in) adapter of the ADR component's reading check: exposes {@code adr_check}
 * (kogn-io/arknet#387), the non-blocking consistency and quality pass over a whole project's
 * decisions.
 *
 * <p><strong>Its own tools class next to {@link AdrMcpTools}.</strong> The eight tools there manage
 * a decision - they write, transition and delete one at a time; this one reads the corpus and
 * judges nothing. The requirements bounded context already carries two tool classes in one adapter
 * for the same kind of reason. Two classes also keep the check's in-ports out of a constructor that
 * already takes fifteen arguments, and let this tool's presentation - two severity classes and the
 * list of what it does not check - live in one place instead of as three more helpers in a
 * 900-line class.</p>
 *
 * <p><strong>Nothing here writes.</strong> No status is set, no record is touched, nothing is
 * refused. A finding is something to read; what follows from it stays with the user, which is also
 * why the tool is declared {@code readOnlyHint}.</p>
 */
public final class AdrCheckMcpTools {

    private static final String FACTS_CAPTION = "Facts (decidable as stated):";
    private static final String SUSPICIONS_CAPTION = "Suspicions (worth a look, a reader decides):";
    private static final String NOT_CHECKED_CAPTION = "Not checked here - these need a reader:";

    private final CheckAdrs checkAdrs;
    private final CountSkippedAdrs countSkippedAdrs;
    private final ProjectResolver projects;

    /**
     * Creates the adapter with the check in-port and the skipped-decision count that tells a caller
     * whether the corpus was even fully visible.
     *
     * @param checkAdrs        in-port backing {@code adr_check}
     * @param countSkippedAdrs in-port backing the skipped-decision note this tool appends, exactly as
     *                         {@code adr_list} does (kogn-io/arknet#359) - a check that reports a
     *                         clean corpus while records were silently unreadable would be the worse
     *                         of the two silences
     * @param projects         resolves each call's target project from its anchor
     */
    public AdrCheckMcpTools(final CheckAdrs checkAdrs, final CountSkippedAdrs countSkippedAdrs,
            final ProjectResolver projects) {
        this.checkAdrs = Objects.requireNonNull(checkAdrs, "checkAdrs");
        this.countSkippedAdrs = Objects.requireNonNull(countSkippedAdrs, "countSkippedAdrs");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    @McpTool(name = "adr_check", description = "Check every recorded architecture decision of this "
            + "project for what a machine can decide, and report it in two separated classes. Facts: "
            + "a decision date on a decision that has not been taken, no consequence or no considered "
            + "option recorded, an option space with nothing CHOSEN on a decision that was taken, a "
            + "decision that addresses no requirement and affects no bounded context, and an ADR-n "
            + "named in the prose that this project does not hold or that no supersedes/relatedTo "
            + "edge backs. Suspicions, each a hint and not a defect: tracker references (#123), "
            + "address and port literals, status prose ('today', 'currently', 'not yet') in the "
            + "decision or a consequence, and two decisions with near-identical titles. Reads only - "
            + "it changes nothing, sets no status and refuses nothing. What it cannot decide is named "
            + "in its own output and stays with the reviewer: whether a record carries more than one "
            + "decision, whether two records contradict one another, and whether a consequence says "
            + "anything.", annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String check(
            final McpSyncRequestContext context,
            @McpToolParam(description = "BCP-47 language tag choosing which candidate of a "
                    + "multilingual field is checked; falls back to the project's configured default "
                    + "language. A pattern present only in a variant this run did not read is not "
                    + "found - check the other language separately.", required = false)
            final String displayLocale,
            @McpToolParam(description = AdrMcpTools.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final CheckReport report = checkAdrs.check(project.id(), effectiveDisplayLocale(project, displayLocale));
        // report.checkedCount() is the subset the check actually read - handed over so the count does
        // not re-read the whole decision graph behind this tool's back (kogn-io/arknet#359).
        return render(report, countSkippedAdrs.skippedCount(project.id(), report.checkedCount()));
    }

    /**
     * Renders one report: a headline, the two severity classes as separate blocks, and always the
     * list of what was not checked - an empty result without that list reads as "reviewed", which is
     * the one misreading this tool must not invite (kogn-io/arknet#387).
     */
    private static String render(final CheckReport report, final int skipped) {
        final StringBuilder rendered = new StringBuilder(headline(report));
        if (skipped > 0) {
            rendered.append("\n").append(AdrMcpTools.skippedNote(skipped));
        }
        appendFindings(rendered, FACTS_CAPTION, report.facts());
        appendFindings(rendered, SUSPICIONS_CAPTION, report.suspicions());
        rendered.append("\n\n").append(NOT_CHECKED_CAPTION);
        for (final String entry : report.notChecked()) {
            rendered.append("\n- ").append(entry);
        }
        return rendered.toString();
    }

    private static String headline(final CheckReport report) {
        if (report.checkedCount() == 0) {
            return "adr_check: no decisions recorded.";
        }
        final String checked = report.checkedCount() + (report.checkedCount() == 1 ? " decision" : " decisions");
        if (report.findings().isEmpty()) {
            return "adr_check: " + checked + " checked, nothing found.";
        }
        return "adr_check: " + checked + " checked, " + report.facts().size() + " fact(s), "
                + report.suspicions().size() + " suspicion(s).";
    }

    private static void appendFindings(final StringBuilder rendered, final String caption,
            final List<Finding> findings) {
        if (findings.isEmpty()) {
            return;
        }
        rendered.append("\n\n").append(caption);
        for (final Finding finding : findings) {
            rendered.append("\n").append(line(finding));
        }
    }

    /**
     * One finding as one line: the decision, what was found, and - in brackets - where it sits and
     * the literal that triggered it, unrewritten so a reader can search for it.
     */
    private static String line(final Finding finding) {
        final StringBuilder line = new StringBuilder("- ").append(finding.code().value()).append(": ")
                .append(finding.rule().label());
        if (finding.field() == null && finding.evidence() == null) {
            return line.toString();
        }
        line.append(" (");
        if (finding.field() != null) {
            line.append(finding.field());
            if (finding.evidence() != null) {
                line.append(": ");
            }
        }
        if (finding.evidence() != null) {
            line.append(finding.evidence());
        }
        return line.append(")").toString();
    }

    /** The same anchor resolution every other ADR tool does: explicit argument first, header second. */
    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }

    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = blankToNull(projectAnchor);
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }
}
