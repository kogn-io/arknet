// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.mcp.dataset;

/**
 * Thrown by {@link LegacyLayoutRefusingDatasetLifecycle} when a project dataset still holds the
 * storage layout of an earlier arknet version - one named graph per component and a provenance
 * graph - instead of the one versioned content graph. The dataset is neither read nor written.
 */
public class LegacyDatasetLayoutException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * @param message names the dataset and what the caller has to do
     */
    public LegacyDatasetLayoutException(String message) {
        super(message);
    }
}
