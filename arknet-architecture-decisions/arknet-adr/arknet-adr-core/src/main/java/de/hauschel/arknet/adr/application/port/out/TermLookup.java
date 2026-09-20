// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.adr.application.port.out;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.kernel.ResourceId;

/**
 * Driven port: resolves a glossary term's human-typed business code to its opaque subject
 * identity in the shared project store.
 *
 * <p>This is the strict cross-BC reference resolution the ADR component needs for
 * {@code arkarch:usesTerm} (kogn-io/arknet#393): the ADR component must not depend on
 * {@code arknet-ubiquitous-language-core}, so it cannot look a term up as a domain object - it can
 * only ask the shared store, through this port, which resource a code currently names. Resolution
 * goes via the term's {@code dcterms:identifier}, never its {@code skos:prefLabel}, so a link
 * survives relabelling the term. Structurally 1:1 to {@link RequirementLookup}/
 * {@link BoundedContextLookup}, and to the sibling requirements/use-cases/bounded-context
 * components' own {@code TermLookup} ports.</p>
 *
 * <p>Called once, at the moment a decision is recorded - not on every subsequent write of that
 * decision. An implementation rejects an unknown or ambiguous code with a runtime exception rather
 * than returning an empty or default result; callers are meant to let that exception propagate as a
 * didactic rejection of the write, not to handle a missing term as a normal case.</p>
 */
public interface TermLookup {

    /**
     * Resolves {@code termCode} to the identity of the glossary term it currently names within
     * {@code projectId}.
     *
     * @param projectId the project (architecture model) to resolve the code in
     * @param termCode  the term's human-readable business code, e.g. {@code TERM-1}
     * @return the resolved term's opaque subject identity
     */
    ResourceId resolveByCode(ProjectId projectId, String termCode);
}
