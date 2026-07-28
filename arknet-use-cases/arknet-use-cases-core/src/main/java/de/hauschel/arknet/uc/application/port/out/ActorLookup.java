// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.out;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driven port: resolves an actor's human-typed name to its opaque subject identity in the shared
 * workspace store.
 *
 * <p>This is the strict cross-BC reference resolution the use-cases component needs for
 * {@code arkreq:primaryActor}/{@code arkreq:supportingActor} (issue #89, the use-cases analogue
 * of requirements' #77): the use-cases component must not depend on
 * {@code arknet-ubiquitous-language-core}, so it cannot look an actor up as a domain object - it
 * can only ask the shared store, through this port, which resource a name currently denotes.
 * Resolution goes via the actor term's {@code skos:prefLabel}, constrained to concepts carrying
 * an actor type ({@code arkproc:HumanActor}/{@code arkproc:SystemActor}).</p>
 *
 * <p>Called once, at the moment a use case is written - not on every subsequent read. An
 * implementation rejects an unknown or ambiguous name with a runtime exception rather than
 * returning an empty or default result; callers are meant to let that exception propagate as a
 * didactic rejection of the write, not to handle a missing actor as a normal case.</p>
 */
public interface ActorLookup {

    /**
     * Resolves {@code actorName} to the identity of the actor term it currently names within
     * {@code projectId}.
     *
     * @param projectId the project (architecture model) to resolve the name in
     * @param actorName   the actor's human-readable name, e.g. {@code Customer}
     * @return the resolved actor's opaque subject identity
     */
    ResourceId resolveByName(ProjectId projectId, String actorName);
}
