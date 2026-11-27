// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.adapter.mcp;

import java.util.List;
import java.util.Optional;

import de.hauschel.arknet.resource.application.port.in.HistoryReading;
import de.hauschel.arknet.resource.application.port.in.RenderedField;
import de.hauschel.arknet.resource.application.port.in.RenderedResource;
import de.hauschel.arknet.resource.application.port.in.RenderedValue;
import de.hauschel.arknet.resource.application.port.in.ResourceReading;

/**
 * Renders what the reading in-ports return as the text of {@code resource_get} and
 * {@code resource_history}. It names no resource type: headings, field names and labels all come
 * from the readings, which take them from the shapes and the ontology.
 */
final class ReadingRenderer {

    private static final String INDENT = "  ";
    private static final String XSD_STRING = "xsd:string";
    private static final String BLOCK_SEPARATOR = "\n";

    private ReadingRenderer() {
    }

    /**
     * Renders the readings of one {@code resource_get} call, one block per handle, in the order
     * of the handles.
     *
     * @param readings the readings
     * @return the text
     */
    static String readings(final List<ResourceReading> readings) {
        StringBuilder out = new StringBuilder();
        for (ResourceReading reading : readings) {
            if (!out.isEmpty()) {
                out.append(BLOCK_SEPARATOR);
            }
            switch (reading) {
                case ResourceReading.Found found -> found(out, found.resource());
                case ResourceReading.Deleted deleted -> deleted(out, deleted, true);
                case ResourceReading.Unresolved unresolved -> out.append("# ").append(unresolved.handle())
                        .append(" NOT FOUND\n- ").append(unresolved.reason()).append('\n');
            }
        }
        return out.toString();
    }

    /**
     * Renders the history of one resource.
     *
     * @param history the history
     * @return the text
     */
    static String history(final HistoryReading history) {
        StringBuilder out = new StringBuilder("# ").append(history.handle()).append('\n');
        out.append("IRI: <").append(history.iri()).append(">\n");
        out.append("Publish state: ").append(history.publishState()).append('\n');
        history.created().ifPresent(created -> out.append("Created: ").append(created).append('\n'));
        history.lastChanged().ifPresent(changed -> out.append("Last changed: ").append(changed).append('\n'));
        history.deleted().ifPresent(deleted -> {
            out.append('\n');
            deleted(out, deleted, false);
        });
        out.append('\n');
        List<HistoryReading.Entry> entries = history.entries();
        out.append("## History (").append(entries.size()).append(")\n");
        if (entries.isEmpty()) {
            boolean beforeVersioning = history.deleted().filter(deleted -> deleted.deletedAt().isEmpty()).isPresent();
            out.append(beforeVersioning
                    ? "- no entries - the resource was deleted before the store recorded a history\n"
                    : "- never published - every write through the tools publishes a version, so this resource"
                            + " was only ever written past the tools\n");
            return out.toString();
        }
        for (HistoryReading.Entry entry : entries) {
            out.append(entry.sequence()).append(". ").append(entry.created()).append(INDENT)
                    .append(entry.tombstone() ? "tombstone" : "publish");
            entry.version().ifPresent(version -> out.append(INDENT).append(version));
            entry.comment().ifPresent(comment -> out.append(INDENT).append('"').append(comment).append('"'));
            out.append('\n');
        }
        return out.toString();
    }

    private static void found(final StringBuilder out, final RenderedResource resource) {
        out.append("# ").append(resource.handle()).append(" [").append(resource.typeLabel()).append(']');
        resource.label().ifPresent(label -> out.append(' ').append(text(label)));
        out.append('\n');
        out.append("IRI: <").append(resource.iri()).append(">\n");
        if (!resource.types().isEmpty()) {
            out.append("Type: ").append(String.join(", ",
                    resource.types().stream().map(ReadingRenderer::reference).toList())).append('\n');
        }
        resource.status().ifPresent(status -> out.append("Status: ").append(reference(status)).append('\n'));
        resource.created().ifPresent(created -> out.append("Created: ").append(created).append('\n'));
        resource.lastChanged().ifPresent(changed -> out.append("Last changed: ").append(changed).append('\n'));
        section(out, "Fields", resource.fields());
        section(out, "Other statements (outside the shapes)", resource.otherStatements());
        section(out, "Incoming (what points here)", resource.incoming());
    }

    private static void deleted(final StringBuilder out, final ResourceReading.Deleted deleted,
            final boolean withHeading) {
        if (withHeading) {
            out.append("# ").append(deleted.handle()).append(" DELETED\n");
            out.append("IRI: <").append(deleted.iri()).append(">\n");
        } else {
            out.append("## DELETED\n");
        }
        if (deleted.deletedAt().isEmpty()) {
            out.append("- deleted before versioning: the deletion time is not recorded\n");
        } else {
            out.append("- deleted at ").append(deleted.deletedAt().get());
            deleted.comment().ifPresent(comment -> out.append(" (\"").append(comment).append("\")"));
            out.append('\n');
        }
        deleted.code().ifPresent(code -> out.append("- code ").append(code)
                .append(" stays taken, it is never assigned to another resource\n"));
        if (deleted.deletedAt().isEmpty()) {
            out.append("- the content is not recorded: the storage layout of that time kept only the code,"
                    + " and the IRI above is the record of that code, not the resource itself\n");
            return;
        }
        if (deleted.lastVersion().isEmpty()) {
            out.append("- the content is not recorded: the resource was deleted without ever having been"
                    + " published\n");
            return;
        }
        section(out, "Last version", deleted.lastVersion());
    }

    private static void section(final StringBuilder out, final String heading, final List<RenderedField> fields) {
        if (fields.isEmpty()) {
            return;
        }
        out.append("## ").append(heading).append('\n');
        fields.forEach(field -> field(out, field, ""));
    }

    private static void field(final StringBuilder out, final RenderedField field, final String indent) {
        out.append(indent).append("- ").append(field.label()).append(" (").append(field.name()).append("):");
        List<RenderedValue> values = field.values();
        boolean nested = values.stream().anyMatch(RenderedValue.Child.class::isInstance);
        if (!nested && values.size() == 1) {
            out.append(' ').append(value(values.get(0))).append('\n');
            return;
        }
        out.append('\n');
        for (RenderedValue value : values) {
            if (value instanceof RenderedValue.Child child) {
                child(out, child, indent + INDENT);
            } else {
                out.append(indent).append(INDENT).append("- ").append(value(value)).append('\n');
            }
        }
    }

    private static void child(final StringBuilder out, final RenderedValue.Child child, final String indent) {
        out.append(indent).append("- ");
        child.position().ifPresent(position -> out.append('[').append(position).append("] "));
        out.append('<').append(child.iri()).append(">\n");
        child.fields().forEach(field -> field(out, field, indent + INDENT));
        child.otherStatements().forEach(field -> field(out, field, indent + INDENT));
    }

    private static String value(final RenderedValue value) {
        return switch (value) {
            case RenderedValue.Text text -> text(text);
            case RenderedValue.Reference reference -> reference(reference);
            case RenderedValue.Child child -> "<" + child.iri() + ">";
        };
    }

    private static String text(final RenderedValue.Text text) {
        StringBuilder rendered = new StringBuilder("\"").append(text.text()).append('"');
        text.language().ifPresent(language -> rendered.append('@').append(language));
        Optional<String> datatype = text.datatype().filter(type -> !XSD_STRING.equals(type));
        datatype.ifPresent(type -> rendered.append("^^").append(type));
        if (text.fallback()) {
            rendered.append(" [fallback: ").append(text.language().orElse("untagged")).append(']');
        }
        return rendered.toString();
    }

    private static String reference(final RenderedValue.Reference reference) {
        if (reference.label().equals(reference.handle())) {
            return reference.handle();
        }
        return reference.handle() + " \"" + reference.label() + "\"";
    }
}
