// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.analysis.application.port.out;

import de.hauschel.arknet.kernel.ProjectId;
import de.hauschel.arknet.persistence.StoreSnapshot;

/**
 * The one out-port through which this context reads: hands out a snapshot of everything the
 * other contexts published about one project.
 *
 * <p>Model analysis owns no resource of the architecture model. It reads every context over that
 * context's published language, never over one of its modules (ADR-49, ADR-54), and this port is
 * where that read enters the hexagon.</p>
 */
public interface ModelSnapshots {

    /**
     * @param projectId the project whose model is read
     * @return every triple of that project's model, grouped by resource, never {@code null}
     */
    StoreSnapshot read(ProjectId projectId);
}
