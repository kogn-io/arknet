package de.hauschel.arknet.req.adapter.kogniordf;

/**
 * Thrown when a requirement references a glossary term ({@code arkreq:usesTerm}) by identity
 * that cannot be resolved unambiguously against the target workspace's store.
 *
 * <p><strong>Strict reference resolution (issue #36).</strong> Requirements and
 * ubiquitous-language terms live in the <em>same</em> per-workspace store. When a requirement
 * is persisted, every term reference is resolved by looking up the existing
 * {@code skos:Concept} via its {@code dcterms:identifier} (e.g. {@code TERM-1}). If an
 * identity matches no concept, or more than one, the write is aborted and nothing is
 * persisted - dangling cross-references are never created.</p>
 *
 * <p>The message is deliberately didactic: it names the missing term and points at the tool
 * that would create it first ({@code term_add}).</p>
 */
public class UnresolvedReferenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnresolvedReferenceException(String message) {
        super(message);
    }
}
