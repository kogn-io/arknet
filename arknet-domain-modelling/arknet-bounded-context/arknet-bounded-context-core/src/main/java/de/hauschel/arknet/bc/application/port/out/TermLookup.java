// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.out;

import java.util.List;
import java.util.Map;

import de.hauschel.arknet.dm.shared.TermCode;
import de.hauschel.arknet.kernel.ResourceId;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driven port: translates between a glossary term's human-typed business code and its opaque
 * subject identity in the shared project store, in both directions.
 *
 * <p>This is the strict cross-component reference resolution the bounded-context component needs for
 * {@code arkddd:ubiquitousLanguageTerm}: the bounded-context component must not
 * depend on any module of the glossary component - not even inside its own bounded context
 * (ADR-49) - so it cannot look a term up as a domain object. It can only ask the shared store,
 * through this port, which resource a code currently names and which code currently names a
 * resource; the store's glossary graph is the published language both directions read. Resolution goes via the term's {@code dcterms:identifier}, never its
 * {@code skos:prefLabel}, so a link survives relabelling the term.</p>
 *
 * <p>Called once, at the moment a term is linked - not on every subsequent write of the bounded
 * context that links it. An implementation rejects an unknown or ambiguous code with a runtime
 * exception rather than returning an empty or default result; callers are meant to let that
 * exception propagate as a didactic rejection of the write, not to handle a missing term as a
 * normal case.</p>
 */
public interface TermLookup {

    /**
     * Resolves {@code termCode} to the identity of the glossary term it currently names within
     * {@code projectId}.
     *
     * @param projectId the project (architecture model) to resolve the code in
     * @param termCode    the term's human-readable business code, e.g. {@code TERM-1}
     * @return the resolved term's opaque subject identity
     */
    ResourceId resolveByCode(ProjectId projectId, String termCode);

    /**
     * The reverse direction: the business codes of the terms {@code ids} currently identify
     * within {@code projectId}, resolved in a single batch (one store round-trip, not one per
     * id).
     *
     * <p><strong>Never rejects.</strong> Unlike {@link #resolveByCode} - which answers a write
     * and must refuse an unknown code - this answers a display and has no error case: an id that
     * names no term in the project is simply absent from the result, and its caller decides what
     * to show instead.</p>
     *
     * @param projectId the project (architecture model) to resolve the identities in
     * @param ids       the opaque identities to resolve; may be empty
     * @return the code of every id that names a term, keyed by that id; never {@code null}
     */
    Map<ResourceId, TermCode> codesById(ProjectId projectId, List<ResourceId> ids);
}
