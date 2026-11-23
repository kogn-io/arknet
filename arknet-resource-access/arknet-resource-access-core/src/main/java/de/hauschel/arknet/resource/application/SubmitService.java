// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import io.kogn.rdf.dataset.GraphImport;
import io.kogn.rdf.terms.Graph;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceIdFactory;
import de.hauschel.arknet.resource.application.port.in.SubmitDocument;
import de.hauschel.arknet.resource.application.port.in.SubmitResult;
import de.hauschel.arknet.resource.application.port.in.SubmittedChange;
import de.hauschel.arknet.resource.application.port.out.ResourceRepository;
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
 * <p>Every rejection names the full IRI of the field or resource it concerns; the shapes of a
 * resource type tell what a valid resource holds.</p>
 */
public final class SubmitService implements SubmitDocument {

    /** How often a document runs before a lost race is reported. */
    static final int MAX_ATTEMPTS = 5;

    private final ResourceSchema schema;
    private final ResourceRepository repository;
    private final GraphImport parser;
    private final ResourceIdFactory ids;

    /**
     * Creates the engine.
     *
     * @param schema     the resource types and owned fields, read from the shapes
     * @param repository the store
     * @param parser     reads Turtle
     * @param ids        mints the IRIs of created resources
     */
    public SubmitService(final ResourceSchema schema, final ResourceRepository repository, final GraphImport parser,
            final ResourceIdFactory ids) {
        this.schema = Objects.requireNonNull(schema, "schema");
        this.repository = Objects.requireNonNull(repository, "repository");
        this.parser = Objects.requireNonNull(parser, "parser");
        this.ids = Objects.requireNonNull(ids, "ids");
    }

    @Override
    public SubmitResult submit(final ProjectId projectId, final String document) {
        Objects.requireNonNull(projectId, "projectId");
        Objects.requireNonNull(document, "document");
        SubmittedDocument parsed = SubmittedDocument.of(parse(document), schema);
        SubmitConflictException lost = null;
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                List<SubmittedChange> changes = new ArrayList<>();
                repository.writeAll(projectId,
                        batch -> changes.addAll(new DocumentApplication(parsed, schema, batch, ids).apply()));
                return new SubmitResult(changes);
            } catch (SubmitConflictException e) {
                lost = e;
            }
        }
        throw new SubmitRejectedException("concurrent writes changed the resources this document reads "
                + MAX_ATTEMPTS + " times in a row; nothing was written, submit it again: " + lost.getMessage());
    }

    private Graph parse(final String document) {
        try {
            return parser.readTurtle(new ByteArrayInputStream(document.getBytes(StandardCharsets.UTF_8)));
        } catch (RuntimeException e) {
            throw new SubmitRejectedException("the document is not valid Turtle; declare every prefix it uses: "
                    + e.getMessage());
        }
    }
}
