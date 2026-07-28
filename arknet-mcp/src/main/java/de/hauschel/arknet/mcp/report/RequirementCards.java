// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;

/**
 * Builds the report's requirement cards from the requirements context's read in-port.
 *
 * <p>Type, status and MoSCoW priority become badges rather than rows of vocabulary IRIs; the
 * acceptance criteria - the part a reviewer actually checks against - become a list instead of
 * {@code n} repeated {@code arkreq:acceptanceCriterion} literals. Glossary references are
 * resolved to their business codes through the borrowed {@link ResolveTerms} port (ADR-008),
 * batched across every requirement in one call.</p>
 */
public final class RequirementCards {

    private final ListRequirements requirements;
    private final ResolveTerms terms;

    /**
     * @param requirements the requirements context's list in-port
     * @param terms        borrowed for glossary display codes
     */
    public RequirementCards(final ListRequirements requirements, final ResolveTerms terms) {
        this.requirements = Objects.requireNonNull(requirements, "requirements");
        this.terms = Objects.requireNonNull(terms, "terms");
    }

    /**
     * @param workspaceId the workspace to read
     * @return the requirements section, ordered by business code
     */
    public ModelSection section(final WorkspaceId workspaceId) {
        final List<Requirement> all = requirements.list(workspaceId);
        final Map<ResourceId, ResolvedTerm> resolved = resolveTerms(workspaceId, all);
        final List<ModelCard> cards = all.stream()
                .sorted(Comparator.comparing(requirement -> requirement.code().value()))
                .map(requirement -> card(requirement, resolved))
                .toList();
        return new ModelSection("Requirements", "requirements",
                "what the system shall do, and what counts as done", cards);
    }

    private static ModelCard card(final Requirement requirement, final Map<ResourceId, ResolvedTerm> resolved) {
        final List<Badge> badges = new ArrayList<>();
        badges.add(new Badge("type", requirement.type().idPrefix()));
        badges.add(new Badge("status", Labels.humanise(requirement.status().name())));
        if (requirement.priority() != null) {
            badges.add(new Badge("priority", Labels.humanise(requirement.priority().name())));
        }

        final List<Block> blocks = new ArrayList<>();
        blocks.add(new Block.Prose("Description", requirement.description()));
        blocks.add(new Block.Bullets("Acceptance criteria", requirement.acceptanceCriteria()));
        if (requirement.qualityCategory() != null) {
            blocks.add(new Block.Prose("Quality category", requirement.qualityCategory()));
        }
        if (requirement.motivatedBy() != null) {
            blocks.add(new Block.Refs("Motivated by",
                    List.of(new Ref(localName(requirement.motivatedBy()), requirement.motivatedBy()))));
        }
        if (!requirement.usesTerms().isEmpty()) {
            blocks.add(new Block.Refs("Uses terms",
                    requirement.usesTerms().stream().map(ref -> termRef(ref, resolved)).toList()));
        }
        return new ModelCard(requirement.code().value(), requirement.title(),
                requirement.id().value().value(), badges, blocks);
    }

    private static Ref termRef(final TermRef ref, final Map<ResourceId, ResolvedTerm> resolved) {
        final ResolvedTerm term = resolved.get(ref.value());
        return new Ref(term != null ? term.code().value() : ref.value().value(), ref.value().value());
    }

    /**
     * {@code arkreq:motivatedBy} points at an {@code arkreq:Goal} for which no aggregate exists
     * yet, so the requirement carries it as a plain IRI. Its local name is the best label
     * available; the IRI still links the reference to a card if the goal happens to be in the
     * store.
     */
    private static String localName(final String iri) {
        final int cut = Math.max(iri.lastIndexOf('#'), iri.lastIndexOf('/'));
        return cut >= 0 && cut + 1 < iri.length() ? iri.substring(cut + 1) : iri;
    }

    /** Resolves every glossary reference of every requirement in one call; see {@link UseCaseCards}. */
    private Map<ResourceId, ResolvedTerm> resolveTerms(final WorkspaceId workspaceId, final List<Requirement> all) {
        final ResourceId[] ids = all.stream()
                .flatMap(requirement -> requirement.usesTerms().stream())
                .map(TermRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return terms.getById(workspaceId, ids).stream()
                .collect(Collectors.toMap(ResolvedTerm::id, t -> t, (first, second) -> first));
    }
}
