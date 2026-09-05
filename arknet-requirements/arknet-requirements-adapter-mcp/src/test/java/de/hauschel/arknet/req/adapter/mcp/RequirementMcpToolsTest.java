// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.provider.tool.SyncMcpToolProvider;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ProjectResolver;
import de.hauschel.arknet.kernel.ResolvedProject;
import de.hauschel.arknet.req.application.port.in.AcceptRequirement;
import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirementSchema;
import de.hauschel.arknet.req.application.port.in.LinkConstraint;
import de.hauschel.arknet.req.application.port.in.LinkTerm;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.ProposeRequirement;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints;
import de.hauschel.arknet.req.application.port.in.ResolveConstraints.ResolvedConstraint;
import de.hauschel.arknet.req.application.port.in.UpdateRequirement;
import de.hauschel.arknet.req.domain.AcceptanceCriterion;
import de.hauschel.arknet.req.domain.AcceptanceCriterionTextPatch;
import de.hauschel.arknet.req.domain.ConstraintCode;
import de.hauschel.arknet.req.domain.ConstraintRef;
import de.hauschel.arknet.req.domain.Priority;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementCode;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.RequirementSchemaTerm;
import de.hauschel.arknet.req.domain.RequirementStatus;
import de.hauschel.arknet.req.domain.RequirementType;
import de.hauschel.arknet.req.domain.TermRef;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms;
import de.hauschel.arknet.ul.application.port.in.ResolveTerms.ResolvedTerm;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Scaffold-level check that the adapter declares exactly the eight requirement
 * tools and guards its in-port dependencies, plus the term-display-resolution
 * contract ({@link ResolveTerms}): renders the resolved
 * business code, falls back to the bare IRI for an id it cannot resolve, and never
 * issues more than one batch call per rendering.
 */
class RequirementMcpToolsTest {

    private static final RequirementId ID =
            new RequirementId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));

    private static final String RATIONALE =
            "so that support stops resetting passwords by hand for every locked-out user";

    /** Fake resolver: every call routes to the same fixed project, ignoring the origin. */
    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Stands in for the registry lookup: every anchor this test sends resolves to {@link #PROJECT}. */
    private static final ProjectResolver PROJECTS = anchor -> new ResolvedProject(PROJECT, null);

    private final Stub stub = new Stub();
    private final RecordingResolveTerms resolveTerms = new RecordingResolveTerms();
    private final RecordingResolveConstraints resolveConstraints = new RecordingResolveConstraints();
    private final RequirementMcpTools adapter = new RequirementMcpTools(
            stub, stub, stub, stub, stub, stub, stub, stub, stub, resolveTerms, resolveConstraints, PROJECTS);

    @Test
    void declaresTheEightRequirementTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(8, names.size());
        assertTrue(names.containsAll(List.of(
                "req_add", "req_list", "req_get", "req_set_status", "req_link_term", "req_link_constraint",
                "req_update", "req_schema")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        null, stub, stub, stub, stub, stub, stub, stub, stub, resolveTerms, resolveConstraints,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, null, stub, stub, stub, stub, stub, stub, stub, resolveTerms, resolveConstraints,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, null, stub, stub, stub, stub, stub, stub, resolveTerms, resolveConstraints,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, null, stub, stub, stub, stub, stub, resolveTerms, resolveConstraints,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, null, stub, stub, stub, stub, resolveTerms, resolveConstraints,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, stub, null, stub, stub, stub, resolveTerms, resolveConstraints,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, stub, stub, null, stub, stub, resolveTerms, resolveConstraints,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, stub, stub, stub, null, stub, resolveTerms, resolveConstraints,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, stub, stub, stub, stub, null, resolveTerms, resolveConstraints,
                        PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, stub, stub, stub, stub, stub, null, resolveConstraints, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, stub, stub, stub, stub, stub, resolveTerms, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, stub, stub, stub, stub, stub, resolveTerms, resolveConstraints,
                        null));
    }

    /**
     * The per-call {@link org.springframework.ai.mcp.annotation.context.McpSyncRequestContext}
     * parameter is a framework type, not a caller-facing tool argument: Spring AI
     * must exclude it from the generated tool input schema. Proven against the real annotation
     * scanner ({@link SyncMcpToolProvider}), not just asserted - {@code req_add}'s schema carries
     * its documented business inputs and no {@code context} property.
     */
    @Test
    void perCallContextParameterIsExcludedFromTheGeneratedToolSchema() {
        Map<String, Object> addSchema = new SyncMcpToolProvider(List.of(adapter)).getToolSpecifications().stream()
                .filter(s -> s.tool().name().equals("req_add"))
                .findFirst().orElseThrow()
                .tool().inputSchema();

        @SuppressWarnings("unchecked")
        Map<String, Object> properties = (Map<String, Object>) addSchema.get("properties");
        assertTrue(properties.containsKey("title"), properties::toString);
        assertTrue(properties.containsKey("acceptanceCriteria"), properties::toString);
        assertFalse(properties.containsKey("context"), properties::toString);
    }

    /** {@code req_schema} delegates to {@link GetRequirementSchema} and renders every term. */
    @Test
    void schemaRendersEveryTermFromTheInPort() {
        String rendered = adapter.schema();

        assertTrue(rendered.contains("Priority: Priorisierung nach MoSCoW. "
                + "(values: MUST_HAVE, SHOULD_HAVE, COULD_HAVE, WONT_HAVE)"), rendered);
    }

    /** The mandatory acceptance criteria reach {@link AddRequirement} and are rendered. */
    @Test
    void addPassesAcceptanceCriteriaThroughAndRendersThem() {
        List<String> criteria = List.of("Login succeeds with valid credentials", "Login is rate-limited");

        String rendered = adapter.add(null, "t", "d", null, "FUNCTIONAL", criteria, null, null, null, null, null);

        assertEquals(criteria, stub.lastAddCommand.acceptanceCriteria());
        assertTrue(rendered.contains("[done when: Login succeeds with valid credentials; Login is rate-limited]"),
                rendered);
    }

    /**
     * {@code req_get}/{@code req_list} must render a requirement's normative statement, not just
     * its title - the actual bug behind issue #249. Mirrors {@link ConstraintPresenter}'s
     * {@code title: statement} rendering.
     */
    @Test
    void addRendersTheNormativeDescriptionAlongsideTheTitle() {
        String rendered = adapter.add(null, "Login", "The system shall authenticate users via OAuth2", null,
                "FUNCTIONAL", List.of("Done when it works"), null, null, null, null, null);

        assertTrue(rendered.contains("The system shall authenticate users via OAuth2"), rendered);
    }

    /**
     * {@code acceptanceCriteria} carries no {@code required = false} - a missing value is
     * caught by the domain's {@code sh:minCount 1} invariant ({@link Requirement}'s compact
     * constructor), not silently normalised away here.
     */
    @Test
    void addWithoutAcceptanceCriteriaIsRejectedByTheDomainInvariant() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "t", "d", null, "FUNCTIONAL", null, null, null, null, null, null));
    }

    // --- rationale (issue #321) --------------------------------------------------------------

    /** {@code req_add}'s optional rationale reaches {@link AddRequirement} and is rendered. */
    @Test
    void addPassesTheRationaleThroughAndRendersIt() {
        String rendered = adapter.add(null, "t", "d", RATIONALE, "FUNCTIONAL", List.of("Done when it works"),
                null, null, null, null, null);

        assertEquals(RATIONALE, stub.lastAddCommand.rationale());
        assertTrue(rendered.contains("[why: " + RATIONALE + "]"), rendered);
    }

    /** A requirement without a recorded reason renders no {@code [why: ]} block at all. */
    @Test
    void addWithoutARationaleRendersNoWhyBlock() {
        String rendered = adapter.add(null, "t", "d", null, "FUNCTIONAL", List.of("Done when it works"),
                null, null, null, null, null);

        assertNull(stub.lastAddCommand.rationale());
        assertFalse(rendered.contains("[why:"), rendered);
    }

    /** A blank rationale is treated as omitted, mirroring every other optional string field. */
    @Test
    void addTreatsABlankRationaleAsOmitted() {
        adapter.add(null, "t", "d", "   ", "FUNCTIONAL", List.of("Done when it works"), null, null, null, null, null);

        assertNull(stub.lastAddCommand.rationale());
    }

    /** {@code req_update} carries the rationale down to {@link UpdateRequirement}. */
    @Test
    void updatePassesTheRationaleThrough() {
        adapter.update(null, "FR-1", null, null, RATIONALE, null, null, null, null, null);

        assertEquals(RATIONALE, stub.lastUpdateRationale);
    }

    /** An omitted rationale reaches the port as {@code null} - "leave it alone", never "remove it". */
    @Test
    void updateWithoutARationalePassesNullThrough() {
        adapter.update(null, "FR-1", "New title", null, null, null, null, null, null, null);

        assertNull(stub.lastUpdateRationale);
    }

    /**
     * {@code req_add}'s explicit {@code language} argument reaches {@link
     * AddRequirement.NewRequirement} unchanged - this adapter never merges it with the project's
     * configured default language itself (unlike {@code displayLocale}'s {@code
     * effectiveDisplayLocale} merge); the explicit-wins-otherwise-fall-back-to-default resolution
     * (issue #258) happens one layer down, in {@link AddRequirement#add}, via {@code
     * LanguageTag#resolveWriteLanguage}.
     */
    @Test
    void addPassesTheLanguageThrough() {
        adapter.add(null, "t", "d", null, "FUNCTIONAL", List.of("Done when it works"), null, null, null, "de", null);

        assertEquals("de", stub.lastAddCommand.language());
    }

    /** A blank {@code language} is treated as omitted (untagged), mirroring every other optional field. */
    @Test
    void addTreatsABlankLanguageAsOmitted() {
        adapter.add(null, "t", "d", null, "FUNCTIONAL", List.of("Done when it works"), null, null, null, "  ", null);

        assertEquals(null, stub.lastAddCommand.language());
    }

    /** An explicit {@code req_get} {@code displayLocale} wins over the project's own default. */
    @Test
    void getPassesAnExplicitDisplayLocaleThrough() {
        RequirementMcpTools adapterWithDefault = new RequirementMcpTools(stub, stub, stub, stub, stub, stub, stub,
                stub, stub, resolveTerms, resolveConstraints, anchor -> new ResolvedProject(PROJECT, "de"));

        adapterWithDefault.get(null, "FR-1", "en", null);

        assertEquals("en", stub.lastGetDisplayLocale);
    }

    /** An omitted {@code req_get} {@code displayLocale} falls back to the project's own default. */
    @Test
    void getFallsBackToTheProjectsDefaultLanguageWhenDisplayLocaleIsOmitted() {
        RequirementMcpTools adapterWithDefault = new RequirementMcpTools(stub, stub, stub, stub, stub, stub, stub,
                stub, stub, resolveTerms, resolveConstraints, anchor -> new ResolvedProject(PROJECT, "de"));

        adapterWithDefault.get(null, "FR-1", null, null);

        assertEquals("de", stub.lastGetDisplayLocale);
    }

    /**
     * {@code req_list} exposes no explicit {@code displayLocale} tool argument of its own (unlike
     * {@code req_get}) - issue #281 asks only that it fall back to the resolved project's own
     * configured default language automatically, the same value {@code req_add}/{@code req_update}
     * already pass to their in-ports. Before this fix, {@code RequirementMcpTools#list} called
     * {@code listRequirements.list(projectId)} without any locale at all, so every listed
     * requirement's title/description was read under whichever language the process-wide,
     * per-daemon default happened to be - never the calling project's own, even for a project
     * (like this test's) whose configured default differs from it.
     */
    @Test
    void listPassesTheProjectsDefaultLanguageThrough() {
        RequirementMcpTools adapterWithGermanDefault = new RequirementMcpTools(stub, stub, stub, stub, stub, stub,
                stub, stub, stub, resolveTerms, resolveConstraints, anchor -> new ResolvedProject(PROJECT, "de"));

        adapterWithGermanDefault.list(null, null);

        assertEquals("de", stub.lastListDisplayLocale);
    }

    /** One of the two legal transitions: {@code ACCEPTED} reaches {@link AcceptRequirement}. */
    @Test
    void setStatusAcceptsARequirementWhenTargetStatusIsAccepted() {
        String rendered = adapter.accept(null, "FR-1", "ACCEPTED", null);

        assertEquals(new RequirementCode("FR-1"), stub.lastAcceptedRequirement);
        assertTrue(rendered.contains("FR-1"), rendered);
    }

    /**
     * The other legal transition (issue #291; an acceptance criterion of FR-5 in arknet's own
     * store): {@code PROPOSED} reaches {@link ProposeRequirement}, resetting an accepted
     * requirement rather than being rejected as a dead-end target - the defect this fix closes.
     */
    @Test
    void setStatusProposesARequirementWhenTargetStatusIsProposed() {
        String rendered = adapter.accept(null, "FR-1", "PROPOSED", null);

        assertEquals(new RequirementCode("FR-1"), stub.lastProposedRequirement);
        assertTrue(rendered.contains("FR-1"), rendered);
    }

    /**
     * An unknown/unsupported status string must reject with this method's own didactic
     * message, not the JDK's raw {@code IllegalArgumentException("No enum constant ...")}
     * from {@link RequirementStatus#valueOf} - the same guard {@code AdrMcpTools.setStatus}
     * already applies.
     */
    @Test
    void setStatusRejectsAnUnknownStatusWithADidacticMessageInsteadOfARawEnumFailure() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> adapter.accept(null, "FR-1", "DOES_NOT_EXIST", null));

        assertTrue(exception.getMessage().contains("req_set_status"), exception.getMessage());
        assertTrue(exception.getMessage().contains("DOES_NOT_EXIST"), exception.getMessage());
    }

    @Test
    void linkTermPassesTheRawTermCodeThroughToTheInPort() {
        // Round trip: the port hands back a TermRef whose IRI resolves to TERM-1 again, proving
        // the code the human typed is what they see rendered back.
        resolveTerms.register(ResourceId.of("https://w3id.org/arknet/id/TERM-1"), new TermCode("TERM-1"));

        String rendered = adapter.linkTerm(null, "FR-1", "TERM-1", null);

        assertEquals(new RequirementCode("FR-1"), stub.lastLinkedRequirement);
        assertEquals("TERM-1", stub.lastLinkedTermCode);
        assertTrue(rendered.contains("[terms: TERM-1]"), rendered);
    }

    @Test
    void linkConstraintPassesTheRawConstraintCodeThroughToTheInPort() {
        resolveConstraints.register(ResourceId.of("https://w3id.org/arknet/id/TCON-1"), new ConstraintCode("TCON-1"));

        String rendered = adapter.linkConstraint(null, "FR-1", "TCON-1", null);

        assertEquals(new RequirementCode("FR-1"), stub.lastLinkedConstraintRequirement);
        assertEquals("TCON-1", stub.lastLinkedConstraintCode);
        assertTrue(rendered.contains("[constraints: TCON-1]"), rendered);
    }

    /**
     * Issue #468: {@code req_set_status}, {@code req_link_term} and {@code req_link_constraint}
     * share {@code req_update}'s read-modify-write round trip, but - unlike {@code req_update}
     * since issue #456 - used to always pass a {@code null} {@code defaultLanguage} to their
     * in-ports regardless of the resolved project's own configured default. A project with
     * {@code defaultLanguage: de} could therefore get the English title back from
     * {@code req_set_status}/{@code req_link_term}/{@code req_link_constraint} and the German one
     * from a directly following {@code req_get}. This pins that the adapter now passes
     * {@link ResolvedProject#defaultLanguage()} through at every one of these call sites, exactly
     * as it already does for {@code req_update}.
     */
    @Test
    void acceptPassesTheProjectsDefaultLanguageThrough() {
        RequirementMcpTools adapterWithGermanDefault = new RequirementMcpTools(stub, stub, stub, stub, stub, stub,
                stub, stub, stub, resolveTerms, resolveConstraints, anchor -> new ResolvedProject(PROJECT, "de"));

        adapterWithGermanDefault.accept(null, "FR-1", "ACCEPTED", null);

        assertEquals("de", stub.lastAcceptDefaultLanguage);
    }

    @Test
    void proposePassesTheProjectsDefaultLanguageThrough() {
        RequirementMcpTools adapterWithGermanDefault = new RequirementMcpTools(stub, stub, stub, stub, stub, stub,
                stub, stub, stub, resolveTerms, resolveConstraints, anchor -> new ResolvedProject(PROJECT, "de"));

        adapterWithGermanDefault.accept(null, "FR-1", "PROPOSED", null);

        assertEquals("de", stub.lastProposeDefaultLanguage);
    }

    @Test
    void linkTermPassesTheProjectsDefaultLanguageThrough() {
        RequirementMcpTools adapterWithGermanDefault = new RequirementMcpTools(stub, stub, stub, stub, stub, stub,
                stub, stub, stub, resolveTerms, resolveConstraints, anchor -> new ResolvedProject(PROJECT, "de"));

        adapterWithGermanDefault.linkTerm(null, "FR-1", "TERM-1", null);

        assertEquals("de", stub.lastLinkTermDefaultLanguage);
    }

    @Test
    void linkConstraintPassesTheProjectsDefaultLanguageThrough() {
        RequirementMcpTools adapterWithGermanDefault = new RequirementMcpTools(stub, stub, stub, stub, stub, stub,
                stub, stub, stub, resolveTerms, resolveConstraints, anchor -> new ResolvedProject(PROJECT, "de"));

        adapterWithGermanDefault.linkConstraint(null, "FR-1", "TCON-1", null);

        assertEquals("de", stub.lastLinkConstraintDefaultLanguage);
    }

    /** Hard invariant, mirroring the term case: an unresolvable constraint falls back to its bare IRI. */
    @Test
    void linkConstraintFallsBackToTheBareIriWhenResolveConstraintsCannotResolveIt() {
        ResourceId unresolvable = ResourceId.of("https://w3id.org/arknet/id/unknown-constraint");
        stub.nextLinkedConstraintResourceId = unresolvable;
        // Deliberately not registered with resolveConstraints - simulates a missing/deleted constraint.

        String rendered = adapter.linkConstraint(null, "FR-1", "TCON-1", null);

        assertTrue(rendered.contains("[constraints: https://w3id.org/arknet/id/unknown-constraint]"), rendered);
    }

    /** {@code req_update} passes every given field through to the in-port. */
    @Test
    void updatePassesAllGivenFieldsThroughToTheInPort() {
        List<String> criteria = List.of("Bundesueberweisung braucht eine Kopfzahl");

        String rendered = adapter.update(null, "FR-1", "Neuer Titel", "Neue Beschreibung", null, criteria, null,
                "SHOULD_HAVE", null, null);

        assertEquals(new RequirementCode("FR-1"), stub.lastUpdatedRequirement);
        assertEquals("Neuer Titel", stub.lastUpdateTitle);
        assertEquals("Neue Beschreibung", stub.lastUpdateDescription);
        assertEquals(criteria, stub.lastUpdateNewAcceptanceCriteria);
        assertEquals(Priority.SHOULD_HAVE, stub.lastUpdatePriority);
        assertTrue(rendered.contains("Neuer Titel"), rendered);
    }

    /** {@code req_update}'s {@code acceptanceCriteriaTextPatches} reach {@link UpdateRequirement} unchanged. */
    @Test
    void updatePassesAcceptanceCriteriaTextPatchesThroughToTheInPort() {
        adapter.update(null, "FR-1", null, null, null, null,
                List.of(new RequirementMcpTools.AcceptanceCriterionPatchInput(1, "Korrigierter Text")), null, null,
                null);

        assertEquals(List.of(new AcceptanceCriterionTextPatch(1, "Korrigierter Text")),
                stub.lastUpdateAcceptanceCriteriaTextPatches);
    }

    /**
     * An omitted field must reach {@link UpdateRequirement} as {@code null} - so the port (not
     * this adapter) decides that "unchanged" means "leave the existing value" rather than the
     * adapter silently substituting a blank or empty value.
     */
    @Test
    void updateWithOmittedFieldsPassesNullThroughForEachOfThem() {
        adapter.update(null, "FR-1", null, null, null, null, null, null, null, null);

        assertEquals(new RequirementCode("FR-1"), stub.lastUpdatedRequirement);
        assertEquals(null, stub.lastUpdateTitle);
        assertEquals(null, stub.lastUpdateDescription);
        assertEquals(null, stub.lastUpdateNewAcceptanceCriteria);
        assertEquals(null, stub.lastUpdateAcceptanceCriteriaTextPatches);
        assertEquals(null, stub.lastUpdatePriority);
    }

    /**
     * The concrete case behind the priority parameter - correcting a requirement
     * mis-prioritised as {@code MUST_HAVE} down to {@code SHOULD_HAVE} without restating any
     * other field, and without the round trip through {@code req_add} that would mint a new code
     * and orphan every reference into the old one.
     */
    @Test
    void updateCanCorrectOnlyThePriority() {
        String rendered = adapter.update(null, "FR-1", null, null, null, null, null, "SHOULD_HAVE", null, null);

        assertEquals(Priority.SHOULD_HAVE, stub.lastUpdatePriority);
        assertEquals(null, stub.lastUpdateTitle);
        assertEquals(null, stub.lastUpdateDescription);
        assertEquals(null, stub.lastUpdateNewAcceptanceCriteria);
        assertTrue(rendered.contains("SHOULD_HAVE"), rendered);
    }

    /**
     * A blank priority is an omitted one, not a parse attempt: MCP clients that send "" for an
     * unfilled optional string must not trip {@link Priority#valueOf} - the same tolerance
     * {@code req_add} already applies.
     */
    @Test
    void updateTreatsABlankPriorityAsOmitted() {
        adapter.update(null, "FR-1", null, null, null, null, null, "  ", null, null);

        assertEquals(null, stub.lastUpdatePriority);
    }

    /** An unknown priority is rejected loudly rather than silently dropped. */
    @Test
    void updateRejectsAnUnknownPriority() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.update(null, "FR-1", null, null, null, null, null, "NICE_TO_HAVE", null, null));
    }

    /** {@code req_update}'s {@code language} argument reaches {@link UpdateRequirement} unchanged. */
    @Test
    void updatePassesTheLanguageThrough() {
        adapter.update(null, "FR-1", "Neuer Titel", null, null, null, null, null, "de", null);

        assertEquals("de", stub.lastUpdateLanguage);
    }

    /** The resolvable case: a linked term shows its business code, not the raw IRI. */
    @Test
    void formatRendersTheResolvedTermCodeInsteadOfTheBareIri() {
        ResourceId termResourceId = ResourceId.of("https://w3id.org/arknet/id/some-term");
        resolveTerms.register(termResourceId, new TermCode("TERM-7"));
        stub.nextLinkedTermResourceId = termResourceId;

        String rendered = adapter.linkTerm(null, "FR-1", "TERM-1", null);

        assertTrue(rendered.contains("[terms: TERM-7]"), rendered);
    }

    /**
     * Hard invariant: an id {@link ResolveTerms} cannot resolve must never be dropped from the
     * rendering and must never make {@code format} throw - it falls back to the bare IRI.
     */
    @Test
    void formatFallsBackToTheBareIriWhenResolveTermsCannotResolveIt() {
        ResourceId unresolvable = ResourceId.of("https://w3id.org/arknet/id/unknown-term");
        stub.nextLinkedTermResourceId = unresolvable;
        // Deliberately not registered with resolveTerms - simulates a missing/deleted term.

        String rendered = adapter.linkTerm(null, "FR-1", "TERM-1", null);

        assertTrue(rendered.contains("[terms: https://w3id.org/arknet/id/unknown-term]"), rendered);
    }

    /**
     * A store-first term with several {@code dcterms:identifier}
     * triples is shape-legal (no {@code sh:maxCount}) and makes
     * {@code KognioRdfTermRepository#findByIds} return more than one {@link ResolvedTerm} for the
     * same identity - see that class for the source-level fix. This pins the structural,
     * implementation-independent backstop in {@link RequirementMcpTools}: even if a
     * {@link ResolveTerms} implementation returned duplicate entries for one id, {@code format}
     * must still not throw. A naive {@code Collectors.toMap(t -> t.id(), t -> t)} throws
     * {@code IllegalStateException} on exactly this input.
     */
    @Test
    void formatNeverThrowsWhenResolveTermsReturnsDuplicateEntriesForTheSameIdentity() {
        ResourceId duplicated = ResourceId.of("https://w3id.org/arknet/id/duplicated-term");
        resolveTerms.register(duplicated, new TermCode("TERM-7"));
        resolveTerms.register(duplicated, new TermCode("TERM-7"));
        stub.nextLinkedTermResourceId = duplicated;

        String rendered = adapter.linkTerm(null, "FR-1", "TERM-1", null);

        assertTrue(rendered.contains("[terms: TERM-7]"), rendered);
    }

    /** {@code format} for a single requirement issues exactly one batch call, not one per term. */
    @Test
    void formatOfASingleRequirementCallsResolveTermsExactlyOnce() {
        ResourceId first = ResourceId.of("https://w3id.org/arknet/id/term-a");
        ResourceId second = ResourceId.of("https://w3id.org/arknet/id/term-b");
        resolveTerms.register(first, new TermCode("TERM-1"));
        resolveTerms.register(second, new TermCode("TERM-2"));
        stub.nextLinkedTerms = List.of(first, second);

        adapter.linkTerm(null, "FR-1", "TERM-1", null);

        assertEquals(1, resolveTerms.callCount());
    }

    /**
     * {@code req_list} must not issue one {@link ResolveTerms} call per requirement - a single
     * batch across every listed requirement's linked terms.
     */
    @Test
    void listResolvesTermsOfAllRequirementsInExactlyOneBatchCall() {
        ResourceId termA = ResourceId.of("https://w3id.org/arknet/id/term-a");
        ResourceId termB = ResourceId.of("https://w3id.org/arknet/id/term-b");
        resolveTerms.register(termA, new TermCode("TERM-1"));
        resolveTerms.register(termB, new TermCode("TERM-2"));
        stub.allRequirements = List.of(
                requirementWithTerms("FR-1", termA),
                requirementWithTerms("FR-2", termB));

        String rendered = adapter.list(null, null);

        assertEquals(1, resolveTerms.callCount());
        assertTrue(rendered.contains("[terms: TERM-1]"), rendered);
        assertTrue(rendered.contains("[terms: TERM-2]"), rendered);
    }

    @Test
    void listOfRequirementsWithoutAnyLinkedTermsDoesNotCallResolveTerms() {
        stub.allRequirements = List.of(requirementWithTerms("FR-1"));

        adapter.list(null, null);

        assertEquals(0, resolveTerms.callCount());
    }

    private static final List<AcceptanceCriterion> DEFAULT_CRITERIA =
            List.of(new AcceptanceCriterion(1, "Login succeeds with valid credentials"));

    private static Requirement requirementWithTerms(String code, ResourceId... termIds) {
        List<TermRef> terms = Arrays.stream(termIds).map(TermRef::new).toList();
        return new Requirement(ID, new RequirementCode(code), "t", "d", null, RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, terms, DEFAULT_CRITERIA, List.of());
    }

    /** Structural stub implementing the nine driving in-ports. */
    private static final class Stub
            implements AddRequirement, ListRequirements, GetRequirement, AcceptRequirement, ProposeRequirement,
            LinkTerm, LinkConstraint, UpdateRequirement, GetRequirementSchema {

        private RequirementCode lastAcceptedRequirement;
        private RequirementCode lastProposedRequirement;
        private RequirementCode lastLinkedRequirement;
        private String lastLinkedTermCode;
        private ResourceId nextLinkedTermResourceId;
        private List<ResourceId> nextLinkedTerms = List.of();
        private RequirementCode lastLinkedConstraintRequirement;
        private String lastLinkedConstraintCode;
        private ResourceId nextLinkedConstraintResourceId;
        private List<Requirement> allRequirements = List.of();
        private NewRequirement lastAddCommand;
        private RequirementCode lastUpdatedRequirement;
        private String lastUpdateTitle;
        private String lastUpdateDescription;
        private String lastUpdateRationale;
        private List<String> lastUpdateNewAcceptanceCriteria;
        private List<AcceptanceCriterionTextPatch> lastUpdateAcceptanceCriteriaTextPatches;
        private Priority lastUpdatePriority;
        private String lastUpdateLanguage;
        private String lastListDisplayLocale;
        private String lastAcceptDefaultLanguage;
        private String lastProposeDefaultLanguage;
        private String lastLinkTermDefaultLanguage;
        private String lastLinkConstraintDefaultLanguage;

        @Override
        public Requirement add(ProjectId projectId, NewRequirement command, String defaultLanguage) {
            lastAddCommand = command;
            return new Requirement(ID, new RequirementCode("FR-1"), command.title(), command.description(),
                    command.rationale(),
                    command.type(), RequirementStatus.PROPOSED, command.priority(), command.motivatedBy(),
                    command.qualityCategory(), List.of(), toCriteria(command.acceptanceCriteria()), List.of());
        }

        @Override
        public List<Requirement> list(ProjectId projectId, String displayLocale) {
            lastListDisplayLocale = displayLocale;
            return allRequirements;
        }

        private String lastGetDisplayLocale;

        @Override
        public Optional<Requirement> get(ProjectId projectId, RequirementCode code, String displayLocale) {
            lastGetDisplayLocale = displayLocale;
            return Optional.of(new Requirement(ID, code, "t", "d", null, RequirementType.FUNCTIONAL,
                    RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, List.of(), DEFAULT_CRITERIA,
                    List.of()));
        }

        @Override
        public Requirement accept(ProjectId projectId, RequirementCode code, String defaultLanguage) {
            lastAcceptedRequirement = code;
            lastAcceptDefaultLanguage = defaultLanguage;
            return new Requirement(ID, code, "t", "d", null, RequirementType.FUNCTIONAL, RequirementStatus.ACCEPTED,
                    Priority.MUST_HAVE, null, null, List.of(), DEFAULT_CRITERIA, List.of());
        }

        @Override
        public Requirement propose(ProjectId projectId, RequirementCode code, String defaultLanguage) {
            lastProposedRequirement = code;
            lastProposeDefaultLanguage = defaultLanguage;
            return new Requirement(ID, code, "t", "d", null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                    Priority.MUST_HAVE, null, null, List.of(), DEFAULT_CRITERIA, List.of());
        }

        @Override
        public Requirement linkTerm(
                ProjectId projectId, RequirementCode code, String termCode, String defaultLanguage) {
            lastLinkedRequirement = code;
            lastLinkedTermCode = termCode;
            lastLinkTermDefaultLanguage = defaultLanguage;
            List<ResourceId> ids = new ArrayList<>(nextLinkedTerms);
            if (nextLinkedTermResourceId != null) {
                ids.add(nextLinkedTermResourceId);
            }
            if (ids.isEmpty()) {
                ids.add(ResourceId.of("https://w3id.org/arknet/id/" + termCode));
            }
            List<TermRef> terms = ids.stream().map(TermRef::new).toList();
            return new Requirement(ID, code, "t", "d", null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                    Priority.MUST_HAVE, null, null, terms, DEFAULT_CRITERIA, List.of());
        }

        @Override
        public Requirement linkConstraint(
                ProjectId projectId, RequirementCode code, String constraintCode, String defaultLanguage) {
            lastLinkedConstraintRequirement = code;
            lastLinkedConstraintCode = constraintCode;
            lastLinkConstraintDefaultLanguage = defaultLanguage;
            ResourceId id = nextLinkedConstraintResourceId != null
                    ? nextLinkedConstraintResourceId
                    : ResourceId.of("https://w3id.org/arknet/id/" + constraintCode);
            return new Requirement(ID, code, "t", "d", null, RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                    Priority.MUST_HAVE, null, null, List.of(), DEFAULT_CRITERIA, List.of(new ConstraintRef(id)));
        }

        @Override
        public List<RequirementSchemaTerm> schema() {
            return List.of(new RequirementSchemaTerm("Priority", "Priorisierung nach MoSCoW.",
                    List.of("MUST_HAVE", "SHOULD_HAVE", "COULD_HAVE", "WONT_HAVE")));
        }

        @Override
        public Requirement update(ProjectId projectId, RequirementCode code, String title, String description,
                String rationale, List<String> newAcceptanceCriteria,
                List<AcceptanceCriterionTextPatch> acceptanceCriteriaTextPatches,
                Priority priority, String language, String defaultLanguage) {
            lastUpdatedRequirement = code;
            lastUpdateTitle = title;
            lastUpdateDescription = description;
            lastUpdateRationale = rationale;
            lastUpdateNewAcceptanceCriteria = newAcceptanceCriteria;
            lastUpdateAcceptanceCriteriaTextPatches = acceptanceCriteriaTextPatches;
            lastUpdatePriority = priority;
            lastUpdateLanguage = language;
            Requirement base = new Requirement(ID, code, title != null ? title : "t",
                    description != null ? description : "d", rationale, RequirementType.FUNCTIONAL,
                    RequirementStatus.PROPOSED,
                    priority != null ? priority : Priority.MUST_HAVE, null, null, List.of(), DEFAULT_CRITERIA,
                    List.of());
            base = base.withAppendedAcceptanceCriteria(newAcceptanceCriteria);
            return acceptanceCriteriaTextPatches != null
                    ? base.withAcceptanceCriteriaTextPatches(projectId, acceptanceCriteriaTextPatches)
                    : base;
        }

        private static List<AcceptanceCriterion> toCriteria(List<String> texts) {
            List<AcceptanceCriterion> criteria = new ArrayList<>();
            int position = 1;
            for (String text : texts) {
                criteria.add(new AcceptanceCriterion(position++, text));
            }
            return criteria;
        }
    }

    /**
     * Fake {@link ResolveTerms}: resolves only what was {@link #register} registered, counts its
     * own invocations so tests can pin the "at most one batch call" invariant, and - like the
     * real port - never throws for an id it cannot resolve.
     */
    private static final class RecordingResolveTerms implements ResolveTerms {

        private final List<ResolvedTerm> known = new ArrayList<>();
        private int calls;

        void register(ResourceId id, TermCode code) {
            known.add(new ResolvedTerm(id, code));
        }

        int callCount() {
            return calls;
        }

        @Override
        public List<ResolvedTerm> resolve(ProjectId projectId, ResourceId... ids) {
            calls++;
            List<ResourceId> wanted = Arrays.asList(ids);
            return known.stream().filter(t -> wanted.contains(t.id())).toList();
        }
    }

    /** {@link RecordingResolveTerms}, for {@link ResolveConstraints}. */
    private static final class RecordingResolveConstraints implements ResolveConstraints {

        private final List<ResolvedConstraint> known = new ArrayList<>();
        private int calls;

        void register(ResourceId id, ConstraintCode code) {
            known.add(new ResolvedConstraint(id, code));
        }

        int callCount() {
            return calls;
        }

        @Override
        public List<ResolvedConstraint> resolveExisting(ProjectId projectId, ResourceId... ids) {
            calls++;
            List<ResourceId> wanted = Arrays.asList(ids);
            return known.stream().filter(c -> wanted.contains(c.id())).toList();
        }
    }
}
