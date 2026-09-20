// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.report;

import java.util.Objects;

/**
 * A reference from one model card to another resource, already resolved for display.
 *
 * <p>The bounded contexts carry their cross-references as opaque subject identities
 * ({@code ResourceId}), never as business labels - a human reading the report needs the word
 * back. Resolving an identity is the card builder's job (see {@link Glossary}); by the time a
 * {@link Ref} reaches the renderer the lookup has already happened.</p>
 *
 * <p><strong>Label, not code.</strong> A glossary chip shows the term itself
 * ({@code Customer}), not its running number ({@code TERM-1}): the number identifies the term
 * for a tool, the label is what the reader came for. The code stays available as the chip's
 * tooltip rather than being dropped, because it is what a human types into {@code term_get}.</p>
 *
 * @param label the text to show: a term's {@code skos:prefLabel}, a requirement's business code
 *              - whatever names the target best. When the owning context could not resolve the
 *              identity at all this falls back to the bare IRI, so a reference is never
 *              silently dropped
 * @param code  the target's business code, shown as a tooltip; {@code null} where the label
 *              already <em>is</em> the code (a requirement chip) or where the target has no
 *              code at all (a goal that has no aggregate yet)
 * @param iri   the referenced resource's subject IRI; used to link the reference to that
 *              resource's card in the same document. Never {@code null} - whether a card for it
 *              exists is the renderer's question, not this type's
 */
public record Ref(String label, String code, String iri) {

    public Ref {
        Objects.requireNonNull(label, "label");
        Objects.requireNonNull(iri, "iri");
    }

    /**
     * A reference whose label already is its own code - a requirement chip, a goal's local
     * name, or an identity nothing could resolve.
     *
     * @param label the text to show
     * @param iri   the subject IRI
     * @return a ref with no separate tooltip code
     */
    public static Ref of(final String label, final String iri) {
        return new Ref(label, null, iri);
    }
}
