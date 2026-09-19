// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.adapter.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.bc.application.port.in.AddBoundedContext;
import de.hauschel.arknet.bc.application.port.in.AddBoundedContext.NewBoundedContext;
import de.hauschel.arknet.bc.application.port.in.BoundedContextDetail;
import de.hauschel.arknet.bc.application.port.in.DeleteBoundedContext;
import de.hauschel.arknet.bc.application.port.in.DescribeBoundedContextDisplayFallback;
import de.hauschel.arknet.bc.application.port.in.GetBoundedContext;
import de.hauschel.arknet.bc.application.port.in.LinkContext;
import de.hauschel.arknet.bc.application.port.in.LinkTerm;
import de.hauschel.arknet.bc.application.port.in.ListBoundedContexts;
import de.hauschel.arknet.bc.application.port.in.RelatedContext;
import de.hauschel.arknet.bc.application.port.in.ResolveLinkedTerms;
import de.hauschel.arknet.bc.application.port.in.ResolveLinkedTerms.LinkedTerm;
import de.hauschel.arknet.bc.application.port.in.UnlinkContext;
import de.hauschel.arknet.bc.application.port.in.UnlinkTerm;
import de.hauschel.arknet.bc.application.port.in.UpdateBoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.bc.domain.BoundedContextDisplayFallback;
import de.hauschel.arknet.bc.domain.ContextRelationship;
import de.hauschel.arknet.bc.domain.RelationshipType;
import de.hauschel.arknet.bc.domain.Subdomain;
import de.hauschel.arknet.kernel.LanguageTag;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.mcpsupport.StaleTranslationHint;
import de.hauschel.arknet.mcpsupport.ToolParameterDescriptions;
import de.hauschel.arknet.mcpsupport.WriteResponse;

/**
 * Driving (in) adapter of the bounded-context component: exposes the bounded-context use-cases as
 * MCP tools ({@code bc_add}, {@code bc_list}, {@code bc_get}, {@code bc_update},
 * {@code bc_link_term}, {@code bc_unlink_term}, {@code bc_link_context}, {@code bc_unlink_context},
 * {@code bc_delete}) and delegates each tool call to the corresponding in-port.
 *
 * <p>This adapter belongs to the bounded-context hexagon (symmetric to the out-adapter
 * {@code arknet-bounded-context-adapter-kogniordf}). Tools are declared Spring-AI-style via
 * {@link McpTool}/{@link McpToolParam} on plain methods - the tool name, description and JSON
 * input schema are derived from the annotations and method signature, not hand-written. This
 * adapter does <strong>not</strong> bootstrap an MCP server or wire any transport; that remains
 * the concern of the composition root (arknet-mcp).</p>
 *
 * <p><strong>Identity vs. code.</strong> Every tool takes a bounded-context identity as a plain
 * {@code String} - what a human types, e.g. {@code BC-1} - and maps it to a
 * {@link BoundedContextCode}, never to the opaque
 * {@link de.hauschel.arknet.bc.domain.BoundedContextId}. The identity itself is a store-internal
 * detail that never needs to cross the MCP boundary; responses render the code back to the
 * caller, not the underlying resource identity.</p>
 *
 * <p><strong>Language (kogn-io/arknet#520), mirroring {@code ConstraintMcpTools} exactly.</strong>
 * {@code bc_add}/{@code bc_update} take an optional {@code language}; {@code bc_get}/
 * {@code bc_list} take an optional {@code displayLocale}, with the same project-default fallback
 * and inline {@code [fallback: ...]} marking {@code bc_list} appends. No FR-10 label-equality
 * guard applies to {@code name}: a bounded context is nowhere referenced by its name (every edge
 * to it runs over {@link BoundedContextCode}), so a name free to differ per language breaks no
 * reference. {@code bc_update} corrects {@code name}/{@code domainVision} and, via the
 * {@code terms} tri-state (kogn-io/arknet#567), the linked glossary terms - not
 * {@code subdomain}/{@code ownedBy}/context relationships, which stay exactly as fixed since
 * {@code bc_add}.</p>
 *
 * <p><strong>Term display resolution.</strong> A bounded context carries a linked term's opaque
 * subject identity, not its business code - but a human who typed {@code TERM-1} into
 * {@code bc_link_term} expects to see {@code TERM-1} again, not a raw IRI they cannot re-type.
 * This adapter asks its own hexagon for that ({@link ResolveLinkedTerms}), which resolves it
 * through the same {@code TermLookup} out-port {@code bc_link_term}'s write path uses, read in
 * the opposite direction: one mechanism for every read of a neighbour (ADR-49), and no module of
 * this component depends on a module of the glossary component. {@link #format} always calls
 * {@link ResolveLinkedTerms#resolveLinkedTerms} exactly once per rendering, batched across every
 * identity involved; an id it could not resolve simply falls back to the bare IRI -
 * {@link #format} never throws and never drops a term.</p>
 *
 * <p><strong>What a writing answer says (kogn-io/arknet#597/#598/#600).</strong> Every writing
 * tool closes its answer with {@code project: <name>}, so a call whose {@code projectAnchor} was
 * forgotten shows which project it actually hit instead of landing silently in the session's one.
 * {@code bc_link_term}/{@code bc_unlink_term}/{@code bc_link_context}/{@code bc_unlink_context}
 * answer with a one-line confirmation of the edge rather than the whole resource - their caller
 * already holds both ends. {@code bc_update} keeps the full resource and its stale-translation
 * signal, preceded by a diff line for every list field that came out holding something else than
 * it held before; the diff is computed from the field's state before and after the write, never
 * from the request, because a wholesale {@code terms} list drops whatever it forgets to restate
 * and that loss is exactly what the request cannot show. All three shapes are rendered by
 * {@link WriteResponse}, so every bounded context's tools read the same.</p>
 *
 * <p><strong>Project (resolved per call).</strong> Every in-port takes a
 * {@link ProjectId} routing key. arknet-mcp runs as one shared server for every
 * project on the machine, so there is no single injected project any
 * more: each tool call resolves its own project from the request's anchor,
 * carried in the MCP transport context under {@link ProjectResolver#ANCHOR_KEY}.
 * The framework hands this adapter that context as an {@link McpSyncRequestContext}
 * parameter - a framework type, excluded from the generated tool input schema, so it is
 * not a caller-facing argument. The anchor is looked up in the project registry:
 * it arrives opaque, is matched whole against what was registered, and either hits exactly
 * one project or fails with an error message naming the possible remedies.</p>
 */
public final class BoundedContextMcpTools {

    /**
     * The prose markup this tool's free-text fields accept, appended to every writing tool's
     * description (issue #388).
     *
     * <p>It belongs on the tool, not only in the module docs: the writing agent reads the tool
     * schema and nothing else, which is exactly why the {@code white-space:pre-line} mechanism of
     * issue #385 was never used by anyone. The same sentence is repeated in each bounded
     * context's MCP adapter rather than shared, because these adapters deliberately have no
     * common module - a shared string is not reason enough to create one.</p>
     */
    private static final String PROSE_MARKUP = " Free-text fields accept a narrow Markdown subset:"
            + " **bold**, *italic*, `code`, lines starting with '- ' as a bullet list, and a blank line"
            + " for a new paragraph. Links, headings, tables and HTML are deliberately not interpreted -"
            + " a reference belongs in the model (an edge such as usesTerm), not in a hand-written link.";

    /**
     * The stale-translation signal, announced on every update tool that writes a multilingual
     * field (kogn-io/arknet#474). It belongs in the tool description for the same reason
     * {@link #PROSE_MARKUP} does: the writing agent reads the tool schema and nothing else, and a
     * signal it does not expect is a signal it does not act on.
     */
    private static final String STALE_TRANSLATION_NOTE = " If the project maintains several languages,"
            + " the answer names the fields that still carry a maintained language this call did not"
            + " write; repeat the call under each of those languages to keep the translations in step."
            + " A field that did not carry the written language yet is being translated, not corrected,"
            + " and is not reported.";

    /**
     * The model name of the edge {@code bc_link_term}/{@code bc_unlink_term} draw and
     * {@code bc_update}'s {@code terms} replaces - the local name of {@code arkddd:ubiquitousLanguageTerm},
     * the same vocabulary {@code store_check} and {@code StaleTranslationHint} name fields in. It
     * names the edge in both the short link confirmation and the diff line, so the two never drift
     * apart on what they are talking about.
     */
    private static final String TERM_EDGE = "ubiquitousLanguageTerm";

    private static final String NAME_FIELD = "name";
    private static final String DOMAIN_VISION_FIELD = "domainVision";

    /**
     * The multilingual fields {@code bc_update} can write, as {@code FieldLanguageLookup} keys -
     * the local names of the predicates behind them. {@code arknet-architecture-tests} reads this
     * list reflectively and holds it against the {@code sh:uniqueLang} properties the shipped
     * shapes declare for this resource, so a typo or a renamed predicate fails a build instead of
     * silently muting the signal for that field.
     */
    private static final List<String> MULTILINGUAL_FIELDS = List.of(NAME_FIELD, DOMAIN_VISION_FIELD);

    private final AddBoundedContext addBoundedContext;
    private final ListBoundedContexts listBoundedContexts;
    private final DescribeBoundedContextDisplayFallback describeBoundedContextDisplayFallback;
    private final GetBoundedContext getBoundedContext;
    private final UpdateBoundedContext updateBoundedContext;
    private final LinkTerm linkTerm;
    private final UnlinkTerm unlinkTerm;
    private final LinkContext linkContext;
    private final UnlinkContext unlinkContext;
    private final DeleteBoundedContext deleteBoundedContext;
    private final ResolveLinkedTerms resolveLinkedTerms;
    private final ProjectResolver projects;
    private final StaleTranslationHint staleTranslations;

    /**
     * Creates the adapter with its driving in-ports and the resolver that maps each call's
     * origin directory to a project.
     *
     * @param addBoundedContext   in-port backing {@code bc_add}
     * @param listBoundedContexts in-port backing {@code bc_list}
     * @param describeBoundedContextDisplayFallback in-port backing {@code bc_list}'s
     *                            fallback-visibility line (kogn-io/arknet#520)
     * @param getBoundedContext   in-port backing {@code bc_get}
     * @param updateBoundedContext in-port backing {@code bc_update} (kogn-io/arknet#520)
     * @param linkTerm            in-port backing {@code bc_link_term}
     * @param unlinkTerm          in-port backing {@code bc_unlink_term} (kogn-io/arknet#598)
     * @param linkContext         in-port backing {@code bc_link_context}
     * @param unlinkContext       in-port backing {@code bc_unlink_context} (kogn-io/arknet#565)
     * @param deleteBoundedContext in-port backing {@code bc_delete} (kogn-io/arknet#566)
     * @param resolveLinkedTerms  own in-port used only to render a linked term's business code
     *                            instead of its bare IRI (kogn-io/arknet#441)
     * @param projects          resolves each call's target project from its origin directory
     * @param staleTranslations   renders {@code bc_update}'s stale-translation signal
     *                            (kogn-io/arknet#474)
     */
    public BoundedContextMcpTools(
            final AddBoundedContext addBoundedContext,
            final ListBoundedContexts listBoundedContexts,
            final DescribeBoundedContextDisplayFallback describeBoundedContextDisplayFallback,
            final GetBoundedContext getBoundedContext,
            final UpdateBoundedContext updateBoundedContext,
            final LinkTerm linkTerm,
            final UnlinkTerm unlinkTerm,
            final LinkContext linkContext,
            final UnlinkContext unlinkContext,
            final DeleteBoundedContext deleteBoundedContext,
            final ResolveLinkedTerms resolveLinkedTerms,
            final ProjectResolver projects,
            final StaleTranslationHint staleTranslations) {
        this.addBoundedContext = Objects.requireNonNull(addBoundedContext, "addBoundedContext");
        this.listBoundedContexts = Objects.requireNonNull(listBoundedContexts, "listBoundedContexts");
        this.describeBoundedContextDisplayFallback = Objects.requireNonNull(
                describeBoundedContextDisplayFallback, "describeBoundedContextDisplayFallback");
        this.getBoundedContext = Objects.requireNonNull(getBoundedContext, "getBoundedContext");
        this.updateBoundedContext = Objects.requireNonNull(updateBoundedContext, "updateBoundedContext");
        this.linkTerm = Objects.requireNonNull(linkTerm, "linkTerm");
        this.unlinkTerm = Objects.requireNonNull(unlinkTerm, "unlinkTerm");
        this.linkContext = Objects.requireNonNull(linkContext, "linkContext");
        this.unlinkContext = Objects.requireNonNull(unlinkContext, "unlinkContext");
        this.deleteBoundedContext = Objects.requireNonNull(deleteBoundedContext, "deleteBoundedContext");
        this.resolveLinkedTerms = Objects.requireNonNull(resolveLinkedTerms, "resolveLinkedTerms");
        this.projects = Objects.requireNonNull(projects, "projects");
        this.staleTranslations = Objects.requireNonNull(staleTranslations, "staleTranslations");
    }

    /**
     * Extracts the calling client's project anchor from the per-call transport context - the value
     * the server's context extractor placed there off the request header. Null-tolerant
     * on every hop: a call without a context, without a transport context, or without the key
     * resolves to {@code null}, which is a caller error rather than a route to a default.
     */
    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        final McpTransportContext transport = context.transportContext();
        final Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }

    /**
     * Resolves the project this call targets: the explicit {@code projectAnchor} parameter if the
     * caller supplied one, otherwise the anchor its transport carried; both delivery paths are open
     * to every MCP client. Neither present is a caller error; there is no default project and no
     * fallback to a server-side working directory.
     */
    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        final String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    // --- Tools: Spring-AI-style, delegate to the in-ports ----------------------

    @McpTool(name = "bc_add", description = "Register a new DDD bounded context (an explicit "
            + "semantic boundary within which a domain model is consistent)." + PROSE_MARKUP)
    public String add(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The context's human-readable name, e.g. OrderManagement")
            final String name,
            @McpToolParam(description = "One sentence stating what this context does and why it exists "
                    + "(min. 10 characters)")
            final String domainVision,
            @McpToolParam(description = "Strategic subdomain classification (optional): CORE_DOMAIN, "
                    + "SUPPORTING_DOMAIN or GENERIC_DOMAIN", required = false)
            final String subdomain,
            @McpToolParam(description = "Owning team name (optional)", required = false)
            final String ownedBy,
            @McpToolParam(description = ToolParameterDescriptions.LANGUAGE_DESCRIPTION, required = false)
            final String language,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final Subdomain subdomainValue = blankToNull(subdomain) == null
                ? null
                : Subdomain.valueOf(subdomain.trim());
        final BoundedContext created = addBoundedContext.add(project.id(),
                new NewBoundedContext(name, domainVision, subdomainValue, blankToNull(ownedBy),
                        blankToNull(language)),
                project.defaultLanguage());
        return WriteResponse.withProject(format(project.id(), created), project);
    }

    @McpTool(name = "bc_list", description = "List all managed bounded contexts. Every context relationship "
            + "a context carries (bc_link_context) is shown inline, e.g. "
            + "'[upstream of: BC-1 (PUBLISHED_LANGUAGE)] [downstream of: BC-5 (CONFORMIST)]'. A context "
            + "shown under a fallen-back language (its name/domainVision is missing in the requested/"
            + "project-default language) carries an inline [fallback: ...] tag naming the language "
            + "actually shown - see displayLocale.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String list(
            final McpSyncRequestContext context,
            @McpToolParam(description = ToolParameterDescriptions.DISPLAY_LOCALE_DESCRIPTION, required = false)
            final String displayLocale,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final ProjectId projectId = project.id();
        final String effective = effectiveDisplayLocale(project, displayLocale);
        final List<BoundedContextDetail> all = listBoundedContexts.list(projectId, effective);
        if (all.isEmpty()) {
            return "(no bounded contexts)";
        }
        // One batch resolution across every context's linked terms, not one per context.
        final List<BoundedContext> contexts = all.stream().map(BoundedContextDetail::context).toList();
        final Map<ResourceId, TermCode> termsById = resolveTermsFor(projectId, contexts);
        final Map<BoundedContextCode, BoundedContextDisplayFallback> fallbacks =
                describeBoundedContextDisplayFallback.describe(projectId, effective);
        return all.stream()
                .map(detail -> format(detail.context(), termsById) + relationshipsSuffix(detail.relationships())
                        + fallbackSuffix(fallbacks.get(detail.context().code())))
                .reduce((a, b) -> a + "\n" + b).orElse("(no bounded contexts)");
    }

    @McpTool(name = "bc_get", description = "Fetch a single bounded context by its identity (e.g. BC-1). "
            + "Every context relationship it carries (bc_link_context) is shown inline, e.g. "
            + "'[upstream of: BC-1 (PUBLISHED_LANGUAGE)] [downstream of: BC-5 (CONFORMIST)]'.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String get(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Bounded-context identity, e.g. BC-1") final String id,
            @McpToolParam(description = ToolParameterDescriptions.DISPLAY_LOCALE_DESCRIPTION, required = false)
            final String displayLocale,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final BoundedContextCode code = new BoundedContextCode(id);
        final String effective = effectiveDisplayLocale(project, displayLocale);
        return getBoundedContext.get(project.id(), code, effective)
                .map(detail -> format(project.id(), detail.context()) + relationshipsSuffix(detail.relationships()))
                .orElse("Bounded context not found: " + code.value());
    }

    @McpTool(name = "bc_update",
            description = "Correct an already-created bounded context's name, domain vision and/or linked "
                    + "glossary terms, or state name/domain vision in a further language. name/domainVision "
                    + "are optional - an omitted one leaves that field unchanged. "
                    + "terms replaces the context's arkddd:ubiquitousLanguageTerm links wholesale: omit it to "
                    + "leave the existing links untouched, pass an empty list to remove them all, or pass the "
                    + "full set of TERM-N codes the context should use going forward (bc_link_term remains "
                    + "the convenient way to add a single link without restating the rest). "
                    + "Does NOT touch subdomain, ownedBy or context relationships (bc_link_context/"
                    + "bc_unlink_context) - those stay fixed since creation. Cannot change the context's code "
                    + "(BC-N): it is "
                    + "fixed at creation, and everything already referring to the context refers to that "
                    + "code." + PROSE_MARKUP + STALE_TRANSLATION_NOTE)
    public String update(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Bounded-context identity, e.g. BC-1") final String id,
            @McpToolParam(description = "New name (optional, unchanged if omitted)", required = false)
            final String name,
            @McpToolParam(description = "New domain vision (optional, unchanged if omitted)", required = false)
            final String domainVision,
            @McpToolParam(description = "Business codes of the glossary terms this bounded context should use "
                    + "going forward, e.g. ['TERM-1', 'TERM-2'] (resolved against the glossary, not "
                    + "skos:prefLabel or store IRIs). Omit to leave the existing arkddd:ubiquitousLanguageTerm "
                    + "links untouched; pass an empty list to remove every link; pass a non-empty list to "
                    + "replace the links wholesale - a term not named here is unlinked even if it was linked "
                    + "before (kogn-io/arknet#567).", required = false)
            final List<String> terms,
            @McpToolParam(description = ToolParameterDescriptions.LANGUAGE_DESCRIPTION, required = false)
            final String language,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final BoundedContextCode code = new BoundedContextCode(id);
        final String staleHint = staleTranslationHint(project, code, blankToNull(language), blankToNull(name),
                blankToNull(domainVision));
        // Read before the write for the same reason the stale-translation hint is: the diff is
        // what left and joined the field, and only the state before this call can say that. A
        // caller that restates its `terms` set from memory silently unlinks what it forgot, and
        // the request it sent is precisely where that loss cannot be seen (kogn-io/arknet#598).
        final List<String> termsBefore = linkedTermCodes(project.id(), code);
        final BoundedContext updated = updateBoundedContext.update(project.id(), code, blankToNull(name),
                blankToNull(domainVision), terms == null ? null : List.copyOf(terms), blankToNull(language),
                project.defaultLanguage());
        final String diff = WriteResponse.listFieldDiff(TERM_EDGE,
                termsBefore, termCodesOf(project.id(), updated));
        final String body = (diff.isEmpty() ? "" : diff + "\n") + format(project.id(), updated) + staleHint;
        return WriteResponse.withProject(body, project);
    }

    @McpTool(name = "bc_link_term",
            description = "Link a bounded context to a glossary term of the ubiquitous language it "
                    + "names. The term must already exist (create it with term_add first). Linking the "
                    + "same term twice is a no-op.")
    public String linkTerm(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Bounded-context identity, e.g. BC-1") final String bcId,
            @McpToolParam(description = "Term code, e.g. TERM-1 (the term's business code, resolved "
                    + "against the glossary - not its skos:prefLabel or its store IRI)")
            final String termId,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        linkTerm.linkTerm(project.id(), new BoundedContextCode(bcId), termId);
        return WriteResponse.withProject(WriteResponse.linked(bcId, termId, TERM_EDGE), project);
    }

    @McpTool(name = "bc_unlink_term",
            description = "Remove one bounded context's link to one glossary term, without restating "
                    + "the rest (bc_update's terms replaces the whole set). Never a silent no-op: a term "
                    + "that is not currently linked is rejected.")
    public String unlinkTerm(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Bounded-context identity, e.g. BC-1") final String bcId,
            @McpToolParam(description = "Term code, e.g. TERM-1") final String termId,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        unlinkTerm.unlinkTerm(project.id(), new BoundedContextCode(bcId), termId);
        return WriteResponse.withProject(WriteResponse.unlinked(bcId, termId, TERM_EDGE), project);
    }

    @McpTool(name = "bc_link_context",
            description = "Record a directed DDD context-mapping relationship between two existing "
                    + "bounded contexts (both must already exist - create them with bc_add first). "
                    + "Valid relationship types: PARTNERSHIP, SHARED_KERNEL, CUSTOMER_SUPPLIER, "
                    + "CONFORMIST, ANTICORRUPTION_LAYER, OPEN_HOST_SERVICE, PUBLISHED_LANGUAGE, "
                    + "SEPARATE_WAYS. Pure CRUD: this tool never judges or suggests which relationship "
                    + "type applies - that call is yours. Idempotent over the exact "
                    + "(upstream, downstream, relationshipType) triple: calling it again with the same "
                    + "three values returns the relationship already recorded rather than creating a "
                    + "second one; two different types between the same pair remain two distinct "
                    + "relationships. Recorded relationships show up on bc_get/bc_list; remove one with "
                    + "bc_unlink_context.")
    public String linkContext(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Upstream bounded-context identity, e.g. BC-1 (the context "
                    + "whose model/protocol prevails)")
            final String upstreamBcId,
            @McpToolParam(description = "Downstream bounded-context identity, e.g. BC-2 (the context "
                    + "that consumes the upstream model/protocol); must differ from upstreamBcId")
            final String downstreamBcId,
            @McpToolParam(description = "Relationship type: PARTNERSHIP, SHARED_KERNEL, "
                    + "CUSTOMER_SUPPLIER, CONFORMIST, ANTICORRUPTION_LAYER, OPEN_HOST_SERVICE, "
                    + "PUBLISHED_LANGUAGE or SEPARATE_WAYS")
            final String relationshipType,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final RelationshipType type = parseRelationshipType(relationshipType);
        final ContextRelationship created = linkContext.linkContext(
                project.id(), new BoundedContextCode(upstreamBcId), new BoundedContextCode(downstreamBcId), type);
        return WriteResponse.withProject(
                WriteResponse.linked(upstreamBcId, downstreamBcId, created.relationshipType().name()), project);
    }

    @McpTool(name = "bc_unlink_context",
            description = "Remove a previously recorded DDD context-mapping relationship between two "
                    + "bounded contexts, addressed by the exact same (upstream, downstream, "
                    + "relationshipType) triple bc_link_context took to create it. Never a silent "
                    + "no-op: a triple that is not currently recorded - a typo in the type, or the "
                    + "direction swapped - is rejected rather than quietly doing nothing, so a mistaken "
                    + "unlink call cannot be confused with a successful one.")
    public String unlinkContext(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Upstream bounded-context identity, e.g. BC-1")
            final String upstreamBcId,
            @McpToolParam(description = "Downstream bounded-context identity, e.g. BC-2")
            final String downstreamBcId,
            @McpToolParam(description = "Relationship type: PARTNERSHIP, SHARED_KERNEL, "
                    + "CUSTOMER_SUPPLIER, CONFORMIST, ANTICORRUPTION_LAYER, OPEN_HOST_SERVICE, "
                    + "PUBLISHED_LANGUAGE or SEPARATE_WAYS")
            final String relationshipType,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final RelationshipType type = parseRelationshipType(relationshipType);
        unlinkContext.unlinkContext(
                project.id(), new BoundedContextCode(upstreamBcId), new BoundedContextCode(downstreamBcId), type);
        return WriteResponse.withProject(
                WriteResponse.unlinked(upstreamBcId, downstreamBcId, type.name()), project);
    }

    @McpTool(name = "bc_delete",
            description = "Delete an already-created bounded context and every triple it carries - "
                    + "not just a correction, the whole resource goes away. The typical case is a "
                    + "boundary that turned out not to be one: a context drawn before the language "
                    + "break was understood, or two contexts that collapsed into one - use bc_update "
                    + "to correct a context that stays. Rejected while anything still points at it: "
                    + "a context relationship via upstream or downstream (remove it with "
                    + "bc_unlink_context - a relationship is its own resource and is never deleted "
                    + "along with a context), a decision via affectsContext (adr_update), a "
                    + "requirement via scopedTo, or a domain via hasContext. The terms it links "
                    + "(ubiquitousLanguageTerm) hold nothing: they are its own edges and go with it, "
                    + "the glossary terms themselves stay. The code (BC-N) stays taken so it never "
                    + "names two different contexts.")
    public String delete(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Bounded-context identity, e.g. BC-1") final String id,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        final ResolvedProject project = resolveProject(context, projectAnchor);
        final BoundedContextCode code = new BoundedContextCode(id);
        deleteBoundedContext.delete(project.id(), code);
        return WriteResponse.withProject("Deleted: " + code.value(), project);
    }

    /**
     * Parses {@code value} against {@link RelationshipType}, rejecting anything else - including
     * an unparseable or blank value - with this tool's own didactic message rather than the JDK's
     * raw {@code No enum constant ...}, mirroring {@code adr_set_status}'s {@code AdrStatus}
     * parsing idiom. Shared by {@link #linkContext} and {@link #unlinkContext} - both address a
     * relationship by the same triple, so both parse the type the same way.
     */
    private static RelationshipType parseRelationshipType(final String value) {
        RelationshipType parsed;
        try {
            parsed = RelationshipType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (NullPointerException | IllegalArgumentException e) {
            parsed = null;
        }
        if (parsed == null) {
            throw new IllegalArgumentException(
                    "only PARTNERSHIP, SHARED_KERNEL, CUSTOMER_SUPPLIER, CONFORMIST, ANTICORRUPTION_LAYER, "
                            + "OPEN_HOST_SERVICE, PUBLISHED_LANGUAGE or SEPARATE_WAYS are valid relationship "
                            + "types, not " + value);
        }
        return parsed;
    }

    /** Renders a single bounded context, resolving its own linked terms in one batch call. */
    private String format(final ProjectId projectId, final BoundedContext bc) {
        return format(bc, resolveTermsFor(projectId, List.of(bc)));
    }

    /**
     * Renders {@code bc} using an already-resolved {@code termsById} lookup - never itself calls
     * {@link ResolveLinkedTerms}, so callers control the batching (one call for a single context,
     * one call total for {@code bc_list}). Never throws: an identity missing from
     * {@code termsById} (unresolvable, or simply not looked up) falls back to its bare IRI.
     */
    private static String format(final BoundedContext bc, final Map<ResourceId, TermCode> termsById) {
        final String subdomain = bc.subdomain() == null ? "" : " {" + bc.subdomain() + "}";
        final String ownedBy = bc.ownedBy() == null ? "" : " <" + bc.ownedBy() + ">";
        final String terms = bc.usesTerms().isEmpty()
                ? ""
                : " [terms: " + bc.usesTerms().stream().map(ref -> renderTerm(ref, termsById))
                        .reduce((a, b) -> a + ", " + b).orElse("") + "]";
        return "%s %s (%s)%s%s%s".formatted(
                bc.code().value(), bc.name(), bc.domainVision(), subdomain, ownedBy, terms);
    }

    /** Renders one term reference: its resolved business code, or its bare IRI as a fallback. */
    private static String renderTerm(final ResourceId ref, final Map<ResourceId, TermCode> termsById) {
        final TermCode code = termsById.get(ref);
        return code != null ? code.value() : ref.value();
    }

    /**
     * Batch-resolves every term referenced by {@code boundedContexts} in exactly one call to
     * {@link ResolveLinkedTerms#resolveLinkedTerms} - the union of all their identities,
     * deduplicated, not one call per context and not one call per identity. Missing ids are
     * simply absent from the returned map, which {@link #renderTerm} treats as "fall back to the
     * IRI". The merge function keeps the first entry for a duplicate key rather than throwing, so
     * a {@link ResolveLinkedTerms} implementation returning two {@link LinkedTerm}s for one
     * identity cannot turn a display concern into a thrown exception.
     */
    private Map<ResourceId, TermCode> resolveTermsFor(
            final ProjectId projectId, final List<BoundedContext> boundedContexts) {
        final ResourceId[] ids = boundedContexts.stream()
                .flatMap(bc -> bc.usesTerms().stream())
                .distinct()
                .toArray(ResourceId[]::new);
        if (ids.length == 0) {
            return Map.of();
        }
        return resolveLinkedTerms.resolveLinkedTerms(projectId, ids).stream()
                .collect(Collectors.toMap(LinkedTerm::id, LinkedTerm::code, (first, second) -> first));
    }

    /**
     * The {@code [fallback: ...]} suffix {@code bc_list} appends to a line whenever
     * {@code fallback} names at least one field that had to degrade past the requested/
     * project-default language (kogn-io/arknet#520) - empty string (no visible change) when
     * {@code fallback} is {@code null} or carries no fallen-back field.
     */
    private static String fallbackSuffix(final BoundedContextDisplayFallback fallback) {
        if (fallback == null || fallback.isEmpty()) {
            return "";
        }
        final List<String> parts = new ArrayList<>();
        if (fallback.nameTag() != null) {
            parts.add("name=" + displayTag(fallback.nameTag()));
        }
        if (fallback.domainVisionTag() != null) {
            parts.add("domainVision=" + displayTag(fallback.domainVisionTag()));
        }
        return " [fallback: " + String.join(", ", parts) + "]";
    }

    private static String displayTag(final String tag) {
        return tag.isEmpty() ? "untagged" : tag;
    }

    /**
     * Renders every {@link RelatedContext} a {@code bc_get}/{@code bc_list} result carries as one
     * inline bracket per relationship, e.g.
     * {@code [upstream of: BC-1 (PUBLISHED_LANGUAGE)] [downstream of: BC-5 (CONFORMIST)]} - the
     * same short form on both tools (kogn-io/arknet#565), {@code bc_list} keeping every context on
     * its own single line exactly as it already does for {@link #fallbackSuffix}. Empty when
     * {@code relationships} is empty, so a context with no recorded relationship renders exactly as
     * it did before this issue.
     */
    private static String relationshipsSuffix(final List<RelatedContext> relationships) {
        if (relationships.isEmpty()) {
            return "";
        }
        final StringBuilder suffix = new StringBuilder();
        for (final RelatedContext related : relationships) {
            final String label = related.direction() == RelatedContext.Direction.UPSTREAM_OF
                    ? "upstream of" : "downstream of";
            suffix.append(" [").append(label).append(": ").append(related.peerCode().value())
                    .append(" (").append(related.relationshipType()).append(")]");
        }
        return suffix.toString();
    }

    /** Mirrors {@code ToolArguments#effectiveDisplayLocale} exactly. */
    private static String effectiveDisplayLocale(final ResolvedProject project, final String explicit) {
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        return project.defaultLanguage();
    }

    /**
     * The stale-translation signal for a {@code bc_update} (kogn-io/arknet#474): the multilingual
     * fields this call is about to write, named as {@code store_check} names them. Asked before
     * the write and appended after it, because only the state before tells a correction from a
     * translation (see {@link StaleTranslationHint}).
     */
    private String staleTranslationHint(final ResolvedProject project, final BoundedContextCode code,
            final String language, final String name, final String domainVision) {
        final List<String> fieldsWritten = new ArrayList<>();
        if (name != null) {
            fieldsWritten.add(NAME_FIELD);
        }
        if (domainVision != null) {
            fieldsWritten.add(DOMAIN_VISION_FIELD);
        }
        if (fieldsWritten.isEmpty()) {
            return "";
        }
        return staleTranslations.forResource(project.id(), code.value(),
                LanguageTag.writtenLanguage(language, project.defaultLanguage()),
                project.maintainedLanguages(), fieldsWritten);
    }

    /**
     * The business codes of the terms bounded context {@code code} links right now - the "before"
     * side of {@code bc_update}'s diff line. An unknown code yields an empty list rather than an
     * error: the update itself is about to reject it with its own message, and a diff has no
     * business deciding that first.
     */
    private List<String> linkedTermCodes(final ProjectId projectId, final BoundedContextCode code) {
        return getBoundedContext.get(projectId, code, null)
                .map(detail -> termCodesOf(projectId, detail.context()))
                .orElseGet(List::of);
    }

    /**
     * Renders {@code bc}'s linked terms as the codes a caller types, resolving them in one batch
     * call. An id {@link ResolveLinkedTerms} cannot resolve contributes its bare IRI, exactly as
     * {@link #renderTerm} does - a term missing from the glossary must still show up on both sides
     * of the diff, or removing it would read as if nothing had changed.
     */
    private List<String> termCodesOf(final ProjectId projectId, final BoundedContext bc) {
        final Map<ResourceId, TermCode> termsById = resolveTermsFor(projectId, List.of(bc));
        return bc.usesTerms().stream().map(ref -> renderTerm(ref, termsById)).toList();
    }

    private static String blankToNull(final String value) {
        return (value == null || value.isBlank()) ? null : value;
    }
}
