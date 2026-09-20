// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.actor.application.port.in;

import de.hauschel.arknet.actor.domain.Actor;
import de.hauschel.arknet.actor.domain.ActorCode;

import de.hauschel.arknet.kernel.ProjectId;

/**
 * Driving port: correct the name and/or description of an already-created actor, or state
 * name/description in a further language.
 *
 * <p>Backs the MVP tool {@code actor_update}. Both fields are optional: {@code null} leaves that
 * field unchanged, so a caller can correct only the description without restating the name. A
 * non-{@code null} value must still satisfy {@link Actor}'s own invariants (non-blank).</p>
 *
 * <p><strong>{@code null} means "leave alone", never "remove"</strong> - the same rule
 * {@code req_update} gives {@code priority} and {@code constraint_update} gives its text fields.
 * Clearing the optional {@code description} would need a signal of its own rather than an
 * overloading of {@code null}, and nothing asks for one yet.</p>
 *
 * <p><strong>Language (kogn-io/arknet#520), mirroring {@code UpdateRole} exactly.</strong>
 * {@code name}/{@code description} may legally carry several language-tagged variants each
 * (SHACL {@code sh:uniqueLang}), so {@link #update}'s {@code language} names the BCP-47 tag every
 * field this call actually touches is written in, falling back to {@code defaultLanguage} if
 * omitted (issue #258's sweep of a stale untagged sibling applies here exactly as it does for a
 * role).</p>
 *
 * <p><strong>What an actor update deliberately cannot change.</strong> Not its
 * {@link de.hauschel.arknet.actor.domain.ActorType}, and not its {@link ActorCode}: both stand from
 * the moment the actor is created. A retyped actor is not a correction of a spelling but a claim
 * that a different kind of thing is being described, and everything already pointing at
 * {@code ACTOR-N} in prose refers to the actor as classified - so retyping is left out of scope
 * here rather than implemented halfway.</p>
 */
public interface UpdateActor {

    /**
     * Updates the actor identified by {@code code} within a project, leaving any {@code null}/
     * omitted argument unchanged.
     *
     * @param projectId       the project (architecture model) the actor lives in
     * @param code            the actor code, e.g. {@code ACTOR-1}
     * @param name            the new name, or {@code null} to leave it unchanged
     * @param description     the new description, or {@code null} to leave it unchanged
     * @param language        the BCP-47 language tag a non-{@code null} {@code name}/
     *                        {@code description} is written in, or {@code null} to fall back to
     *                        {@code defaultLanguage}. Only the existing literal carrying the tag
     *                        actually written is replaced - every other language-tagged variant of
     *                        a field being corrected survives untouched, except an existing
     *                        untagged one that a fallback to {@code defaultLanguage} sweeps away
     *                        (issue #258)
     * @param defaultLanguage the target project's configured default language, or {@code null} if
     *                        it has none - only consulted for a field this call is actually
     *                        changing and that ships no {@code language}
     * @return the updated actor
     * @throws de.hauschel.arknet.actor.domain.ActorNotFoundException if no actor with {@code code}
     *                    exists in this project
     * @throws de.hauschel.arknet.kernel.MissingDefaultLanguageException if a changed text field
     *                    ships no {@code language} and {@code defaultLanguage} is {@code null} too
     * @throws de.hauschel.arknet.actor.domain.ActorConcurrentlyModifiedException if the write keeps
     *                    losing the race against concurrent writers across every retry attempt
     */
    Actor update(ProjectId projectId, ActorCode code, String name, String description,
            String language, String defaultLanguage);
}
