// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.domain;

import de.hauschel.arknet.kernel.ProjectId;
import java.util.Objects;

/**
 * A client-supplied handle a {@link Project} is reachable by (ADR-016 decision 2).
 *
 * <p>The value is deliberately opaque: the server looks it up, it never parses or validates it
 * for well-formedness. Whether a string looks like a plausible filesystem path or URL is the
 * sending client's concern, not this component's - the whole point of ADR-016 is that the server
 * stopped trying to derive meaning from what a client sends and started only ever looking it up
 * against what was explicitly registered. A malformed-looking anchor is not a domain error here;
 * an anchor nobody registered is (see {@link UnknownAnchorException}).</p>
 *
 * <p>Anchor and {@link ProjectId} are two different things on purpose (ADR-016 decision 2): the
 * anchor is the place a client works from and recognises; the {@code ProjectId} is the opaque
 * identity the server keeps. That split is exactly what lets several anchors - a main checkout, a
 * git worktree, a second IDE window on a copy of the same repository - resolve to one project
 * without the project existing more than once.</p>
 *
 * <p><strong>Surrounding whitespace is stripped, and that is not a contradiction of opacity.</strong>
 * The value is a lookup key, and a key that changes under invisible transport noise is not a key:
 * an anchor arriving once with and once without a trailing newline - from a header, a shell
 * expansion, a copied tool argument - would register a project and then fail to resolve it, with
 * nothing to see in either string. Stripping normalises the <em>representation</em> so that
 * equality means what a human reading the two values would say it means; the content in between
 * is still never parsed, validated or interpreted. It also removes the temptation for each adapter
 * to trim on its own, which is how one call site ends up normalising and another one, written
 * later, does not.</p>
 *
 * <p><strong>Identity is the value alone, not the (value, type) pair.</strong> {@link #equals}
 * and {@link #hashCode} are overridden to compare {@link #value} only, deliberately diverging
 * from the record default. {@link AnchorType} "carries no behaviour" (see its own javadoc): it is
 * descriptive metadata about the shape of the value, not a second identity axis. The value, on the
 * other hand, is the lookup key - the out-adapter derives the anchor's storage identity as a
 * SHA-256 digest over the value alone (see {@code ProjectGraphs}), with the type playing no part in
 * that derivation. If domain equality disagreed with that storage identity, the same value under
 * two different types would look like two distinct anchors here while colliding on write into one
 * node there: an idempotency check ({@code ProjectService#attach}) would treat the second call as a
 * new anchor, let it past the uniqueness guard because no equal anchor is on file yet, and only the
 * SHACL gate would catch the resulting duplicate anchor node - with a message that names a
 * cardinality violation, not the mistake the caller actually made. Same value, different type, is
 * therefore one anchor, not two.</p>
 *
 * @param value the opaque anchor value as sent by the client, stripped of surrounding whitespace;
 *              never {@code null} and never blank
 * @param type  the kind of value this is, never {@code null}
 */
public record Anchor(String value, AnchorType type) {

    public Anchor {
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(type, "type");
        if (value.isBlank()) {
            throw new IllegalArgumentException("Anchor value must not be blank");
        }
        value = value.strip();
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Anchor a && value.equals(a.value);
    }

    @Override
    public int hashCode() {
        return value.hashCode();
    }
}
