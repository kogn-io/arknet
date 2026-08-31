// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.application.port.in.ListTerms;

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
 * <p><strong>Never fails the tool.</strong> The report gained a dependency on five contexts'
 * read paths, and {@code store_overview} is the very tool a user reaches for when they suspect
 * something is wrong with the store. A section whose in-port throws is therefore dropped with
 * a recorded failure instead of taking the whole response down - and because the raw view
 * covers every resource no card was built for, the dropped section's resources still appear,
 * just unstyled. Same reasoning as {@code StoreReportTools#writeReportLine}.</p>
 */
public final class ModelViews {

    private final UseCaseCards useCases;
    private final RequirementCards requirements;
    private final ConstraintCards constraints;
    private final ListTerms terms;
    private final BoundedContextCards boundedContexts;
    private final AdrCards adrs;
    private final ActorCards actors;

    /**
     * @param terms           the ubiquitous-language context's list in-port, read once into the
     *                        {@link Glossary} every other section is rendered against
     * @param useCases        builds the use-case section
     * @param requirements    builds the requirements section
     * @param constraints     builds the constraint section (issue #390)
     * @param boundedContexts builds the bounded-context section
     * @param adrs            builds the architecture-decisions section
     * @param actors          builds the actor section
     */
    public ModelViews(
            final ListTerms terms,
            final UseCaseCards useCases,
            final RequirementCards requirements,
            final ConstraintCards constraints,
            final BoundedContextCards boundedContexts,
            final AdrCards adrs,
            final ActorCards actors) {
        this.terms = Objects.requireNonNull(terms, "terms");
        this.useCases = Objects.requireNonNull(useCases, "useCases");
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.constraints = Objects.requireNonNull(constraints, "constraints");
        this.boundedContexts = Objects.requireNonNull(boundedContexts, "boundedContexts");
        this.adrs = Objects.requireNonNull(adrs, "adrs");
        this.actors = Objects.requireNonNull(actors, "actors");
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
     * @param projectId     the project to read
     * @param displayLocale the resolved project's own configured default display language (BCP-47
     *                      tag), or {@code null} if it has none - passed straight through to
     *                      {@code term_list}'s own port so the report's glossary section honours
     *                      the same project default {@code orphan_check}/{@code trace_matrix}
     *                      already do (issue #276), instead of always the process-wide default
     * @return the sections that could be read and carry at least one card - with cross-section
     *         code references in their prose marked up ({@link CodeReferences}, which needs every
     *         section in hand and therefore runs here rather than in a card builder) - plus the
     *         failures
     */
    public Views of(final ProjectId projectId, final String displayLocale) {
        final List<ModelSection> sections = new ArrayList<>();
        final List<String> failures = new ArrayList<>();
        final Glossary glossary = glossary(projectId, displayLocale, failures);
        collect(sections, failures, BoundedContextCards.SECTION_TITLE,
                () -> boundedContexts.section(projectId, glossary));
        collect(sections, failures, RequirementCards.SECTION_TITLE,
                () -> requirements.section(projectId, displayLocale, glossary));
        collect(sections, failures, ConstraintCards.SECTION_TITLE,
                () -> constraints.section(projectId, displayLocale));
        collect(sections, failures, UseCaseCards.SECTION_TITLE,
                () -> useCases.section(projectId, displayLocale, glossary));
        collect(sections, failures, AdrCards.SECTION_TITLE, () -> adrs.section(projectId, glossary));
        collect(sections, failures, ActorCards.SECTION_TITLE, () -> actors.section(projectId));
        collect(sections, failures, TermCards.SECTION_TITLE, () -> TermCards.section(glossary));
        return new Views(CodeReferences.markUp(sections), failures);
    }

    /**
     * Reads the glossary once for the whole report: it is the glossary section, the label behind
     * every term chip, and what the other sections' prose is matched against.
     *
     * <p>Being one read makes it a single point of failure, so it fails the same way a section
     * does - loudly, and only for itself. An unreadable glossary leaves every other section
     * standing, with chips falling back to bare identities and no prose marked up; the warning
     * says so, because chips that suddenly read as IRIs would otherwise look like a modelling
     * mistake rather than a read error.</p>
     */
    private Glossary glossary(final ProjectId projectId, final String displayLocale, final List<String> failures) {
        try {
            return Glossary.of(terms.list(projectId, displayLocale));
        } catch (final RuntimeException e) {
            failures.add("Glossary: could not be read (" + e.getClass().getSimpleName() + ": " + e.getMessage()
                    + ") - its terms appear under \"Other resources\" below, and references to them show as"
                    + " identities rather than labels.");
            return Glossary.empty();
        }
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
