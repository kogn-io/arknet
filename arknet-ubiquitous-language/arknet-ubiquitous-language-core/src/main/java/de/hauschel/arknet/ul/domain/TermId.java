// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.domain;

import java.util.Objects;

import de.hauschel.arknet.kernel.ResourceId;

/**
 * Opaque, unchanging identity of a {@link Term}.
 *
 * <p>Thin newtype over the shared-kernel {@link ResourceId} for type safety within the
 * ubiquitous-language bounded context. Identity is deliberately independent of both the
 * human-readable {@link TermCode} ({@code TERM-1}) and the term's {@code skos:prefLabel}: the
 * label may be edited or carry alternatives ({@code skos:altLabel}) and the code may be
 * relabelled, but this identity never changes. That the concept IRI must not be derived from a
 * mutable label is a core SKOS principle - here it is enforced by construction, because the
 * identity is a minted {@link ResourceId} rather than a slug of any business string.</p>
 *
 * @param value the wrapped resource identity, never {@code null}
 */
public record TermId(ResourceId value) {

    public TermId {
        Objects.requireNonNull(value, "value");
    }
}
