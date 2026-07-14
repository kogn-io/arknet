package de.hauschel.arknet.uc.adapter.kogniordf;

/**
 * Thrown when a use case references another resource by label - a functional requirement
 * ({@code arkreq:stepRealises}) or an actor ({@code arkreq:primaryActor}/
 * {@code arkreq:supportingActor}) - that cannot be resolved unambiguously against the target
 * workspace's store.
 *
 * <p><strong>Strict reference resolution (issue #41).</strong> Use cases, requirements and
 * ubiquitous-language terms/actors all live in the <em>same</em> per-workspace store. When a
 * use case is persisted, every label reference is resolved by looking up the existing
 * resource ({@code dcterms:identifier} for requirements, {@code skos:prefLabel} plus an actor
 * type for actors). If a label matches no resource, or more than one, the write is aborted
 * and nothing is persisted - dangling cross-references are never created.</p>
 *
 * <p>The message is deliberately didactic: it names the missing label and points at the tool
 * that would create it first (e.g. {@code req_add}, {@code term_add}).</p>
 */
public class UnresolvedReferenceException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    UnresolvedReferenceException(String message) {
        super(message);
    }
}
