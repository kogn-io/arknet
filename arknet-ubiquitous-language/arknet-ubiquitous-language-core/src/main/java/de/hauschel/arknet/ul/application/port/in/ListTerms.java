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
     * Returns all terms currently under management in the given workspace glossary.
     *
     * @param projectId the workspace (architecture model) to list terms from
     * @return all terms, never {@code null}
     */
    List<Term> list(ProjectId projectId);
}
