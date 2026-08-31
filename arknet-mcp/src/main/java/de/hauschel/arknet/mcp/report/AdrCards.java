// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import de.hauschel.arknet.adr.application.port.in.AdrDetail;
import de.hauschel.arknet.adr.application.port.in.ListAdrs;
import de.hauschel.arknet.adr.domain.Adr;
import de.hauschel.arknet.adr.domain.AdrCode;
import de.hauschel.arknet.adr.domain.BoundedContextRef;
import de.hauschel.arknet.adr.domain.Consequence;
import de.hauschel.arknet.adr.domain.ConsideredOption;
import de.hauschel.arknet.adr.domain.OptionOutcome;
import de.hauschel.arknet.adr.domain.RequirementRef;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.ResolveBoundedContexts.ResolvedBoundedContext;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements;
import de.hauschel.arknet.req.application.port.in.ResolveRequirements.ResolvedRequirement;

/**
 * Builds the report's architecture-decision cards from the ADR context's read in-port.
 *
 * <p>Status becomes a badge; context, decision, consequences and alternatives - the MADR shape
 * this component records a decision in - become their own prose blocks rather than four
 * indistinguishable {@code arkarch:adr*} literals. {@link ListAdrs} already inverts the
 * self-referential {@code arkarch:supersededBy} edge (kogn-io/arknet#357: written on the superseded
 * decision, folding in any pre-#357 legacy {@code arkarch:supersedes} edge too) into both directions
 * and merges the equally self-referential, symmetric {@code arkarch:relatedTo} edge into one list
 * ({@link AdrDetail}), so this class only has to turn the resolved {@link AdrCode}s it hands back
 * into {@link Ref}s.</p>
 *
 * <p><strong>Two borrowed ports, one relation resolved locally.</strong> {@code
 * addressesRequirement} and {@code affectsContext} point into neighbour hexagons, so their
 * business codes are rendered through the borrowed {@link ResolveRequirements}/
 * {@link ResolveBoundedContexts} ports (ADR-008, the same borrowing {@code uc_get}/{@code
 * adr_get} already do) - batched once per report across every ADR, never per card.
 * {@code supersedes}/{@code supersededBy}/{@code relatedTo} point back into this hexagon's own
 * resources, and {@link AdrDetail} already carries their codes; only the target's subject id, which a
 * {@link Ref} needs to link to that ADR's own card, is missing, so this class builds an
 * in-memory code-to-id lookup from the same list of decisions it renders. A code named there
 * that no longer resolves (deleted store-first, ADR-005) falls back to itself rather than being
 * dropped, the same "never drop a reference" stance the two borrowed ports already take.</p>
 *
 * <p><strong>No marked-up prose here.</strong> An ADR has no glossary edge - see
 * {@link UseCaseCards}'s Javadoc for the same reasoning - so its text arrives unmarked-up.</p>
 */
public final class AdrCards {

    /** The section title, shared with {@link ModelViews}' failure message for this section. */
    public static final String SECTION_TITLE = "Architecture Decisions";

    /**
     * The consequences block's label, shared with {@link HtmlReportRenderer} - the key by which
     * it finds this block's own positioned sub-resources (issue #382), the same role {@link
     * UseCaseCards#EXTENSIONS_LABEL}/{@link RequirementCards#ACCEPTANCE_CRITERIA_LABEL} already
     * play.
     */
    public static final String CONSEQUENCES_LABEL = "Consequences";

    /** The considered-options block's label, shared with {@link HtmlReportRenderer} likewise. */
    public static final String CONSIDERED_OPTIONS_LABEL = "Considered options";

    private final ListAdrs adrs;
    private final ResolveRequirements resolveRequirements;
    private final ResolveBoundedContexts resolveBoundedContexts;

    /**
     * @param adrs                   the ADR context's list in-port
     * @param resolveRequirements    borrowed for addressed-requirement display codes
     * @param resolveBoundedContexts borrowed for affected-context display codes
     */
    public AdrCards(
            final ListAdrs adrs,
            final ResolveRequirements resolveRequirements,
            final ResolveBoundedContexts resolveBoundedContexts) {
        this.adrs = Objects.requireNonNull(adrs, "adrs");
        this.resolveRequirements = Objects.requireNonNull(resolveRequirements, "resolveRequirements");
        this.resolveBoundedContexts = Objects.requireNonNull(resolveBoundedContexts, "resolveBoundedContexts");
    }

    /**
     * @param projectId the project to read
     * @param glossary  the project's glossary; accepted for the signature every section shares,
     *                  but never consulted - an ADR's prose has no glossary edge to mark up against
     * @return the architecture-decisions section, ordered by business code
     */
    public ModelSection section(final ProjectId projectId, final Glossary glossary) {
        Objects.requireNonNull(glossary, "glossary");
        final List<AdrDetail> all = adrs.list(projectId, null);
        final Map<ResourceId, ResolvedRequirement> requirements = resolveRequirements(projectId, all);
        final Map<ResourceId, ResolvedBoundedContext> contexts = resolveContexts(projectId, all);
        final Map<AdrCode, String> idsByCode = all.stream()
                .collect(Collectors.toMap(detail -> detail.adr().code(),
                        detail -> detail.adr().id().value().value(), (first, second) -> first));
        final List<ModelCard> cards = all.stream()
                .sorted(Comparator.comparing(detail -> detail.adr().code().value(), BusinessCodes.ORDER))
                .map(detail -> card(detail, requirements, contexts, idsByCode))
                .toList();
        return new ModelSection(SECTION_TITLE, "architecture-decisions",
                "decisions made, their status, what they replace and what they relate to", cards);
    }

    private static ModelCard card(
            final AdrDetail detail,
            final Map<ResourceId, ResolvedRequirement> requirements,
            final Map<ResourceId, ResolvedBoundedContext> contexts,
            final Map<AdrCode, String> idsByCode) {
        final Adr adr = detail.adr();
        final List<Badge> badges = List.of(new Badge(Badge.Kind.Known.STATUS, Labels.humanise(adr.status().name())));

        final List<Block> blocks = new ArrayList<>();
        blocks.add(ProseMarkdown.prose("Context", adr.context(), RichText::plain));
        blocks.add(ProseMarkdown.prose("Decision", adr.decision(), RichText::plain));
        addConsequences(blocks, adr.consequences());
        addConsideredOptions(blocks, adr.consideredOptions());
        if (adr.decisionDate() != null) {
            blocks.add(Block.Prose.plain("Decision date", adr.decisionDate().toString()));
        }
        if (!adr.addressesRequirements().isEmpty()) {
            blocks.add(new Block.Refs("Addresses requirements", adr.addressesRequirements().stream()
                    .map(RequirementRef::value)
                    .map(id -> requirementRef(id, requirements))
                    .toList()));
        }
        if (!adr.affectsContexts().isEmpty()) {
            blocks.add(new Block.Refs("Affects contexts", adr.affectsContexts().stream()
                    .map(BoundedContextRef::value)
                    .map(id -> boundedContextRef(id, contexts))
                    .toList()));
        }
        if (!detail.supersedes().isEmpty()) {
            blocks.add(new Block.Refs("Supersedes", detail.supersedes().stream()
                    .map(code -> codeRef(code, idsByCode))
                    .toList()));
        }
        if (!detail.supersededBy().isEmpty()) {
            blocks.add(new Block.Refs("Superseded by", detail.supersededBy().stream()
                    .map(code -> codeRef(code, idsByCode))
                    .toList()));
        }
        if (!detail.relatedTo().isEmpty()) {
            blocks.add(new Block.Refs("Related to", detail.relatedTo().stream()
                    .map(code -> codeRef(code, idsByCode))
                    .toList()));
        }
        return new ModelCard(adr.code().value(), adr.name(), adr.id().value().value(), badges, blocks);
    }

    private static Ref requirementRef(final ResourceId id, final Map<ResourceId, ResolvedRequirement> requirements) {
        final ResolvedRequirement resolved = requirements.get(id);
        return Ref.of(resolved != null ? resolved.code().value() : id.value(), id.value());
    }

    private static Ref boundedContextRef(
            final ResourceId id, final Map<ResourceId, ResolvedBoundedContext> contexts) {
        final ResolvedBoundedContext resolved = contexts.get(id);
        return Ref.of(resolved != null ? resolved.code().value() : id.value(), id.value());
    }

    private static Ref codeRef(final AdrCode code, final Map<AdrCode, String> idsByCode) {
        final String iri = idsByCode.get(code);
        return Ref.of(code.value(), iri != null ? iri : code.value());
    }

    /**
     * Renders {@code consequences} (kogn-io/arknet#357) as a {@link Block.Bullets} list, one item
     * per structured consequence, its {@link Consequence#type()} shown as a {@link Badge} rather
     * than folded into the text (issue #382 - {@code HtmlReportRenderer}'s language-switch, added
     * for {@link Block.Bullets} by issue #358, keys a list's positioned sub-resources by this
     * block's own label; see {@link #CONSEQUENCES_LABEL}). A legacy-only decision (the flat
     * {@code arkarch:adrConsequences} literal, synthesised by the out-adapter as one {@code NEUTRAL}
     * entry) renders identically - a single, badged bullet.
     */
    private static void addConsequences(final List<Block> blocks, final List<Consequence> consequences) {
        if (consequences.isEmpty()) {
            return;
        }
        final List<BulletItem> items = consequences.stream()
                .map(c -> new BulletItem(c.position(), ProseMarkdown.inline(c.statement(), RichText::plain),
                        new Badge(Badge.Kind.Known.CONSEQUENCE, Labels.humanise(c.type().name())), null))
                .toList();
        blocks.add(new Block.Bullets(CONSEQUENCES_LABEL, items));
    }

    /**
     * Renders {@code consideredOptions} (kogn-io/arknet#357) as a {@link Block.Bullets} list, one
     * item per option, mirroring {@link #addConsequences} now that issue #358 lets a card carry
     * more than one {@link Block.Bullets} list without losing its language switch. An option's
     * {@link ConsideredOption#outcome()} becomes a {@link Badge} and its {@link
     * ConsideredOption#name()} the item's {@code caption}, kept apart from {@link
     * ConsideredOption#rationale()} (the item {@code text}) rather than glued together with
     * {@code " - "} as the pre-#382 single merged {@link Block.Prose} block did - name and
     * rationale are separate fields on the resource, not one string. Unlike the pre-#357 flat
     * {@code arkarch:adrAlternatives} literal (rejected options only), this also shows the {@code
     * CHOSEN} option, since {@link ConsideredOption} makes the MADR "Decision Outcome"
     * representable at all. An outcome-less option (the out-adapter's legacy-literal fallback,
     * see {@link ConsideredOption#outcome()}) gets a neutral {@code Unclassified} badge instead of
     * one of the two real outcomes, since neither would be honest.
     */
    private static void addConsideredOptions(final List<Block> blocks, final List<ConsideredOption> options) {
        if (options.isEmpty()) {
            return;
        }
        final List<BulletItem> items = options.stream()
                .map(o -> new BulletItem(o.position(), ProseMarkdown.inline(o.rationale(), RichText::plain),
                        outcomeBadge(o.outcome()), o.name()))
                .toList();
        blocks.add(new Block.Bullets(CONSIDERED_OPTIONS_LABEL, items));
    }

    private static Badge outcomeBadge(final OptionOutcome outcome) {
        return outcome == null
                ? new Badge(new Badge.Kind.Custom("outcome"), "Unclassified")
                : new Badge(Badge.Kind.Known.OUTCOME, Labels.humanise(outcome.name()));
    }

    /**
     * Resolves every requirement addressed by any ADR in one call - the same batching-not-throwing
     * shape {@link UseCaseCards#resolveRequirements} uses, for the same reason.
     */
    private Map<ResourceId, ResolvedRequirement> resolveRequirements(
            final ProjectId projectId, final List<AdrDetail> all) {
        final ResourceId[] ids = all.stream()
                .flatMap(detail -> detail.adr().addressesRequirements().stream())
                .map(RequirementRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveRequirements.resolveExisting(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedRequirement::id, r -> r, (first, second) -> first));
    }

    /** Resolves every bounded context affected by any ADR in one call, same shape as above. */
    private Map<ResourceId, ResolvedBoundedContext> resolveContexts(
            final ProjectId projectId, final List<AdrDetail> all) {
        final ResourceId[] ids = all.stream()
                .flatMap(detail -> detail.adr().affectsContexts().stream())
                .map(BoundedContextRef::value)
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveBoundedContexts.resolveExisting(projectId, ids).stream()
                .collect(Collectors.toMap(ResolvedBoundedContext::id, r -> r, (first, second) -> first));
    }
}
