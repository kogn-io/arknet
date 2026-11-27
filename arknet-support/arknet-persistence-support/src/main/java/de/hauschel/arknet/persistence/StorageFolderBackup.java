// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.persistence;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import io.kogn.rdf.dataset.hosting.DatasetId;
import io.kogn.rdf.dataset.hosting.DatasetLifecycle;
import io.kogn.rdf.dataset.hosting.DatasetShutdownOutcome;

/**
 * Backs a project dataset up as a byte-exact copy of its directory under the storage root.
 *
 * <p><strong>Where.</strong> Every backup lies in {@value #BACKUP_DIRECTORY} inside the storage
 * root, the one place that is certain to exist, to be writable and to outlive a container, because
 * the data itself lies there. The dot puts the name outside the Base64url alphabet the library
 * names a dataset directory in, so the library skips it when it lists the datasets. Below it each
 * dataset has a directory under its own directory name, and each backup a directory named after
 * the time it was taken ({@code 20261127T101500Z}), holding the copy under the dataset's directory
 * name and the text file {@value #NOTE_FILE}: project id, daemon version, the due steps, the time
 * and how to restore. Nothing is ever deleted automatically except the remains of an interrupted
 * backup.</p>
 *
 * <p><strong>How.</strong> The dataset is shut down first, so its store files are closed while
 * they are copied; a dataset someone still holds open ({@link DatasetShutdownOutcome#STILL_LEASED})
 * is not copied. The copy is written under {@code <time>}{@value #INCOMPLETE_SUFFIX} and renamed
 * once it and the note are complete; {@link #discardIncomplete} removes such a remainder. Restoring
 * needs no tool: stop the daemon, replace the dataset directory with the copy, start a daemon.</p>
 *
 * <p>The directory name is the library's: the dataset id, UTF-8, Base64url without padding. The
 * neutral lifecycle port does not name it, so this class repeats the encoding, and a test against
 * the library keeps the two equal.</p>
 */
public final class StorageFolderBackup implements MigrationBackup {

    /** The directory under the storage root every backup lies in. */
    public static final String BACKUP_DIRECTORY = ".arknet-backups";

    /** The text file next to each copy that says what the copy is. */
    public static final String NOTE_FILE = "backup.txt";

    /** The suffix of a backup directory that is still being written. */
    public static final String INCOMPLETE_SUFFIX = ".incomplete";

    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private final Path storageRoot;
    private final String daemonVersion;
    private final Clock clock;

    /**
     * @param storageRoot   the storage root the datasets lie in
     * @param daemonVersion the version of the daemon taking the backup, recorded in the note
     * @param clock         the clock the backup time is read from
     */
    public StorageFolderBackup(Path storageRoot, String daemonVersion, Clock clock) {
        this.storageRoot = Objects.requireNonNull(storageRoot, "storageRoot").toAbsolutePath().normalize();
        this.daemonVersion = Objects.requireNonNull(daemonVersion, "daemonVersion");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * @param storageRoot   the storage root the datasets lie in
     * @param daemonVersion the version of the daemon taking the backup, recorded in the note
     */
    public StorageFolderBackup(Path storageRoot, String daemonVersion) {
        this(storageRoot, daemonVersion, Clock.systemUTC());
    }

    /**
     * The name of the directory the library keeps a dataset in, directly under the storage root.
     *
     * @param id the dataset
     * @return the directory name
     */
    public static String directoryName(DatasetId id) {
        return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(id.value().getBytes(StandardCharsets.UTF_8));
    }

    /** @return the directory under the storage root every backup lies in */
    public Path backupRoot() {
        return storageRoot.resolve(BACKUP_DIRECTORY);
    }

    @Override
    public Copy backUp(DatasetLifecycle lifecycle, DatasetId id, List<String> dueSteps) {
        Objects.requireNonNull(lifecycle, "lifecycle");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(dueSteps, "dueSteps");
        String name = directoryName(id);
        Path source = storageRoot.resolve(name);
        if (!Files.isDirectory(source)) {
            throw new MigrationBackupException("Cannot back up project '" + id.value() + "': its directory "
                    + source + " does not exist.", null);
        }
        DatasetShutdownOutcome outcome = lifecycle.shutDown(id);
        if (outcome == DatasetShutdownOutcome.STILL_LEASED) {
            throw new MigrationBackupException("Cannot back up project '" + id.value()
                    + "': it is still open, its store files cannot be copied consistently.", null);
        }
        Instant taken = clock.instant().truncatedTo(ChronoUnit.SECONDS);
        Path datasetBackups = backupRoot().resolve(name);
        Path target = freeTarget(datasetBackups, TIMESTAMP.format(taken));
        Path incomplete = target.resolveSibling(target.getFileName() + INCOMPLETE_SUFFIX);
        try {
            Files.createDirectories(incomplete);
            long bytes = copyTree(source, incomplete.resolve(name));
            Files.writeString(incomplete.resolve(NOTE_FILE), note(id, name, dueSteps, taken), StandardCharsets.UTF_8);
            Files.move(incomplete, target, StandardCopyOption.ATOMIC_MOVE);
            return new Copy(target, bytes);
        } catch (IOException | UncheckedIOException | SecurityException e) {
            deleteQuietly(incomplete);
            throw new MigrationBackupException("Cannot back up project '" + id.value() + "' to " + target + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public void discardIncomplete() {
        Path root = backupRoot();
        if (!Files.isDirectory(root)) {
            return;
        }
        try (Stream<Path> datasets = Files.list(root)) {
            for (Path dataset : datasets.filter(Files::isDirectory).toList()) {
                try (Stream<Path> backups = Files.list(dataset)) {
                    backups.filter(path -> path.getFileName().toString().endsWith(INCOMPLETE_SUFFIX))
                            .toList()
                            .forEach(StorageFolderBackup::delete);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot clear interrupted backups under " + root, e);
        }
    }

    private static Path freeTarget(Path datasetBackups, String timestamp) {
        Path target = datasetBackups.resolve(timestamp);
        int suffix = 1;
        while (Files.exists(target) || Files.exists(target.resolveSibling(target.getFileName() + INCOMPLETE_SUFFIX))) {
            target = datasetBackups.resolve(timestamp + "-" + suffix);
            suffix++;
        }
        return target;
    }

    private static long copyTree(Path source, Path target) throws IOException {
        AtomicLong bytes = new AtomicLong();
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Files.createDirectories(target.resolve(source.relativize(dir).toString()));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.copy(file, target.resolve(source.relativize(file).toString()), StandardCopyOption.COPY_ATTRIBUTES);
                bytes.addAndGet(attrs.size());
                return FileVisitResult.CONTINUE;
            }
        });
        return bytes.get();
    }

    private String note(DatasetId id, String name, List<String> dueSteps, Instant taken) {
        return "arknet migration backup\n"
                + "project id: " + id.value() + "\n"
                + "directory: " + name + "\n"
                + "daemon version: " + daemonVersion + "\n"
                + "due steps: " + String.join(", ", dueSteps) + "\n"
                + "taken at: " + taken + "\n"
                + "\n"
                + "The directory '" + name + "' next to this file is a copy of the project's store directory,\n"
                + "taken before the migration changed anything. To restore it: stop the daemon, replace\n"
                + "<storage root>/" + name + " with this copy, then start a daemon. A daemon that still\n"
                + "carries the migration migrates the restored copy again on its next start.\n"
                + "arknet never deletes this backup; delete it by hand once it is no longer needed.\n";
    }

    private static void delete(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (Stream<Path> tree = Files.walk(path)) {
            for (Path entry : tree.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(entry);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot delete " + path, e);
        }
    }

    private static void deleteQuietly(Path path) {
        try {
            delete(path);
        } catch (UncheckedIOException e) {
            // the remainder carries the incomplete suffix; discardIncomplete removes it on the next start
        }
    }
}
