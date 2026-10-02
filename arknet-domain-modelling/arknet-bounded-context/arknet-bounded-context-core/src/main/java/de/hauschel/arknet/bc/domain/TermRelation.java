// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.domain;

/**
 * How a bounded context relates to a glossary term it names.
 *
 * <p>{@link #USES} makes the term part of the context's ubiquitous language
 * ({@code arkddd:ubiquitousLanguageTerm}). {@link #DELIMITS} records that the context names the
 * term only to draw its boundary against it - the term is deliberately not part of its language
 * ({@code arkddd:delimitsTerm}). The two are mutually exclusive per term.</p>
 */
public enum TermRelation {

    /** The term belongs to the context's language. */
    USES,

    /** The context names the term to exclude it from its language. */
    DELIMITS;

    /** The other relation - the one a term must not already carry before it takes this one. */
    public TermRelation opposite() {
        return this == USES ? DELIMITS : USES;
    }
}
