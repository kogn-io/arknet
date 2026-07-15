package de.hauschel.arknet.req.domain;

import java.util.Objects;

/**
 * Reference from a {@link Requirement} to a glossary term of the ubiquitous
 * language it uses, carried as a bare term identity such as {@code TERM-1}.
 *
 * <p><strong>Deliberately not a link to the ubiquitous-language bounded context.</strong>
 * The requirements component must not depend on {@code arknet-ubiquitous-language-core};
 * the two BCs stay decoupled. This value object therefore holds only the term's
 * identity as a string. Resolving it to an actual {@code skos:Concept} - and rejecting
 * unknown identities - is the job of a driven adapter (lookup against the store), not
 * of this pure domain type.</p>
 *
 * <p><strong>Identity, not label.</strong> The reference carries the term's
 * {@code dcterms:identifier} ({@code TERM-N}), never its {@code skos:prefLabel}. Term
 * identity is deliberately label-independent, so a {@code usesTerm} edge survives
 * relabelling the term.</p>
 *
 * @param termId the non-blank term identity (e.g. {@code TERM-1})
 */
public record TermRef(String termId) {

    public TermRef {
        Objects.requireNonNull(termId, "termId");
        if (termId.isBlank()) {
            throw new IllegalArgumentException("TermRef termId must not be blank");
        }
    }
}
