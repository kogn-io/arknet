// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import io.kogn.rdf.dataset.hosting.DatasetHandle;
import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetStoreConfig;
import io.kogn.rdf.rdf4j.dataset.hosting.DatasetLifecycleRdf4j;

/**
 * {@link StorageFolderBackup} against a real {@code NativeStore} under {@link TempDir}: the copy of
 * a closed project directory opens cleanly and holds the same data, the library does not take the
 * backup directory for a project, and a backup that cannot be written leaves nothing behind.
 */
class StorageFolderBackupTest {

    private static final DatasetId PROJECT = new DatasetId("proj/ä-1");
    private static final List<String> STEPS = List.of("legacy layout migration");
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-27T10:15:00Z"), ZoneOffset.UTC);

    @TempDir
    Path storageRoot;

    @TempDir
    Path restoreRoot;

    @Test
    void theDirectoryNameIsTheOneTheLibraryWrites() {
        DatasetLifecycle lifecycle = lifecycle(storageRoot);
        try {
            write(lifecycle, "<urn:s> <urn:p> \"o\"");
        } finally {
            lifecycle.shutDownAll();
        }

        assertTrue(Files.isDirectory(storageRoot.resolve(StorageFolderBackup.directoryName(PROJECT))));
    }

    @Test
    void theCopyOfAClosedProjectOpensWithTheSameData() throws IOException {
        DatasetLifecycle lifecycle = lifecycle(storageRoot);
        Set<String> before;
        MigrationBackup.Copy copy;
        try {
            write(lifecycle, "<urn:s> <urn:p> \"eins\"@de . <urn:s> <urn:q> <urn:o>");
            before = quads(lifecycle);

            copy = backup().backUp(lifecycle, PROJECT, STEPS);

            assertEquals(Set.of(PROJECT), lifecycle.list(), "the library skips the backup directory");
            assertEquals(before, quads(lifecycle), "the backed-up project opens again in place");
        } finally {
            lifecycle.shutDownAll();
        }
        String name = StorageFolderBackup.directoryName(PROJECT);
        assertEquals(storageRoot.resolve(StorageFolderBackup.BACKUP_DIRECTORY).resolve(name)
                .resolve("20261127T101500Z"), copy.directory());
        assertTrue(copy.bytes() > 0);

        copyTree(copy.directory().resolve(name), restoreRoot.resolve(name));
        DatasetLifecycle restored = lifecycle(restoreRoot);
        try {
            assertEquals(Set.of(PROJECT), restored.list());
            assertEquals(before, quads(restored));
        } finally {
            restored.shutDownAll();
        }
    }

    @Test
    void theNoteNamesProjectVersionAndSteps() throws IOException {
        DatasetLifecycle lifecycle = lifecycle(storageRoot);
        MigrationBackup.Copy copy;
        try {
            write(lifecycle, "<urn:s> <urn:p> \"o\"");
            copy = backup().backUp(lifecycle, PROJECT, STEPS);
        } finally {
            lifecycle.shutDownAll();
        }

        String note = Files.readString(copy.directory().resolve(StorageFolderBackup.NOTE_FILE));
        assertTrue(note.contains("project id: " + PROJECT.value()), note);
        assertTrue(note.contains("daemon version: 9.9.9"), note);
        assertTrue(note.contains("due steps: legacy layout migration"), note);
        assertTrue(note.contains("taken at: 2026-11-27T10:15:00Z"), note);
    }

    @Test
    void aSecondBackupInTheSameSecondGetsItsOwnDirectory() {
        DatasetLifecycle lifecycle = lifecycle(storageRoot);
        try {
            write(lifecycle, "<urn:s> <urn:p> \"o\"");
            MigrationBackup.Copy first = backup().backUp(lifecycle, PROJECT, STEPS);
            MigrationBackup.Copy second = backup().backUp(lifecycle, PROJECT, STEPS);

            assertTrue(Files.isDirectory(first.directory()));
            assertTrue(Files.isDirectory(second.directory()));
            assertFalse(first.directory().equals(second.directory()));
        } finally {
            lifecycle.shutDownAll();
        }
    }

    @Test
    void aBackupThatCannotBeWrittenFailsAndLeavesNothing() throws IOException {
        DatasetLifecycle lifecycle = lifecycle(storageRoot);
        try {
            write(lifecycle, "<urn:s> <urn:p> \"o\"");
            Path blocked = storageRoot.resolve(StorageFolderBackup.BACKUP_DIRECTORY);
            Files.writeString(blocked, "not a directory");

            MigrationBackupException failure = assertThrows(MigrationBackupException.class,
                    () -> backup().backUp(lifecycle, PROJECT, STEPS));

            assertTrue(failure.getMessage().contains(PROJECT.value()), failure.getMessage());
            assertTrue(Files.isRegularFile(blocked));
        } finally {
            lifecycle.shutDownAll();
        }
    }

    @Test
    void aProjectStillOpenIsNotCopied() {
        DatasetLifecycle lifecycle = lifecycle(storageRoot);
        try {
            write(lifecycle, "<urn:s> <urn:p> \"o\"");
            try (DatasetHandle held = lifecycle.acquire(PROJECT)) {
                assertThrows(MigrationBackupException.class, () -> backup().backUp(lifecycle, PROJECT, STEPS));
            }
            assertFalse(Files.exists(storageRoot.resolve(StorageFolderBackup.BACKUP_DIRECTORY)
                    .resolve(StorageFolderBackup.directoryName(PROJECT)).resolve("20261127T101500Z")));
        } finally {
            lifecycle.shutDownAll();
        }
    }

    @Test
    void discardIncompleteRemovesOnlyInterruptedBackups() throws IOException {
        Path datasetBackups = storageRoot.resolve(StorageFolderBackup.BACKUP_DIRECTORY).resolve("abc");
        Path complete = Files.createDirectories(datasetBackups.resolve("20261126T000000Z"));
        Path interrupted = Files.createDirectories(datasetBackups.resolve("20261127T000000Z"
                + StorageFolderBackup.INCOMPLETE_SUFFIX).resolve("abc"));
        Files.writeString(interrupted.resolve("part"), "x");

        backup().discardIncomplete();

        assertTrue(Files.isDirectory(complete));
        assertFalse(Files.exists(interrupted.getParent()));
    }

    private StorageFolderBackup backup() {
        return new StorageFolderBackup(storageRoot, "9.9.9", CLOCK);
    }

    private static DatasetLifecycle lifecycle(Path root) {
        return new DatasetLifecycleRdf4j(DatasetStoreConfig.persistentDefault(), root);
    }

    private static void write(DatasetLifecycle lifecycle, String triples) {
        try (DatasetHandle handle = lifecycle.acquire(PROJECT)) {
            handle.transactor().inTransaction(tx -> {
                tx.update("INSERT DATA { GRAPH <urn:g> { " + triples + " } }");
                return null;
            });
        }
    }

    private static Set<String> quads(DatasetLifecycle lifecycle) {
        try (DatasetHandle handle = lifecycle.acquire(PROJECT)) {
            return handle.sparqlQuery().select("SELECT ?g ?s ?p ?o WHERE { GRAPH ?g { ?s ?p ?o } }")
                    .map(row -> row.getValue("g").orElseThrow() + " " + row.getValue("s").orElseThrow() + " "
                            + row.getValue("p").orElseThrow() + " " + row.getValue("o").orElseThrow())
                    .collect(Collectors.toSet());
        }
    }

    private static void copyTree(Path source, Path target) throws IOException {
        try (Stream<Path> tree = Files.walk(source)) {
            for (Path entry : tree.toList()) {
                Path to = target.resolve(source.relativize(entry).toString());
                if (Files.isDirectory(entry)) {
                    Files.createDirectories(to);
                } else {
                    Files.copy(entry, to);
                }
            }
        }
    }
}
