// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.bc.application.port.in;

import java.util.List;

import de.hauschel.arknet.bc.domain.BoundedContext;
import de.hauschel.arknet.bc.domain.BoundedContextCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: correct the name, domain vision and/or linked glossary terms of an already-created
 * bounded context, or state the name/domain vision in a further language.
 *
 * <p>Backs the MVP tool {@code bc_update} (kogn-io/arknet#520) - before that issue, a bounded
 * context had no correction path at all, only {@code bc_link_term}/{@code bc_link_context} as later
 * mutators. {@code name}/{@code domainVision} are optional: {@code null} leaves that field
 * unchanged, so a caller can correct only the domain vision without restating the name. A
 * non-{@code null} value must still satisfy {@link BoundedContext}'s own invariants (non-blank).</p>
 *
 * <p><strong>{@code termCodes} is a tri-state, mirroring {@code req_update}'s
 * {@code usesTermCodes} and {@code adr_update}'s {@code usesTermCodes} (kogn-io/arknet#567).
 * </strong> {@code null} leaves the existing {@code arkddd:ubiquitousLanguageTerm} edges untouched,
 * an empty list is the explicit, unambiguous signal to remove every one of them, and a non-empty
 * list replaces them wholesale - the one field of this port where {@code null} and empty must not
 * be conflated. {@code bc_link_term} remains the convenient way to add a single edge without
 * restating the rest; this parameter is what lets a caller drop one, which {@code bc_link_term}
 * (deliberately idempotent, add-only) cannot.</p>
 *
 * <p><strong>What a bounded-context update deliberately cannot change.</strong> Not its
 * {@link de.hauschel.arknet.bc.domain.Subdomain} classification and not its {@code ownedBy} team -
 * both stay exactly as fixed since {@code bc_add}. This port exists for text and linked terms, and
 * for the second language {@code bc_add} could not write in one call - not to turn
 * {@link BoundedContext} into a generally mutable resource, mirroring {@code UpdateConstraint}'s
 * identical narrowness for {@code Constraint}.</p>
 *
 * <p><strong>Language.</strong> {@code name}/{@code domainVision} may each legally carry several
 * language-tagged variants (SHACL {@code sh:uniqueLang}). {@code language} names the BCP-47 tag
 * every field this call actually touches is written in, falling back to {@code defaultLanguage} if
 * omitted (issue #258's sweep of a stale untagged sibling applies here exactly as it does for a
 * constraint or a role). A field this call does not touch keeps every language variant it already
 * had, untouched. {@code termCodes} carries no language of its own and never influences which
 * language {@code name}/{@code domainVision} are written under. No FR-10 label-equality guard
 * applies to {@code name} - see {@link BoundedContext#name()}'s own javadoc for why.</p>
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
     * @param termCodes       business codes of the glossary terms this bounded context should use
     *                        going forward, e.g. {@code TERM-1}, replacing the existing
     *                        {@code arkddd:ubiquitousLanguageTerm} edges wholesale; an empty list
     *                        clears them all, {@code null} leaves them unchanged
     *                        (kogn-io/arknet#567) - see the class-level note
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
     * @throws RuntimeException if a {@code termCodes} entry names a glossary term unknown within
     *         {@code projectId} - the same didactic rejection {@code bc_link_term} raises, thrown
     *         before anything is written
     */
    BoundedContext update(ProjectId projectId, BoundedContextCode code, String name, String domainVision,
            List<String> termCodes, String language, String defaultLanguage);
}
