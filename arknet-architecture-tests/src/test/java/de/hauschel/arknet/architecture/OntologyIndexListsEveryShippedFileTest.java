// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.ontologyModules;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.rootTurtleFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.stream.Collectors;
import java.util.stream.Stream;

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
     * listed -- is on this module's test classpath.
     */
    @Test
    void everyOntologyModuleOfTheBuildIsOnTheTestClasspath() {
        Path reactorRoot = Path.of("").toAbsolutePath().getParent();
        Set<String> inSources;
        try (Stream<Path> files = Files.walk(reactorRoot, 8)) {
            inSources = files
                    .filter(file -> file.endsWith(Path.of("src", "main", "resources", "META-INF", "arknet",
                            "ontology.index")))
                    .filter(file -> !file.toString().contains("/target/"))
                    .map(file -> reactorRoot.relativize(file).toString())
                    .collect(Collectors.toCollection(TreeSet::new));
        } catch (IOException e) {
            throw new UncheckedIOException("failed to walk " + reactorRoot, e);
        }
        assertEquals(inSources.size(), ontologyModules().size(), "the build has the ontology modules " + inSources
                + ", the test classpath of arknet-architecture-tests sees " + ontologyModules().keySet());
    }
}
