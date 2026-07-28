// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.Objects;

/**
 * A reference from one model card to another resource, already resolved for display.
 *
 * <p>The bounded contexts carry their cross-references as opaque subject identities
 * ({@code ResourceId}), never as business labels - a human reading the report needs the label
 * back. Resolving an identity to its business code is the card builder's job (it borrows the
 * owning context's {@code Resolve*} in-port for exactly that, see
 * {@link de.hauschel.arknet.mcp.report.UseCaseCards}); by the time a {@link Ref} reaches the
 * renderer the lookup has already happened.</p>
 *
 * @param code the business code to show (e.g. {@code FR-1}, {@code Customer}); when the owning
 *             context could not resolve the identity this falls back to the bare IRI, so a
 *             reference is never silently dropped
 * @param iri  the referenced resource's subject IRI; used to link the reference to that
 *             resource's card in the same document. Never {@code null} - whether a card for it
 *             exists is the renderer's question, not this type's
 */
public record Ref(String code, String iri) {

    public Ref {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(iri, "iri");
    }
}
