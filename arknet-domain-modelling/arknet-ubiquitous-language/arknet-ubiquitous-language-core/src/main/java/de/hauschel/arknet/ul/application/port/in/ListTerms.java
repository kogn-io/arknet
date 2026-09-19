// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.ul.application.port.in;

import java.util.List;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.ul.domain.Term;

/**
 * Driving port: list all glossary terms.
 *
 * <p>Backs the MVP tool {@code term_list}.</p>
 */
public interface ListTerms {

    /**
     * Returns all terms currently under management in the given project glossary.
     *
     * @param projectId     the project (architecture model) to list terms from
     * @param displayLocale the BCP-47 language tag the caller wants each term's
     *                      {@code prefLabel}/{@code definition} shown in (e.g. {@code "de"}), or
     *                      {@code null} to fall back to the project's own configured default
     *                      language, and from there to the process-wide default - the same
     *                      fallback chain {@link GetTerm#get} already applies, so a project whose
     *                      default differs from this daemon's sees the same language variant of a
     *                      multi-language term whether it calls {@code term_get} or {@code
     *                      term_list} (issue #274)
     * @return all terms, never {@code null}
     */
    List<Term> list(ProjectId projectId, String displayLocale);
}
