// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.TermRef;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;

/**
 * Builds the report's bounded-context cards from the bounded-context context's read in-port.
 *
 * <p>Shows the domain vision statement as prose, the strategic classification (core /
 * supporting / generic) as a badge, and the context's ubiquitous language as linked chips into
 * the glossary section - resolved through the borrowed {@link ResolveTerms} port (ADR-008) in
 * one batched call.</p>
 */
public final class BoundedContextCards {

    private final ListBoundedContexts contexts;
    private final ResolveTerms terms;

    /**
     * @param contexts the bounded-context context's list in-port
     * @param terms    borrowed for glossary display codes
     */
    public BoundedContextCards(final ListBoundedContexts contexts, final ResolveTerms terms) {
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.terms = Objects.requireNonNull(terms, "terms");
    }

    /**
     * @param workspaceId the workspace to read
     * @return the bounded-context section, ordered by business code
     */
    public ModelSection section(final WorkspaceId workspaceId) {
        final List<BoundedContext> all = contexts.list(workspaceId);
        final Map<ResourceId, ResolvedTerm> resolved = resolveTerms(workspaceId, all);
        final List<ModelCard> cards = all.stream()
                .sorted(Comparator.comparing(context -> context.code().value()))
                .map(context -> card(context, resolved))
                .toList();
        return new ModelSection("Bounded Contexts", "bounded-contexts",
                "the strategic model boundaries and the language inside each", cards);
    }

    private static ModelCard card(final BoundedContext context, final Map<ResourceId, ResolvedTerm> resolved) {
        final List<Badge> badges = new ArrayList<>();
        if (context.subdomain() != null) {
            badges.add(new Badge("subdomain", Labels.humanise(context.subdomain().name())));
        }
        final List<Block> blocks = new ArrayList<>();
        blocks.add(new Block.Prose("Domain vision", context.domainVision()));
        if (context.ownedBy() != null) {
            blocks.add(new Block.Prose("Owned by", context.ownedBy()));
        }
        if (!context.usesTerms().isEmpty()) {
            blocks.add(new Block.Refs("Ubiquitous language",
                    context.usesTerms().stream().map(ref -> termRef(ref, resolved)).toList()));
        }
        return new ModelCard(context.code().value(), context.name(), context.id().value().value(), badges, blocks);
    }

    private static Ref termRef(final TermRef ref, final Map<ResourceId, ResolvedTerm> resolved) {
        final ResolvedTerm term = resolved.get(ref.value());
        return new Ref(term != null ? term.code().value() : ref.value().value(), ref.value().value());
    }

    /** Resolves every glossary reference of every bounded context in one call; see {@link UseCaseCards}. */
    private Map<ResourceId, ResolvedTerm> resolveTerms(
            final WorkspaceId workspaceId, final List<BoundedContext> all) {
        final ResourceId[] ids = all.stream()
                .flatMap(context -> context.usesTerms().stream())
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
