// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.List;

import de.hauschel.arknet.actor.domain.RoleCode;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: correct the name, description and/or occupancy of an already-created role, or
 * state name/description in a further language.
 *
 * <p>Backs the tool {@code role_update}. {@code name}/{@code description} follow the tri-state
 * {@code null}-means-"leave alone" rule, mirroring {@code UpdateConstraint} - not
 * {@link UpdateActor}: both may legally carry several language-tagged variants each (SHACL
 * {@code sh:uniqueLang}), so {@link #update}'s {@code language} names the BCP-47 tag every field
 * this call actually touches is written in, falling back to {@code defaultLanguage} if omitted
 * (issue #258's sweep of a stale untagged sibling applies here exactly as it does for a
 * constraint). See {@link de.hauschel.arknet.actor.domain.Role}'s own javadoc for why this
 * hexagon's two resource types disagree on this.</p>
 *
 * <p><strong>{@code filledByActorCodes} is a second, independent tri-state</strong> - exactly the
 * tri-state {@code adr_update}'s four reference lists carry (see {@code UpdateAdr}'s own javadoc,
 * whose wording this one deliberately reuses): {@code null} leaves the existing occupancy
 * untouched, an empty list is the explicit, unambiguous signal to remove every occupant (vacating
 * the role, a legitimate state - TERM-21/FR-7), and a non-empty list replaces the occupancy
 * wholesale. Independent of {@code language}: touching the occupancy never demands a language, and
 * touching the text never demands an occupancy list.</p>
 *
 * <p><strong>What a role update deliberately cannot change.</strong> Not its {@link RoleCode}: it
 * stands from the moment the role is created, the same reasoning {@link UpdateActor} gives for
 * {@link de.hauschel.arknet.actor.domain.ActorCode} - everything already pointing at
 * {@code ROLE-N} in prose refers to the role as named.</p>
 */
public interface UpdateRole {

    /**
     * Updates the role identified by {@code code} within a project, leaving any {@code null}/
     * omitted argument unchanged.
     *
     * @param projectId          the project (architecture model) the role lives in
     * @param code               the role code, e.g. {@code ROLE-1}
     * @param name               the new name, or {@code null} to leave it unchanged
     * @param description        the new description, or {@code null} to leave it unchanged
     * @param filledByActorCodes business codes of the actors that should fill this role going
     *                           forward, replacing the existing occupants wholesale; an empty list
     *                           removes every occupant, {@code null} leaves the occupancy unchanged
     * @param language           the BCP-47 language tag a non-{@code null} {@code name}/
     *                           {@code description} is written in, or {@code null} to fall back to
     *                           {@code defaultLanguage}. Only the existing literal carrying the
     *                           tag actually written is replaced - every other language-tagged
     *                           variant of a field being corrected survives untouched, except an
     *                           existing untagged one that a fallback to {@code defaultLanguage}
     *                           sweeps away (issue #258)
     * @param defaultLanguage    the target project's configured default language, or {@code null}
     *                           if it has none - only consulted for a field this call is actually
     *                           changing and that ships no {@code language}
     * @return the updated role, with its {@code filledBy} occupants resolved
     * @throws de.hauschel.arknet.actor.domain.RoleNotFoundException if no role with {@code code}
     *         exists in this project
     * @throws de.hauschel.arknet.actor.domain.ActorNotFoundException if a
     *         {@code filledByActorCodes} entry names no actor in {@code projectId} - rejected
     *         before anything is written
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if a changed text field
     *         ships no {@code language} and {@code defaultLanguage} is {@code null} too
     * @throws de.hauschel.arknet.actor.domain.RoleConcurrentlyModifiedException if the write keeps
     *         losing the race against concurrent writers across every retry attempt
     */
    RoleDetail update(ProjectId projectId, RoleCode code, String name, String description,
            List<String> filledByActorCodes, String language, String defaultLanguage);
}
