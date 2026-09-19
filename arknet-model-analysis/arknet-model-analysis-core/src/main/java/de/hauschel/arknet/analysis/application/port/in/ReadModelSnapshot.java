// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.StoreSnapshot;

/**
 * Reads one project's model as a raw snapshot of its published language: the in-port behind
 * {@code store_check}.
 *
 * <p>The checks run over the snapshot rather than over the traced graph because they ask about
 * fields and literals - a missing language variant, a role and a term carrying the same name, a
 * step no acceptance criterion stands behind - and not about edges between resources.</p>
 */
public interface ReadModelSnapshot {

    /**
     * @param projectId the project whose model is read
     * @return every triple of that project's model, grouped by resource, never {@code null}
     */
    StoreSnapshot read(ProjectId projectId);
}
