// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.in;

import de.hauschel.arknet.kernel.WorkspaceId;
import de.hauschel.arknet.ul.domain.ActorFacet;
import de.hauschel.arknet.ul.domain.Term;
import de.hauschel.arknet.ul.domain.TermCode;

/**
 * Driving port: correct the preferred label, definition and/or Actor facette of an
 * already-created term, keeping its identity (and thus every existing
 * {@code arkreq:usesTerm}/{@code primaryActor}/{@code supportingActors} link into it) intact.
 *
 * <p>Backs the MVP tool {@code term_update} (issue #163). Before this port existed, correcting a
 * term's wording meant registering a fresh one via {@link AddTerm} - which mints a new identity
 * and orphans every existing link to the old one. As with the requirements bounded context's
 * sibling {@code UpdateRequirement} port (#162), every field here is optional: {@code null}
 * leaves that field unchanged, so a caller can correct only the definition without having to
 * restate the label.</p>
 *
 * <p>Every argument here passes straight through to {@link
 * de.hauschel.arknet.ul.application.port.out.TermRepository#update} unmerged - this port's
 * implementation must not pre-read the current term and fold an omitted field's old value into a
 * fresh {@link Term} before writing it, which would round-trip that field through {@link Term}'s
 * single-{@code String} projection and silently collapse a multi-valued {@code skos:prefLabel}/
 * {@code skos:definition} (issues #80/#81) down to one value even though the caller never asked
 * to change it. "Not provided" and "provided" must stay distinguishable all the way to the
 * out-adapter, which is the only place that knows how to leave an untouched predicate's triples
 * alone.</p>
 *
 * <p><strong>Actor facette.</strong> A {@code null} {@code actorFacet} argument leaves an
 * already-set facette untouched - it is not itself a "replace with null" signal, since {@code
 * null} is already the sentinel for every other field here. Explicitly removing a term's Actor
 * facette once set is out of this MVP's scope (no caller need has surfaced yet); it would need a
 * distinct signal (e.g. a wrapper type) rather than overloading {@code null}. The same
 * null-means-unchanged rule applies one level down: within a non-{@code null} {@link ActorFacet},
 * a {@code null} {@link ActorFacet#role() role} leaves an already-set role untouched too, so
 * correcting only the kind (e.g. {@code HUMAN} to {@code SYSTEM}) never has to restate the role to
 * avoid losing it.</p>
 */
public interface UpdateTerm {

    /**
     * Updates the term identified by {@code code} within a workspace, leaving any {@code null}
     * argument unchanged.
     *
     * @param workspaceId the workspace (architecture model) the term lives in
     * @param code        the term code, e.g. {@code TERM-1}
     * @param prefLabel   the new preferred label, or {@code null} to leave it unchanged
     * @param definition  the new definition, or {@code null} to leave it unchanged
     * @param actorFacet  the new Actor facette, or {@code null} to leave an already-set one
     *                    unchanged
     * @return the updated term
     */
    Term update(WorkspaceId workspaceId, TermCode code, String prefLabel, String definition, ActorFacet actorFacet);
}
