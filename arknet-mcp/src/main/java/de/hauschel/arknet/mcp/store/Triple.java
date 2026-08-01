// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.store;

import java.util.Objects;

/**
 * One RDF statement read from the store: a {@code subject} (an IRI, or a blank-node reference
 * as {@code "_:" + reference}), an IRI {@code predicate} and an {@link RdfNode} {@code object}.
 * Backend-neutral value object shared by the reader and the renderers.
 *
 * @param subject   the subject IRI, or a blank-node reference
 * @param predicate the predicate IRI
 * @param object    the object node
 */
public record Triple(String subject, String predicate, RdfNode object) {

    public Triple {
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(predicate, "predicate");
        Objects.requireNonNull(object, "object");
    }
}
