// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.prj.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.prj.domain.DefaultLanguageNotMaintainedException;
import de.hauschel.arknet.prj.domain.Project;
import de.hauschel.arknet.prj.domain.ProjectNotFoundException;

/**
 * Driving port: correct a project's optional description, default display language and/or the set
 * of languages it undertakes to maintain, after the fact.
 *
 * <p>Backs the tool {@code project_update}. Deliberately separate from {@link RenameProject}
 * and {@link AttachAnchor}, not a third field on either: those two go through {@link
 * de.hauschel.arknet.prj.application.port.out.ProjectRegistry#compareAndUpdate}, a
 * replace-by-identity write of the project's {@code label}/{@code anchors} - folding a
 * multilingual {@code description} into that same replace would collapse every other language
 * variant on the very next rename or attached anchor, exactly the bug fixed for
 * {@code term_update} (issue #228). This port instead reaches a dedicated, targeted patch
 * ({@code ProjectRegistry#updateAttributes}) that touches only {@code dcterms:description}/
 * {@code arkprj:defaultLanguage}, scoped to the same language tag as what is being written -
 * {@code label} and {@code anchors} (and every other language variant of {@code description}
 * this call does not touch) survive completely untouched.</p>
 *
 * <p>Every argument is optional: {@code null} leaves that field unchanged, mirroring {@code
 * UpdateTerm}'s "{@code null} means unchanged" contract. A call with every argument {@code null}
 * is a no-op - no write, no revision, no moved head - for the same reason {@code term_update}'s
 * equivalent no-op is: recording one for an empty patch would grow the provenance trail without
 * cause and hand a concurrent CAS writer a spurious conflict it did not actually have.</p>
 *
 * <p>{@code maintainedLanguages} is the one three-state argument, the same tri-state {@code
 * adr_update}'s reference lists carry: {@code null} leaves the set untouched, a list replaces it
 * wholesale, and an <em>empty</em> list removes it - a set cannot be cleared by omission, because
 * omission already means "leave alone". Whichever way it moves, the resulting pair is checked:
 * the project's default language after this call has to be a member of its maintained set after
 * this call, so both a set that no longer holds the default and a default that leaves the set are
 * refused ({@link Project#requireDefaultLanguageMaintained}).</p>
 */
public interface UpdateProject {

    /**
     * Corrects {@code projectId}'s description and/or default language, leaving any {@code null}
     * argument unchanged.
     *
     * @param projectId           the project to correct
     * @param description         the new description, or {@code null} to leave every existing
     *                            {@code dcterms:description} triple untouched
     * @param descriptionLanguage the BCP-47 language tag the new {@code description} is written
     *                            in (e.g. {@code "de"}), or {@code null} for a plain, untagged
     *                            literal. Only the existing description literal carrying this
     *                            same tag is replaced - every other language variant survives.
     *                            Ignored if {@code description} is {@code null}
     * @param defaultLanguage     the new default language, or {@code null} to leave the existing
     *                            {@code arkprj:defaultLanguage} untouched
     * @param maintainedLanguages the languages this project should maintain going forward,
     *                            replacing the existing set wholesale; an empty list removes the
     *                            set, {@code null} leaves it untouched
     * @return the project's up-to-date state after the correction
     * @throws ProjectNotFoundException if no project is registered under {@code projectId}
     * @throws DefaultLanguageNotMaintainedException if the resulting default language is not one
     *                                  of the resulting, non-empty maintained set
     */
    Project update(ProjectId projectId, String description, String descriptionLanguage,
            String defaultLanguage, List<String> maintainedLanguages);
}
