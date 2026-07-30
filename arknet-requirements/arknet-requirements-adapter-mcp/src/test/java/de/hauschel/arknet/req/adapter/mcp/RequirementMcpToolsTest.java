// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.req.adapter.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import de.hauschel.arknet.req.application.port.in.AcceptRequirement;
import de.hauschel.arknet.req.application.port.in.AddRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirement;
import de.hauschel.arknet.req.application.port.in.GetRequirementSchema;
import de.hauschel.arknet.req.application.port.in.LinkTerm;
import de.hauschel.arknet.req.application.port.in.ListRequirements;
import de.hauschel.arknet.req.application.port.in.UpdateRequirement;
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
 * Scaffold-level check that the adapter declares exactly the seven requirement
 * tools and guards its in-port dependencies, plus the term-display-resolution
 * contract added in the #77 nachtrag ({@link ResolveTerms}): renders the resolved
 * business code, falls back to the bare IRI for an id it cannot resolve, and never
 * issues more than one batch call per rendering.
 */
class RequirementMcpToolsTest {

    private static final RequirementId ID =
            new RequirementId(ResourceId.of("https://w3id.org/arknet/id/11111111-1111-1111-1111-111111111111"));

    /** Fake resolver: every call routes to the same fixed workspace, ignoring the origin. */
    private static final ProjectId PROJECT = new ProjectId("test-project");

    /** Stands in for the registry lookup: every anchor this test sends resolves to {@link #PROJECT}. */
    private static final ProjectResolver PROJECTS = anchor -> PROJECT;

    private final Stub stub = new Stub();
    private final RecordingResolveTerms resolveTerms = new RecordingResolveTerms();
    private final RequirementMcpTools adapter =
            new RequirementMcpTools(stub, stub, stub, stub, stub, stub, stub, resolveTerms, PROJECTS);

    @Test
    void declaresTheSevenRequirementTools() {
        List<String> names = Arrays.stream(adapter.getClass().getDeclaredMethods())
                .map(m -> m.getAnnotation(McpTool.class))
                .filter(a -> a != null)
                .map(McpTool::name)
                .toList();

        assertEquals(7, names.size());
        assertTrue(names.containsAll(List.of(
                "req_add", "req_list", "req_get", "req_set_status", "req_link_term", "req_update",
                "req_schema")));
    }

    @Test
    void rejectsNullInPort() {
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        null, stub, stub, stub, stub, stub, stub, resolveTerms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, null, stub, stub, resolveTerms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, stub, null, stub, resolveTerms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, stub, stub, null, resolveTerms, PROJECTS));
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(
                        stub, stub, stub, stub, stub, stub, stub, null, PROJECTS));
    }

    @Test
    void rejectsNullProjectResolver() {
        assertThrows(NullPointerException.class,
                () -> new RequirementMcpTools(stub, stub, stub, stub, stub, stub, stub, resolveTerms, null));
    }

    /**
     * The per-call {@link org.springframework.ai.mcp.annotation.context.McpSyncRequestContext}
     * parameter (issue #137) is a framework type, not a caller-facing tool argument: Spring AI
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

    /** Issue #31: {@code req_schema} delegates to {@link GetRequirementSchema} and renders every term. */
    @Test
    void schemaRendersEveryTermFromTheInPort() {
        String rendered = adapter.schema();

        assertTrue(rendered.contains("Priority: Priorisierung nach MoSCoW. "
                + "(values: MUST_HAVE, SHOULD_HAVE, COULD_HAVE, WONT_HAVE)"), rendered);
    }

    /** Issue #91: the mandatory acceptance criteria reach {@link AddRequirement} and are rendered. */
    @Test
    void addPassesAcceptanceCriteriaThroughAndRendersThem() {
        List<String> criteria = List.of("Login succeeds with valid credentials", "Login is rate-limited");

        String rendered = adapter.add(null, "t", "d", "FUNCTIONAL", criteria, null, null, null, null);

        assertEquals(criteria, stub.lastAddCommand.acceptanceCriteria());
        assertTrue(rendered.contains("[done when: Login succeeds with valid credentials; Login is rate-limited]"),
                rendered);
    }

    /**
     * {@code acceptanceCriteria} carries no {@code required = false} - a missing value is
     * caught by the domain's {@code sh:minCount 1} invariant ({@link Requirement}'s compact
     * constructor), not silently normalised away here.
     */
    @Test
    void addWithoutAcceptanceCriteriaIsRejectedByTheDomainInvariant() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.add(null, "t", "d", "FUNCTIONAL", null, null, null, null, null));
    }

    @Test
    void linkTermPassesTheRawTermCodeThroughToTheInPort() {
        // Round trip: the port hands back a TermRef whose IRI resolves to TERM-1 again, proving
        // the code the human typed is what they see rendered back (issue #77 nachtrag).
        resolveTerms.register(ResourceId.of("https://w3id.org/arknet/id/TERM-1"), new TermCode("TERM-1"));

        String rendered = adapter.linkTerm(null, "FR-1", "TERM-1", null);

        assertEquals(new RequirementCode("FR-1"), stub.lastLinkedRequirement);
        assertEquals("TERM-1", stub.lastLinkedTermCode);
        assertTrue(rendered.contains("[terms: TERM-1]"), rendered);
    }

    /** Issue #162: {@code req_update} passes every given field through to the in-port. */
    @Test
    void updatePassesAllGivenFieldsThroughToTheInPort() {
        List<String> criteria = List.of("Bundesueberweisung braucht eine Kopfzahl");

        String rendered = adapter.update(null, "FR-1", "Neuer Titel", "Neue Beschreibung", criteria,
                "SHOULD_HAVE", null);

        assertEquals(new RequirementCode("FR-1"), stub.lastUpdatedRequirement);
        assertEquals("Neuer Titel", stub.lastUpdateTitle);
        assertEquals("Neue Beschreibung", stub.lastUpdateDescription);
        assertEquals(criteria, stub.lastUpdateAcceptanceCriteria);
        assertEquals(Priority.SHOULD_HAVE, stub.lastUpdatePriority);
        assertTrue(rendered.contains("Neuer Titel"), rendered);
    }

    /**
     * An omitted field must reach {@link UpdateRequirement} as {@code null} - so the port (not
     * this adapter) decides that "unchanged" means "leave the existing value" rather than the
     * adapter silently substituting a blank or empty value.
     */
    @Test
    void updateWithOmittedFieldsPassesNullThroughForEachOfThem() {
        adapter.update(null, "FR-1", null, null, null, null, null);

        assertEquals(new RequirementCode("FR-1"), stub.lastUpdatedRequirement);
        assertEquals(null, stub.lastUpdateTitle);
        assertEquals(null, stub.lastUpdateDescription);
        assertEquals(null, stub.lastUpdateAcceptanceCriteria);
        assertEquals(null, stub.lastUpdatePriority);
    }

    /**
     * Issue #170: the concrete case behind the priority parameter - correcting a requirement
     * mis-prioritised as {@code MUST_HAVE} down to {@code SHOULD_HAVE} without restating any
     * other field, and without the round trip through {@code req_add} that would mint a new code
     * and orphan every reference into the old one.
     */
    @Test
    void updateCanCorrectOnlyThePriority() {
        String rendered = adapter.update(null, "FR-1", null, null, null, "SHOULD_HAVE", null);

        assertEquals(Priority.SHOULD_HAVE, stub.lastUpdatePriority);
        assertEquals(null, stub.lastUpdateTitle);
        assertEquals(null, stub.lastUpdateDescription);
        assertEquals(null, stub.lastUpdateAcceptanceCriteria);
        assertTrue(rendered.contains("SHOULD_HAVE"), rendered);
    }

    /**
     * A blank priority is an omitted one, not a parse attempt: MCP clients that send "" for an
     * unfilled optional string must not trip {@link Priority#valueOf} - the same tolerance
     * {@code req_add} already applies.
     */
    @Test
    void updateTreatsABlankPriorityAsOmitted() {
        adapter.update(null, "FR-1", null, null, null, "  ", null);

        assertEquals(null, stub.lastUpdatePriority);
    }

    /** An unknown priority is rejected loudly rather than silently dropped. */
    @Test
    void updateRejectsAnUnknownPriority() {
        assertThrows(IllegalArgumentException.class,
                () -> adapter.update(null, "FR-1", null, null, null, "NICE_TO_HAVE", null));
    }

    /** The resolvable case: a linked term shows its business code, not the raw IRI (issue #77). */
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
     * Issue #77, second nachtrag: a store-first term with several {@code dcterms:identifier}
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

    private static Requirement requirementWithTerms(String code, ResourceId... termIds) {
        List<TermRef> terms = Arrays.stream(termIds).map(TermRef::new).toList();
        return new Requirement(ID, new RequirementCode(code), "t", "d", RequirementType.FUNCTIONAL,
                RequirementStatus.PROPOSED, Priority.MUST_HAVE, null, null, terms,
                List.of("Login succeeds with valid credentials"));
    }

    /** Structural stub implementing the seven driving in-ports. */
    private static final class Stub
            implements AddRequirement, ListRequirements, GetRequirement, AcceptRequirement, LinkTerm,
            UpdateRequirement, GetRequirementSchema {

        private RequirementCode lastLinkedRequirement;
        private String lastLinkedTermCode;
        private ResourceId nextLinkedTermResourceId;
        private List<ResourceId> nextLinkedTerms = List.of();
        private List<Requirement> allRequirements = List.of();
        private NewRequirement lastAddCommand;
        private RequirementCode lastUpdatedRequirement;
        private String lastUpdateTitle;
        private String lastUpdateDescription;
        private List<String> lastUpdateAcceptanceCriteria;
        private Priority lastUpdatePriority;

        @Override
        public Requirement add(ProjectId projectId, NewRequirement command) {
            lastAddCommand = command;
            return new Requirement(ID, new RequirementCode("FR-1"), command.title(), command.description(),
                    command.type(), RequirementStatus.PROPOSED, command.priority(), command.motivatedBy(),
                    command.qualityCategory(), List.of(), command.acceptanceCriteria());
        }

        @Override
        public List<Requirement> list(ProjectId projectId) {
            return allRequirements;
        }

        @Override
        public Optional<Requirement> get(ProjectId projectId, RequirementCode code) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Requirement accept(ProjectId projectId, RequirementCode code) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Requirement linkTerm(ProjectId projectId, RequirementCode code, String termCode) {
            lastLinkedRequirement = code;
            lastLinkedTermCode = termCode;
            List<ResourceId> ids = new ArrayList<>(nextLinkedTerms);
            if (nextLinkedTermResourceId != null) {
                ids.add(nextLinkedTermResourceId);
            }
            if (ids.isEmpty()) {
                ids.add(ResourceId.of("https://w3id.org/arknet/id/" + termCode));
            }
            List<TermRef> terms = ids.stream().map(TermRef::new).toList();
            return new Requirement(ID, code, "t", "d", RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                    Priority.MUST_HAVE, null, null, terms, List.of("Login succeeds with valid credentials"));
        }

        @Override
        public List<RequirementSchemaTerm> schema() {
            return List.of(new RequirementSchemaTerm("Priority", "Priorisierung nach MoSCoW.",
                    List.of("MUST_HAVE", "SHOULD_HAVE", "COULD_HAVE", "WONT_HAVE")));
        }

        @Override
        public Requirement update(ProjectId projectId, RequirementCode code, String title, String description,
                List<String> acceptanceCriteria, Priority priority) {
            lastUpdatedRequirement = code;
            lastUpdateTitle = title;
            lastUpdateDescription = description;
            lastUpdateAcceptanceCriteria = acceptanceCriteria;
            lastUpdatePriority = priority;
            return new Requirement(ID, code, title != null ? title : "t", description != null ? description : "d",
                    RequirementType.FUNCTIONAL, RequirementStatus.PROPOSED,
                    priority != null ? priority : Priority.MUST_HAVE, null, null,
                    List.of(), acceptanceCriteria != null ? acceptanceCriteria : List.of("Done when it works"));
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
        public List<ResolvedTerm> getById(ProjectId projectId, ResourceId... ids) {
            calls++;
            List<ResourceId> wanted = Arrays.asList(ids);
            return known.stream().filter(t -> wanted.contains(t.id())).toList();
        }
    }
}
