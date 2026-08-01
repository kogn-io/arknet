// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Builds the report's bounded-context cards from the bounded-context context's read in-port.
 *
 * <p>Shows the domain vision statement as prose and the strategic classification (core /
 * supporting / generic) as a badge. The context's ubiquitous language is marked up inside the
 * vision statement itself through {@link Glossary} - a term the context links to reads as a
 * link where it is used, a glossary word the vision names without an
 * {@code arkddd:ubiquitousLanguageTerm} edge as a gap. Only linked terms the vision never names
 * remain as chips; see {@link RequirementCards} for the same reasoning at length.</p>
 */
public final class BoundedContextCards {

    /** The section title, shared with {@link ModelViews}' failure message for this section. */
    public static final String SECTION_TITLE = "Bounded Contexts";

    private final ListBoundedContexts contexts;

    /**
     * @param contexts the bounded-context context's list in-port
     */
    public BoundedContextCards(final ListBoundedContexts contexts) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
    }

    /**
     * @param projectId the project to read
     * @param glossary    the project's glossary, for labelling and marking up references
     * @return the bounded-context section, ordered by business code
     */
    public ModelSection section(final ProjectId projectId, final Glossary glossary) {
        Objects.requireNonNull(glossary, "glossary");
        final List<ModelCard> cards = contexts.list(projectId).stream()
                .sorted(Comparator.comparing(context -> context.code().value()))
                .map(context -> card(context, glossary))
                .toList();
        return new ModelSection(SECTION_TITLE, "bounded-contexts",
                "the strategic model boundaries and the language inside each", cards);
    }

    private static ModelCard card(final BoundedContext context, final Glossary glossary) {
        final List<Badge> badges = new ArrayList<>();
        if (context.subdomain() != null) {
            badges.add(new Badge(Badge.Kind.Known.SUBDOMAIN, Labels.humanise(context.subdomain().name())));
        }

        final Set<ResourceId> linked = context.usesTerms().stream()
                .map(TermRef::value)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        final List<Block> blocks = new ArrayList<>();
        blocks.add(new Block.Prose("Domain vision", glossary.markUp(context.domainVision(), linked)));
        if (context.ownedBy() != null) {
            blocks.add(Block.Prose.plain("Owned by", context.ownedBy()));
        }
        UnmentionedTerms.addTo(blocks, linked, glossary, List.of(context.domainVision()),
                "Ubiquitous language", "not named in the vision");
        return new ModelCard(context.code().value(), context.name(), context.id().value().value(), badges, blocks);
    }
}
