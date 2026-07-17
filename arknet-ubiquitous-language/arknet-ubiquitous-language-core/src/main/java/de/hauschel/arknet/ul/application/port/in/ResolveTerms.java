package de.hauschel.arknet.ul.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.domain.Term;

/**
 * Driving port: batch-resolves opaque term identities back to full {@link Term}s.
 *
 * <p>The ubiquitous-language bounded context owns the glossary, so it - not a caller reading
 * the store directly - is who answers "what does this identity currently name?" This exists
 * for a <em>sibling</em> bounded context's driving (In-) adapter to consume (issue #77): an
 * In-Adapter is the gate into its own hexagon, not part of its core, so it may call another
 * hexagon's driving port without breaking the "no {@code *-core} depends on another bounded
 * context" invariant, which binds the {@code *-core} modules, not the adapters around them. The
 * requirements MCP adapter uses this to render a linked term's business code ({@code TERM-1})
 * instead of its bare subject IRI, without the requirements bounded context ever depending on
 * {@code arknet-ubiquitous-language-core}.</p>
 *
 * <p><strong>Never rejects.</strong> Unlike {@code GetTerm} (single lookup by code, empty if
 * absent) this is a batch lookup by identity with no error case: an id that resolves to nothing
 * in the workspace is simply absent from the result. The caller - not this port - decides
 * whether "missing" means "fall back to something else" or is itself an error.</p>
 */
public interface ResolveTerms {

    /**
     * Resolves {@code ids} to the {@link Term}s they currently identify within
     * {@code workspaceId}, in a single batch (one store round-trip, not one per id).
     *
     * @param workspaceId the workspace (architecture model) to resolve terms in
     * @param ids         the opaque identities to resolve; may be empty
     * @return the terms found; an id absent from the workspace is simply absent here too, never
     *         {@code null}
     */
    List<Term> getById(WorkspaceId workspaceId, ResourceId... ids);
}
