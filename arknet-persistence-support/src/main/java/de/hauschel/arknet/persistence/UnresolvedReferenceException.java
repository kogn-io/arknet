package de.hauschel.arknet.persistence;

/**
 * Thrown when a cross-bounded-context reference cannot be resolved unambiguously against the
 * target workspace's store during strict resolve-before-write.
 *
 * <p><strong>Strict reference resolution.</strong> Several bounded contexts share one
 * per-workspace store. Before a candidate graph is written, every cross-BC reference it carries
 * is resolved against that store by looking up the referenced resource's stable identity. If the
 * identity matches no resource, or more than one, the write is aborted and nothing is persisted -
 * dangling cross-references are never created. Two concrete shapes of this pattern exist so far:
 * a requirement's {@code arkreq:usesTerm} edge, resolved by the term's {@code dcterms:identifier}
 * (requirements BC, issue #36); and a use case's {@code stepRealises}/actor references, resolved
 * by {@code dcterms:identifier} (requirements) or {@code skos:prefLabel} (actors) respectively
 * (use-cases BC, issue #41).</p>
 *
 * <p>The message is deliberately didactic: it names the missing/ambiguous reference and points
 * at the tool that would create or disambiguate it (e.g. {@code term_add}, {@code req_add}).</p>
 */
public class UnresolvedReferenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnresolvedReferenceException(String message) {
        super(message);
    }
}
