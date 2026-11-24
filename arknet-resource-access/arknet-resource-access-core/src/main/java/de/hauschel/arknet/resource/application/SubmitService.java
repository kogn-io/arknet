// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import io.kogn.rdf.dataset.GraphImport;
import io.kogn.rdf.terms.Graph;
import io.kogn.rdf.terms.SimpleRdf;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.resource.application.port.in.SubmitDocument;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;
import de.hauschel.arknet.resource.application.port.out.HandleLookup;
import de.hauschel.arknet.resource.application.port.out.ResourceRepository;
import de.hauschel.arknet.resource.domain.HandleReferences;
import de.hauschel.arknet.resource.domain.PrefixBindings;
import de.hauschel.arknet.resource.domain.ResourceSchema;
import de.hauschel.arknet.resource.domain.SubmitConflictException;
import de.hauschel.arknet.resource.domain.SubmitRejectedException;
import de.hauschel.arknet.resource.domain.SubmittedDocument;

/**
 * The submit engine: parses a document, checks its activities, and applies all of it in one
 * transaction or none of it.
 *
 * <p><strong>No expected state.</strong> An update names no revision it expects: like the typed
 * write tools, a document applies to the state its transaction reads. A concurrent write that
 * changes what the transaction read makes the commit fail; the engine then runs the whole document
 * again against the new state, a bounded number of times - the same way a typed create retries on a
 * taken code.</p>
 *
 * <p><strong>Known prefixes and handles.</strong> A document may write every prefix the shipped files
 * bind without declaring it; a declaration of its own wins. It may name an existing resource by its
 * handle, a relative IRI such as {@code <REQ-12>}: the engine resolves every handle of the document
 * before it reads the activities, outside the write transaction, and rejects the document if one
 * names no resource or more than one. A resource deleted between that lookup and the write is
 * caught by the write itself, which finds no type for the reference.</p>
 *
 * <p>Every rejection names the full IRI of the field or resource it concerns, declares the full
 * namespace behind every prefix its text uses, and points to {@code describe}, which tells what a
 * valid resource of a type holds (ADR-59, consequence 5). A document that is no valid Turtle gets
 * every known prefix declared, since a missing declaration is its most likely cause.</p>
 */
public final class SubmitService implements SubmitDocument {

    /** How often a document runs before a lost race is reported. */
    static final int MAX_ATTEMPTS = 5;

    private static final SimpleRdf RDF_FACTORY = new SimpleRdf();

    private final ResourceSchema schema;
    private final ResourceRepository repository;
    private final GraphImport parser;
    private final ResourceIdFactory ids;
    private final PrefixBindings prefixes;
    private final HandleLookup handles;
    private final Clock clock;

    /**
     * Creates the engine.
     *
     * @param schema     the resource types and owned fields, read from the shapes
     * @param repository the store
     * @param parser     reads Turtle
     * @param ids        mints the IRIs of created resources
     * @param prefixes   the prefixes the shipped files bind, known to every document and declared in rejections
     * @param handles    finds the resource a handle names
     * @param clock      supplies the day a default value {@code arkres:today} stands for, in its zone
     */
    public SubmitService(final ResourceSchema schema, final ResourceRepository repository, final GraphImport parser,
            final ResourceIdFactory ids, final PrefixBindings prefixes, final HandleLookup handles,
            final Clock clock) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.ids = Objects.requireNonNull(ids, "ids");
        this.prefixes = Objects.requireNonNull(prefixes, "prefixes");
        this.handles = Objects.requireNonNull(handles, "handles");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    @Override
    public SubmitResult submit(final ProjectId projectId, final String document) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(document, "document");
        Graph graph = parse(document);
        try {
            return apply(projectId, SubmittedDocument.of(resolveHandles(projectId, graph), schema));
        } catch (SubmitRejectedException e) {
            throw explained(e.getMessage(), prefixes.usedIn(e.getMessage()));
        }
    }

    private SubmitResult apply(final ProjectId projectId, final SubmittedDocument parsed) {
        SubmitConflictException lost = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                List<SubmittedChange> changes = new ArrayList<>();
                LocalDate today = LocalDate.now(clock);
                repository.writeAll(projectId,
                        batch -> changes.addAll(new DocumentApplication(parsed, schema, batch, ids, today).apply()));
                return new SubmitResult(changes);
            } catch (SubmitConflictException e) {
                lost = e;
            }
        }
        throw new SubmitRejectedException("concurrent writes changed the resources this document reads "
                + MAX_ATTEMPTS + " times in a row; nothing was written, submit it again: " + lost.getMessage());
    }

    private Graph resolveHandles(final ProjectId projectId, final Graph graph) {
        Map<String, String> iriByHandle = new LinkedHashMap<>();
        List<String> unresolved = new ArrayList<>();
        HandleReferences.in(graph).forEach(handle -> {
            try {
                iriByHandle.put(handle, handles.resolve(projectId, handle));
            } catch (IllegalArgumentException e) {
                unresolved.add("<" + handle + "> " + e.getMessage());
            }
        });
        if (!unresolved.isEmpty()) {
            throw new SubmitRejectedException("a handle in the document names no single resource: "
                    + String.join("; ", unresolved));
        }
        return iriByHandle.isEmpty() ? graph : HandleReferences.replaced(graph, iriByHandle);
    }

    /**
     * Parses a document against the handle base, with every known prefix declared on its first line:
     * a declaration of the document's own comes later and wins, and the line a parse error names is
     * still the line of the document.
     */
    private Graph parse(final String document) {
        String known = prefixes.declarations().replace('\n', ' ');
        try {
            return parser.readTurtle(new ByteArrayInputStream((known + document).getBytes(StandardCharsets.UTF_8)),
                    RDF_FACTORY.createIRI(HandleReferences.BASE));
        } catch (RuntimeException e) {
            throw explained("the document is not valid Turtle; the prefixes below need no declaration, any other"
                    + " prefix does: " + e.getMessage(), prefixes);
        }
    }

    private static SubmitRejectedException explained(final String reason, final PrefixBindings used) {
        StringBuilder message = new StringBuilder(reason).append("\nNothing was written.");
        if (!used.isEmpty()) {
            message.append("\nThe prefixes for the namespaces this text names:\n").append(used.declarations());
        } else {
            message.append('\n');
        }
        message.append("Call describe without a type for every resource type and the activities a document"
                + " consists of, and describe with a type for its fields, edges, rules and a runnable example.");
        return new SubmitRejectedException(message.toString());
    }
}
