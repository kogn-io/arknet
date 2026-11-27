// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.resource.application.port.in;

import de.hauschel.arknet.kernel.ProjectId;

/** Reads the history the versioning recorded for one resource of any type. */
public interface ResourceHistory {

    /**
     * Reads the history of the resource a handle names.
     *
     * @param projectId     the project to read
     * @param handle        a business code, a full IRI or a prefixed name
     * @param displayLocale the display language of a deleted resource's last version, {@code null}
     *                      or blank for the default
     * @return the history
     * @throws IllegalArgumentException if the handle names no resource, living or deleted, or more
     *                                  than one
     */
    HistoryReading history(ProjectId projectId, String handle, String displayLocale);
}
