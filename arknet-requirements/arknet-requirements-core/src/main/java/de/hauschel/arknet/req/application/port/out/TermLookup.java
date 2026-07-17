package de.hauschel.arknet.req.application.port.out;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.WorkspaceId;

/**
 * Driven port: resolves a glossary term's human-typed business code to its opaque subject
 * identity in the shared workspace store.
 *
 * <p>This is the strict cross-BC reference resolution the requirements component needs for
 * {@code arkreq:usesTerm} (issue #36, identity-carrying since #77): the requirements component
 * must not depend on {@code arknet-ubiquitous-language-core}, so it cannot look a term up as a
 * domain object - it can only ask the shared store, through this port, which resource a code
 * currently names. Resolution goes via the term's {@code dcterms:identifier}, never its
 * {@code skos:prefLabel}, so a link survives relabelling the term.</p>
 *
 * <p>Called once, at the moment a term is linked - not on every subsequent write of the
 * requirement that links it. An implementation rejects an unknown or ambiguous code with a
 * runtime exception rather than returning an empty or default result; callers are meant to let
 * that exception propagate as a didactic rejection of the write, not to handle a missing term as
 * a normal case.</p>
 */
public interface TermLookup {

    /**
     * Resolves {@code termCode} to the identity of the glossary term it currently names within
     * {@code workspaceId}.
     *
     * @param workspaceId the workspace (architecture model) to resolve the code in
     * @param termCode    the term's human-readable business code, e.g. {@code TERM-1}
     * @return the resolved term's opaque subject identity
     */
    ResourceId resolveByCode(WorkspaceId workspaceId, String termCode);
}
