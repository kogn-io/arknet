// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.ontologyModules;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.rootTurtleFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.architecture.support.OntologyFixtures;

/**
 * Every ontology module -- {@code arknet-ontology} and the ontology module of each bounded context --
 * names its live files in {@value OntologyFixtures#ONTOLOGY_INDEX}, and generic resource access finds
 * the files of the bounded contexts only through these indices (ADR-69, consequence 8). A file a
 * module ships but its index forgets would silently stay outside the one gate of generic access and
 * outside every ontology cross-check; a file two modules ship would be loaded from whichever comes
 * first on the classpath. Every cross-check of this module reads the ontology modules off its test
 * classpath, so one missing there would leave every such check one module short and still green.
 */
class OntologyIndexListsEveryShippedFileTest {

    @Test
    void everyIndexNamesExactlyTheLiveFilesItsModuleShips() {
        ontologyModules().forEach((index, listed) -> assertEquals(rootTurtleFiles(index), listed,
                "the index at " + index + " must name every .ttl at the root of its module, and nothing else"));
    }

    @Test
    void noFileIsShippedByTwoOntologyModules() {
        Map<String, Set<URL>> shippedBy = new TreeMap<>();
        ontologyModules().forEach((index, listed) -> listed
                .forEach(name -> shippedBy.computeIfAbsent(name, key -> new TreeSet<>(
                        Comparator.comparing(URL::toString))).add(index)));
        shippedBy.forEach((name, indices) -> assertEquals(1, indices.size(),
                name + " is shipped by more than one ontology module: " + indices));
    }

    /**
     * Every ontology module of the build -- found as an index in the source tree of the reactor, not
     * listed -- is on this module's test classpath, and nothing else is. Both sides are compared as
     * module names: the directory holding {@code src} in the source tree, and on the classpath the
     * module directory above {@code target/classes} or the artifact directory above a jar's version
     * directory (module directory and artifactId are the same by the naming rule).
     */
    @Test
    void everyOntologyModuleOfTheBuildIsOnTheTestClasspath() {
        Set<String> inSources = new TreeSet<>();
        Path reactorRoot = reactorRoot();
        try {
            Files.walkFileTree(reactorRoot, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    String name = String.valueOf(dir.getFileName());
                    return !dir.equals(reactorRoot) && ("target".equals(name) || name.startsWith("."))
                            ? FileVisitResult.SKIP_SUBTREE
                            : FileVisitResult.CONTINUE;
                }

                @Override
                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    if (file.endsWith(INDEX_IN_SOURCES)) {
                        inSources.add(file.getParent().getParent().getParent().getParent().getParent().getParent()
                                .getFileName().toString());
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException("failed to walk " + reactorRoot, e);
        }
        Set<String> onClasspath = ontologyModules().keySet().stream()
                .map(OntologyIndexListsEveryShippedFileTest::moduleOf)
                .collect(Collectors.toCollection(TreeSet::new));
        assertEquals(inSources, onClasspath,
                "every ontology module of the build, and only those, must be on the test classpath of "
                        + "arknet-architecture-tests");
    }

    private static final Path INDEX_IN_SOURCES = Path.of("src", "main", "resources", "META-INF", "arknet",
            "ontology.index");

    /** The reactor root: the nearest directory upwards whose {@code pom.xml} declares no parent. */
    private static Path reactorRoot() {
        Path module = Path.of(System.getProperty("basedir", "")).toAbsolutePath();
        for (Path dir = module.getParent(); dir != null; dir = dir.getParent()) {
            Path pom = dir.resolve("pom.xml");
            try {
                if (Files.isRegularFile(pom) && !Files.readString(pom).contains("<parent>")) {
                    return dir;
                }
            } catch (IOException e) {
                throw new UncheckedIOException("failed to read " + pom, e);
            }
        }
        throw new IllegalStateException("no reactor root above " + module);
    }

    private static String moduleOf(final URL index) {
        String path = index.getPath();
        if ("jar".equals(index.getProtocol())) {
            Path jar = Path.of(path.substring("file:".length(), path.indexOf('!')));
            return jar.getParent().getParent().getFileName().toString();
        }
        try {
            return Path.of(index.toURI()).getParent().getParent().getParent().getParent().getParent().getFileName()
                    .toString();
        } catch (URISyntaxException e) {
            throw new IllegalStateException("not a file location: " + index, e);
        }
    }
}
