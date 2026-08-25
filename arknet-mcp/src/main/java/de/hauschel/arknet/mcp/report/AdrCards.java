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
        blocks.add(Block.Prose.plain("Context", adr.context()));
        blocks.add(Block.Prose.plain("Decision", adr.decision()));
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
     * Renders {@code consequences} (kogn-io/arknet#357) as a single {@link Block.Bullets} list, one
     * item per structured consequence - the existing bullets mechanism already carries exactly one
     * list per card without a language-switch regression (see {@link HtmlReportRenderer}'s
     * {@code LangSources} javadoc). {@link Block.Bullets#plain} numbers items by list order, which
     * matches this list's own {@code arknet:position} order. A legacy-only decision (the flat
     * {@code arkarch:adrConsequences} literal, synthesised by the out-adapter as one {@code NEUTRAL}
     * entry) renders identically - a single bullet - so there is no visible regression against the
     * pre-#357 report.
     *
     * <p><strong>Not yet a second list.</strong> Rendering considered options as their own bullets
     * list too is deliberately deferred to issue #358, which reworks {@code HtmlReportRenderer}'s
     * language-switch to support more than one {@link Block.Bullets} per card; see
     * {@link #addConsideredOptions} for the conservative {@link Block.Prose} it uses instead in the
     * meantime.</p>
     */
    private static void addConsequences(final List<Block> blocks, final List<Consequence> consequences) {
        if (consequences.isEmpty()) {
            return;
        }
        final List<String> lines = consequences.stream()
                .map(c -> "[%s] %s".formatted(c.type(), c.statement()))
                .toList();
        blocks.add(Block.Bullets.plain("Consequences", lines));
    }

    /**
     * Renders {@code consideredOptions} (kogn-io/arknet#357) as one merged {@link Block.Prose}
     * block, exactly as the pre-#357 flat {@code arkarch:adrAlternatives} literal was rendered -
     * deliberately conservative rather than a second {@link Block.Bullets} list: see
     * {@link #addConsequences}'s javadoc for why a second list is issue #358's job, not this one's.
     * Unlike the pre-#357 literal (rejected options only), this now also shows the {@code CHOSEN}
     * option, since {@link ConsideredOption} makes the MADR "Decision Outcome" representable at
     * all.
     */
    private static void addConsideredOptions(final List<Block> blocks, final List<ConsideredOption> options) {
        if (options.isEmpty()) {
            return;
        }
        final String merged = options.stream()
                .map(o -> "[%s] %s - %s".formatted(o.outcome() == null ? "?" : o.outcome(), o.name(), o.rationale()))
                .collect(Collectors.joining("\n"));
        blocks.add(Block.Prose.plain("Considered options", merged));
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
