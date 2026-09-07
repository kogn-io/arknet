// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.uc.application.port.out;

import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driven port: resolves a glossary term's human-typed business code to its opaque subject
 * identity in the shared project store.
 *
 * <p>Backs {@code uc_link_term} (issue #329), mirroring the sibling requirements bounded
 * context's own {@code TermLookup} exactly: the use-cases component must not depend on
 * {@code arknet-ubiquitous-language-core}, so it cannot look a term up as a domain object - it
 * can only ask the shared store, through this port, which resource a code currently names.
 * Resolution goes via the term's {@code dcterms:identifier}, never its {@code skos:prefLabel}, so
 * a link survives relabelling the term.</p>
 *
 * <p>Called once, at the moment a term is linked - not on every subsequent write of the use case
 * that links it. An implementation rejects an unknown or ambiguous code with a runtime exception
 * rather than returning an empty or default result; callers are meant to let that exception
 * propagate as a didactic rejection of the write, not to handle a missing term as a normal
 * case.</p>
 *
 * <p><strong>Not the same port as {@code RoleLookup}.</strong> A role is its own resource type in
 * {@code arknet-actor}'s register, resolved by its {@code ROLE-N} code among subjects typed
 * {@code arkproc:Role} in the roles graph - a different graph, a different type constraint and a
 * different identity space than a glossary term used via {@code arkreq:usesTerm}, which is
 * resolved by {@code dcterms:identifier} among {@code skos:Concept}s - the same distinction
 * {@code KognioRdfTermLookup} (requirements) already draws against {@code KognioRdfRoleLookup}
 * (use-cases).</p>
 */
public interface TermLookup {

    /**
     * Resolves {@code termCode} to the identity of the glossary term it currently names within
     * {@code projectId}.
     *
     * @param projectId the project (architecture model) to resolve the code in
     * @param termCode  the term's human-readable business code, e.g. {@code TERM-1}
     * @return the resolved term's opaque subject identity
     * @throws RuntimeException if {@code termCode} is unknown or ambiguous within
     *                          {@code projectId}. The concrete signal type is deliberately not
     *                          fixed by this port: a real implementation's {@code
     *                          UnresolvedReferenceException} lives in {@code
     *                          arknet-persistence-support}, a module {@code arknet-use-cases-core}
     *                          must not depend on.
     */
    ResourceId resolveByCode(ProjectId projectId, String termCode);
}
