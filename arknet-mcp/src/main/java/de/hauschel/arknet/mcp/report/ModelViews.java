// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Assembles the report's per-bounded-context sections by asking each context's read in-ports
 * for its own model elements.
 *
 * <p><strong>Why this exists.</strong> {@code store_overview}'s data path stays what ADR-006
 * made it - one generic {@code SELECT ?s ?p ?o} - but the human-facing HTML no longer renders
 * that flat result as its skeleton. A use case read as triples is unreadable as a use case:
 * its flow is {@code n} opaque {@code arkreq:Step} subjects ordered by an
 * {@code arkreq:position} literal, its actors and realised requirements further opaque IRIs.
 * The context that wrote it already knows how to read it back; this class asks it, rather than
 * re-deriving the answer in the composition root. See the ADR-006 addendum.</p>
 *
 * <p><strong>Never fails the tool.</strong> The report gained a dependency on four contexts'
 * read paths, and {@code store_overview} is the very tool a user reaches for when they suspect
 * something is wrong with the store. A section whose in-port throws is therefore dropped with
 * a recorded failure instead of taking the whole response down - and because the raw view
 * covers every resource no card was built for, the dropped section's resources still appear,
 * just unstyled. Same reasoning as {@code StoreReportTools#writeReportLine}.</p>
 */
public final class ModelViews {

    private final UseCaseCards useCases;
    private final RequirementCards requirements;
    private final TermCards terms;
    private final BoundedContextCards boundedContexts;

    /**
     * @param useCases        builds the use-case section
     * @param requirements    builds the requirements section
     * @param terms           builds the glossary section
     * @param boundedContexts builds the bounded-context section
     */
    public ModelViews(
            final UseCaseCards useCases,
            final RequirementCards requirements,
            final TermCards terms,
            final BoundedContextCards boundedContexts) {
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.terms = Objects.requireNonNull(terms, "terms");
        this.boundedContexts = Objects.requireNonNull(boundedContexts, "boundedContexts");
    }

    /**
     * The assembled sections plus whatever went wrong assembling them.
     *
     * @param sections the non-empty sections, in reading order; never {@code null}
     * @param failures one human-readable line per section that could not be read; never
     *                 {@code null}, and rendered as a visible warning rather than swallowed -
     *                 a silently missing section would read as "the store is empty"
     */
    public record Views(List<ModelSection> sections, List<String> failures) {
        public Views {
            sections = sections == null ? List.of() : List.copyOf(sections);
            failures = failures == null ? List.of() : List.copyOf(failures);
        }
    }

    /**
     * Reads every context and assembles its section.
     *
     * @param workspaceId the workspace to read
     * @return the sections that could be read and carry at least one card, plus the failures
     */
    public Views of(final WorkspaceId workspaceId) {
        final List<ModelSection> sections = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        collect(sections, failures, "Bounded Contexts", () -> boundedContexts.section(workspaceId));
        collect(sections, failures, "Requirements", () -> requirements.section(workspaceId));
        collect(sections, failures, "Use Cases", () -> useCases.section(workspaceId));
        collect(sections, failures, "Glossary", () -> terms.section(workspaceId));
        return new Views(sections, failures);
    }

    private static void collect(
            final List<ModelSection> sections,
            final List<String> failures,
            final String name,
            final Supplier<ModelSection> builder) {
        final ModelSection section;
        try {
            section = builder.get();
        } catch (final RuntimeException e) {
            failures.add(name + ": could not be read (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + ") - its resources appear under \"Other resources\" below.");
            return;
        }
        if (!section.isEmpty()) {
            sections.add(section);
        }
    }
}
