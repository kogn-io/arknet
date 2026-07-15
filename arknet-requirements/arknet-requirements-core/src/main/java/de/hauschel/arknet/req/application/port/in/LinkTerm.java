package de.hauschel.arknet.req.application.port.in;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.req.domain.Requirement;
import de.hauschel.arknet.req.domain.RequirementId;
import de.hauschel.arknet.req.domain.TermRef;

/**
 * Driving port: link a requirement to a glossary term of the ubiquitous language.
 *
 * <p>Backs the MVP tool {@code req_link_term}. The edge ({@code arkreq:usesTerm}) is owned
 * by the requirements bounded context - that is what keeps the dependency direction
 * requirements -&gt; ubiquitous-language rather than the other way round.</p>
 *
 * <p>Whether the referenced term actually exists is not decided here: {@link TermRef} is a
 * bare identity, resolved (and rejected if unknown) by the driven adapter against the
 * shared workspace store.</p>
 */
public interface LinkTerm {

    /**
     * Links {@code term} to the requirement {@code id}. Linking an already-linked term is an
     * idempotent no-op.
     *
     * @return the requirement including the link
     */
    Requirement linkTerm(WorkspaceId workspaceId, RequirementId id, TermRef term);
}
