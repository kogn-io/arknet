// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Fred Hauschel

package de.hauschel.arknet.architecture;

import static de.hauschel.arknet.architecture.support.OntologyFixtures.ontologyModules;
import static de.hauschel.arknet.architecture.support.OntologyFixtures.rootTurtleFiles;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.URL;
import java.util.Comparator;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

import org.junit.jupiter.api.Test;

import de.hauschel.arknet.architecture.support.OntologyFixtures;

/**
 * Every ontology module -- {@code arknet-ontology} and the ontology module of each bounded context --
 * names its live files in {@value OntologyFixtures#ONTOLOGY_INDEX}, and generic resource access finds
 * the files of the bounded contexts only through these indices (ADR-69, consequence 8). A file a
 * module ships but its index forgets would silently stay outside the one gate of generic access and
 * outside every ontology cross-check; a file two modules ship would be loaded from whichever comes
 * first on the classpath.
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
}
