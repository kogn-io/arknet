// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.kernel;

import java.util.Objects;

/**
 * A candidate value for display, together with its optional RDF language tag.
 *
 * <p>Technology-neutral projection of a language-tag-capable RDF literal: it carries just
 * the lexical form and the (optional) language tag, so the display-language selection in
 * {@link DisplayLocale} can operate without ever knowing the concrete RDF term type of any
 * persistence backend. An out-adapter maps its own literal representation onto this record
 * before handing candidates to {@link DisplayLocale#select(java.util.Collection)}.</p>
 *
 * @param value       the lexical form (the actual string), never {@code null}
 * @param languageTag the RDF language tag (e.g. {@code "de"}, {@code "en-US"}), or
 *                    {@code null} for a plain, untagged literal
 */
public record LocalizedLiteral(String value, String languageTag) {

    public LocalizedLiteral {
        Objects.requireNonNull(value, "value");
        // languageTag is deliberately nullable: null models a plain literal without a tag
        // (`"Kunde"` rather than `"Kunde"@de`) - today's normal case, since term_add writes
        // untagged labels.
    }

    /** A plain literal without a language tag. */
    public static LocalizedLiteral untagged(String value) {
        return new LocalizedLiteral(value, null);
    }

    /** A language-tagged literal; the tag must not be {@code null}. */
    public static LocalizedLiteral tagged(String value, String languageTag) {
        return new LocalizedLiteral(value, Objects.requireNonNull(languageTag, "languageTag"));
    }

    /** Whether this literal carries no language tag. */
    public boolean isUntagged() {
        return languageTag == null;
    }
}
