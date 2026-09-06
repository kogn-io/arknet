// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: register a new role.
 *
 * <p>Backs the tool {@code role_add}. Identity assignment ({@code ROLE-N}, a counter of its own -
 * see {@link de.hauschel.arknet.actor.domain.RoleCode}) is policy of the implementing application
 * service.</p>
 *
 * <p><strong>No glossary obligation</strong> - the same rule {@link AddActor} states for an actor:
 * adding a role writes a role and nothing else, no {@code skos:Concept} multi-typing (ADR-39).</p>
 *
 * <p><strong>Language.</strong> {@code name} and {@code description} are written as
 * language-tagged literals, both under the single {@link NewRole#language()} this one call names -
 * mirrors {@code AddConstraint} exactly, not {@link AddActor} (see {@link
 * de.hauschel.arknet.actor.domain.Role}'s own javadoc for why the two resource types of this
 * hexagon disagree here). A second language is added afterwards by {@link UpdateRole}, one tag per
 * call.</p>
 */
public interface AddRole {

    /**
     * Adds a new role.
     *
     * @param projectId       the project (architecture model) to add the role to
     * @param command         the data describing the role to create
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - used only when
     *                        {@link NewRole#language()} is omitted
     * @return the persisted role, with its {@code filledBy} occupants resolved
     * @throws de.hauschel.arknet.actor.domain.ActorNotFoundException if a
     *         {@code filledByActorCodes} entry names no actor in {@code projectId} - rejected
     *         before anything is written
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if {@code command} names
     *         no {@code language} and {@code defaultLanguage} is {@code null} too
     */
    RoleDetail add(ProjectId projectId, NewRole command, String defaultLanguage);

    /**
     * Input data for {@link #add(ProjectId, NewRole, String)}.
     *
     * @param name               what this role is called
     * @param description        free-text description, or {@code null} if none
     * @param filledByActorCodes business codes (e.g. {@code ACTOR-1}) of the actors that fill this
     *                           role from the start; {@code null}/empty leaves the role unfilled -
     *                           a legitimate, common state (TERM-21/FR-7), not a caller mistake
     * @param language           the BCP-47 language tag {@code name} and {@code description} are
     *                           written in (e.g. {@code "de"}), or {@code null} to fall back to
     *                           the project's configured default language
     */
    record NewRole(String name, String description, List<String> filledByActorCodes, String language) {

        public NewRole {
            filledByActorCodes = filledByActorCodes == null ? List.of() : List.copyOf(filledByActorCodes);
        }
    }
}
