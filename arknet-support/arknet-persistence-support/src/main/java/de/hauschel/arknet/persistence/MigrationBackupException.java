// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

/**
 * No complete backup of a dataset could be written before its migration, so the dataset is not
 * migrated. The message names the dataset, the place the backup was to be written and the cause.
 */
public class MigrationBackupException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * Creates the exception.
     *
     * @param message what failed and where
     * @param cause   the underlying failure, or {@code null}
     */
    public MigrationBackupException(String message, Throwable cause) {
        super(message, cause);
    }
}
