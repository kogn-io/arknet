// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: correct the name and/or domain vision of an already-created bounded context, or
 * state either of them in a further language.
 *
 * <p>Backs the new MVP tool {@code bc_update} (kogn-io/arknet#520) - before this issue, a bounded
 * context had no correction path at all, only {@code bc_link_term}/{@code bc_link_context} as later
 * mutators. Both fields are optional: {@code null} leaves that field unchanged, so a caller can
 * correct only the domain vision without restating the name. A non-{@code null} value must still
 * satisfy {@link BoundedContext}'s own invariants (non-blank).</p>
 *
 * <p><strong>What a bounded-context update deliberately cannot change.</strong> Not its
 * {@link de.hauschel.arknet.bc.domain.Subdomain} classification, not its {@code ownedBy} team, and
 * not its linked terms/aggregates - all of those stay exactly as fixed since {@code bc_add} (or,
 * for terms, since the last {@code bc_link_term}). This port exists for text, and for the second
 * language {@code bc_add} could not write in one call - not to turn {@link BoundedContext} into a
 * generally mutable resource, mirroring {@code UpdateConstraint}'s identical narrowness for
 * {@code Constraint}.</p>
 *
 * <p><strong>Language.</strong> {@code name}/{@code domainVision} may each legally carry several
 * language-tagged variants (SHACL {@code sh:uniqueLang}). {@code language} names the BCP-47 tag
 * every field this call actually touches is written in, falling back to {@code defaultLanguage} if
 * omitted (issue #258's sweep of a stale untagged sibling applies here exactly as it does for a
 * constraint or a role). A field this call does not touch keeps every language variant it already
 * had, untouched. No FR-10 label-equality guard applies to {@code name} - see
 * {@link BoundedContext#name()}'s own javadoc for why.</p>
 */
public interface UpdateBoundedContext {

    /**
     * Updates the bounded context identified by {@code code} within a project, leaving any
     * {@code null}/omitted argument unchanged.
     *
     * @param projectId       the project (architecture model) the bounded context lives in
     * @param code            the bounded-context code, e.g. {@code BC-1}
     * @param name            the new name, or {@code null} to leave it unchanged
     * @param domainVision    the new domain vision, or {@code null} to leave it unchanged
     * @param language        the BCP-47 language tag a non-{@code null} {@code name}/
     *                        {@code domainVision} is written in, or {@code null} to fall back to
     *                        {@code defaultLanguage}. Only the existing literal carrying the tag
     *                        actually written is replaced - every other language-tagged variant of
     *                        a field being corrected survives untouched, except an existing
     *                        untagged one that a fallback to {@code defaultLanguage} sweeps away
     *                        (issue #258)
     * @param defaultLanguage the target project's configured default language, or {@code null} if
     *                        it has none - only consulted for a field this call is actually
     *                        changing and that ships no {@code language}
     * @return the updated bounded context
     * @throws de.hauschel.arknet.bc.domain.BoundedContextNotFoundException if no bounded context
     *         with {@code code} exists in this project
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if a changed text field
     *         ships no {@code language} and {@code defaultLanguage} is {@code null} too
     * @throws de.hauschel.arknet.bc.domain.BoundedContextConcurrentlyModifiedException if the write
     *         keeps losing the race against concurrent writers across every retry attempt
     */
    BoundedContext update(ProjectId projectId, BoundedContextCode code, String name, String domainVision,
            String language, String defaultLanguage);
}
