// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.ai.mcp.annotation.context.McpSyncRequestContext;

import io.modelcontextprotocol.common.McpTransportContext;

import de.hauschel.arknet.mcpsupport.ProjectResolver;
import de.hauschel.arknet.mcpsupport.ResolvedProject;
import de.hauschel.arknet.mcpsupport.ToolParameterDescriptions;
import de.hauschel.arknet.resource.application.port.in.ChangeExample;
import de.hauschel.arknet.resource.application.port.in.DescribeResourceType;
import de.hauschel.arknet.resource.application.port.in.FieldDescription;
import de.hauschel.arknet.resource.application.port.in.ResourceCatalog;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeDescription;
import de.hauschel.arknet.resource.application.port.in.ResourceTypeSummary;
import de.hauschel.arknet.resource.application.port.in.SubmitDocument;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;

/**
 * The MCP tools of generic resource access: {@code describe} and {@code submit} (ADR-59).
 *
 * <p>Both render what the in-ports return and add nothing a resource type would have to name: the
 * fields, edges, rules and examples come from the shapes and the ontology through
 * {@link DescribeResourceType}. A rejected document surfaces as the tool error; its text already
 * declares the namespaces behind its prefixes and points back to {@code describe}.</p>
 *
 * <p><strong>Off by default.</strong> This class registers nothing itself: whether its tools are
 * offered is decided where the composition root declares it as a bean, which conditions that bean on
 * {@value #ENABLED_PROPERTY} being {@code true}. The property is the contract between the two; the
 * type-specific tools stay the write surface while it is off.</p>
 */
public final class ResourceAccessMcpTools {

    /** The property that registers these tools; absent or anything but {@code true} leaves them out. */
    public static final String ENABLED_PROPERTY = "arknet.resource-access.enabled";

    private static final String INDENT = "  ";

    private final DescribeResourceType describer;
    private final SubmitDocument submitter;
    private final ProjectResolver projects;

    /**
     * Creates the tools.
     *
     * @param describer the describing in-port
     * @param submitter the submitting in-port
     * @param projects  resolves each call's target project from its anchor
     */
    public ResourceAccessMcpTools(final DescribeResourceType describer, final SubmitDocument submitter,
            final ProjectResolver projects) {
        this.describer = Objects.requireNonNull(describer, "describer");
        this.submitter = Objects.requireNonNull(submitter, "submitter");
        this.projects = Objects.requireNonNull(projects, "projects");
    }

    /**
     * Lists the resource types or describes one.
     *
     * @param context       the request context carrying the anchor
     * @param type          the type, blank to list every type
     * @param displayLocale the display language, blank for the project's default
     * @param projectAnchor an explicit anchor overriding the context's
     * @return the rendered catalog or description
     */
    @McpTool(name = "describe",
            description = "Describe what a Turtle document for submit may write. Without a type: every resource"
                    + " type, the activities a document consists of (Create, Update, AddTo, RemoveFrom, Delete)"
                    + " with their properties, and the prefix declarations behind every name. With a type"
                    + " (prefixed name as listed, or full IRI): its fields with presence, cardinality and value"
                    + " kind, the edges to other resources, the children it owns, the rules of its shapes in"
                    + " plain sentences, an example document that creates one resource of the type and"
                    + " runs as it stands, and documents that update, add to, remove from and delete that"
                    + " resource after it, once their placeholder is replaced by the code or IRI the create"
                    + " returned. Call it before writing a document for a type you have not written"
                    + " yet.",
            annotations = @McpTool.McpAnnotations(readOnlyHint = true))
    public String describe(
            final McpSyncRequestContext context,
            @McpToolParam(description = "Resource type as prefixed name (as the listing names it) or full IRI;"
                    + " omit to list every type and the activity vocabulary", required = false)
            final String type,
            @McpToolParam(description = ToolParameterDescriptions.DISPLAY_LOCALE_DESCRIPTION, required = false)
            final String displayLocale,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        String locale = displayLocale != null && !displayLocale.isBlank() ? displayLocale
                : resolveProject(context, projectAnchor).defaultLanguage();
        if (type == null || type.isBlank()) {
            return render(describer.catalog(locale));
        }
        return render(describer.describe(type, locale));
    }

    /**
     * Applies a document of activities.
     *
     * @param context       the request context carrying the anchor
     * @param document      the Turtle document
     * @param projectAnchor an explicit anchor overriding the context's
     * @return the rendered changes
     */
    @McpTool(name = "submit",
            description = "Apply one Turtle document of activities completely or not at all, in one"
                    + " transaction. Each activity is a node typed with an activity class (describe without a"
                    + " type lists them) whose arkres:object is the resource it acts on: a new resource as a"
                    + " blank node with its type and fields, an existing one by its IRI or by its business"
                    + " code as a relative IRI such as <FR-12>. The prefixes describe lists need no"
                    + " declaration; declare any other prefix the document uses. The document is validated against the shapes of every resource it"
                    + " touches; a rejection names what failed, declares the namespaces it mentions and"
                    + " changes nothing. The answer lists every created, updated and deleted resource with"
                    + " its IRI and business code.")
    public String submit(
            final McpSyncRequestContext context,
            @McpToolParam(description = "The Turtle document of activities; known prefixes need no declaration")
            final String document,
            @McpToolParam(description = ToolParameterDescriptions.PROJECT_ANCHOR_DESCRIPTION, required = false)
            final String projectAnchor) {
        ResolvedProject project = resolveProject(context, projectAnchor);
        return render(submitter.submit(project.id(), document));
    }

    private ResolvedProject resolveProject(final McpSyncRequestContext context, final String projectAnchor) {
        String explicit = projectAnchor == null || projectAnchor.isBlank() ? null : projectAnchor;
        return projects.resolve(explicit != null ? explicit : contextAnchor(context));
    }

    private static String contextAnchor(final McpSyncRequestContext context) {
        if (context == null) {
            return null;
        }
        McpTransportContext transport = context.transportContext();
        Object anchor = transport == null ? null : transport.get(ProjectResolver.ANCHOR_KEY);
        return anchor == null ? null : anchor.toString();
    }

    static String render(final ResourceCatalog catalog) {
        StringBuilder out = new StringBuilder("Resource types (describe one for its fields and an example):\n");
        catalog.types().forEach(type -> out.append("- ").append(summaryLine(type)).append('\n'));
        out.append("\nActivity vocabulary of a submit document:\n");
        catalog.activityVocabulary().forEach(term -> out.append("- ").append(term.name()).append(" (")
                .append(term.label()).append(')').append(sentence(term.comment())).append('\n'));
        out.append("\nPrefixes:\n").append(catalog.prefixDeclarations());
        return out.toString();
    }

    static String render(final ResourceTypeDescription description) {
        ResourceTypeSummary type = description.summary();
        StringBuilder out = new StringBuilder(summaryLine(type)).append('\n');
        type.codePrefix().ifPresent(prefix -> out.append("Business codes start with ").append(prefix)
                .append(" and are assigned by submit.\n"));
        out.append("\nFields:\n");
        description.fields().forEach(field -> field(out, field, ""));
        if (!description.rules().isEmpty()) {
            out.append("\nRules across fields:\n");
            description.rules().forEach(rule -> out.append("- ").append(rule).append('\n'));
        }
        out.append("\nExample document (creates one resource; runs as it stands):\n")
                .append(description.example());
        description.changeExamples().forEach(change -> out.append("\nThen ").append(change.activity())
                .append(" (runs after the documents above, after replacing ").append(ChangeExample.PLACEHOLDER)
                .append(" with the code or IRI the create returned):\n").append(change.document()));
        out.append("\nPrefixes behind every name above:\n").append(description.prefixDeclarations());
        return out.toString();
    }

    static String render(final SubmitResult result) {
        if (result.changes().isEmpty()) {
            return "Applied; the document changed nothing.";
        }
        return "Applied " + result.changes().size() + " change(s):\n" + result.changes().stream()
                .map(ResourceAccessMcpTools::changeLine)
                .collect(Collectors.joining("\n"));
    }

    private static String changeLine(final SubmittedChange change) {
        return "- " + change.kind() + (change.code() == null ? "" : " " + change.code()) + " <" + change.iri()
                + ">";
    }

    private static String summaryLine(final ResourceTypeSummary type) {
        return type.name() + " (" + type.label() + ")"
                + type.codePrefix().map(prefix -> ", codes " + prefix + "N").orElse("")
                + sentence(type.comment());
    }

    private static void field(final StringBuilder out, final FieldDescription field, final String indent) {
        out.append(indent).append("- ").append(field.name()).append(" (").append(field.label()).append("): ")
                .append(shape(field)).append('\n');
        if (!field.comment().isBlank()) {
            out.append(indent).append(INDENT).append(field.comment()).append('\n');
        }
        field.rules().forEach(rule -> out.append(indent).append(INDENT).append("Rule: ").append(rule).append('\n'));
        field.children().ifPresent(children -> {
            out.append(indent).append(INDENT).append("Each child is written in place as a blank node with its")
                    .append(" position in ").append(children.orderedBy()).append("; its fields:\n");
            children.fields().forEach(child -> field(out, child, indent + INDENT + INDENT));
        });
    }

    private static String shape(final FieldDescription field) {
        if (field.assigned()) {
            return "assigned by submit, never written by a document";
        }
        String presence = switch (field.presence()) {
            case REQUIRED -> "required";
            case RECOMMENDED -> "recommended (a warning without it)";
            case OPTIONAL -> "optional";
        };
        String values = switch (field.kind()) {
            case TEXT -> "text with a language tag";
            case VALUE -> "literal" + field.datatype().map(datatype -> " of " + datatype).orElse("");
            case CHOICE -> "one of " + String.join(", ", field.allowedValues());
            case REFERENCE -> "edge to " + (field.valueTypes().isEmpty() ? "a resource IRI"
                    : String.join(" or ", field.valueTypes()));
            case CHILDREN -> "owned children of " + String.join(", ", field.valueTypes());
        };
        String count = field.maxCount().map(max -> max == 1 ? "at most one value" : "at most " + max + " values")
                .orElse(field.onePerLanguage() ? "one value per language" : "any number of values");
        return presence + ", " + values + ", " + count;
    }

    private static String sentence(final String text) {
        return text == null || text.isBlank() ? "" : " - " + text;
    }
}
