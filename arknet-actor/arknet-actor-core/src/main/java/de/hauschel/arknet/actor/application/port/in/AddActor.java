// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorType;
import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: register a new actor.
 *
 * <p>Backs the MVP tool {@code actor_add}. Identity assignment ({@code ACTOR-N}, one running
 * number shared by all four {@link ActorType}s) is policy of the implementing application
 * service.</p>
 *
 * <p><strong>No glossary obligation.</strong> Adding an actor writes an actor and nothing else: no
 * {@code skos:Concept}, no definition, no {@code TERM-N}. An actor that also deserves a glossary
 * entry gets one through {@code term_add}, as a separate decision.</p>
 *
 * <p><strong>Language (kogn-io/arknet#520).</strong> {@code name} and {@code description} are
 * written as language-tagged literals, both under the single {@link NewActor#language()} this one
 * call names - mirrors {@code AddRole} exactly. A second language is added afterwards by
 * {@link UpdateActor}, one tag per call.</p>
 */
public interface AddActor {

    /**
     * Adds a new actor.
     *
     * @param projectId       the project (architecture model) to add the actor to
     * @param command         the data describing the actor to create
     * @param defaultLanguage the target project's configured default language (see
     *                        {@link de.hauschel.arknet.kernel.ResolvedProject#defaultLanguage()}),
     *                        or {@code null} if it has none - used only when
     *                        {@link NewActor#language()} is omitted
     * @return the persisted actor including its assigned identity and code
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if {@code command} names
     *         no {@code language} and {@code defaultLanguage} is {@code null} too
     */
    Actor add(ProjectId projectId, NewActor command, String defaultLanguage);

    /**
     * Input data for {@link #add(ProjectId, NewActor, String)}.
     *
     * @param type        which of the four kinds this actor is; fixed from here on
     * @param name        what this actor is called
     * @param description free-text description, or {@code null} if none
     * @param language    the BCP-47 language tag {@code name} and {@code description} are written
     *                    in (e.g. {@code "de"}), or {@code null} to fall back to the project's
     *                    configured default language
     */
    record NewActor(ActorType type, String name, String description, String language) {
    }
}
